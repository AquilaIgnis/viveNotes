"""Scores every candidate fit against every page of a real notes.db.

    python3 simulations/pdf-page-break/study.py path/to/notes.db 0.25 > results/margin-0.25.txt

Three numbers per page and rule, and they are in the order they matter:

  overlaps  pairs of entities that did not overlap before the fit and do afterwards. The bug.
  cut       entities small enough for a sheet that a sheet edge still runs through. The thing the
            option exists to prevent, and what a rule that refuses to move anything scores badly on.
  sheets    how much paper it costs.
"""

from __future__ import annotations

import sqlite3
import sys

from pagebreak import (
    STRATEGIES,
    Bounds,
    Item,
    Shifts,
    _placed,
    load_atoms,
    page_items,
    sheets_for,
    tile_size,
)

# A coordinate landing exactly on a tile edge is on the next tile, and floating point says so only
# to within a rounding error. Nothing on a page is a twentieth of a dp wide.
EPSILON: float = 0.05


def score(
    items: list[Item],
    content: Bounds,
    tile_w: float,
    tile_h: float,
    shifts: Shifts,
) -> tuple[int, int, int]:
    placed = {item.id: _placed(item, shifts) for item in items}
    overlaps = 0
    for first in range(len(items)):
        for second in range(first + 1, len(items)):
            one, other = items[first], items[second]
            if placed[one.id].overlaps(placed[other.id]) and not one.bounds.overlaps(other.bounds):
                overlaps += 1

    cut = 0
    for item in items:
        if item.title or item.bounds.width > tile_w or item.bounds.height > tile_h:
            continue
        box = placed[item.id]
        column = int((box.left - content.left + EPSILON) // tile_w)
        row = int((box.top - content.top + EPSILON) // tile_h)
        if box.right > content.left + (column + 1) * tile_w + EPSILON:
            cut += 1
        elif box.bottom > content.top + (row + 1) * tile_h + EPSILON:
            cut += 1

    return overlaps, cut, len(sheets_for(items, content, tile_w, tile_h, shifts))


def main() -> None:
    database = sys.argv[1]
    margin = float(sys.argv[2]) if len(sys.argv) > 2 else 0.25
    tile_w, tile_h = tile_size(margin)

    connection = sqlite3.connect(database)
    pages = connection.execute(
        "select id, (select count(*) from ink_strokes s where s.pageId = p.id and s.deletedAt is null)"
        " from pages p where p.deletedAt is null"
    ).fetchall()
    connection.close()

    names = list(STRATEGIES)
    print(f"A4, margins {margin} in — tile {tile_w:.0f} x {tile_h:.0f} dp")
    print("overlaps / cut / sheets\n")
    print(f"{'page':>6} {'strokes':>8} {'entities':>9}  " + "  ".join(f"{name:>14}" for name in names))
    for index, (page_id, strokes) in enumerate(sorted(pages, key=_by_strokes), start=1):
        atoms = load_atoms(database, page_id)
        if not atoms:
            continue
        items, content, _ = page_items(atoms, tile_w, tile_h)
        cells: list[str] = []
        for name in names:
            overlaps, cut, sheets = score(
                items, content, tile_w, tile_h, STRATEGIES[name](items, content, tile_w, tile_h)
            )
            cells.append(f"{overlaps:3} /{cut:3} /{sheets:4}")
        print(f"{index:>6} {strokes:>8} {len(items) - 1:>9}  " + "  ".join(f"{cell:>14}" for cell in cells))


def _by_strokes(page: tuple[str, int]) -> int:
    return page[1]


if __name__ == "__main__":
    main()
