"""
Selfie-to-document face matching and a head-turn liveness check.

Models (OpenCV Zoo, run with OpenCV's own DNN module -- no extra ML framework):
  - YuNet (face_detection_yunet_2023mar.onnx, MIT): face boxes + 5 landmarks.
  - SFace (face_recognition_sface_2021dec.onnx, Apache-2.0): 128-d face embeddings,
    compared by cosine similarity.

Liveness is an *active challenge*: the applicant takes a frame looking straight at the
camera and two frames with the head turned one way and then the other. A printed photo or a
still image on a screen cannot produce that change of head pose, and every frame must show
the same single person. It does not stop a replayed video or a 3-D mask -- that needs
passive anti-spoofing models or depth sensing, out of scope here.
"""
import os
from dataclasses import dataclass
from functools import lru_cache
from typing import List, Optional, Sequence

import cv2
import numpy as np

MODELS_DIR = os.environ.get("FACE_MODELS_DIR", os.path.join(os.path.dirname(os.path.dirname(__file__)), "models"))
DETECTOR_MODEL = "face_detection_yunet_2023mar.onnx"
RECOGNIZER_MODEL = "face_recognition_sface_2021dec.onnx"

# Match threshold on cosine similarity. SFace's published operating point is 0.363 (LFW), but
# on real Tunisian ID documents two *related* people scored 0.352 while the same person across
# a CIN and a passport photographed years apart scored 0.72 (ai-service/eval/README.md). A
# stricter threshold only sends more borderline cases to a human reviewer.
MATCH_THRESHOLD = 0.45
# Frames of one selfie sequence (same person, seconds apart) are compared with the looser
# published threshold: there the question is only "is this still the same face?".
SAME_SEQUENCE_THRESHOLD = 0.363
# Head-turn liveness, on the landmark yaw proxy below (~0 looking straight, grows with turn).
FRONTAL_MAX_YAW = 0.12
TURNED_MIN_YAW = 0.18
MIN_DETECTION_SCORE = 0.8


class FaceModelsMissing(RuntimeError):
    pass


@dataclass
class Face:
    box: np.ndarray        # x, y, w, h
    landmarks: np.ndarray  # 5 x 2: right eye, left eye, nose tip, right/left mouth corner
    score: float
    row: np.ndarray        # raw YuNet row, needed by SFace's alignCrop

    @property
    def area(self) -> float:
        return float(self.box[2] * self.box[3])


@lru_cache(maxsize=1)
def _models():
    detector_path = os.path.join(MODELS_DIR, DETECTOR_MODEL)
    recognizer_path = os.path.join(MODELS_DIR, RECOGNIZER_MODEL)
    for path in (detector_path, recognizer_path):
        if not os.path.exists(path):
            raise FaceModelsMissing(f"Face model not found: {path} (see ai-service/Dockerfile)")
    detector = cv2.FaceDetectorYN.create(detector_path, "", (320, 320), MIN_DETECTION_SCORE, 0.3, 5000)
    recognizer = cv2.FaceRecognizerSF.create(recognizer_path, "")
    return detector, recognizer


def decode(image_bytes: bytes) -> Optional[np.ndarray]:
    return cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)


def detect_faces(img: np.ndarray) -> List[Face]:
    """All faces, largest first. Large photos are scaled down for detection speed."""
    detector, _ = _models()
    scale = min(1.0, 1280.0 / max(img.shape[:2]))
    work = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1 else img
    detector.setInputSize((work.shape[1], work.shape[0]))
    _, rows = detector.detect(work)
    faces = []
    for row in ([] if rows is None else rows):
        full = row.copy()
        full[:14] /= scale  # box + landmarks back to the original image's coordinates
        faces.append(Face(full[0:4], full[4:14].reshape(5, 2), float(full[14]), full))
    return sorted(faces, key=lambda f: f.area, reverse=True)


def embedding(img: np.ndarray, face: Face) -> np.ndarray:
    _, recognizer = _models()
    return recognizer.feature(recognizer.alignCrop(img, face.row))


def similarity(a: np.ndarray, b: np.ndarray) -> float:
    _, recognizer = _models()
    return float(recognizer.match(a, b, cv2.FaceRecognizerSF_FR_COSINE))


def yaw(face: Face) -> float:
    """
    Signed head-turn proxy from landmarks: horizontal offset of the nose tip from the midpoint
    between the eyes, relative to the eye distance. ~0 looking straight; the sign flips with
    the direction of the turn (and with mirroring, so callers only rely on *opposite* signs).
    """
    right_eye, left_eye, nose = face.landmarks[0], face.landmarks[1], face.landmarks[2]
    eye_distance = float(np.linalg.norm(left_eye - right_eye)) or 1.0
    return float((nose[0] - (right_eye[0] + left_eye[0]) / 2) / eye_distance)


def document_portrait(img: np.ndarray) -> Optional[Face]:
    """The ID photo on a document: the largest face (a passport also carries a small ghost image)."""
    faces = detect_faces(img)
    return faces[0] if faces else None


@dataclass
class LivenessResult:
    passed: bool
    reason: Optional[str]
    yaws: List[Optional[float]]


def check_liveness(frames: Sequence[np.ndarray], frame_faces: Sequence[List[Face]],
                   frame_embeddings: Sequence[Optional[np.ndarray]]) -> LivenessResult:
    """
    frames[0] must look straight at the camera, frames[1:] turn the head -- at least one turn
    each way. Every frame must contain exactly one face, and all of them the same person.
    """
    yaws: List[Optional[float]] = [yaw(f[0]) if len(f) == 1 else None for f in frame_faces]
    if len(frames) < 3:
        return LivenessResult(False, "three selfie frames are required", yaws)
    for i, faces in enumerate(frame_faces):
        if not faces:
            return LivenessResult(False, f"no face in selfie frame {i + 1}", yaws)
        if len(faces) > 1:
            return LivenessResult(False, f"more than one face in selfie frame {i + 1}", yaws)
    if abs(yaws[0]) > FRONTAL_MAX_YAW:
        return LivenessResult(False, "the first frame must look straight at the camera", yaws)
    turned = [y for y in yaws[1:] if abs(y) >= TURNED_MIN_YAW]
    if not (any(y > 0 for y in turned) and any(y < 0 for y in turned)):
        return LivenessResult(False, "the head did not turn both ways", yaws)
    reference = frame_embeddings[0]
    for i, emb in enumerate(frame_embeddings[1:], start=2):
        if similarity(reference, emb) < SAME_SEQUENCE_THRESHOLD:
            return LivenessResult(False, f"selfie frame {i} shows a different person", yaws)
    return LivenessResult(True, None, yaws)


@dataclass
class Verification:
    document_face_found: bool
    selfie_face_found: bool
    similarity: Optional[float]
    match: Optional[bool]
    liveness: LivenessResult


def verify(document_bytes: bytes, selfie_frames: Sequence[bytes]) -> Verification:
    """Document portrait vs the frontal selfie frame, plus the head-turn liveness check."""
    document = decode(document_bytes)
    frames = [decode(b) for b in selfie_frames]
    if document is None or any(f is None for f in frames):
        raise ValueError("Could not decode one of the images.")

    portrait = document_portrait(document)
    frame_faces = [detect_faces(f) for f in frames]
    frame_embeddings = [embedding(f, faces[0]) if len(faces) == 1 else None
                        for f, faces in zip(frames, frame_faces)]
    liveness = check_liveness(frames, frame_faces, frame_embeddings) if all(e is not None for e in frame_embeddings)         else LivenessResult(False, _no_face_reason(frame_faces), [None] * len(frames))

    selfie_embedding = frame_embeddings[0] if frame_embeddings else None
    score = None
    if portrait is not None and selfie_embedding is not None:
        score = similarity(embedding(document, portrait), selfie_embedding)
    return Verification(
        document_face_found=portrait is not None,
        selfie_face_found=selfie_embedding is not None,
        similarity=None if score is None else round(score, 3),
        match=None if score is None else score >= MATCH_THRESHOLD,
        liveness=liveness,
    )


def _no_face_reason(frame_faces: Sequence[List[Face]]) -> str:
    for i, faces in enumerate(frame_faces):
        if not faces:
            return f"no face in selfie frame {i + 1}"
        if len(faces) > 1:
            return f"more than one face in selfie frame {i + 1}"
    return "three selfie frames are required"
