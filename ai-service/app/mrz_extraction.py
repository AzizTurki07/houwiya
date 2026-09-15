import io
import datetime
from typing import Optional

from passporteye import read_mrz

from app.schemas import PassportExtractionResult


class MrzNotFoundError(Exception):
    """Raised when no machine-readable zone could be located in the image."""


def _yy_to_full_year(yy: int, is_expiry: bool) -> int:
    """
    MRZ dates only carry a 2-digit year. ICAO 9303 doesn't mandate a resolution
    rule, so this uses the common convention:
      - date of birth: assume the most recent matching year that isn't in the future
      - expiry date: assume 20xx, nudged forward a century if that reads as
        already-expired by more than ~1 year (handles the century-wrap edge case)
    This is a simplification worth knowing about -- flag low-confidence results
    for manual review rather than trusting the century guess blindly.
    """
    current_yy = datetime.date.today().year % 100
    if is_expiry:
        year = 2000 + yy
        if year < datetime.date.today().year - 1:
            year += 100
        return year
    else:
        year = 2000 + yy if yy <= current_yy else 1900 + yy
        return year


def _mrz_date_to_iso(yymmdd: str, is_expiry: bool) -> Optional[str]:
    if not yymmdd or len(yymmdd) != 6 or not yymmdd.isdigit():
        return None
    yy, mm, dd = int(yymmdd[0:2]), int(yymmdd[2:4]), int(yymmdd[4:6])
    try:
        year = _yy_to_full_year(yy, is_expiry)
        return datetime.date(year, mm, dd).isoformat()
    except ValueError:
        return None


def extract_passport_mrz(image_bytes: bytes) -> PassportExtractionResult:
    mrz = read_mrz(io.BytesIO(image_bytes))

    if mrz is None:
        raise MrzNotFoundError(
            "Could not locate a machine-readable zone. Retake the photo with the "
            "full bio page visible, well lit, and not tilted."
        )

    data = mrz.to_dict()

    checksums = [
        data.get("valid_number"),
        data.get("valid_date_of_birth"),
        data.get("valid_expiration_date"),
        data.get("valid_composite"),
    ]
    checksum_valid = all(bool(c) for c in checksums)

    # valid_score (0-100) is PassportEye's own OCR-quality estimate. Blend it
    # with checksum validity so a clean-looking OCR read that fails a check
    # digit still surfaces as low confidence for the review queue.
    ocr_score = (data.get("valid_score") or 0) / 100.0
    overall_confidence = ocr_score if checksum_valid else min(ocr_score, 0.4)

    return PassportExtractionResult(
        document_number=(data.get("number") or "").replace("<", "") or None,
        surname=(data.get("surname") or "").replace("<", " ").strip() or None,
        given_names=(data.get("names") or "").replace("<", " ").strip() or None,
        nationality=data.get("nationality") or None,
        date_of_birth=_mrz_date_to_iso(data.get("date_of_birth", ""), is_expiry=False),
        sex=data.get("sex") or None,
        expiry_date=_mrz_date_to_iso(data.get("expiration_date", ""), is_expiry=True),
        checksum_valid=checksum_valid,
        overall_confidence=round(overall_confidence, 2),
    )
