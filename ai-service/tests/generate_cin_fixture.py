"""
Generates synthetic Tunisian CIN photos (front + back) for the test suite.

Real cards can't be committed, so these reproduce the real layout with fictitious data:
Arabic-only, purple field labels with black values right-aligned beside them, a pale
patterned background, the photo on the front's left and the fingerprint box on the back's
right. The card is drawn at the pipeline's canonical 1000x630 size, then tilted and placed
on a darker surface like a phone photo, so tests exercise detection + warping as well.

Needs Pillow with raqm (Arabic shaping) and an Arabic font, e.g. in the AI service image:
    docker run --rm -v "$PWD/ai-service:/app" -w /app houwiya-ai sh -c \
      "apt-get update -qq && apt-get install -y -qq fonts-noto-core >/dev/null && \
       python tests/generate_cin_fixture.py"
"""
import os

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(__file__)
FONT_DIR = "/usr/share/fonts/truetype/noto"
# Noto Sans Arabic is the closest freely available match to the card's plain typeface.
REGULAR = os.path.join(FONT_DIR, "NotoSansArabic-Regular.ttf")
BOLD = os.path.join(FONT_DIR, "NotoSansArabic-Bold.ttf")

W, H = 1000, 630
CARD_BG = (246, 244, 250)
PURPLE = (112, 72, 160)
BLACK = (25, 25, 25)

FRONT = {
    "document_number": "07845213",
    "last_name": "بن سالم",
    "first_name": "أمين",
    "lineage": "بن محمد بن صالح",
    "date_of_birth": "14 سبتمبر 1996",
    "place_of_birth": "صفاقس",
}
# A married woman's card: the first-name line carries the lineage ("... بنت ... بن ...") and
# the next line is the spouse ("حرم ..."). Printed faded (grey, not black) -- both patterns
# broke the first version of the pipeline on real cards.
FRONT_MARRIED = {
    "document_number": "05512873",
    "last_name": "بن سالم",
    "first_name": "أمينة بنت محمد بن صالح",
    "lineage": "حرم علي التونسي",
    "date_of_birth": "07 جانفي 1992",
    "place_of_birth": "سوسة",
}
FADED = (115, 115, 118)

BACK = {
    "mother": "فاطمة الزهراء",
    "profession": "مهندس",
    "address_1": "12 نهج الحرية",
    "address_2": "ساقية الزيت صفاقس",
    "issue_date": "03 مارس 2018",
}


def font(size, bold=False):
    return ImageFont.truetype(BOLD if bold else REGULAR, size, layout_engine=ImageFont.Layout.RAQM)


def rtl(draw, right_x, y, text, size, fill, bold=False):
    """Draw Arabic text whose right edge is at right_x; returns the text's left edge."""
    f = font(size, bold)
    left, _, right, _ = draw.textbbox((0, 0), text, font=f, direction="rtl", language="ar")
    x = right_x - (right - left)
    draw.text((x - left, y), text, font=f, fill=fill, direction="rtl", language="ar")
    return x


def background(draw):
    # Pale decorative strokes, like the monument / dove artwork on the real card.
    for i in range(12):
        x = 380 + i * 45
        draw.line([(x, 200), (x + 60, 620)], fill=(228, 222, 236), width=6)
    draw.ellipse([520, 260, 760, 500], outline=(232, 226, 214), width=10)


def front(values=FRONT, ink=BLACK):
    img = Image.new("RGB", (W, H), CARD_BG)
    d = ImageDraw.Draw(img)
    background(d)
    d.rectangle([60, 40, 200, 140], fill=(200, 30, 45))                   # flag
    rtl(d, 720, 30, "الجمهورية التونسية", 40, PURPLE, bold=True)
    rtl(d, 790, 85, "بطاقة التعريف الوطنية", 52, PURPLE, bold=True)
    d.line([(20, 190), (980, 190)], fill=PURPLE, width=2)
    d.rectangle([60, 235, 300, 570], fill=(200, 200, 200))                 # photo
    d.text((405, 212), values["document_number"], font=font(50, bold=True), fill=ink)

    rows = [  # (label, value key, row top, label right edge)
        ("اللقب", "last_name", 315, 950),
        ("الاسم", "first_name", 372, 950),
        (None, "lineage", 425, 955),
        ("تاريخ الولادة", "date_of_birth", 475, 950),
        ("مكانها", "place_of_birth", 530, 950),
    ]
    for label, key, top, right in rows:
        if label:
            right = rtl(d, right, top + 12, label, 24, PURPLE) - 14
        rtl(d, right, top - 14, values[key], 44, ink, bold=True)
    return img


def back():
    img = Image.new("RGB", (W, H), CARD_BG)
    d = ImageDraw.Draw(img)
    background(d)
    right = rtl(d, 960, 85, "اسم ولقب الأم", 24, PURPLE) - 12
    rtl(d, right, 62, BACK["mother"], 42, BLACK, bold=True)
    right = rtl(d, 655, 150, "المهنة", 24, PURPLE) - 12
    rtl(d, right, 127, BACK["profession"], 42, BLACK, bold=True)
    right = rtl(d, 655, 215, "العنوان", 24, PURPLE) - 12
    rtl(d, right, 192, BACK["address_1"], 42, BLACK, bold=True)
    rtl(d, 655, 254, BACK["address_2"], 42, BLACK, bold=True)
    right = rtl(d, 440, 352, "تونس في", 24, PURPLE) - 12
    rtl(d, right, 329, BACK["issue_date"], 42, BLACK, bold=True)
    d.ellipse([490, 330, 640, 470], outline=(90, 120, 200), width=4)       # stamp
    d.rectangle([680, 160, 970, 440], fill=(70, 70, 70))                   # fingerprint
    d.text((740, 455), "17501978", font=font(30), fill=BLACK)
    d.rectangle([0, 500, W, H], fill=(236, 240, 238))
    for i in range(60):                                                    # barcode
        if i % 3:
            d.rectangle([240 + i * 8, 515, 243 + i * 8, 600], fill=BLACK)
    return img


def photograph(card: Image.Image) -> np.ndarray:
    """Place the card, slightly rotated and in perspective, on a dark textured surface."""
    canvas_w, canvas_h = 1400, 1050
    rng = np.random.default_rng(7)
    surface = np.full((canvas_h, canvas_w, 3), (70, 55, 60), np.uint8)
    surface = cv2.add(surface, rng.integers(0, 25, surface.shape, dtype=np.uint8))
    src = np.array([[0, 0], [W, 0], [W, H], [0, H]], dtype="float32")
    dst = np.array([[230, 190], [1210, 245], [1175, 860], [185, 800]], dtype="float32")
    warped = cv2.warpPerspective(cv2.cvtColor(np.array(card), cv2.COLOR_RGB2BGR),
                                 cv2.getPerspectiveTransform(src, dst), (canvas_w, canvas_h))
    mask = cv2.warpPerspective(np.full((H, W), 255, np.uint8), cv2.getPerspectiveTransform(src, dst),
                               (canvas_w, canvas_h))
    surface[mask > 0] = warped[mask > 0]
    return surface


if __name__ == "__main__":
    out_dir = os.path.join(HERE, "fixtures")
    for name, card in (("sample_cin_front.png", front()), ("sample_cin_back.png", back()),
                       ("sample_cin_front_married_faded.png", front(FRONT_MARRIED, FADED))):
        path = os.path.join(out_dir, name)
        cv2.imwrite(path, photograph(card))
        print("wrote", path)
