"""
Generates a synthetic Tunisian-CIN-style card image, then pastes it rotated
onto a plain background to simulate a real captured photo -- this exercises
the OpenCV detect+crop step, not just the OCR step.

Fully synthetic data (fictional name/number). Safe to commit as a test
fixture. See roadmap note: never use real people's ID photos for tests.
"""
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from PIL import Image, ImageDraw, ImageFont
from app.cin_extraction import CARD_W, CARD_H, FIELD_REGIONS

FIELDS = {
    "last_name": "BEN SALAH",
    "first_name": "AMINE",
    "date_of_birth": "14/09/1996",
    "place_of_birth": "SFAX",
    "document_number": "07845213",
    "address": "12 RUE DE LA LIBERTE, TUNIS",
}

LABELS = {
    "last_name": "Nom:",
    "first_name": "Prenom:",
    "date_of_birth": "Ne(e) le:",
    "place_of_birth": "a:",
    "document_number": "N. Carte:",
    "address": "Adresse:",
}


def build_card() -> Image.Image:
    card = Image.new("RGB", (CARD_W, CARD_H), "#F5F2EA")
    draw = ImageDraw.Draw(card)

    try:
        font_title = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 24)
        font_label = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 18)
        font_value = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 26)
    except Exception:
        font_title = font_label = font_value = ImageFont.load_default()

    draw.rectangle([0, 0, CARD_W - 1, CARD_H - 1], outline="black", width=3)
    draw.text((30, 30), "REPUBLIQUE TUNISIENNE", font=font_title, fill="black")
    draw.text((30, 65), "CARTE D'IDENTITE NATIONALE", font=font_title, fill="black")

    # Photo placeholder, left side
    draw.rectangle([30, 140, 360, 540], outline="black", width=2)
    draw.text((150, 320), "PHOTO", font=font_label, fill="black")

    # Draw each label just to the left of its calibrated value region,
    # and the value centered inside the exact region the extraction
    # pipeline will crop -- keeps fixture and pipeline in lockstep.
    for field, (x1, y1, x2, y2) in FIELD_REGIONS.items():
        draw.text((x1 - 150, y1 + 8), LABELS[field], font=font_label, fill="black")
        draw.text((x1 + 10, y1 + 8), FIELDS[field], font=font_value, fill="black")

    return card


if __name__ == "__main__":
    card = build_card()

    # Simulate a real captured photo: paste the card, slightly rotated, onto
    # a plain contrasting background so the OpenCV contour-detection step is
    # actually exercised rather than trivially skipped.
    bg = Image.new("RGB", (1400, 1000), "#7D8996")
    rotated = card.rotate(-6, expand=True, fillcolor="#7D8996")
    offset = ((bg.width - rotated.width) // 2, (bg.height - rotated.height) // 2)
    bg.paste(rotated, offset)

    out_path = os.path.join(os.path.dirname(__file__), "fixtures", "sample_cin.png")
    bg.save(out_path)
    print(f"Saved {out_path}")
