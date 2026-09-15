import re
import datetime
from typing import Optional, Tuple, Dict

import cv2
import numpy as np
import pytesseract

from app.schemas import CinExtractionResult

# Canonical size we normalize every detected card to, matching the ID-1 card
# aspect ratio (85.60mm x 53.98mm ~= 1.586:1). Every field region below is
# calibrated against THIS size -- if you change it, the regions must move too.
CARD_W = 1000
CARD_H = 630

# Value-only regions (label text is deliberately excluded so OCR isn't
# confused by "Nom:" etc). Calibrated against the current CIN layout -- real
# cards vary in exact print position, so expect to tune these against a small
# batch of real (consented) sample cards before trusting this in production.
FIELD_REGIONS: Dict[str, Tuple[int, int, int, int]] = {
    "last_name": (420, 150, 950, 205),
    "first_name": (420, 215, 950, 270),
    "date_of_birth": (420, 280, 950, 335),
    "place_of_birth": (420, 345, 950, 400),
    "document_number": (420, 410, 950, 465),
    "address": (420, 475, 950, 535),
}


class CardNotDetectedError(Exception):
    """Raised when no card-shaped contour could be located in the image."""


def _order_corners(pts: np.ndarray) -> np.ndarray:
    """Order 4 points as top-left, top-right, bottom-right, bottom-left."""
    rect = np.zeros((4, 2), dtype="float32")
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]
    rect[2] = pts[np.argmax(s)]
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]
    rect[3] = pts[np.argmax(diff)]
    return rect


def detect_and_crop_card(image_bytes: bytes) -> np.ndarray:
    """
    Finds the largest 4-cornered contour in the image (assumed to be the
    card against a plain background), perspective-corrects it, and returns
    it warped to the canonical CARD_W x CARD_H size.
    """
    arr = np.frombuffer(image_bytes, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise CardNotDetectedError("Could not decode the uploaded image.")

    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edged = cv2.Canny(blurred, 50, 150)
    edged = cv2.dilate(edged, np.ones((5, 5), np.uint8), iterations=1)

    contours, _ = cv2.findContours(edged, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    contours = sorted(contours, key=cv2.contourArea, reverse=True)[:10]

    image_area = img.shape[0] * img.shape[1]
    card_contour = None
    for c in contours:
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(approx) == 4 and cv2.contourArea(approx) > 0.15 * image_area:
            card_contour = approx
            break

    if card_contour is None:
        raise CardNotDetectedError(
            "Could not detect the card outline. Retake the photo with the card "
            "flat, fully in frame, against a plain contrasting background."
        )

    pts = card_contour.reshape(4, 2).astype("float32")
    ordered = _order_corners(pts)
    dst = np.array(
        [[0, 0], [CARD_W - 1, 0], [CARD_W - 1, CARD_H - 1], [0, CARD_H - 1]],
        dtype="float32",
    )
    matrix = cv2.getPerspectiveTransform(ordered, dst)
    return cv2.warpPerspective(img, matrix, (CARD_W, CARD_H))


def _ocr_region(region: np.ndarray, lang: str = "fra") -> Tuple[str, float]:
    """OCRs a single field region and returns (text, average_word_confidence)."""
    upscaled = cv2.resize(region, None, fx=2, fy=2, interpolation=cv2.INTER_CUBIC)
    gray = cv2.cvtColor(upscaled, cv2.COLOR_BGR2GRAY)
    _, thresh = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    data = pytesseract.image_to_data(thresh, lang=lang, output_type=pytesseract.Output.DICT)

    words, confidences = [], []
    for i, word in enumerate(data["text"]):
        word = word.strip()
        try:
            conf = int(data["conf"][i])
        except (ValueError, TypeError):
            conf = -1
        if word and conf >= 0:
            words.append(word)
            confidences.append(conf)

    text = " ".join(words)
    avg_conf = (sum(confidences) / len(confidences) / 100.0) if confidences else 0.0
    return text, avg_conf


def _parse_ddmmyyyy(raw: str) -> Optional[str]:
    match = re.search(r"(\d{1,2})\D(\d{1,2})\D(\d{4})", raw)
    if not match:
        return None
    day, month, year = (int(g) for g in match.groups())
    try:
        return datetime.date(year, month, day).isoformat()
    except ValueError:
        return None


def _clean_document_number(raw: str) -> Optional[str]:
    digits = re.sub(r"\D", "", raw)
    return digits or None


def extract_cin_fields(image_bytes: bytes, lang: str = "fra") -> CinExtractionResult:
    """
    Full CIN pipeline: detect+crop the card, OCR each pre-defined field
    region independently, validate/parse each value, and roll per-field
    confidence up into one overall_confidence (the minimum across fields --
    a single badly-read field should be enough to flag the whole document
    for manual review, not get averaged away by the fields that read fine).

    `lang` defaults to French only, since that's what this pipeline has been
    verified against here (synthetic fixture, Latin script only). Real CIN
    cards are bilingual Arabic/French -- try lang="fra+ara" against real
    (consented) sample cards and compare accuracy before relying on it.
    """
    warped = detect_and_crop_card(image_bytes)

    texts: Dict[str, str] = {}
    confidences: Dict[str, float] = {}
    for field, (x1, y1, x2, y2) in FIELD_REGIONS.items():
        region = warped[y1:y2, x1:x2]
        text, conf = _ocr_region(region, lang=lang)
        texts[field] = text
        confidences[field] = conf

    document_number = _clean_document_number(texts["document_number"])
    if document_number and not re.fullmatch(r"\d{8}", document_number):
        # Tunisian CIN numbers are 8 digits -- doesn't match, flag it.
        confidences["document_number"] = min(confidences["document_number"], 0.3)

    date_of_birth = _parse_ddmmyyyy(texts["date_of_birth"])
    if texts["date_of_birth"] and not date_of_birth:
        confidences["date_of_birth"] = min(confidences["date_of_birth"], 0.3)

    overall_confidence = min(confidences.values()) if confidences else 0.0

    return CinExtractionResult(
        document_number=document_number,
        last_name=texts["last_name"].strip().title() or None,
        first_name=texts["first_name"].strip().title() or None,
        date_of_birth=date_of_birth,
        place_of_birth=texts["place_of_birth"].strip().title() or None,
        address=texts["address"].strip() or None,
        overall_confidence=round(overall_confidence, 2),
    )
