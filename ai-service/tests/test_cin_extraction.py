import os
import io
import pytest
from PIL import Image

from app.cin_extraction import extract_cin_fields, CardNotDetectedError

FIXTURE_DIR = os.path.join(os.path.dirname(__file__), "fixtures")


def _read(name: str) -> bytes:
    with open(os.path.join(FIXTURE_DIR, name), "rb") as f:
        return f.read()


def test_extracts_valid_synthetic_cin():
    result = extract_cin_fields(_read("sample_cin.png"))

    assert result.last_name == "Ben Salah"
    assert result.first_name == "Amine"
    assert result.date_of_birth == "1996-09-14"
    assert result.place_of_birth == "Sfax"
    assert result.document_number == "07845213"
    assert "LIBERTE" in result.address.upper()
    assert result.overall_confidence > 0.5


def test_raises_when_no_card_detected():
    blank = Image.new("RGB", (400, 300), "white")
    buf = io.BytesIO()
    blank.save(buf, format="PNG")

    with pytest.raises(CardNotDetectedError):
        extract_cin_fields(buf.getvalue())
