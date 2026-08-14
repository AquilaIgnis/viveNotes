"""DB post-processing without OpenCV, written the way the Kotlin port has to be written.

PaddleOCR's `DBPostProcess` leans on `cv2.findContours`, `cv2.minAreaRect` and pyclipper. None of
those exist on Android, and pulling OpenCV in for three geometry routines would cost more than the
detector itself. So every step here is one that survives a transliteration into
`model/ocr/TextDetection.kt`: integer flood fill, monotone-chain hull, rotating calipers, and a
rectangle expansion that stands in for polygon offsetting.

The one deliberate divergence from PaddleOCR is where the unclip happens. Paddle offsets the
*contour* with Vatti clipping and then takes the minimum rectangle of the result; this takes the
minimum rectangle first and expands that. For a text line — which is what these components are —
the two agree closely, and the second is forty lines of Kotlin instead of a clipping library.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class Quad:
    """Four corners in clockwise order, in probability-map pixels."""

    points: tuple[tuple[float, float], ...]
    score: float

    def bounds(self) -> tuple[float, float, float, float]:
        xs: list[float] = [point[0] for point in self.points]
        ys: list[float] = [point[1] for point in self.points]
        return min(xs), min(ys), max(xs), max(ys)

    def height(self) -> float:
        (ax, ay), (bx, by), (cx, cy), _ = self.points
        return min(math.hypot(bx - ax, by - ay), math.hypot(cx - bx, cy - by))

    def width(self) -> float:
        (ax, ay), (bx, by), (cx, cy), _ = self.points
        return max(math.hypot(bx - ax, by - ay), math.hypot(cx - bx, cy - by))


def components(mask: np.ndarray, min_pixels: int) -> list[np.ndarray]:
    """Every 8-connected run of set pixels, as an array of its boundary points.

    Only boundary pixels are kept: the convex hull of a filled blob is the hull of its outline, and
    a paragraph-sized blob has thousands of interior pixels that can never be a hull vertex.
    """
    height, width = mask.shape
    seen: np.ndarray = np.zeros((height, width), dtype=bool)
    out: list[np.ndarray] = []
    stack: list[int] = []
    for start_y in range(height):
        for start_x in range(width):
            if not mask[start_y, start_x] or seen[start_y, start_x]:
                continue
            seen[start_y, start_x] = True
            stack.append(start_y * width + start_x)
            pixels: list[tuple[int, int]] = []
            while stack:
                index: int = stack.pop()
                y, x = divmod(index, width)
                pixels.append((x, y))
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < width and 0 <= ny < height:
                            if mask[ny, nx] and not seen[ny, nx]:
                                seen[ny, nx] = True
                                stack.append(ny * width + nx)
            if len(pixels) < min_pixels:
                continue
            out.append(boundary(pixels, mask))
    return out


def boundary(pixels: list[tuple[int, int]], mask: np.ndarray) -> np.ndarray:
    """Pixels with at least one 4-neighbour outside the component."""
    height, width = mask.shape
    kept: list[tuple[int, int]] = []
    for x, y in pixels:
        if (
            x == 0
            or y == 0
            or x == width - 1
            or y == height - 1
            or not mask[y, x - 1]
            or not mask[y, x + 1]
            or not mask[y - 1, x]
            or not mask[y + 1, x]
        ):
            kept.append((x, y))
    return np.array(kept if kept else pixels, dtype=np.float64)


def convex_hull(points: np.ndarray) -> np.ndarray:
    """Andrew's monotone chain, counter-clockwise in screen coordinates."""
    order: np.ndarray = np.lexsort((points[:, 1], points[:, 0]))
    sorted_points: np.ndarray = points[order]
    if len(sorted_points) <= 2:
        return sorted_points

    def half(sequence: np.ndarray) -> list[np.ndarray]:
        chain: list[np.ndarray] = []
        for point in sequence:
            while len(chain) >= 2 and cross(chain[-2], chain[-1], point) <= 0:
                chain.pop()
            chain.append(point)
        return chain

    lower: list[np.ndarray] = half(sorted_points)
    upper: list[np.ndarray] = half(sorted_points[::-1])
    return np.array(lower[:-1] + upper[:-1])


def cross(origin: np.ndarray, first: np.ndarray, second: np.ndarray) -> float:
    return float(
        (first[0] - origin[0]) * (second[1] - origin[1])
        - (first[1] - origin[1]) * (second[0] - origin[0])
    )


def min_area_rect(hull: np.ndarray) -> tuple[tuple[tuple[float, float], ...], float, float]:
    """The smallest enclosing rectangle, by rotating calipers over the hull's own edges.

    Returns its four corners plus its short and long side. The minimum-area rectangle always shares
    an edge with the hull, so trying each edge direction is exact rather than a search.
    """
    if len(hull) < 3:
        left, top = hull.min(axis=0)
        right, bottom = hull.max(axis=0)
        corners = ((left, top), (right, top), (right, bottom), (left, bottom))
        return corners, min(right - left, bottom - top), max(right - left, bottom - top)

    best: tuple[tuple[tuple[float, float], ...], float, float] | None = None
    best_area: float = math.inf
    for index in range(len(hull)):
        start: np.ndarray = hull[index]
        end: np.ndarray = hull[(index + 1) % len(hull)]
        edge: np.ndarray = end - start
        length: float = float(math.hypot(edge[0], edge[1]))
        if length < 1e-6:
            continue
        ux, uy = edge[0] / length, edge[1] / length
        along: np.ndarray = hull[:, 0] * ux + hull[:, 1] * uy
        across: np.ndarray = -hull[:, 0] * uy + hull[:, 1] * ux
        min_along, max_along = float(along.min()), float(along.max())
        min_across, max_across = float(across.min()), float(across.max())
        area: float = (max_along - min_along) * (max_across - min_across)
        if area >= best_area:
            continue
        best_area = area

        def point(a: float, b: float) -> tuple[float, float]:
            return (a * ux - b * uy, a * uy + b * ux)

        corners = (
            point(min_along, min_across),
            point(max_along, min_across),
            point(max_along, max_across),
            point(min_along, max_across),
        )
        best = (
            corners,
            min(max_along - min_along, max_across - min_across),
            max(max_along - min_along, max_across - min_across),
        )
    assert best is not None
    return best


def unclip(corners: tuple[tuple[float, float], ...], ratio: float) -> tuple[tuple[float, float], ...]:
    """Grow the rectangle outwards by Vatti's offset distance for its own area and perimeter.

    `distance = area * ratio / perimeter`, which for a rectangle is a uniform outset of every side.
    Detection is trained to predict a *shrunk* text region, so a box that is not unclipped clips the
    ascenders and descenders off every line it finds.
    """
    (ax, ay), (bx, by), (cx, cy), (dx, dy) = corners
    width: float = math.hypot(bx - ax, by - ay)
    height: float = math.hypot(cx - bx, cy - by)
    perimeter: float = 2 * (width + height)
    if perimeter < 1e-6:
        return corners
    distance: float = width * height * ratio / perimeter
    center_x: float = (ax + bx + cx + dx) / 4
    center_y: float = (ay + by + cy + dy) / 4
    grown: list[tuple[float, float]] = []
    for x, y in corners:
        # Each corner moves along both axes of the rectangle, which for a rectangle is the same as
        # moving it away from the centre by `distance` measured on each axis separately.
        ux: float = (bx - ax) / width if width > 1e-6 else 1.0
        uy: float = (by - ay) / width if width > 1e-6 else 0.0
        vx: float = (cx - bx) / height if height > 1e-6 else 0.0
        vy: float = (cy - by) / height if height > 1e-6 else 1.0
        along: float = (x - center_x) * ux + (y - center_y) * uy
        across: float = (x - center_x) * vx + (y - center_y) * vy
        along += math.copysign(distance, along if along != 0 else 1.0)
        across += math.copysign(distance, across if across != 0 else 1.0)
        grown.append((center_x + along * ux + across * vx, center_y + along * uy + across * vy))
    return tuple(grown)


def box_score(prob: np.ndarray, corners: tuple[tuple[float, float], ...]) -> float:
    """Mean probability *inside the quadrilateral* — PaddleOCR's `box_score_fast`.

    **The polygon has to be filled; its bounding box will not do.** A line of text photographed at
    an angle fills barely half of its own axis-aligned bounds, so scoring the bounds averages the
    line together with the paper beside it and drags every rotated line under `box_thresh`. Measured
    on the corpus: with the bounding box, the photographed page returned *no lines at all*; filling
    the quad returns all four.
    """
    height, width = prob.shape
    xs: list[float] = [point[0] for point in corners]
    ys: list[float] = [point[1] for point in corners]
    top: int = max(0, min(height - 1, int(math.floor(min(ys)))))
    bottom: int = max(0, min(height - 1, int(math.ceil(max(ys)))))
    left: int = max(0, min(width - 1, int(math.floor(min(xs)))))
    right: int = max(0, min(width - 1, int(math.ceil(max(xs)))))
    if right < left or bottom < top:
        return 0.0

    total: float = 0.0
    count: int = 0
    for y in range(top, bottom + 1):
        spans: list[float] = []
        for index in range(len(corners)):
            (x0, y0) = corners[index]
            (x1, y1) = corners[(index + 1) % len(corners)]
            if (y0 <= y < y1) or (y1 <= y < y0):
                spans.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
        if len(spans) < 2:
            continue
        spans.sort()
        row_left: int = max(left, int(math.floor(spans[0])))
        row_right: int = min(right, int(math.ceil(spans[-1])))
        if row_right < row_left:
            continue
        total += float(prob[y, row_left : row_right + 1].sum())
        count += row_right - row_left + 1
    if count == 0:
        return float(prob[top : bottom + 1, left : right + 1].mean())
    return total / count


def detect(
    prob: np.ndarray,
    thresh: float = 0.3,
    box_thresh: float = 0.6,
    unclip_ratio: float = 1.5,
    min_side: float = 3.0,
    max_boxes: int = 1000,
) -> list[Quad]:
    """Probability map to text quads."""
    mask: np.ndarray = prob > thresh
    quads: list[Quad] = []
    for points in components(mask, min_pixels=max(4, int(min_side * min_side))):
        hull: np.ndarray = convex_hull(points)
        corners, short, _ = min_area_rect(hull)
        if short < min_side:
            continue
        score: float = box_score(prob, corners)
        if score < box_thresh:
            continue
        grown = unclip(corners, unclip_ratio)
        _, short_after, _ = min_area_rect(np.array(grown))
        if short_after < min_side + 2:
            continue
        quads.append(Quad(clockwise(grown), score))
        if len(quads) >= max_boxes:
            break
    return quads


def clockwise(corners: tuple[tuple[float, float], ...]) -> tuple[tuple[float, float], ...]:
    """Corners starting nearest the top-left, going clockwise — what the crop step expects.

    Sorted by angle about the centroid rather than by splitting on x. The x-split ordering is the
    common recipe and it mislabels corners once a box passes about 45 degrees, which is exactly the
    case the ordering exists to handle.
    """
    center_x: float = sum(point[0] for point in corners) / len(corners)
    center_y: float = sum(point[1] for point in corners) / len(corners)
    # Screen y grows downwards, so increasing atan2 walks clockwise on screen.
    ordered: list[tuple[float, float]] = sorted(
        corners, key=lambda point: math.atan2(point[1] - center_y, point[0] - center_x)
    )
    start: int = min(
        range(len(ordered)), key=lambda index: ordered[index][0] + ordered[index][1]
    )
    return tuple(ordered[(start + offset) % len(ordered)] for offset in range(len(ordered)))
