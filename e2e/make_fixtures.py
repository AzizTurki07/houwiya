"""
Synthetic inputs for the end-to-end run -- no real person's document or face.

  passport_a.png / passport_b.png   synthetic TD3 passports (valid MRZ, different numbers),
                                    both with the same portrait in the photo box
  selfie.jpg                        that portrait: same person, but a still photo
  other.jpg                         a different person

The portraits are scikit-image's public-domain samples ("astronaut": Eileen Collins, NASA;
"camera": the cameraman). A still image can't pass the head-turn liveness check, so the e2e
run checks the real pipeline's match / mismatch / liveness-failed outcomes; liveness passing
is unit-tested in ai-service/tests/test_face.py and backend FaceMatchTest.

Runs inside the AI service image, which has cv2, PIL, skimage and the MRZ helper in tests/.
"""
import sys
from pathlib import Path

import cv2
import numpy as np
import skimage.data as samples
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, "/app/tests")
from generate_fixture import build_td3_mrz  # noqa: E402

# Keep in step with run_e2e.py.
PASSPORTS = {"passport_a.png": "PT4021988", "passport_b.png": "PT7730154"}


def bgr(name: str) -> np.ndarray:
    img = getattr(samples, name)()
    return cv2.cvtColor(img, cv2.COLOR_GRAY2BGR if img.ndim == 2 else cv2.COLOR_RGB2BGR)


def passport(number: str, portrait: np.ndarray) -> Image.Image:
    line1, line2 = build_td3_mrz(
        surname="BENALI", given_names="SAMI", country="TUN", nationality="TUN",
        passport_number=number, dob_yymmdd="950311", sex="M", expiry_yymmdd="350101")
    img = Image.new("RGB", (1000, 680), "white")
    draw = ImageDraw.Draw(img)
    body = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
    mrz = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", 30)
    draw.rectangle([0, 0, 999, 679], outline="black", width=2)
    draw.text((40, 40), "REPUBLIQUE TUNISIENNE", font=body, fill="black")
    draw.text((40, 80), "PASSPORT / PASSEPORT", font=body, fill="black")
    draw.text((40, 140), "Surname/Nom: BENALI", font=body, fill="black")
    draw.text((40, 175), "Given names/Prenoms: SAMI", font=body, fill="black")
    photo = cv2.cvtColor(cv2.resize(portrait, (260, 260)), cv2.COLOR_BGR2RGB)
    img.paste(Image.fromarray(photo), (680, 120))
    draw.text((45, 560), line1, font=mrz, fill="black")
    draw.text((45, 605), line2, font=mrz, fill="black")
    return img


def main(out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    astronaut = bgr("astronaut")
    for filename, number in PASSPORTS.items():
        passport(number, astronaut).save(out / filename)
    cv2.imwrite(str(out / "selfie.jpg"), astronaut)
    cv2.imwrite(str(out / "other.jpg"), bgr("camera"))
    print("fixtures written to", out)


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else "/out"))
