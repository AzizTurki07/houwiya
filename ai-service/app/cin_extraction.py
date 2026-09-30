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
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple

import cv2
import numpy as np
import pytesseract

from app.arabic_text import clean, clean_name, parse_arabic_date
from app.card_detection import CardNotDetectedError, detect_and_crop_card  # noqa: F401 (re-exported)
from app.schemas import CinBackExtractionResult, CinExtractionResult

# Same value as the passport pipeline: a field that fails its own validation.
LOW_FIELD_CONFIDENCE = 0.3

# Ink filter thresholds (CIE L*a*b*, 8-bit), measured on real cards: value text is near black
# (L ~45, chroma ~5); the purple labels are lighter (L 70-95) and violet (a > 0, b < 0).
INK_MAX_LIGHTNESS = 100
INK_MAX_CHROMA = 12
INK_MIN_B_MINUS_A = -8
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


def ink_image(region: np.ndarray) -> np.ndarray:
    """Grayscale image with everything except the black value text turned white, upscaled."""
    lab = cv2.cvtColor(region, cv2.COLOR_BGR2LAB).astype(np.int16)
    lightness, a, b = lab[..., 0], lab[..., 1] - 128, lab[..., 2] - 128
    ink = ((lightness < INK_MAX_LIGHTNESS)
           & (np.sqrt(a * a + b * b) < INK_MAX_CHROMA)
           & ((b - a) > INK_MIN_B_MINUS_A)).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(ink, connectivity=8)
    keep = np.zeros(count, bool)
    keep[1:] = stats[1:, cv2.CC_STAT_AREA] >= MIN_SPECK_AREA
    # Small marks are only kept near real text: Arabic dots sit right next to their letter,
    # while stray remnants of a label or the background sit apart (and OCR as phantom digits).
    big = [i for i in range(1, count) if stats[i, cv2.CC_STAT_AREA] >= 40]
    if big:
        x1 = min(stats[i, cv2.CC_STAT_LEFT] for i in big) - 12
        x2 = max(stats[i, cv2.CC_STAT_LEFT] + stats[i, cv2.CC_STAT_WIDTH] for i in big) + 12
        for i in range(1, count):
            left, width = stats[i, cv2.CC_STAT_LEFT], stats[i, cv2.CC_STAT_WIDTH]
            if stats[i, cv2.CC_STAT_AREA] < 40 and (left + width < x1 or left > x2):
                keep[i] = False
    # Keep the original grey levels of the ink (anti-aliased edges help the LSTM) on white.
    # Growing the mask must not pull the violet label pixels next to a value back in.
    violet = ((a - b) > 8) & (np.sqrt(a * a + b * b) > 8)
    mask = cv2.dilate(keep[labels].astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool) & ~violet
    gray = cv2.cvtColor(region, cv2.COLOR_BGR2GRAY)
    out = np.full_like(gray, 255)
    out[mask] = gray[mask]
    # Tesseract reads a line most reliably when it is tightly framed by a white margin:
    # text touching the edge, or a wide empty stretch beside it, causes dropped/phantom glyphs.
    ys, xs = np.nonzero(mask)
    if len(xs):
        out = out[max(0, ys.min() - 4):ys.max() + 5, max(0, xs.min() - 4):xs.max() + 5]
    out = cv2.resize(out, None, fx=UPSCALE, fy=UPSCALE, interpolation=cv2.INTER_CUBIC)
    return cv2.copyMakeBorder(out, 24, 24, 24, 24, cv2.BORDER_CONSTANT, value=255)


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
    # especially -- so read both and keep the one Tesseract is more confident in.
    return max((_join(_reread_digits(image, _ocr_words(image, "ara", f"--psm {psm}"))) for psm in (7, 13)),
               key=lambda r: r[1])


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


def read_fields(card: np.ndarray, fields: Dict[str, FieldBand], reader: Reader = tesseract_read):
    """Returns ({field: value}, {field: confidence}, number of fields that validated)."""
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


def _read_card(image_bytes: bytes, fields: Dict[str, FieldBand], key_field: str, reader: Reader):
    card = detect_and_crop_card(image_bytes)
    values, confidences, valid = read_fields(card, fields, reader)
    if not _key_ok(values, confidences, key_field):
        # Upside-down photo is the common mistake: try the card rotated 180 degrees.
        rotated = read_fields(cv2.rotate(card, cv2.ROTATE_180), fields, reader)
        if rotated[2] > valid:
            values, confidences, valid = rotated
    return values, confidences


def _key_ok(values, confidences, key_field) -> bool:
    return values.get(key_field) is not None and confidences.get(key_field, 0) > LOW_FIELD_CONFIDENCE


def extract_cin_fields(image_bytes: bytes, reader: Reader = tesseract_read) -> CinExtractionResult:
    """Front side. Raises CardNotDetectedError if no card outline can be found."""
    values, confidences = _read_card(image_bytes, FRONT_FIELDS, "document_number", reader)
    return CinExtractionResult(
        **values,
        # The weakest field decides: one badly read field should send the document to review.
        overall_confidence=min(confidences.values()),
        field_confidence=confidences,
    )


def extract_cin_back_fields(image_bytes: bytes, reader: Reader = tesseract_read) -> CinBackExtractionResult:
    """Back side. Raises CardNotDetectedError if no card outline can be found."""
    values, confidences = _read_card(image_bytes, BACK_FIELDS, "issue_date", reader)
    return CinBackExtractionResult(
        **values,
        overall_confidence=min(confidences.values()),
        field_confidence=confidences,
    )
