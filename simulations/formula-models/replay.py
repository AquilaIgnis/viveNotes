"""Replay a schema-2 starter page fixture into the polylines the page shows.

The app replays partial erases and lasso moves on androidx.ink meshes (`InkPageLoader.replay`),
which cannot run off the device. This is a centreline approximation of the same fold, good enough
to raster the page for a recognizer and checked by eye against `results/page.png`:

- Normal erase: a stroke loses every centreline sample within half the eraser's width of its
  path, and splits where it lost them. Each run becomes its own piece, as `Stroke.split` does.
- Object erase: a piece goes whole when its ink comes within reach of the eraser path.
- Move: a piece moves when its stroke is a target and every sample lies inside the lasso, with
  the app's 4-unit edge tolerance; a resize then scales the pieces still inside the same lasso.

Operations fold in `(createdAt, id)` order, as the loader sorts them.
"""

from __future__ import annotations

import json
import math
import struct
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "formula-render"))
from inkdecode import decode_points  # noqa: E402

FIXTURE = HERE.parents[1] / "app/src/main/assets/default_notebook/recognition_page_2.json"
LASSO_EDGE_TOLERANCE: float = 4.0
RESAMPLE_SPACING: float = 0.25

Point = tuple[float, float]


@dataclass
class Piece:
    stroke_id: str
    seq: int
    size_dp: float
    points: np.ndarray  # (n, 2)


@dataclass
class Erase:
    id: str
    created_at: int
    mode: str
    size_dp: float
    path: np.ndarray
    targets: set[str]


@dataclass
class Move:
    id: str
    created_at: int
    dx: float
    dy: float
    scale_x: float
    scale_y: float
    anchor: Point
    path: np.ndarray
    targets: set[str]


def resample(points: np.ndarray, spacing: float) -> np.ndarray:
    """Insert samples so no segment is longer than [spacing]; the cut test then sees every gap."""
    if len(points) < 2:
        return points
    out: list[np.ndarray] = [points[:1]]
    for start, end in zip(points[:-1], points[1:]):
        length = float(np.hypot(*(end - start)))
        steps = max(1, math.ceil(length / spacing))
        fractions = np.arange(1, steps + 1)[:, None] / steps
        out.append(start + (end - start) * fractions)
    return np.concatenate(out)


def decode_stroke_points(hex_blob: str) -> np.ndarray:
    runs = decode_points(bytes.fromhex(hex_blob))
    return np.column_stack([runs[1], runs[2]]).astype(np.float64)


def decode_lasso(hex_blob: str) -> np.ndarray:
    raw = bytes.fromhex(hex_blob)
    count = struct.unpack_from("<i", raw, 0)[0]
    values = struct.unpack_from(f"<{count * 2}f", raw, 4)
    return np.asarray(values, dtype=np.float64).reshape(count, 2)


def distance_to_path(points: np.ndarray, path: np.ndarray) -> np.ndarray:
    """Distance from each point to the nearest segment (or the only point) of [path]."""
    if len(path) == 1:
        return np.hypot(*(points - path[0]).T)
    starts = path[:-1]
    ends = path[1:]
    segment = ends - starts
    lengths = np.maximum((segment**2).sum(axis=1), 1e-12)
    best = np.full(len(points), np.inf)
    for begin in range(0, len(starts), 512):
        s = starts[begin : begin + 512]
        d = segment[begin : begin + 512]
        l2 = lengths[begin : begin + 512]
        relative = points[:, None, :] - s[None, :, :]
        t = np.clip((relative * d[None, :, :]).sum(axis=2) / l2[None, :], 0.0, 1.0)
        nearest = s[None, :, :] + t[:, :, None] * d[None, :, :]
        distance = np.hypot(*(points[:, None, :] - nearest).transpose(2, 0, 1))
        best = np.minimum(best, distance.min(axis=1))
    return best


def inside_polygon(points: np.ndarray, polygon: np.ndarray) -> np.ndarray:
    x, y = points[:, 0], points[:, 1]
    inside = np.zeros(len(points), dtype=bool)
    previous = polygon[-1]
    for current in polygon:
        crosses = (current[1] > y) != (previous[1] > y)
        with np.errstate(divide="ignore", invalid="ignore"):
            crossing_x = (previous[0] - current[0]) * (y - current[1]) / (
                previous[1] - current[1]
            ) + current[0]
        inside ^= crosses & (x < crossing_x)
        previous = current
    return inside


def lasso_contains(piece: Piece, lasso: np.ndarray) -> bool:
    closed = np.vstack([lasso, lasso[:1]])
    inside = inside_polygon(piece.points, lasso)
    if inside.all():
        return True
    outside = piece.points[~inside]
    return bool((distance_to_path(outside, closed) <= LASSO_EDGE_TOLERANCE).all())


def stroke_order(row: dict) -> int:
    return int(row["seq"])


def operation_order(operation: Erase | Move) -> tuple[int, str]:
    return operation.created_at, operation.id


def load_fixture(path: Path = FIXTURE) -> tuple[list[Piece], list[Erase | Move]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    pieces = [
        Piece(
            stroke_id=row["id"],
            seq=row["seq"],
            size_dp=float(row["sizeDp"]),
            points=resample(decode_stroke_points(row["pointsHex"]), RESAMPLE_SPACING),
        )
        for row in sorted(data["strokes"], key=stroke_order)
    ]
    erase_targets: dict[str, set[str]] = {}
    for link in data["eraseTargets"]:
        erase_targets.setdefault(link["eraseId"], set()).add(link["strokeId"])
    move_targets: dict[str, set[str]] = {}
    for link in data["moveTargets"]:
        move_targets.setdefault(link["moveId"], set()).add(link["strokeId"])

    operations: list[Erase | Move] = [
        Erase(
            id=row["id"],
            created_at=int(row["createdAt"]),
            mode=row["mode"],
            size_dp=float(row["sizeDp"]),
            path=decode_stroke_points(row["pointsHex"]),
            targets=erase_targets.get(row["id"], set()),
        )
        for row in data["erases"]
    ]
    operations += [
        Move(
            id=row["id"],
            created_at=int(row["createdAt"]),
            dx=float(row["dxDp"]),
            dy=float(row["dyDp"]),
            scale_x=float(row["scaleX"]),
            scale_y=float(row["scaleY"]),
            anchor=(float(row["anchorX"]), float(row["anchorY"])),
            path=decode_lasso(row["pointsHex"]),
            targets=move_targets.get(row["id"], set()),
        )
        for row in data["moves"]
    ]
    operations.sort(key=operation_order)
    return pieces, operations


def apply_erase(pieces: list[Piece], erase: Erase) -> list[Piece]:
    out: list[Piece] = []
    reach = erase.size_dp / 2
    for piece in pieces:
        if piece.stroke_id not in erase.targets:
            out.append(piece)
            continue
        distance = distance_to_path(piece.points, erase.path)
        if erase.mode == "Object":
            if not (distance <= reach + piece.size_dp / 2).any():
                out.append(piece)
            continue
        keep = distance > reach
        if keep.all():
            out.append(piece)
            continue
        edges = np.flatnonzero(np.diff(np.concatenate([[0], keep.astype(np.int8), [0]])))
        for begin, end in zip(edges[::2], edges[1::2]):
            out.append(Piece(piece.stroke_id, piece.seq, piece.size_dp, piece.points[begin:end]))
    return out


def apply_move(pieces: list[Piece], move: Move) -> list[Piece]:
    if len(move.path) < 3 or not move.targets:
        return pieces
    selected = [p.stroke_id in move.targets and lasso_contains(p, move.path) for p in pieces]
    moved = [
        Piece(p.stroke_id, p.seq, p.size_dp, p.points + (move.dx, move.dy)) if chosen else p
        for p, chosen in zip(pieces, selected)
    ]
    if move.scale_x == 1.0 and move.scale_y == 1.0:
        return moved
    anchor = np.asarray(move.anchor)
    scale = np.asarray((move.scale_x, move.scale_y))
    reselected = [p.stroke_id in move.targets and lasso_contains(p, move.path) for p in moved]
    return [
        Piece(p.stroke_id, p.seq, p.size_dp, anchor + (p.points - anchor) * scale) if chosen else p
        for p, chosen in zip(moved, reselected)
    ]


def replay(path: Path = FIXTURE) -> list[Piece]:
    pieces, operations = load_fixture(path)
    for operation in operations:
        if isinstance(operation, Erase):
            pieces = apply_erase(pieces, operation)
        else:
            pieces = apply_move(pieces, operation)
    return [piece for piece in pieces if len(piece.points) > 0]


def render_page(pieces: list[Piece], output: Path, scale: float = 1.5) -> None:
    from PIL import Image, ImageDraw

    every = np.concatenate([piece.points for piece in pieces])
    left, top = every.min(axis=0) - 20
    right, bottom = every.max(axis=0) + 20
    image = Image.new("RGB", (int((right - left) * scale), int((bottom - top) * scale)), "white")
    draw = ImageDraw.Draw(image)
    for piece in pieces:
        coordinates = [((x - left) * scale, (y - top) * scale) for x, y in piece.points]
        width = max(1, round(piece.size_dp * scale * 0.6))
        if len(coordinates) == 1:
            x, y = coordinates[0]
            draw.ellipse((x - width, y - width, x + width, y + width), fill="black")
        else:
            draw.line(coordinates, fill="black", width=width, joint="curve")
    # A 100-unit grid, labelled, so segmentation bands can be read off the picture.
    for gx in range(int(left // 100 + 1) * 100, int(right), 100):
        draw.line([((gx - left) * scale, 0), ((gx - left) * scale, 12)], fill="red")
        draw.text(((gx - left) * scale + 2, 2), str(gx), fill="red")
    for gy in range(int(top // 100 + 1) * 100, int(bottom), 100):
        draw.line([(0, (gy - top) * scale), (12, (gy - top) * scale)], fill="red")
        draw.text((14, (gy - top) * scale - 6), str(gy), fill="red")
    image.save(output)


if __name__ == "__main__":
    replayed = replay()
    print(f"{len(replayed)} pieces from {len({p.stroke_id for p in replayed})} strokes")
    (HERE / "results").mkdir(exist_ok=True)
    render_page(replayed, HERE / "results" / "page.png")
    print("wrote results/page.png")
