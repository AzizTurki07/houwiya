import os

import cv2
import numpy as np

from app.card_detection import find_card_quad

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "sample_cin_front.png")
# Where generate_cin_fixture.py placed the card's corners (TL, TR, BR, BL).
TRUE_CORNERS = np.array([[230, 190], [1210, 245], [1175, 860], [185, 800]], dtype=float)
TOLERANCE_PX = 20


def _max_corner_error(quad, truth=TRUE_CORNERS):
    assert quad is not None, "card not detected"
    return float(np.linalg.norm(quad - truth, axis=1).max())


def test_finds_a_card_on_a_plain_surface():
    assert _max_corner_error(find_card_quad(cv2.imread(FIXTURE))) < TOLERANCE_PX


def test_finds_a_card_with_a_thumb_over_a_corner():
    # Hand-held photos: a skin-coloured thumb hides the top-left corner and part of two edges.
    img = cv2.imread(FIXTURE)
    cv2.ellipse(img, (225, 200), (110, 70), -30, 0, 360, (120, 150, 205), -1)
    assert _max_corner_error(find_card_quad(img)) < 2 * TOLERANCE_PX


def test_finds_a_card_running_off_the_edge_of_the_photo():
    img = cv2.imread(FIXTURE)[:, :1190]  # cut through the card's right side
    quad = find_card_quad(img)
    assert quad is not None
    # Left corners must be right; the right side is wherever the photo ends.
    assert np.linalg.norm(quad[0] - TRUE_CORNERS[0]) < TOLERANCE_PX
    assert np.linalg.norm(quad[3] - TRUE_CORNERS[3]) < TOLERANCE_PX
    assert quad[1][0] > 1150 and quad[2][0] > 1150


def test_ignores_an_image_without_a_card():
    assert find_card_quad(np.full((600, 800, 3), 255, np.uint8)) is None
    assert find_card_quad(np.full((600, 800, 3), 60, np.uint8)) is None
