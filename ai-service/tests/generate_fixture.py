"""
Generates a synthetic passport bio-page image with a valid TD3 MRZ for testing.
This is NOT a real document -- fully synthetic data, safe to commit to the repo
as a test fixture (see roadmap note: never use real people's ID photos for tests).
"""
from PIL import Image, ImageDraw, ImageFont

def check_digit(s: str) -> str:
    weights = [7, 3, 1]
    total = 0
    for i, ch in enumerate(s):
        if ch.isdigit():
            v = int(ch)
        elif ch == '<':
            v = 0
        else:
            v = ord(ch) - ord('A') + 10
        total += v * weights[i % 3]
    return str(total % 10)

def build_td3_mrz(surname, given_names, country, nationality, passport_number,
                   dob_yymmdd, sex, expiry_yymmdd):
    name_field = f"{surname}<<{given_names}".replace(" ", "<")
    line1 = f"P<{country}{name_field}"
    line1 = (line1 + "<" * 44)[:44]

    number = (passport_number + "<" * 9)[:9]
    number_cd = check_digit(number)
    dob_cd = check_digit(dob_yymmdd)
    expiry_cd = check_digit(expiry_yymmdd)
    personal_number = "<" * 14
    personal_cd = check_digit(personal_number)

    composite_input = number + number_cd + dob_yymmdd + dob_cd + expiry_yymmdd + expiry_cd + personal_number + personal_cd
    composite_cd = check_digit(composite_input)

    line2 = f"{number}{number_cd}{nationality}{dob_yymmdd}{dob_cd}{sex}{expiry_yymmdd}{expiry_cd}{personal_number}{personal_cd}{composite_cd}"
    line2 = (line2 + "<" * 44)[:44]
    return line1, line2

if __name__ == "__main__":
    line1, line2 = build_td3_mrz(
        surname="BENALI", given_names="SAMI", country="TUN", nationality="TUN",
        passport_number="PT4021988", dob_yymmdd="950311", sex="M", expiry_yymmdd="300101"
    )
    print(line1)
    print(line2)

    img = Image.new("RGB", (900, 620), "white")
    draw = ImageDraw.Draw(img)
    try:
        font_body = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
        font_mrz = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", 30)
    except Exception:
        font_body = ImageFont.load_default()
        font_mrz = ImageFont.load_default()

    draw.rectangle([0, 0, 899, 619], outline="black", width=2)
    draw.text((40, 40), "REPUBLIQUE TUNISIENNE", font=font_body, fill="black")
    draw.text((40, 80), "PASSPORT / PASSEPORT", font=font_body, fill="black")
    draw.text((40, 140), "Surname/Nom: BENALI", font=font_body, fill="black")
    draw.text((40, 175), "Given names/Prenoms: SAMI", font=font_body, fill="black")
    draw.text((40, 210), "Nationality: TUNISIAN", font=font_body, fill="black")
    draw.rectangle([600, 130, 820, 330], outline="black", width=2)
    draw.text((650, 210), "PHOTO", font=font_body, fill="black")

    draw.text((45, 500), line1, font=font_mrz, fill="black")
    draw.text((45, 545), line2, font=font_mrz, fill="black")

    img.save("/home/claude/id-onboarding-platform/ai-service/tests/fixtures/sample_passport.png")
    print("Saved sample_passport.png")
