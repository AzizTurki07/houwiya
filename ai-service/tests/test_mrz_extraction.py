import os
import pytest

from app.mrz_extraction import extract_passport_mrz, MrzNotFoundError

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


def test_raises_when_no_mrz_present():
    from PIL import Image
    import io

    blank = Image.new("RGB", (400, 300), "white")
    buf = io.BytesIO()
    blank.save(buf, format="PNG")

    with pytest.raises(MrzNotFoundError):
        extract_passport_mrz(buf.getvalue())
