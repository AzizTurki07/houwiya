"""
Locate an ID-1 card (85.60 x 53.98 mm, e.g. the Tunisian CIN) in a phone photo and
warp it to a canonical, axis-aligned image.

Real photos are usually taken with the card held in the hand, so a plain "largest
4-sided contour" search is fragile: fingers cover corners, and part of the card can
run off the edge of the frame. Instead:

  1. Segment the card as the largest bright, low-chroma region (card stock is close to
     neutral white; skin is reddish, most backgrounds are darker or more saturated).
  2. Collect straight edge segments near that region's outline (Canny + Hough), plus the
     image border where the card runs off the frame.
  3. Pick the two near-parallel pairs of lines whose rectangle has the most real edge
     support and an aspect ratio close to ID-1. A side hidden by a finger is inferred
     from the opposite side and the known aspect ratio, at a scoring penalty.
"""
import itertools
import math
from typing import List, Optional, Tuple

import cv2
import numpy as np

# Canonical output size, ID-1 aspect ratio (85.60 / 53.98 ~= 1.586). Field regions in the
# extraction pipelines are calibrated against exactly this size.
CARD_W = 1000
CARD_H = 630
ID1_ASPECT = 85.60 / 53.98
CROP_MARGIN = 0.04  # total growth (2% per side) applied to the detected quad before warping

# [theta, rho, support, kind]: a line in Hesse normal form, x*cos(theta) + y*sin(theta) = rho.
# support = total length of edge segments (or border pixels) backing it; kind says where it
# came from, since the weaker kinds are penalised when scoring a candidate quad.
EDGE, BORDER, INFERRED = "edge", "border", "inferred"
Line = list


class CardNotDetectedError(Exception):
    """Raised when no card-shaped region could be located in the image."""


def decode_image(image_bytes: bytes) -> np.ndarray:
    img = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise CardNotDetectedError("Could not decode the uploaded image.")
    return img


def detect_and_crop_card(image_bytes: bytes) -> np.ndarray:
    """Returns the card warped to CARD_W x CARD_H, or raises CardNotDetectedError."""
    img = decode_image(image_bytes)
    quad = find_card_quad(img)
    if quad is None:
        raise CardNotDetectedError(
            "Could not detect the card outline. Retake the photo with the whole card in "
            "frame, held flat, against a darker background."
        )
    # Grow the quad slightly: an inferred side (finger over the edge) is only an estimate
    # under perspective, and a sliver of background is harmless where clipped text is not.
    center = quad.mean(axis=0)
    quad = center + (quad - center) * (1 + CROP_MARGIN)
    dst = np.array([[0, 0], [CARD_W - 1, 0], [CARD_W - 1, CARD_H - 1], [0, CARD_H - 1]], dtype="float32")
    return cv2.warpPerspective(img, cv2.getPerspectiveTransform(quad.astype("float32"), dst), (CARD_W, CARD_H),
                               borderMode=cv2.BORDER_REPLICATE)


def find_card_quad(img: np.ndarray) -> Optional[np.ndarray]:
    """Four corners (TL, TR, BR, BL) of the card in image coordinates, or None."""
    # Work on a bounded size: keeps thresholds meaningful and Hough fast on 12MP photos.
    scale = 1000.0 / max(img.shape[:2])
    small = cv2.resize(img, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA) if scale < 1 else img
    scale = min(scale, 1.0)

    blob = _card_region(small)
    if blob is None:
        return None
    lines = [_segment_to_line(s) for s in _edge_segments(small, blob)] + _border_lines(blob)
    quad = _best_quad(small.shape[:2], lines)
    return None if quad is None else quad / scale


def _card_region(img: np.ndarray) -> Optional[np.ndarray]:
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB).astype(np.int16)
    lightness, a, b = lab[..., 0], lab[..., 1] - 128, lab[..., 2] - 128
    # Measured on real photos: card 200-220, thumb ~130, table/cloth backgrounds 115-150.
    mask = ((np.sqrt(a * a + b * b) < 18) & (lightness > 165)).astype(np.uint8) * 255
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel, iterations=2)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
    if count < 2:
        return None
    biggest = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    if stats[biggest, cv2.CC_STAT_AREA] < 0.08 * img.shape[0] * img.shape[1]:
        return None
    return (labels == biggest).astype(np.uint8) * 255


def _edge_segments(img: np.ndarray, blob: np.ndarray) -> List[np.ndarray]:
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    outline = cv2.morphologyEx(blob, cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8))
    band = cv2.dilate(outline, np.ones((31, 31), np.uint8))
    edges = (cv2.Canny(cv2.GaussianBlur(gray, (5, 5), 0), 40, 120) & band) | outline
    min_len = int(0.12 * min(img.shape[:2]))
    segs = cv2.HoughLinesP(edges, 1, np.pi / 360, threshold=60, minLineLength=min_len, maxLineGap=15)
    return [] if segs is None else [s[0] for s in segs]


def _segment_to_line(seg: np.ndarray) -> Line:
    x1, y1, x2, y2 = map(float, seg)
    theta = math.atan2(x2 - x1, -(y2 - y1))
    rho = x1 * math.cos(theta) + y1 * math.sin(theta)
    if rho < 0:
        theta, rho = theta + math.pi, -rho
    return [theta % (2 * math.pi), rho, math.hypot(x2 - x1, y2 - y1), EDGE]


def _border_lines(blob: np.ndarray) -> List[Line]:
    """The card running off the edge of the photo: that image border is its edge there."""
    h, w = blob.shape
    candidates = (
        (np.count_nonzero(blob[:, -1]), 0.0, w - 1.0, h),                # right
        (np.count_nonzero(blob[:, 0]), math.pi, 0.0, h),                 # left
        (np.count_nonzero(blob[-1, :]), math.pi / 2, h - 1.0, w),        # bottom
        (np.count_nonzero(blob[0, :]), 3 * math.pi / 2, 0.0, w),         # top
    )
    return [[theta, rho, float(touching), BORDER] for touching, theta, rho, size in candidates if touching > 0.15 * size]


def _merge(lines: List[Line]) -> List[Line]:
    merged: List[Line] = []
    for theta, rho, support, kind in sorted(lines, key=lambda l: -l[2]):
        for m in merged:
            dtheta = abs((theta - m[0] + math.pi) % (2 * math.pi) - math.pi)
            if kind == m[3] and dtheta < math.radians(4) and abs(rho - m[1]) < 25:
                m[2] += support
                break
        else:
            merged.append([theta, rho, support, kind])
    return merged


def _intersect(l1, l2) -> Optional[np.ndarray]:
    a = np.array([[math.cos(l1[0]), math.sin(l1[0])], [math.cos(l2[0]), math.sin(l2[0])]])
    if abs(np.linalg.det(a)) < 1e-6:
        return None
    return np.linalg.solve(a, np.array([l1[1], l2[1]]))


def _separation(l1, l2) -> float:
    foot = np.array([l2[1] * math.cos(l2[0]), l2[1] * math.sin(l2[0])])
    return abs(foot @ np.array([math.cos(l1[0]), math.sin(l1[0])]) - l1[1])


def order_corners(pts: np.ndarray) -> np.ndarray:
    """Order 4 points as top-left, top-right, bottom-right, bottom-left."""
    rect = np.zeros((4, 2), dtype="float32")
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]
    rect[2] = pts[np.argmax(s)]
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]
    rect[3] = pts[np.argmax(diff)]
    return rect


def _best_quad(shape: Tuple[int, int], raw_lines: List[Line]) -> Optional[np.ndarray]:
    h, w = shape
    lines = _merge(raw_lines)
    if not lines:
        return None
    ref = lines[0][0] % math.pi

    def off_axis(line) -> float:
        return abs(((line[0] % math.pi) - ref + math.pi / 2) % math.pi - math.pi / 2)

    # Two roughly perpendicular families; keep the best-supported few of each.
    group_a = [l for l in lines if off_axis(l) < math.radians(15)][:6]
    group_b = [l for l in lines if abs(off_axis(l) - math.pi / 2) < math.radians(15)][:6]

    def inferred_pairs(group, other_pair):
        # A side hidden by a finger: the line opposite `line`, placed where the known
        # aspect ratio says it must be given the other pair's spacing.
        d = _separation(*other_pair)
        for line in group:
            for size in (d * ID1_ASPECT, d / ID1_ASPECT):
                for sign in (1, -1):
                    yield line, [line[0], line[1] + sign * size, 0.0, INFERRED]

    candidates = []
    pairs_a = list(itertools.combinations(group_a, 2))
    pairs_b = list(itertools.combinations(group_b, 2))
    for pa in pairs_a:
        for pb in pairs_b:
            candidates.append((pa, pb))
    for pb in pairs_b:
        candidates.extend((pa, pb) for pa in inferred_pairs(group_a, pb))
    for pa in pairs_a:
        candidates.extend((pa, pb) for pb in inferred_pairs(group_b, pa))

    best, best_score = None, 0.0
    for (a1, a2), (b1, b2) in candidates:
        sides = (a1, a2, b1, b2)
        if sum(1 for s in sides if s[3] == EDGE) < 2:
            continue
        # Side lengths come from the spacing of the opposite pair.
        len_a, len_b = _separation(b1, b2), _separation(a1, a2)
        if min(len_a, len_b) < 1:
            continue
        aspect_err = abs(math.log((max(len_a, len_b) / min(len_a, len_b)) / ID1_ASPECT))
        if aspect_err > 0.15:
            continue
        # A photo-border side only counts if the card runs along most of it; a patch of pale
        # background touching the border (a leak) doesn't.
        if any(s[3] == BORDER and s[2] < 0.6 * length
               for s, length in ((a1, len_a), (a2, len_a), (b1, len_b), (b2, len_b))):
            continue
        corners = [_intersect(x, y) for x in (a1, a2) for y in (b1, b2)]
        if any(c is None for c in corners):
            continue
        quad = order_corners(np.array(corners, dtype="float32"))
        if (quad[:, 0] < -0.05 * w).any() or (quad[:, 0] > 1.05 * w).any()                 or (quad[:, 1] < -0.05 * h).any() or (quad[:, 1] > 1.05 * h).any():
            continue
        area = cv2.contourArea(quad) / float(w * h)
        if area < 0.1:
            continue
        support = sum(s[2] for s in sides)
        # Area breaks ties with smaller rectangles sharing some card edges; weaker evidence
        # costs: an inferred side 30%, a border side 15%.
        penalty = 0.7 ** sum(1 for s in sides if s[3] == INFERRED) * 0.85 ** sum(1 for s in sides if s[3] == BORDER)
        score = support * (1 - 1.5 * aspect_err) * (0.5 + area) * penalty
        if score > best_score:
            best, best_score = quad, score

    if best is None:
        return None
    # Portrait result (card photographed sideways): rotate corner order to landscape.
    if np.linalg.norm(best[1] - best[0]) < np.linalg.norm(best[3] - best[0]):
        best = np.roll(best, -1, axis=0)
    return best
