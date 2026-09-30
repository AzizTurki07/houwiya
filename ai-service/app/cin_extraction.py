"""
Tunisian CIN (national ID card) extraction, front and back.

The card is Arabic-only and has a fixed layout, so instead of reading the whole card as
free text, each field is read from its own horizontal band of the normalised card image:

  1. card_detection finds the card in the photo and warps it to CARD_W x CARD_H.
  2. For each field band, keep only the "ink": the values are printed in black, while the
     field labels are purple and the background art is pale or coloured. Dropping every
     other pixel removes labels, flags, the stamp and the monument drawing before OCR,
     so the bands can span the full row and tolerate small layout shifts between cards.
  3. Tesseract reads each band (Arabic, or digits-only for the card number).
  4. Values are cleaned and validated (8-digit number, parseable date, "بن/بنت" lineage);
     a value that fails validation keeps its text but gets a low confidence.

Bands were calibrated on real (consented) cards -- see ai-service/eval/ for the labelled
evaluation set and the accuracy report.
"""
import difflib
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple

import cv2
import numpy as np
import pytesseract

from app.arabic_text import clean, clean_name, parse_arabic_date
from app.card_detection import CardNotDetectedError, detect_and_crop_card  # noqa: F401 (re-exported)
from app.lexicon import ADDRESS_WORDS, PLACES, PROFESSIONS, correct_phrase, correct_tokens
from app.schemas import CinBackExtractionResult, CinExtractionResult

# Same value as the passport pipeline: a field that fails its own validation.
LOW_FIELD_CONFIDENCE = 0.3

# Ink filter (CIE L*a*b*, 8-bit). Value text is colour-neutral; the purple labels are violet
# (a > 0, b < 0) and the stamp blue, so colour separates them. *How dark* the text is depends
# on the photo: measured text lightness ranged from 27 to 144 across real cards (lighting, a
# faded print), so the dark/light split is computed per band (Otsu) rather than fixed.
INK_MAX_CHROMA = 16
INK_MIN_B_MINUS_A = -10
INK_LIGHTNESS_CAP = 190        # never treat anything lighter than this as ink
INK_MIN_CONTRAST = 25          # text must be at least this much darker than the background
# Connected components smaller than this are background specks. Keep it small: Arabic dots
# are what tell ب/ت/ث/ن/ي apart, and a larger cut-off measurably hurt accuracy.
MIN_SPECK_AREA = 4
UPSCALE = 3


@dataclass(frozen=True)
class FieldBand:
    box: Tuple[int, int, int, int]  # x1, y1, x2, y2 on the CARD_W x CARD_H card
    kind: str                       # "digits" | "name" | "date" | "text"
    block: bool = False             # several lines of text (address)


# Rows on the normalised front: number under the header, then label/value rows on the right
# (the photo occupies the left third).
FRONT_FIELDS: Dict[str, FieldBand] = {
    "document_number": FieldBand((360, 215, 700, 285), "digits"),
    "last_name": FieldBand((330, 310, 965, 378), "name"),
    "first_name": FieldBand((330, 376, 965, 428), "name"),
    "lineage": FieldBand((330, 424, 965, 480), "name"),
    "date_of_birth": FieldBand((330, 474, 965, 530), "date"),
    "place_of_birth": FieldBand((330, 528, 965, 596), "name"),
}

# Back: text column on the left (the fingerprint box starts around x=675). The first row,
# the mother's name, is deliberately not read -- verification doesn't need it.
BACK_FIELDS: Dict[str, FieldBand] = {
    "profession": FieldBand((300, 135, 668, 198), "name"),
    "address": FieldBand((25, 198, 668, 325), "text", block=True),
    "issue_date": FieldBand((25, 335, 480, 395), "date"),
}


def ink_mask(region: np.ndarray) -> Tuple[np.ndarray, float]:
    """(boolean mask of the black value text in a card region, the lightness threshold used)."""
    lab = cv2.cvtColor(region, cv2.COLOR_BGR2LAB).astype(np.int16)
    lightness, a, b = lab[..., 0], lab[..., 1] - 128, lab[..., 2] - 128
    neutral = (np.sqrt(a * a + b * b) < INK_MAX_CHROMA) & ((b - a) > INK_MIN_B_MINUS_A)
    threshold = ink_threshold(lightness[neutral])
    ink = (neutral & (lightness <= threshold)).astype(np.uint8)

    count, labels, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
    area = stats[:, cv2.CC_STAT_AREA]
    keep = np.zeros(count, bool)
    keep[1:] = area[1:] >= MIN_SPECK_AREA
    # Small marks are kept only right next to real letters: Arabic dots sit within a few
    # pixels of their letter, while remnants of a label or the background art sit apart (and
    # OCR as phantom zeros).
    big = np.zeros(count, bool)
    big[1:] = area[1:] >= 40
    if big.any():
        near_letters = cv2.dilate(big[labels].astype(np.uint8), np.ones((21, 21), np.uint8)).astype(bool)
        attached = np.zeros(count, bool)
        attached[np.unique(labels[near_letters])] = True
        keep &= big | attached
    # Growing the mask (below) must not pull the violet label pixels next to a value back in.
    violet = ((a - b) > 8) & (np.sqrt(a * a + b * b) > 8)
    mask = cv2.dilate(keep[labels].astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool) & ~violet
    return mask, threshold


def ink_image(region: np.ndarray) -> np.ndarray:
    """Grayscale image with everything except the black value text turned white, upscaled."""
    mask, threshold = ink_mask(region)
    gray = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
    out = np.full_like(gray, 255)
    if mask.any():
        # Stretch the ink's own range to near-black: faded grey print reads far better.
        darkest = float(np.percentile(gray[mask], 2))
        span = max(1.0, float(threshold) - darkest)
        out[mask] = np.clip((gray[mask] - darkest) / span * 200, 0, 255).astype(np.uint8)
    # Tesseract reads a line most reliably when it is tightly framed by a white margin:
    # text touching the edge, or a wide empty stretch beside it, causes dropped/phantom glyphs.
    ys, xs = np.nonzero(mask)
    if len(xs):
        out = out[max(0, ys.min() - 4):ys.max() + 5, max(0, xs.min() - 4):xs.max() + 5]
    out = cv2.resize(out, None, fx=UPSCALE, fy=UPSCALE, interpolation=cv2.INTER_CUBIC)
    return cv2.copyMakeBorder(out, 24, 24, 24, 24, cv2.BORDER_CONSTANT, value=255)


def ink_threshold(neutral_lightness: np.ndarray) -> float:
    """
    Lightness at or below which a neutral pixel counts as text, for one band: Otsu's split between
    the dark text and the light card, capped, and never closer to the background than
    INK_MIN_CONTRAST (so an empty band doesn't turn background texture into "text").
    """
    if neutral_lightness.size < 50:
        return 0.0
    values = np.clip(neutral_lightness, 0, 255).astype(np.uint8).reshape(-1, 1)
    otsu, _ = cv2.threshold(values, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    background = float(np.percentile(neutral_lightness, 60))
    return min(float(otsu), INK_LIGHTNESS_CAP, background - INK_MIN_CONTRAST)


# Where the value rows are searched for: the text column right of the photo on the front,
# left of the fingerprint on the back. Rows are then found from the ink itself, because their
# exact height varies from card to card by more than a fixed band tolerates.
FRONT_COLUMN = (330, 290, 965, 615)
BACK_COLUMN = (25, 40, 668, 415)


def find_rows(card: np.ndarray, column: Tuple[int, int, int, int]) -> List[Tuple[int, int]]:
    """Text rows (top, bottom) in card coordinates inside `column`, top to bottom."""
    x1, y1, x2, y2 = column
    mask, _ = ink_mask(card[y1:y2, x1:x2])
    profile = np.convolve(mask.sum(axis=1).astype(float), np.ones(3) / 3, mode="same")
    if profile.max() == 0:
        return []
    has_ink = profile > 0.08 * profile.max()
    runs, start = [], None
    for y, on in enumerate(has_ink):
        if on and start is None:
            start = y
        elif not on and start is not None:
            runs.append([start, y])
            start = None
    if start is not None:
        runs.append([start, len(has_ink)])
    # Dots above/below a line leave small gaps: merge runs a few pixels apart.
    merged: List[List[int]] = []
    for begin, end in runs:
        if merged and begin - merged[-1][1] < 7:
            merged[-1][1] = end
        else:
            merged.append([begin, end])
    # Closely spaced lines (a married woman's name lines, faded print) touch through their
    # ascenders/descenders and come out as one tall run: split those at their valleys.
    rows = [piece for begin, end in merged for piece in _split_at_valleys(profile, begin, end)]
    # Text rows on the normalised card are ~30-60 px tall; thinner runs are noise.
    return [(y1 + b, y1 + e) for b, e in rows if e - b >= 15]


def _split_at_valleys(profile: np.ndarray, begin: int, end: int) -> List[Tuple[int, int]]:
    """One text line has no deep gap in its middle; two merged lines do. Split recursively
    at the lowest point if it is well below the ink peaks on *both* sides of it."""
    if end - begin < 60:
        return [(begin, end)]
    segment = profile[begin:end]
    left_peak = np.maximum.accumulate(segment)
    right_peak = np.maximum.accumulate(segment[::-1])[::-1]
    ratio = segment / np.maximum(1e-6, np.minimum(left_peak, right_peak))
    ratio[:15] = ratio[-15:] = np.inf  # a cut must leave a real line on each side
    cut = int(np.argmin(ratio))
    if ratio[cut] >= 0.45:
        return [(begin, end)]
    return _split_at_valleys(profile, begin, begin + cut) + _split_at_valleys(profile, begin + cut, end)


SNAP_TOLERANCE = 30  # px on the normalised card


def _band_from_rows(rows: List[Tuple[int, int]], column, top: int, bottom: int, template: FieldBand) -> FieldBand:
    """Band spanning [top, bottom], extended halfway towards the neighbouring detected rows."""
    _, y1, _, y2 = column
    x1, _, x2, _ = template.box  # keep the field's own horizontal extent
    above = [r for r in rows if r[1] <= top]
    below = [r for r in rows if r[0] >= bottom]
    upper = max(top - 8, (above[-1][1] + top) // 2) if above else top - 8
    lower = min(bottom + 8, (bottom + below[0][0]) // 2) if below else bottom + 8
    return FieldBand((x1, max(y1, upper), x2, min(y2, lower)), template.kind, template.block)


def snap_to_rows(card: np.ndarray, column, template: Dict[str, FieldBand]) -> Dict[str, FieldBand]:
    """
    Fit each field's band to the text row actually found near its usual position. A field
    with no detected row close enough (e.g. two lines merged into one run) keeps its fixed
    band, so one bad row never shifts the others. Multi-line fields take every row whose
    centre lies inside their usual band.
    """
    rows = find_rows(card, column)
    layout = dict(template)
    for name, band in template.items():
        x1, y1, x2, y2 = band.box
        if band.box[0] != column[0] and band.box[2] != column[2]:
            continue  # not part of this column (e.g. the card number above the rows)
        if band.block:
            inside = [r for r in rows if y1 <= (r[0] + r[1]) / 2 <= y2]
            if inside:
                layout[name] = _band_from_rows(rows, column, inside[0][0], inside[-1][1], band)
            continue
        centre = (y1 + y2) / 2
        nearest = min(rows, key=lambda r: abs((r[0] + r[1]) / 2 - centre), default=None)
        if nearest is not None and abs((nearest[0] + nearest[1]) / 2 - centre) <= SNAP_TOLERANCE:
            layout[name] = _band_from_rows(rows, column, nearest[0], nearest[1], band)
    return layout


def locate_front(card: np.ndarray) -> Dict[str, FieldBand]:
    """Front bands: surname, first name, lineage (or, on a married woman's card, the spouse
    line), date and place of birth -- each snapped to its detected row."""
    return snap_to_rows(card, FRONT_COLUMN, FRONT_FIELDS)


def locate_back(card: np.ndarray) -> Dict[str, FieldBand]:
    """Back bands: profession, the address line(s), issue date -- snapped to detected rows.
    The mother's name row is never read."""
    return snap_to_rows(card, BACK_COLUMN, BACK_FIELDS)


def field_images(card: np.ndarray, fields: Dict[str, FieldBand]) -> Dict[str, np.ndarray]:
    """Preprocessed image per field; the evaluation harness feeds these to other OCR engines."""
    return {name: ink_image(card[y1:y2, x1:x2]) for name, (x1, y1, x2, y2) in
            ((n, f.box) for n, f in fields.items())}


def _ocr_words(image: np.ndarray, lang: str, config: str):
    """Tesseract words as (text, confidence 0-100, (left, top, width, height), line key)."""
    data = pytesseract.image_to_data(image, lang=lang, config=config, output_type=pytesseract.Output.DICT)
    words = []
    for i, text in enumerate(data["text"]):
        text = text.strip()
        try:
            conf = float(data["conf"][i])
        except (TypeError, ValueError):
            conf = -1
        if text and conf >= 0:
            box = (data["left"][i], data["top"][i], data["width"][i], data["height"][i])
            words.append((text, conf, box, (data["block_num"][i], data["par_num"][i], data["line_num"][i])))
    return words


def _join(words) -> Tuple[str, float]:
    lines: Dict[Tuple[int, int, int], list] = {}
    for text, _, _, line in words:
        lines.setdefault(line, []).append(text)
    text = " ".join(" ".join(ws) for _, ws in sorted(lines.items()))
    confs = [w[1] for w in words]
    return text, (sum(confs) / len(confs) / 100.0) if confs else 0.0


_DIGITS_ONLY = "-c tessedit_char_whitelist=0123456789"


def _reread_digits(image: np.ndarray, words):
    """
    The Arabic model is unreliable on the Latin digits inside a date ("26" -> "6"), so every
    word it saw digits in is re-read on its own with the digits-only English model.
    """
    fixed = []
    for text, conf, (left, top, width, height), line in words:
        if any(c.isdigit() for c in text):
            # The Arabic model's box around a number is often too tight (it clips a leading
            # "1"), so widen it a little -- too much and it picks up the next letter as a "0".
            pad = max(6, height // 4)
            crop = image[max(0, top - 6):top + height + 6, max(0, left - pad):left + width + pad]
            crop = cv2.copyMakeBorder(crop, 16, 16, 16, 16, cv2.BORDER_CONSTANT, value=255)
            digits = _join(_ocr_words(crop, "eng", f"--psm 8 {_DIGITS_ONLY}"))
            if digits[0]:
                text, conf = digits[0], digits[1] * 100
        fixed.append((text, conf, (left, top, width, height), line))
    return fixed


def split_lines(image: np.ndarray):
    """
    Split a multi-line field image into one image per text line, using the rows that
    contain ink. Tesseract's own block layout (psm 6) tended to silently drop a line.
    """
    # A row counts as a gap if it is *nearly* empty: descenders of one line often reach
    # into the next, so perfectly empty rows between Arabic lines are rare.
    ink_per_row = (image < 128).sum(axis=1)
    rows = ink_per_row > 0.03 * ink_per_row.max() if ink_per_row.max() else ink_per_row > 0
    runs, start = [], None
    for y, has_ink in enumerate(rows):
        if has_ink and start is None:
            start = y
        elif not has_ink and start is not None:
            runs.append([start, y])
            start = None
    if start is not None:
        runs.append([start, len(rows)])
    if not runs:
        return []
    tallest = max(end - begin for begin, end in runs)
    # Dots above/below a line leave small gaps: merge runs closer than a quarter line.
    merged = [runs[0]]
    for begin, end in runs[1:]:
        if begin - merged[-1][1] < 0.25 * tallest:
            merged[-1][1] = end
        else:
            merged.append([begin, end])
    tallest = max(end - begin for begin, end in merged)
    return [cv2.copyMakeBorder(image[begin:end], 24, 24, 0, 0, cv2.BORDER_CONSTANT, value=255)
            for begin, end in merged
            if end - begin >= 0.5 * tallest]  # slivers of the neighbouring rows' text


def _read_line(image: np.ndarray, kind: str) -> Tuple[str, float]:
    # Digits inside Arabic text are always re-read with the digit model.
    if kind == "date":
        # The raw-line mode tends to fuse the day with neighbouring glyphs ("02" -> "12").
        return _join(_reread_digits(image, _ocr_words(image, "ara", "--psm 7")))
    # psm 7 (line) and 13 (raw line) fail on different inputs -- short one-word values
    # especially -- so read both and keep the one Tesseract is more confident in. Single-word
    # mode (8) is only a fallback: it readily returns a confident fragment of a longer word.
    reads = [_join(_reread_digits(image, _ocr_words(image, "ara", f"--psm {psm}"))) for psm in (7, 13)]
    best = max(reads, key=lambda r: (bool(r[0]), r[1]))
    return best if best[0] else _join(_reread_digits(image, _ocr_words(image, "ara", "--psm 8")))


def tesseract_read(image: np.ndarray, band: FieldBand) -> Tuple[str, float]:
    """(text, mean word confidence 0-1) for one field image."""
    if band.kind == "digits":
        return _join(_ocr_words(image, "eng", f"--psm 7 {_DIGITS_ONLY}"))
    lines = split_lines(image) if band.block else [image]
    reads = [r for r in (_read_line(line, band.kind) for line in lines) if r[0]]
    if not reads:
        return "", 0.0
    return " ".join(text for text, _ in reads), sum(conf for _, conf in reads) / len(reads)


def parse_value(field: str, band: FieldBand, raw: str) -> Tuple[Optional[str], bool]:
    """(value to return, passed validation). Invalid values are still returned for review."""
    if band.kind == "digits":
        digits = "".join(c for c in raw if c.isdigit())
        return digits or None, len(digits) == 8
    if band.kind == "date":
        parsed = parse_arabic_date(raw)
        return parsed, parsed is not None
    value = clean_name(raw) if band.kind == "name" else clean(raw)
    if not value:
        return None, False
    if field == "lineage":
        # "بن ..." (son of) or "بنت ..." (daughter of).
        return value, value.split()[0] in ("بن", "بنت")
    return value, True


Reader = Callable[[np.ndarray, FieldBand], Tuple[str, float]]


Locator = Callable[[np.ndarray], Dict[str, FieldBand]]


def read_fields(card: np.ndarray, locate: Locator, reader: Reader = tesseract_read):
    """Returns ({field: value}, {field: confidence}, number of fields that validated)."""
    fields = locate(card)
    values, confidences, valid_count = {}, {}, 0
    for name, image in field_images(card, fields).items():
        band = fields[name]
        raw, conf = reader(image, band)
        value, valid = parse_value(name, band, raw)
        if value is None:
            conf = 0.0
        elif not valid:
            conf = min(conf, LOW_FIELD_CONFIDENCE)
        values[name] = value
        confidences[name] = round(conf, 2)
        valid_count += valid
    return values, confidences, valid_count


def _read_card(image_bytes: bytes, locate: Locator, key_field: str, reader: Reader):
    card = detect_and_crop_card(image_bytes)
    values, confidences, valid = read_fields(card, locate, reader)
    if not _key_ok(values, confidences, key_field):
        # Upside-down photo is the common mistake: try the card rotated 180 degrees.
        rotated = read_fields(cv2.rotate(card, cv2.ROTATE_180), locate, reader)
        if rotated[2] > valid:
            values, confidences, valid = rotated
    return values, confidences


def split_name_and_lineage(values: Dict[str, Optional[str]], confidences: Dict[str, float]) -> None:
    """
    A married woman's card prints "<first name> بنت <father> بن <grandfather>" on the first-name
    line and "حرم <husband's name>" on the next. Move the lineage out of the first name, and
    drop the spouse line: verification doesn't need it (data minimisation, like the mother's
    name on the back).
    """
    first = (values.get("first_name") or "").split()
    marker = _lineage_start(first)
    lineage = values.get("lineage") or ""
    is_spouse_line = lineage.startswith("حر")  # "حرم", tolerant of a misread last letter
    if marker is not None:
        values["first_name"] = " ".join(first[:marker])
        values["lineage"] = " ".join(first[marker:])
        confidences["lineage"] = confidences.get("first_name", 0.0)
    elif is_spouse_line:
        values["lineage"] = None
        confidences["lineage"] = 0.0


def _lineage_start(tokens: List[str]) -> Optional[int]:
    """
    Index where "بن/بنت <father> بن <grandfather>" starts inside a name line, tolerating an
    OCR-damaged "بنت" (بثت, نت...). To avoid splitting a first name that merely looks like
    "بنت", the rest must also contain a "بن" further on -- a lineage always does.
    """
    def like(token: str, word: str) -> bool:
        return token == word or (abs(len(token) - len(word)) <= 1
                                 and difflib.SequenceMatcher(None, token, word).ratio() >= 0.66)

    for i in range(1, len(tokens)):
        if tokens[i] in ("بن", "بنت") or like(tokens[i], "بنت"):
            if tokens[i] in ("بن", "بنت") and any(t == "بن" for t in tokens[i + 1:]) or                     any(like(t, "بن") or t == "بن" for t in tokens[i + 2:]):
                return i
            if tokens[i] in ("بن", "بنت") and i + 1 < len(tokens):
                return i  # clearly read marker: trust it even if the rest is damaged
    return None


def _key_ok(values, confidences, key_field) -> bool:
    return values.get(key_field) is not None and confidences.get(key_field, 0) > LOW_FIELD_CONFIDENCE


def extract_cin_fields(image_bytes: bytes, reader: Reader = tesseract_read) -> CinExtractionResult:
    """Front side. Raises CardNotDetectedError if no card outline can be found."""
    values, confidences = _read_card(image_bytes, locate_front, "document_number", reader)
    split_name_and_lineage(values, confidences)
    values["place_of_birth"] = correct_phrase(values.get("place_of_birth"), PLACES)
    return CinExtractionResult(
        **values,
        # The weakest field decides: one badly read field should send the document to review.
        overall_confidence=min(confidences.values()),
        field_confidence=confidences,
    )


def extract_cin_back_fields(image_bytes: bytes, reader: Reader = tesseract_read) -> CinBackExtractionResult:
    """Back side. Raises CardNotDetectedError if no card outline can be found."""
    values, confidences = _read_card(image_bytes, locate_back, "issue_date", reader)
    values["profession"] = correct_phrase(values.get("profession"), PROFESSIONS)
    values["address"] = correct_tokens(values.get("address"), ADDRESS_WORDS)
    return CinBackExtractionResult(
        **values,
        overall_confidence=min(confidences.values()),
        field_confidence=confidences,
    )
