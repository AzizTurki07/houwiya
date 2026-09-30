"""Unit tests for the CIN layout logic that real cards forced: adaptive ink threshold, row
splitting, the married-woman name layout, and dictionary correction."""
import os

import numpy as np

from app.cin_extraction import (
    _split_at_valleys,
    extract_cin_fields,
    ink_threshold,
    split_name_and_lineage,
)
from app.lexicon import ADDRESS_WORDS, PLACES, PROFESSIONS, correct_phrase, correct_tokens

FIXTURE_DIR = os.path.join(os.path.dirname(__file__), "fixtures")


# ---- ink threshold: text darkness varies a lot between photos ----

def _band(text_lightness: int, background: int = 225) -> np.ndarray:
    values = np.full(2000, background, float)
    values[:200] = text_lightness  # ~10% of pixels are text
    return values


def test_ink_threshold_separates_black_text():
    t = ink_threshold(_band(40))
    assert 40 <= t < 225  # text is lightness <= threshold


def test_ink_threshold_follows_faded_text():
    # Measured on a real faded card: text around lightness 144. A fixed cut-off at 100 missed it.
    t = ink_threshold(_band(144))
    assert 144 <= t < 225


def test_ink_threshold_never_turns_an_empty_band_into_text():
    assert ink_threshold(np.full(2000, 220.0)) <= 220 - 25


# ---- row splitting ----

def _hump(height, width=40):
    return np.concatenate([np.linspace(5, height, width // 2), np.linspace(height, 5, width // 2)])


def test_two_touching_lines_are_split_at_the_valley():
    profile = np.concatenate([_hump(100), [30, 28, 30], _hump(230)])
    pieces = _split_at_valleys(profile, 0, len(profile))
    assert len(pieces) == 2
    assert 38 <= pieces[0][1] <= 44


def test_a_single_line_is_not_split():
    profile = np.concatenate([_hump(200, 70)])
    assert _split_at_valleys(profile, 0, len(profile)) == [(0, len(profile))]


# ---- married woman's card: lineage on the name line, spouse line dropped ----

def test_a_mans_name_and_lineage_are_left_alone():
    values = {"first_name": "محمد عزيز", "lineage": "بن محمد بن ابراهيم"}
    conf = {"first_name": 0.8, "lineage": 0.7}
    split_name_and_lineage(values, conf)
    assert values == {"first_name": "محمد عزيز", "lineage": "بن محمد بن ابراهيم"}


def test_a_married_womans_lineage_moves_out_and_the_spouse_is_dropped():
    values = {"first_name": "سنية بنت محمد بن علي", "lineage": "حرم محمد تركي"}
    conf = {"first_name": 0.8, "lineage": 0.7}
    split_name_and_lineage(values, conf)
    assert values == {"first_name": "سنية", "lineage": "بنت محمد بن علي"}
    assert conf["lineage"] == 0.8


def test_an_ocr_damaged_bint_is_still_recognised():
    values = {"first_name": "زهرة بثت محمد بن ابراهيم", "lineage": "حرم سلطان"}
    split_name_and_lineage(values, {"first_name": 0.8, "lineage": 0.7})
    assert values["first_name"] == "زهرة"
    assert values["lineage"].endswith("محمد بن ابراهيم")


def test_a_first_name_that_merely_resembles_bint_is_not_split():
    values = {"first_name": "محمد ثابت", "lineage": "بن علي بن صالح"}
    split_name_and_lineage(values, {"first_name": 0.8, "lineage": 0.7})
    assert values == {"first_name": "محمد ثابت", "lineage": "بن علي بن صالح"}


def test_a_lone_spouse_line_is_never_returned():
    values = {"first_name": "سنية", "lineage": "حرم محمد تركي"}
    conf = {"first_name": 0.8, "lineage": 0.7}
    split_name_and_lineage(values, conf)
    assert values["lineage"] is None and conf["lineage"] == 0.0


def test_married_faded_card_end_to_end():
    result = extract_cin_fields(open(os.path.join(FIXTURE_DIR, "sample_cin_front_married_faded.png"), "rb").read())
    assert result.document_number == "05512873"
    assert result.first_name == "أمينة"
    assert result.lineage == "بنت محمد بن صالح"
    assert result.date_of_birth == "1992-01-07"
    # The spouse's name is printed on the card but must never come back.
    assert "حرم" not in str(result.model_dump()) and "التونسي" not in str(result.model_dump())


# ---- dictionary correction ----

def test_place_near_misses_are_corrected():
    for misread in ("بنررت", "بعزرت", "بتزرت", "ننزرت"):
        assert correct_phrase(misread, PLACES) == "بنزرت"


def test_unknown_or_distant_words_are_left_alone():
    assert correct_phrase("هزرت", PLACES) == "هزرت"         # two letters off: not a near miss
    assert correct_phrase("قرية مجهولة", PLACES) == "قرية مجهولة"


def test_address_corrects_generic_words_but_not_names_numbers_or_abbreviations():
    assert correct_tokens("نهج سبدي سالم 18 ب.المدينة بعزرت", ADDRESS_WORDS) == "نهج سيدي سالم 18 ب.المدينة بنزرت"


def test_profession_near_miss():
    assert correct_phrase("تلمين", PROFESSIONS) == "تلميذ"
