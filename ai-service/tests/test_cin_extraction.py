import io
import os

import cv2
import pytest
from PIL import Image

from app.arabic_text import fold
from app.cin_extraction import CardNotDetectedError, extract_cin_back_fields, extract_cin_fields

FIXTURE_DIR = os.path.join(os.path.dirname(__file__), "fixtures")

# Fictitious values drawn by generate_cin_fixture.py.
FRONT = {
    "document_number": "07845213",
    "last_name": "بن سالم",
    "first_name": "أمين",
    "lineage": "بن محمد بن صالح",
    "date_of_birth": "1996-09-14",
    "place_of_birth": "صفاقس",
}
BACK = {
    "profession": "مهندس",
    "address": "12 نهج الحرية ساقية الزيت صفاقس",
    "issue_date": "2018-03-03",
}


def _read(name: str) -> bytes:
    with open(os.path.join(FIXTURE_DIR, name), "rb") as f:
        return f.read()


def _assert_fields(result, expected):
    for field, value in expected.items():
        # fold(): the same letter-variant tolerance (أ/ا, ة/ه ...) the accuracy report uses.
        assert fold(getattr(result, field) or "") == fold(value), field
    assert set(result.field_confidence) == set(expected)
    assert result.overall_confidence == min(result.field_confidence.values())


def test_reads_every_field_on_the_front():
    result = extract_cin_fields(_read("sample_cin_front.png"))
    _assert_fields(result, FRONT)
    assert result.field_confidence["document_number"] > 0.8


def test_reads_every_field_on_the_back():
    result = extract_cin_back_fields(_read("sample_cin_back.png"))
    _assert_fields(result, BACK)


def test_reads_an_upside_down_photo():
    img = cv2.imread(os.path.join(FIXTURE_DIR, "sample_cin_front.png"))
    ok, buf = cv2.imencode(".png", cv2.rotate(img, cv2.ROTATE_180))
    assert ok
    result = extract_cin_fields(buf.tobytes())
    assert result.document_number == FRONT["document_number"]
    assert result.date_of_birth == FRONT["date_of_birth"]


def test_raises_when_no_card_detected():
    blank = Image.new("RGB", (400, 300), "white")
    buf = io.BytesIO()
    blank.save(buf, format="PNG")

    with pytest.raises(CardNotDetectedError):
        extract_cin_fields(buf.getvalue())
