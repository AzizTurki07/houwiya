"""
Helpers for Arabic text read off the Tunisian CIN: digit/letter normalisation, cleanup of
OCR noise, and parsing dates written with Arabic month names ("02 اكتوبر 2001").
"""
import datetime
import difflib
import re
from typing import Optional

# Arabic-Indic and Persian digits -> ASCII.
_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")
# Tatweel, harakat (short-vowel marks) and invisible bidi controls Tesseract likes to emit.
_NOISE = re.compile(r"[\u0640\u064B-\u065F\u0670\u200B-\u200F\u202A-\u202E\u2066-\u2069]")
_NON_ARABIC_JUNK = re.compile(r"[^\u0600-\u06FF0-9\s\-/]")

# Tunisia (like the rest of the Maghreb) uses French-derived month names; the Middle-Eastern
# ones are included in case a card or OCR output uses them.
_MONTHS = {
    1: ["جانفي", "يناير"],
    2: ["فيفري", "فبراير"],
    3: ["مارس"],
    4: ["افريل", "ابريل"],
    5: ["ماي", "مايو"],
    6: ["جوان", "يونيو"],
    7: ["جويلية", "يوليو"],
    8: ["اوت", "اغسطس"],
    9: ["سبتمبر"],
    10: ["اكتوبر"],
    11: ["نوفمبر"],
    12: ["ديسمبر"],
}
_MONTH_LOOKUP = {name: month for month, names in _MONTHS.items() for name in names}


def normalize_digits(text: str) -> str:
    return text.translate(_DIGITS)


def clean(text: str) -> str:
    """Strip OCR noise: bidi marks, tatweel, stray Latin/punctuation, extra whitespace."""
    text = normalize_digits(_NOISE.sub("", text or ""))
    text = _NON_ARABIC_JUNK.sub(" ", text)
    return " ".join(text.split())


def clean_name(text: str) -> str:
    """
    Like clean(), for names and places: digits can't occur, and single-letter tokens at
    either end are almost always a leftover sliver of the printed field label.
    """
    tokens = [t for t in clean(text).split() if not any(c.isdigit() for c in t)]
    while tokens and len(tokens[0]) == 1:
        tokens.pop(0)
    while tokens and len(tokens[-1]) == 1:
        tokens.pop()
    return " ".join(tokens)


def fold(text: str) -> str:
    """
    Loose comparison form: unify letter variants that OCR (and people) mix up freely --
    alef forms, alef maqsura/ya, taa marbuta/ha. For matching and scoring only, never
    for values shown to the user.
    """
    text = clean(text)
    text = re.sub("[أإآٱ]", "ا", text)
    return text.replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")


def match_month(word: str) -> Optional[int]:
    folded = fold(word)
    if folded in _MONTH_LOOKUP:
        return _MONTH_LOOKUP[folded]
    close = difflib.get_close_matches(folded, _MONTH_LOOKUP.keys(), n=1, cutoff=0.6)
    return _MONTH_LOOKUP[close[0]] if close else None


def parse_arabic_date(text: str) -> Optional[str]:
    """
    "02 اكتوبر 2001" -> "2001-10-02". Also accepts numeric dd/mm/yyyy. Returns None when no
    plausible date can be recovered, so the caller can flag the field for manual review.
    """
    text = clean(text)
    numeric = re.search(r"(\d{1,2})\s*[/\-.]\s*(\d{1,2})\s*[/\-.]\s*(\d{4})", text)
    if numeric:
        day, month, year = (int(g) for g in numeric.groups())
        return _iso(year, month, day)

    year_match = re.search(r"(?<!\d)(\d{4})(?!\d)", text)
    if not year_match:
        return None
    rest = text[:year_match.start()] + " " * (year_match.end() - year_match.start()) + text[year_match.end():]
    month, month_pos = None, 0
    for word in re.finditer(r"[\u0600-\u06FF]+", rest):
        month = match_month(word.group())
        if month:
            month_pos = word.start()
            break
    if month is None:
        return None
    # The day sits next to the month; stray digits (label residue) further away are ignored.
    days = list(re.finditer(r"(?<!\d)(\d{1,2})(?!\d)", rest))
    if not days:
        return None
    day = min(days, key=lambda m: abs(m.start() - month_pos))
    return _iso(int(year_match.group(1)), month, int(day.group(1)))


def _iso(year: int, month: int, day: int) -> Optional[str]:
    if not 1900 <= year <= datetime.date.today().year + 20:
        return None
    try:
        return datetime.date(year, month, day).isoformat()
    except ValueError:
        return None
