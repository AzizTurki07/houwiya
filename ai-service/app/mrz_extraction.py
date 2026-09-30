import io
import re
import datetime
from typing import Dict, Optional

from passporteye import read_mrz

from app.schemas import PassportExtractionResult


class MrzNotFoundError(Exception):
    """Raised when no machine-readable zone could be located in the image."""


# Passports are issued for at most 10 years, so an expiry further out than this can only
# mean the two-digit year belongs to the previous century.
_MAX_EXPIRY_YEARS_AHEAD = 20


def _yy_to_full_year(yy: int, is_expiry: bool, today: Optional[datetime.date] = None) -> int:
    """
    MRZ dates only carry a 2-digit year. ICAO 9303 doesn't mandate a resolution rule, so:
      - date of birth: the most recent matching year that isn't in the future.
      - expiry date: 20yy, unless that lies implausibly far in the future. An expiry in
        the past must stay in the past -- an expired passport has to read as expired, not
        get pushed a century ahead.
    """
    today = today or datetime.date.today()
    if is_expiry:
        year = 2000 + yy
        if year > today.year + _MAX_EXPIRY_YEARS_AHEAD:
            year -= 100
        return year
    return 2000 + yy if yy <= today.year % 100 else 1900 + yy


def _mrz_date_to_iso(yymmdd: str, is_expiry: bool) -> Optional[str]:
    if not yymmdd or len(yymmdd) != 6 or not yymmdd.isdigit():
        return None
    yy, mm, dd = int(yymmdd[0:2]), int(yymmdd[2:4]), int(yymmdd[4:6])
    try:
        year = _yy_to_full_year(yy, is_expiry)
        return datetime.date(year, mm, dd).isoformat()
    except ValueError:
        return None


# Confidence given to a field that failed its own validation. Matches the CIN pipeline's
# downgrade value so both document types flag fields the same way on the review screen.
LOW_FIELD_CONFIDENCE = 0.3

_NAME_RE = re.compile(r"^[A-Z][A-Z '\-]*$")
# ICAO codes are 3 characters, padded with "<" for the few shorter ones (Germany is "D<<").
_NATIONALITY_RE = re.compile(r"^[A-Z][A-Z<]{2}$")
_SEXES = {"M", "F", "X", "<"}


def passport_field_confidence(fields: Dict[str, Optional[str]], checks: Dict[str, bool],
                              ocr_score: float) -> Dict[str, float]:
    """
    Per-field confidence for a parsed MRZ.

    The MRZ protects document number, date of birth and expiry date with their own
    check digits, so each of those fields is judged on its own digit: a pass keeps the
    OCR score, a fail drops just that field to LOW_FIELD_CONFIDENCE. Names, nationality
    and sex have no check digit, so they fall back to the OCR score plus a format sanity
    check (the MRZ alphabet is A-Z and '<', so e.g. a digit in a name is certainly a
    misread). A field that wasn't read at all gets 0.0.
    """
    ocr_score = max(0.0, min(1.0, ocr_score))

    def checked(key: str, check: str) -> float:
        if not fields.get(key):
            return 0.0
        return ocr_score if checks.get(check) else min(ocr_score, LOW_FIELD_CONFIDENCE)

    def pattern(key: str, ok) -> float:
        value = fields.get(key)
        if not value:
            return 0.0
        return ocr_score if ok(value) else min(ocr_score, LOW_FIELD_CONFIDENCE)

    return {
        "document_number": checked("document_number", "valid_number"),
        "date_of_birth": checked("date_of_birth", "valid_date_of_birth"),
        "expiry_date": checked("expiry_date", "valid_expiration_date"),
        "surname": pattern("surname", lambda v: bool(_NAME_RE.match(v))),
        "given_names": pattern("given_names", lambda v: bool(_NAME_RE.match(v))),
        "nationality": pattern("nationality", lambda v: bool(_NATIONALITY_RE.match(v))),
        "sex": pattern("sex", lambda v: v in _SEXES),
    }


def extract_passport_mrz(image_bytes: bytes) -> PassportExtractionResult:
    mrz = read_mrz(io.BytesIO(image_bytes))

    if mrz is None:
        raise MrzNotFoundError(
            "Could not locate a machine-readable zone. Retake the photo with the "
            "full bio page visible, well lit, and not tilted."
        )

    data = mrz.to_dict()

    checks = {
        key: bool(data.get(key))
        for key in ("valid_number", "valid_date_of_birth", "valid_expiration_date", "valid_composite")
    }
    checksum_valid = all(checks.values())

    # valid_score (0-100) is PassportEye's own OCR-quality estimate. Blend it
    # with checksum validity so a clean-looking OCR read that fails a check
    # digit still surfaces as low confidence for the review queue.
    ocr_score = (data.get("valid_score") or 0) / 100.0
    overall_confidence = ocr_score if checksum_valid else min(ocr_score, 0.4)

    fields = {
        "document_number": (data.get("number") or "").replace("<", "") or None,
        "surname": (data.get("surname") or "").replace("<", " ").strip() or None,
        "given_names": (data.get("names") or "").replace("<", " ").strip() or None,
        "nationality": data.get("nationality") or None,
        "date_of_birth": _mrz_date_to_iso(data.get("date_of_birth", ""), is_expiry=False),
        "sex": data.get("sex") or None,
        "expiry_date": _mrz_date_to_iso(data.get("expiration_date", ""), is_expiry=True),
    }
    field_confidence = passport_field_confidence(fields, checks, ocr_score)

    return PassportExtractionResult(
        **fields,
        checksum_valid=checksum_valid,
        overall_confidence=round(overall_confidence, 2),
        field_confidence={k: round(v, 2) for k, v in field_confidence.items()},
    )
