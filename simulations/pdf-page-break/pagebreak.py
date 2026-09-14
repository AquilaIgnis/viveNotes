"""A replay of `com.vivenotes.pdf` off-device: grouping, tiling, and four candidate fits.

Everything here mirrors Kotlin that has no Android in it — `ContentGroups.segmentByWhitespace`,
`PageTiling.plan` — so a rule can be measured against real pages without an emulator, a render, or a
human looking at a PDF. The geometry comes out of `notes.db`: a stroke row carries its own bounds
(`minX`/`minY`/`maxX`/`maxY`), which is all the grouping and the fit ever ask for, and `ink_moves`
rows are replayed over them the way `InkPageLoader` does.

What is *not* here is anything that needs a device: text containers and tables are measured with
`StaticLayout` in the app, so a page whose entities are text comes out of this with only the stored
frame. Pages of ink — the ones the fit was wrong on — are exact.
"""

from __future__ import annotations

import json
import math
import sqlite3
import sys
from collections import defaultdict, deque
from pathlib import Path
from dataclasses import dataclass
from typing import Callable, Optional

DP_PER_INCH: float = 160.0
GROUP_GAP_X_DP: float = 40.0
GROUP_GAP_Y_DP: float = 40.0
MAX_TILES_PER_AXIS: int = 100

# PageMeasurer's title band, near enough: it is only ever an obstacle and the top-left of the grid.
HEADER_HEIGHT_DP: float = 62.0
HEADER_CONTENT_GAP_DP: float = 24.0
HEADER_MAX_WIDTH_DP: float = 900.0

# A4, which is what PaperSize.Auto exports as.
PAPER_W_IN: float = 8.27
PAPER_H_IN: float = 11.69


@dataclass(frozen=True)
class Bounds:
    left: float
    top: float
    right: float
    bottom: float

    @property
    def width(self) -> float:
        return self.right - self.left

    @property
    def height(self) -> float:
        return self.bottom - self.top

    def translated(self, dx: float, dy: float) -> "Bounds":
        return Bounds(self.left + dx, self.top + dy, self.right + dx, self.bottom + dy)

    def overlaps(self, other: "Bounds") -> bool:
        return (
            self.left < other.right
            and other.left < self.right
            and self.top < other.bottom
            and other.top < self.bottom
        )

    def __str__(self) -> str:
        return (
            f"({self.left:8.1f},{self.top:8.1f})-({self.right:8.1f},{self.bottom:8.1f})"
            f" {self.width:7.1f}x{self.height:7.1f}"
        )


def union(boxes: list[Bounds]) -> Bounds:
    return Bounds(
        left=min(box.left for box in boxes),
        top=min(box.top for box in boxes),
        right=max(box.right for box in boxes),
        bottom=max(box.bottom for box in boxes),
    )


@dataclass
class Atom:
    """One stroke, one lasso group, or one outline — `ContentGroups.PdfAtom`."""

    id: str
    bounds: Bounds
    points: list[tuple[float, float]]


@dataclass
class Item:
    """What the tiling sees: an entity, or the title band."""

    id: str
    bounds: Bounds
    title: bool = False


Shifts = dict[str, list[float]]


# --- PD6, the whitespace cut ------------------------------------------------------------------


@dataclass
class Lane:
    size: float
    at: float
    horizontal: bool


def _interval_start(interval: tuple[float, float]) -> float:
    return interval[0]


def widest_gap(block: list[Atom], horizontal: bool) -> Optional[Lane]:
    intervals: list[tuple[float, float]] = []
    for atom in block:
        box = atom.bounds
        intervals.append((box.left, box.right) if horizontal else (box.top, box.bottom))
    intervals.sort(key=_interval_start)
    reach: float = intervals[0][1]
    widest: Optional[Lane] = None
    for index in range(1, len(intervals)):
        start, end = intervals[index]
        gap = start - reach
        if gap > 0.0 and (widest is None or gap > widest.size):
            widest = Lane(gap, reach + gap / 2.0, horizontal)
        reach = max(reach, end)
    return widest


def choose_cut(
    horizontal: Optional[Lane],
    vertical: Optional[Lane],
    too_wide: bool,
    too_tall: bool,
) -> Optional[Lane]:
    horizontal_score = horizontal.size / GROUP_GAP_X_DP if horizontal is not None else 0.0
    vertical_score = vertical.size / GROUP_GAP_Y_DP if vertical is not None else 0.0
    if horizontal_score >= 1.0 or vertical_score >= 1.0:
        return horizontal if horizontal_score >= vertical_score else vertical
    if too_wide and horizontal is not None:
        return horizontal
    if too_tall and vertical is not None:
        return vertical
    if too_wide or too_tall:
        return horizontal if horizontal is not None else vertical
    return None


def group_content(atoms: list[Atom], max_width: float, max_height: float) -> list[list[Atom]]:
    if not atoms:
        return []
    leaves: list[list[Atom]] = []
    pending: deque[list[Atom]] = deque()
    pending.append(atoms)
    while pending:
        block = pending.popleft()
        if len(block) == 1:
            leaves.append(block)
            continue
        box = union([atom.bounds for atom in block])
        cut = choose_cut(
            horizontal=widest_gap(block, horizontal=True),
            vertical=widest_gap(block, horizontal=False),
            too_wide=box.width > max_width,
            too_tall=box.height > max_height,
        )
        if cut is None:
            leaves.append(block)
            continue
        near: list[Atom] = []
        far: list[Atom] = []
        for atom in block:
            edge = atom.bounds.left if cut.horizontal else atom.bounds.top
            (near if edge < cut.at else far).append(atom)
        if not near or not far:
            leaves.append(block)
            continue
        pending.append(near)
        pending.append(far)
    return leaves


# --- PD5, the four candidate fits ----------------------------------------------------------------


def _placed(item: Item, shifts: Shifts) -> Bounds:
    dx, dy = shifts[item.id]
    return item.bounds.translated(dx, dy)


def _is_clear(candidate: Bounds, moving: Item, items: list[Item], shifts: Shifts) -> bool:
    for other in items:
        if other.id != moving.id and candidate.overlaps(_placed(other, shifts)):
            return False
    return True


def _left(item: Item) -> float:
    return item.bounds.left


def _top(item: Item) -> float:
    return item.bounds.top


def fit_pull_only(items: list[Item], content: Bounds, tile_w: float, tile_h: float) -> Shifts:
    """What shipped first: the least translation inward, whatever is already there."""
    shifts: Shifts = {item.id: [0.0, 0.0] for item in items}
    columns = max(1, math.ceil(content.width / tile_w))
    rows = max(1, math.ceil(content.height / tile_h))
    for item in items:
        if item.title or item.bounds.width > tile_w or item.bounds.height > tile_h:
            continue
        column = min(max(int((item.bounds.left - content.left) / tile_w), 0), columns - 1)
        row = min(max(int((item.bounds.top - content.top) / tile_h), 0), rows - 1)
        over_right = item.bounds.right - (content.left + (column + 1) * tile_w)
        over_bottom = item.bounds.bottom - (content.top + (row + 1) * tile_h)
        if over_right > 0.0:
            shifts[item.id][0] = -over_right
        if over_bottom > 0.0:
            shifts[item.id][1] = -over_bottom
    return shifts


def fit(
    items: list[Item],
    content: Bounds,
    tile_w: float,
    tile_h: float,
    pull: bool,
    carry_x: bool,
    carry_y: bool,
) -> Shifts:
    """The family the study is over.

    `pull` tries the inward pull first and takes it when the landing is clear. `carry_*` decides
    what a break does to the rest of its strip: carried, the strip moves with it; uncarried, the
    break is only taken when it happens to land clear, and otherwise the item stays and is cut.
    """
    shifts: Shifts = {item.id: [0.0, 0.0] for item in items}
    movable = [item for item in items if not item.title]

    def pass_along(
        along_origin: float,
        along_size: float,
        strip_origin: float,
        strip_size: float,
        axis: int,
        carry_on: bool,
    ) -> None:
        def near(box: Bounds) -> float:
            return box.left if axis == 0 else box.top

        def far(box: Bounds) -> float:
            return box.right if axis == 0 else box.bottom

        def strip_near(box: Bounds) -> float:
            return box.top if axis == 0 else box.left

        def shift_of(distance: float) -> tuple[float, float]:
            return (distance, 0.0) if axis == 0 else (0.0, distance)

        strips: dict[int, list[Item]] = defaultdict(list)
        for item in movable:
            index = int((strip_near(_placed(item, shifts)) - strip_origin) // strip_size)
            strips[max(index, 0)].append(item)

        for _, members in sorted(strips.items()):
            members.sort(key=_left if axis == 0 else _top)
            carry: float = 0.0
            for item in members:
                if carry != 0.0:
                    dx, dy = shift_of(carry)
                    shifts[item.id][0] += dx
                    shifts[item.id][1] += dy
                if item.bounds.width > tile_w or item.bounds.height > tile_h:
                    continue
                box = _placed(item, shifts)
                index = max(int((near(box) - along_origin) // along_size), 0)
                edge = along_origin + (index + 1) * along_size
                over = far(box) - edge
                if over <= 0.0:
                    continue
                if pull:
                    dx, dy = shift_of(-over)
                    if _is_clear(box.translated(dx, dy), item, items, shifts):
                        shifts[item.id][0] += dx
                        shifts[item.id][1] += dy
                        continue
                if index + 1 >= MAX_TILES_PER_AXIS:
                    continue
                onward = edge - near(box)
                dx, dy = shift_of(onward)
                if not carry_on and not _is_clear(box.translated(dx, dy), item, items, shifts):
                    continue
                shifts[item.id][0] += dx
                shifts[item.id][1] += dy
                if carry_on:
                    carry += onward

    pass_along(content.left, tile_w, content.top, tile_h, axis=0, carry_on=carry_x)
    pass_along(content.top, tile_h, content.left, tile_w, axis=1, carry_on=carry_y)
    return shifts


def fit_clear(items: list[Item], content: Bounds, tile_w: float, tile_h: float) -> Shifts:
    """Pull if the landing is clear, break if *that* landing is clear, else leave it cut."""
    return fit(items, content, tile_w, tile_h, pull=True, carry_x=False, carry_y=False)


def fit_break(items: list[Item], content: Bounds, tile_w: float, tile_h: float) -> Shifts:
    """Pull if clear, otherwise break and carry the rest of the strip. Shipped 2026-09-13."""
    return fit(items, content, tile_w, tile_h, pull=True, carry_x=True, carry_y=True)


def fit_break_down(items: list[Item], content: Bounds, tile_w: float, tile_h: float) -> Shifts:
    """A carry down the column only; across the page, break only when it lands clear."""
    return fit(items, content, tile_w, tile_h, pull=True, carry_x=False, carry_y=True)


def fit_break_only(items: list[Item], content: Bounds, tile_w: float, tile_h: float) -> Shifts:
    """Never pull: every straddling item starts the next sheet."""
    return fit(items, content, tile_w, tile_h, pull=False, carry_x=True, carry_y=True)


STRATEGIES: dict[str, Callable[[list[Item], Bounds, float, float], Shifts]] = {
    "pull": fit_pull_only,
    "clear": fit_clear,
    "break": fit_break,
    "break-y": fit_break_down,
    "break-only": fit_break_only,
}


# --- PD3, the grid -------------------------------------------------------------------------------


def sheets_for(items: list[Item], content: Bounds, tile_w: float, tile_h: float, shifts: Shifts) -> list[Bounds]:
    placed = [_placed(item, shifts) for item in items]
    columns = min(MAX_TILES_PER_AXIS, max(1, math.ceil((max(b.right for b in placed) - content.left) / tile_w)))
    rows = min(MAX_TILES_PER_AXIS, max(1, math.ceil((max(b.bottom for b in placed) - content.top) / tile_h)))
    tiles: list[Bounds] = []
    for column in range(columns):
        for row in range(rows):
            area = Bounds(
                content.left + column * tile_w,
                content.top + row * tile_h,
                content.left + (column + 1) * tile_w,
                content.top + (row + 1) * tile_h,
            )
            if any(box.overlaps(area) for box in placed):
                tiles.append(area)
    return tiles


# --- reading a page ------------------------------------------------------------------------------


def load_atoms(database: str, page_id: str, with_points: bool = False) -> list[Atom]:
    """A page's ink and outlines as atoms, with `ink_moves` replayed over them."""
    connection = sqlite3.connect(database)
    columns = "id, minX, minY, maxX, maxY" + (", points" if with_points else "")
    rows = connection.execute(
        f"select {columns} from ink_strokes where pageId = ? and deletedAt is null order by seq, id",
        (page_id,),
    ).fetchall()

    atoms: dict[str, Atom] = {}
    for row in rows:
        stroke_id, min_x, min_y, max_x, max_y = row[:5]
        points: list[tuple[float, float]] = []
        if with_points:
            points = _decode(row[5])
        atoms[stroke_id] = Atom("ink:" + stroke_id, Bounds(min_x, min_y, max_x, max_y), points)

    for move_id, dx, dy in connection.execute(
        "select id, dxDp, dyDp from ink_moves where pageId = ? and deletedAt is null order by createdAt",
        (page_id,),
    ).fetchall():
        for (stroke_id,) in connection.execute(
            "select strokeId from ink_move_targets where moveId = ?", (move_id,)
        ):
            atom = atoms.get(stroke_id)
            if atom is None:
                continue
            atom.bounds = atom.bounds.translated(dx, dy)
            atom.points = [(x + dx, y + dy) for x, y in atom.points]

    document = connection.execute(
        "select docJson from page_content where pageId = ?", (page_id,)
    ).fetchone()
    connection.close()

    outlines: list[Atom] = []
    if document is not None:
        for outline in json.loads(document[0]).get("outlines", []):
            x = float(outline.get("x", 0.0))
            y = float(outline.get("y", 0.0))
            width = float(outline.get("width", 0.0))
            height = float(outline.get("height", 0.0))
            pen = float(outline.get("borderWidth", 2.0)) / 2.0 if outline.get("t") == "shape" else 0.0
            points = []
            for segment in outline.get("segments", []):
                points.append((float(segment["x1"]), float(segment["y1"])))
                points.append((float(segment["x2"]), float(segment["y2"])))
            outlines.append(
                Atom(
                    outline["id"],
                    Bounds(x - pen, y - pen, x + width + pen, y + height + pen),
                    points,
                )
            )
    return outlines + list(atoms.values())


def _decode(blob: bytes) -> list[tuple[float, float]]:
    """Points, for the renderer. The decoder is `formula-render`'s, which already owns this format."""
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "formula-render"))
    from inkdecode import decode_points

    decoded = decode_points(blob)
    return list(zip(decoded.get(1, []), decoded.get(2, [])))


def page_items(atoms: list[Atom], tile_w: float, tile_h: float) -> tuple[list[Item], Bounds, dict[str, str]]:
    """Entities plus the title band, and which entity each atom ended up in."""
    groups = group_content(atoms, tile_w, tile_h)
    items: list[Item] = []
    group_of: dict[str, str] = {}
    for members in groups:
        name = "group:" + min(atom.id for atom in members)
        items.append(Item(name, union([atom.bounds for atom in members])))
        for atom in members:
            group_of[atom.id] = name
    items.sort(key=_top)

    content = union([item.bounds for item in items])
    top = max(0.0, content.top - HEADER_HEIGHT_DP - HEADER_CONTENT_GAP_DP)
    items.append(
        Item(
            " header",
            Bounds(
                content.left,
                top,
                content.left + min(tile_w, HEADER_MAX_WIDTH_DP),
                top + HEADER_HEIGHT_DP,
            ),
            title=True,
        )
    )
    return items, union([item.bounds for item in items]), group_of


def tile_size(margin_inches: float) -> tuple[float, float]:
    return (
        (PAPER_W_IN - 2 * margin_inches) * DP_PER_INCH,
        (PAPER_H_IN - 2 * margin_inches) * DP_PER_INCH,
    )
