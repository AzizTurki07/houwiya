"""
Face match + liveness. Real-face tests use scikit-image's bundled public-domain sample photos
("astronaut": Eileen Collins, NASA; "camera": the cameraman; "chelsea": a cat) -- never
real applicants.
"""
import cv2
import numpy as np
import pytest
import skimage.data as samples

from app.face import (
    MATCH_THRESHOLD,
    Face,
    check_liveness,
    detect_faces,
    verify,
    yaw,
)


def _bgr(name: str) -> np.ndarray:
    img = getattr(samples, name)()
    return cv2.cvtColor(img, cv2.COLOR_GRAY2BGR if img.ndim == 2 else cv2.COLOR_RGB2BGR)


def _png(img: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", img)
    assert ok
    return buf.tobytes()


def _on_a_card(portrait: np.ndarray) -> np.ndarray:
    """A document-like image: the portrait small, in the corner of a pale card."""
    card = np.full((630, 1000, 3), 240, np.uint8)
    card[180:430, 60:310] = cv2.resize(portrait, (250, 250))
    return card


# ---- matching on real photographs ----

def test_same_person_matches_across_document_and_selfie():
    astronaut = _bgr("astronaut")
    result = verify(_png(_on_a_card(astronaut)), [_png(astronaut)] * 3)
    assert result.document_face_found and result.selfie_face_found
    assert result.match is True
    assert result.similarity >= MATCH_THRESHOLD


def test_different_people_do_not_match():
    result = verify(_png(_on_a_card(_bgr("astronaut"))), [_png(_bgr("camera"))] * 3)
    assert result.match is False
    assert result.similarity < MATCH_THRESHOLD


def test_a_selfie_without_a_face_is_reported_not_raised():
    result = verify(_png(_on_a_card(_bgr("astronaut"))), [_png(_bgr("chelsea"))] * 3)
    assert result.document_face_found is True
    assert result.selfie_face_found is False
    assert result.match is None
    assert result.liveness.passed is False and "no face" in result.liveness.reason


def test_a_still_photo_repeated_fails_liveness():
    # The same still image three times: right person, but the head never turns.
    astronaut = _bgr("astronaut")
    result = verify(_png(_on_a_card(astronaut)), [_png(astronaut)] * 3)
    assert result.liveness.passed is False
    assert "turn" in result.liveness.reason


def test_undecodable_images_raise():
    with pytest.raises(ValueError):
        verify(b"not an image", [b"x", b"y", b"z"])


def test_yaw_is_near_zero_for_a_frontal_face_and_large_for_a_turned_one():
    assert abs(yaw(detect_faces(_bgr("astronaut"))[0])) < 0.12
    assert abs(yaw(detect_faces(_bgr("camera"))[0])) > 0.3


# ---- liveness decision on synthetic landmarks ----

def _face(nose_offset: float) -> Face:
    """Eyes 60 px apart; nose shifted horizontally by nose_offset * eye distance."""
    right_eye, left_eye = np.array([100.0, 100.0]), np.array([160.0, 100.0])
    nose = np.array([130.0 + nose_offset * 60, 130.0])
    landmarks = np.vstack([right_eye, left_eye, nose, [110.0, 160.0], [150.0, 160.0]])
    return Face(np.array([80.0, 60.0, 100.0, 130.0]), landmarks, 0.95, np.zeros(15, np.float32))


SAME = np.ones((1, 128), np.float32)
OTHER = np.concatenate([np.ones((1, 64)), -np.ones((1, 64))], axis=1).astype(np.float32)
FRAMES = [np.zeros((10, 10, 3), np.uint8)] * 3


def test_liveness_passes_straight_then_both_turns():
    result = check_liveness(FRAMES, [[_face(0.0)], [_face(-0.3)], [_face(0.3)]], [SAME, SAME, SAME])
    assert result.passed, result.reason


def test_liveness_accepts_either_turn_order_and_mirroring():
    assert check_liveness(FRAMES, [[_face(0.02)], [_face(0.3)], [_face(-0.25)]], [SAME] * 3).passed


def test_liveness_fails_when_turning_only_one_way():
    result = check_liveness(FRAMES, [[_face(0.0)], [_face(0.3)], [_face(0.35)]], [SAME] * 3)
    assert not result.passed and "both ways" in result.reason


def test_liveness_fails_when_the_first_frame_is_not_frontal():
    result = check_liveness(FRAMES, [[_face(0.3)], [_face(-0.3)], [_face(0.3)]], [SAME] * 3)
    assert not result.passed and "straight" in result.reason


def test_liveness_fails_when_the_person_changes_between_frames():
    result = check_liveness(FRAMES, [[_face(0.0)], [_face(-0.3)], [_face(0.3)]], [SAME, SAME, OTHER])
    assert not result.passed and "different person" in result.reason


def test_liveness_fails_with_a_second_face_in_frame():
    result = check_liveness(FRAMES, [[_face(0.0)], [_face(-0.3), _face(0.0)], [_face(0.3)]], [SAME] * 3)
    assert not result.passed and "more than one face" in result.reason
