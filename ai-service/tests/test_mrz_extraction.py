import datetime
import os

import pytest

from app.mrz_extraction import (
    _yy_to_full_year,
    extract_passport_mrz,
    passport_field_confidence,
    MrzNotFoundError,
    LOW_FIELD_CONFIDENCE,
)

FIXTURE_DIR = os.path.join(os.path.dirname(__file__), "fixtures")


def _read(name: str) -> bytes:
    with open(os.path.join(FIXTURE_DIR, name), "rb") as f:
        return f.read()


def test_extracts_valid_synthetic_passport():
    result = extract_passport_mrz(_read("sample_passport.png"))

    assert result.document_number == "PT4021988"
    assert result.surname == "BENALI"
    assert result.given_names == "SAMI"
    assert result.nationality == "TUN"
    assert result.date_of_birth == "1995-03-11"
    assert result.expiry_date == "2030-01-01"
    assert result.sex == "M"
    assert result.checksum_valid is True
    assert result.overall_confidence > 0.9
    # every field is reported, and a clean synthetic read is confident across the board
    assert set(result.field_confidence) == {
        "document_number", "surname", "given_names", "nationality",
        "date_of_birth", "sex", "expiry_date",
    }
    assert all(c > 0.9 for c in result.field_confidence.values())


def test_raises_when_no_mrz_present():
    from PIL import Image
    import io

    blank = Image.new("RGB", (400, 300), "white")
    buf = io.BytesIO()
    blank.save(buf, format="PNG")

    with pytest.raises(MrzNotFoundError):
        extract_passport_mrz(buf.getvalue())


ALL_CHECKS_PASS = {
    "valid_number": True,
    "valid_date_of_birth": True,
    "valid_expiration_date": True,
    "valid_composite": True,
}
CLEAN_FIELDS = {
    "document_number": "PT4021988",
    "surname": "BEN ALI",
    "given_names": "SAMI",
    "nationality": "TUN",
    "date_of_birth": "1995-03-11",
    "sex": "M",
    "expiry_date": "2030-01-01",
}


def test_field_confidence_clean_read_uses_ocr_score():
    conf = passport_field_confidence(CLEAN_FIELDS, ALL_CHECKS_PASS, 0.92)
    assert conf == {k: 0.92 for k in CLEAN_FIELDS}


def test_field_confidence_only_downgrades_the_field_whose_check_digit_failed():
    checks = {**ALL_CHECKS_PASS, "valid_date_of_birth": False, "valid_composite": False}
    conf = passport_field_confidence(CLEAN_FIELDS, checks, 0.92)

    assert conf["date_of_birth"] == LOW_FIELD_CONFIDENCE
    assert conf["document_number"] == 0.92
    assert conf["expiry_date"] == 0.92


def test_field_confidence_flags_impossible_mrz_characters():
    fields = {**CLEAN_FIELDS, "surname": "BEN4LI", "nationality": "TU1", "sex": "Q"}
    conf = passport_field_confidence(fields, ALL_CHECKS_PASS, 0.92)

    assert conf["surname"] == LOW_FIELD_CONFIDENCE
    assert conf["nationality"] == LOW_FIELD_CONFIDENCE
    assert conf["sex"] == LOW_FIELD_CONFIDENCE
    assert conf["given_names"] == 0.92


def test_field_confidence_accepts_padded_nationality_codes():
    conf = passport_field_confidence({**CLEAN_FIELDS, "nationality": "D<<"}, ALL_CHECKS_PASS, 0.92)
    assert conf["nationality"] == 0.92


def test_field_confidence_is_zero_for_fields_not_read():
    conf = passport_field_confidence({**CLEAN_FIELDS, "expiry_date": None}, ALL_CHECKS_PASS, 0.92)
    assert conf["expiry_date"] == 0.0


def test_field_confidence_never_exceeds_a_poor_ocr_score():
    checks = {**ALL_CHECKS_PASS, "valid_number": False}
    conf = passport_field_confidence(CLEAN_FIELDS, checks, 0.2)
    assert conf["document_number"] == 0.2


TODAY = datetime.date(2026, 9, 30)


def test_expired_passport_stays_in_the_past():
    # Regression: a 2020 expiry used to be pushed to 2120, so expired passports read as valid.
    assert _yy_to_full_year(20, is_expiry=True, today=TODAY) == 2020


def test_future_expiry_is_this_century():
    assert _yy_to_full_year(34, is_expiry=True, today=TODAY) == 2034


def test_expiry_implausibly_far_ahead_wraps_back_a_century():
    assert _yy_to_full_year(99, is_expiry=True, today=TODAY) == 1999


def test_birth_year_is_never_in_the_future():
    assert _yy_to_full_year(1, is_expiry=False, today=TODAY) == 2001
    assert _yy_to_full_year(95, is_expiry=False, today=TODAY) == 1995
