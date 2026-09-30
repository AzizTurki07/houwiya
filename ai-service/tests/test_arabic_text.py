import pytest

from app.arabic_text import clean, clean_name, fold, match_month, parse_arabic_date


@pytest.mark.parametrize("text, expected", [
    ("02 اكتوبر 2001", "2001-10-02"),
    ("26 سبتمبر 2019", "2019-09-26"),
    ("٠٢ أكتوبر ٢٠٠١", "2001-10-02"),         # Arabic-Indic digits, hamza on the alef
    ("14/09/1996", "1996-09-14"),             # numeric form
    ("03 مارس 2018", "2018-03-03"),
    ("15 جويلية 1990", "1990-07-15"),         # Tunisian month name
    ("15 يوليو 1990", "1990-07-15"),          # Middle-Eastern month name
])
def test_parses_dates_as_printed(text, expected):
    assert parse_arabic_date(text) == expected


@pytest.mark.parametrize("ocr_output, expected", [
    ("02 اكتوير 2001", "2001-10-02"),         # misread month letters
    ("نرشفٍ 26 سيتمير 2019", "2019-09-26"),   # label residue + misread month
    ("2 02 اكتوبير 2001", "2001-10-02"),      # stray digit: the day nearest the month wins
])
def test_recovers_dates_from_real_ocr_noise(ocr_output, expected):
    assert parse_arabic_date(ocr_output) == expected


@pytest.mark.parametrize("text", ["غير مقروء", "02 اكتوبر", "31 فيفري 2001", "02 اكتوبر 1850", ""])
def test_rejects_unparseable_or_impossible_dates(text):
    assert parse_arabic_date(text) is None


def test_month_matching_is_fuzzy_but_not_reckless():
    assert match_month("سبتمير") == 9
    assert match_month("قلم") is None


def test_clean_strips_bidi_marks_and_junk():
    assert clean("سب يتروكي ‏|") == "سب يتروكي"


def test_clean_name_drops_label_slivers_and_digits():
    assert clean_name("م بنزرت") == "بنزرت"
    assert clean_name("محمد عزيز 0") == "محمد عزيز"
    assert clean_name("بن محمد بن 'براهيم") == "بن محمد بن براهيم"


def test_fold_unifies_letter_variants_only():
    assert fold("أمين") == fold("امين")
    assert fold("الروابى") == fold("الروابي")
    assert fold("هراقة") == fold("هراقه")
    assert fold("تركي") != fold("سركي")
