"""Render the live ink in a .vive notebook for handwriting-recognition studies.

This deliberately consumes the portable notebook rather than a screenshot.  Each stored stroke is
decoded from its androidx.ink input batch, and normal erasers are applied only to the stroke ids they
target.  The result is close enough to the app's native mesh rendering to inspect segmentation and
preprocessing choices without making a screenshot the source of truth.

The study fixture currently has no lasso moves.  A notebook with moves is rejected rather than
quietly rendering stale coordinates; the Android implementation reuses the app's exact replay path.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw

FORMULA_SIM = Path(__file__).resolve().parents[1] / "formula-render"
sys.path.insert(0, str(FORMULA_SIM))
from inkdecode import decode_points  # noqa: E402


Point = tuple[float, float]


@dataclass(frozen=True)
class Stroke:
    id: str
    seq: int
    size: float
    points: tuple[Point, ...]
    clipped_bounds: tuple[float, float, float, float] | None = None

    @property
    def bounds(self) -> tuple[float, float, float, float]:
        if self.clipped_bounds is not None:
            return self.clipped_bounds
        xs = [point[0] for point in self.points]
        ys = [point[1] for point in self.points]
        half = self.size / 2
        return min(xs) - half, min(ys) - half, max(xs) + half, max(ys) + half


@dataclass(frozen=True)
class Erase:
    id: str
    size: float
    points: tuple[Point, ...]
    targets: frozenset[str]


@dataclass(frozen=True)
class Cell:
    id: str
    left: float
    top: float
    right: float
    bottom: float


def extract_database(bundle: Path, directory: Path) -> Path:
    with zipfile.ZipFile(bundle) as archive:
        names = set(archive.namelist())
        required = {"manifest.json", "notebook.sqlite", "checksums.sha256"}
        if not required.issubset(names):
            raise ValueError(f"{bundle} is not a supported .vive bundle")
        target = directory / "notebook.sqlite"
        target.write_bytes(archive.read("notebook.sqlite"))
        return target


def load_strokes(connection: sqlite3.Connection, page_id: str) -> list[Stroke]:
    rows = connection.execute(
        "select id, seq, sizeDp, points from ink_strokes "
        "where pageId = ? and deletedAt is null order by seq",
        (page_id,),
    )
    strokes: list[Stroke] = []
    for identity, sequence, size, blob in rows:
        runs = decode_points(blob)
        points = tuple(zip(runs[1], runs[2]))
        if points:
            strokes.append(Stroke(identity, sequence, size, points))
    return strokes


def load_erases(connection: sqlite3.Connection, page_id: str) -> list[Erase]:
    rows = connection.execute(
        "select id, sizeDp, points from ink_erases "
        "where pageId = ? and deletedAt is null and mode = 'Normal' order by createdAt, id",
        (page_id,),
    )
    erases: list[Erase] = []
    for identity, size, blob in rows:
        runs = decode_points(blob)
        targets = frozenset(
            row[0]
            for row in connection.execute(
                "select strokeId from ink_erase_targets where eraseId = ?", (identity,)
            )
        )
        erases.append(Erase(identity, size, tuple(zip(runs[1], runs[2])), targets))
    return erases


def load_cells(connection: sqlite3.Connection, page_id: str) -> list[Cell]:
    row = connection.execute(
        "select docJson from page_content where pageId = ?", (page_id,)
    ).fetchone()
    if row is None:
        return []
    document = json.loads(row[0])
    cells: list[Cell] = []
    for outline in document.get("outlines", []):
        if outline.get("t") != "table" or not outline.get("inkOnly", False):
            continue
        left = float(outline["x"])
        top = float(outline["y"])
        columns = [float(width) for width in outline["columns"]]
        x_edges = [left]
        for width in columns:
            x_edges.append(x_edges[-1] + width)
        y = top
        for row_data in outline["rows"]:
            bottom = y + float(row_data["minHeight"])
            for index, cell in enumerate(row_data["cells"]):
                cells.append(Cell(cell["id"], x_edges[index], y, x_edges[index + 1], bottom))
            y = bottom
    return cells


def content_bounds(
    strokes: list[Stroke], cells: list[Cell], margin: float
) -> tuple[float, float, float, float]:
    rectangles = [stroke.bounds for stroke in strokes]
    rectangles += [(cell.left, cell.top, cell.right, cell.bottom) for cell in cells]
    left = min(rectangle[0] for rectangle in rectangles) - margin
    top = min(rectangle[1] for rectangle in rectangles) - margin
    right = max(rectangle[2] for rectangle in rectangles) + margin
    bottom = max(rectangle[3] for rectangle in rectangles) + margin
    return left, top, right, bottom


def transformed(points: tuple[Point, ...], left: float, top: float, scale: float) -> list[Point]:
    return [((x - left) * scale, (y - top) * scale) for x, y in points]


def render(
    strokes: list[Stroke],
    erases: list[Erase],
    cells: list[Cell],
    output: Path,
    scale: float = 2.0,
) -> None:
    # Supersampling preserves the small gaps inside e/a/o when the preview is reduced.
    supersample = 3
    render_scale = scale * supersample
    left, top, right, bottom = content_bounds(strokes, cells, margin=24)
    width = max(1, round((right - left) * render_scale))
    height = max(1, round((bottom - top) * render_scale))
    page = Image.new("L", (width, height), 255)

    # The table itself is a document object rather than ink, but showing it is essential for
    # transcribing the fixture and for seeing which stroke groups may use exact cell boundaries.
    table = ImageDraw.Draw(page)
    for cell in cells:
        table.rectangle(
            (
                (cell.left - left) * render_scale,
                (cell.top - top) * render_scale,
                (cell.right - left) * render_scale,
                (cell.bottom - top) * render_scale,
            ),
            outline=190,
            width=max(1, round(render_scale * 0.8)),
        )

    by_target: dict[str, list[Erase]] = {}
    for erase in erases:
        for target in erase.targets:
            by_target.setdefault(target, []).append(erase)

    for stroke in strokes:
        mask = Image.new("L", page.size, 0)
        drawing = ImageDraw.Draw(mask)
        points = transformed(stroke.points, left, top, render_scale)
        stroke_width = max(1, round(stroke.size * render_scale))
        if len(points) == 1:
            x, y = points[0]
            radius = stroke_width / 2
            drawing.ellipse((x - radius, y - radius, x + radius, y + radius), fill=255)
        else:
            drawing.line(points, fill=255, width=stroke_width, joint="curve")
        for erase in by_target.get(stroke.id, []):
            erased = transformed(erase.points, left, top, render_scale)
            if len(erased) == 1:
                x, y = erased[0]
                radius = erase.size * render_scale / 2
                drawing.ellipse((x - radius, y - radius, x + radius, y + radius), fill=0)
            else:
                drawing.line(
                    erased,
                    fill=0,
                    width=max(1, round(erase.size * render_scale)),
                    joint="curve",
                )
        page.paste(0, mask=mask)

    target_size = (max(1, width // supersample), max(1, height // supersample))
    page.resize(target_size, Image.Resampling.LANCZOS).save(output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vive", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scale", type=float, default=2.0)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="vivenotes-handwriting-") as temporary:
        database = extract_database(args.vive, Path(temporary))
        connection = sqlite3.connect(database)
        page = connection.execute(
            "select id, title from pages where deletedAt is null order by sortIndex limit 1"
        ).fetchone()
        if page is None:
            raise ValueError("the notebook has no live page")
        move_count = connection.execute(
            "select count(*) from ink_moves where pageId = ? and deletedAt is null", (page[0],)
        ).fetchone()[0]
        if move_count:
            raise ValueError("this desktop renderer does not approximate lasso moves")
        strokes = load_strokes(connection, page[0])
        erases = load_erases(connection, page[0])
        cells = load_cells(connection, page[0])
        connection.close()

    args.output.parent.mkdir(parents=True, exist_ok=True)
    render(strokes, erases, cells, args.output, args.scale)
    print(
        f"rendered {page[1]!r}: {len(strokes)} strokes, {len(erases)} erases, "
        f"{len(cells)} table cells -> {args.output}"
    )


if __name__ == "__main__":
    main()
