"""
Dictionary-guided correction for the CIN fields whose vocabulary is small and known:
places (governorates and main towns), the generic words of Tunisian addresses, and common
professions. OCR mostly fails on these by one or two letters (بنزرت -> بنررت, سيدي -> سبدي),
so a token is replaced by the closest known word -- but only when it is very close, never
for personal names, and never when the token is already a known word.
"""
from typing import Iterable, Optional

from app.arabic_text import fold

# The 24 governorates, plus larger towns that commonly appear as a place of birth/address.
PLACES = [
    "تونس", "أريانة", "بن عروس", "منوبة", "نابل", "زغوان", "بنزرت", "باجة", "جندوبة", "الكاف",
    "سليانة", "سوسة", "المنستير", "المهدية", "صفاقس", "القيروان", "القصرين", "سيدي بوزيد", "قابس",
    "مدنين", "تطاوين", "قفصة", "توزر", "قبلي",
    "منزل بورقيبة", "منزل جميل", "منزل عبد الرحمان", "رأس الجبل", "ماطر", "غار الملح", "العالية",
    "حمام الأنف", "حمام سوسة", "الحمامات", "قليبية", "المرسى", "حلق الوادي", "جرجيس", "جربة",
    "الزهراء", "رادس", "المحمدية", "قرقنة", "المكنين", "الجم", "دوز", "المتلوي", "طبرقة",
]

# Generic address vocabulary (street types, common qualifiers and place-name words).
ADDRESS_WORDS = [
    "نهج", "شارع", "طريق", "حي", "زنقة", "ساحة", "عمارة", "شقة", "مدخل", "الطابق", "إقامة", "مسلك",
    "سيدي", "وادي", "المدينة", "الجديدة", "العتيقة", "الشمالية", "الجنوبية", "المنزه",
    "الحبيب", "بورقيبة", "الجمهورية", "الاستقلال", "الحرية", "جانفي", "مارس", "أفريل", "ماي",
    "جوان", "جويلية", "أوت", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
] + [p for p in PLACES if " " not in p]
# Deliberately generic: local neighbourhood names seen in the evaluation samples are *not*
# listed, or the accuracy report would partly be scoring the samples against themselves.

PROFESSIONS = [
    "تلميذ", "تلميذة", "طالب", "طالبة", "عامل", "عاملة", "موظف", "موظفة", "شؤون المنزل", "حلاق",
    "حلاقة", "معلم", "معلمة", "أستاذ", "أستاذة", "مهندس", "مهندسة", "طبيب", "طبيبة", "ممرض", "ممرضة",
    "فلاح", "تاجر", "سائق", "متقاعد", "متقاعدة", "بدون", "عاطل", "حرفي", "بحار", "جندي", "إطار",
    "محاسب", "محامي", "صيدلي", "خياطة", "بناء", "نجار", "كهربائي", "ميكانيكي",
]

_FOLDED = {}


def _vocab(words: Iterable[str]):
    key = id(words)
    if key not in _FOLDED:
        _FOLDED[key] = {fold(w): w for w in words}
    return _FOLDED[key]


def _distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def closest(text: str, words: Iterable[str]) -> Optional[str]:
    """
    The known word nearest to `text` (compared in folded form) if it is a near miss: at most
    one letter off for words up to 6 letters, two for longer ones. A similarity *ratio* is not
    enough for short words -- "سالم" (a name) vs "الجم" (a town) scores 0.75 yet is two edits
    apart, and must not be "corrected".
    """
    vocab = _vocab(words)
    folded = fold(text)
    if folded in vocab:
        return vocab[folded]
    best, best_distance = None, None
    for key, word in vocab.items():
        allowed = 1 if len(key) <= 6 else 2
        if abs(len(key) - len(folded)) > allowed:
            continue
        d = _distance(folded, key)
        if d <= allowed and (best_distance is None or d < best_distance):
            best, best_distance = word, d
    return best


def correct_phrase(text: Optional[str], words: Iterable[str]) -> Optional[str]:
    """Whole-value correction (place of birth, profession): replace only a near miss."""
    if not text:
        return text
    return closest(text, words) or text


def correct_tokens(text: Optional[str], words: Iterable[str]) -> Optional[str]:
    """
    Token-by-token correction (address). Short tokens (< 4 letters) and anything with a digit
    or punctuation (house numbers, abbreviations like "ب.المدينة") are left alone: too little
    to go on, too easy to break.
    """
    if not text:
        return text
    out = []
    for token in text.split():
        if len(token) < 4 or not all("؀" <= c <= "ۿ" for c in token):
            out.append(token)
        else:
            out.append(closest(token, words) or token)
    return " ".join(out)
