"""Draws a page's sheets as one SVG, under a chosen fit, so an overlap can be seen rather than counted.

    python3 simulations/pdf-page-break/render.py notes.db <pageId> 0.25 break out.svg
    rsvg-convert -w 1800 out.svg -o out.png

Not the app's renderer: strokes are polylines rather than brush meshes and text outlines are not
drawn at all. The grouping, the tiling and the shifts are the app's, which is the part worth looking
at — and the sheets come out in the order the export writes them.
"""

from __future__ import annotations

import sys

from pagebreak import (
    DP_PER_INCH,
    PAPER_H_IN,
    PAPER_W_IN,
    STRATEGIES,
    load_atoms,
    page_items,
    sheets_for,
    tile_size,
)

SHEET_GAP_DP: float = 120.0


def render(database: str, page_id: str, margin: float, strategy: str, out: str) -> None:
    tile_w, tile_h = tile_size(margin)
    inset = margin * DP_PER_INCH
    paper_w = PAPER_W_IN * DP_PER_INCH
    paper_h = PAPER_H_IN * DP_PER_INCH

    atoms = load_atoms(database, page_id, with_points=True)
    items, content, group_of = page_items(atoms, tile_w, tile_h)
    shifts = STRATEGIES[strategy](items, content, tile_w, tile_h)
    tiles = sheets_for(items, content, tile_w, tile_h, shifts)

    width = len(tiles) * (paper_w + SHEET_GAP_DP) + SHEET_GAP_DP
    height = paper_h + SHEET_GAP_DP * 2
    parts: list[str] = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width:.0f}" height="{height:.0f}"'
        f' viewBox="0 0 {width:.0f} {height:.0f}">',
        f'<rect width="{width:.0f}" height="{height:.0f}" fill="#8a8a8a"/>',
    ]
    for index, area in enumerate(tiles):
        left = SHEET_GAP_DP + index * (paper_w + SHEET_GAP_DP)
        parts.append(f'<g transform="translate({left:.1f},{SHEET_GAP_DP:.1f})">')
        parts.append(f'<rect width="{paper_w:.0f}" height="{paper_h:.0f}" fill="white" stroke="#333"/>')
        parts.append(
            f'<rect x="{inset:.1f}" y="{inset:.1f}" width="{tile_w:.1f}" height="{tile_h:.1f}"'
            f' fill="none" stroke="#d24" stroke-width="2" stroke-dasharray="12 10"/>'
        )
        parts.append(
            f'<clipPath id="sheet{index}"><rect x="{inset:.1f}" y="{inset:.1f}"'
            f' width="{tile_w:.1f}" height="{tile_h:.1f}"/></clipPath>'
        )
        parts.append(f'<g clip-path="url(#sheet{index})">')
        for atom in atoms:
            if len(atom.points) < 2:
                continue
            dx, dy = shifts.get(group_of.get(atom.id, atom.id), [0.0, 0.0])
            path = " ".join(
                f"{inset + x + dx - area.left:.1f},{inset + y + dy - area.top:.1f}"
                for x, y in atom.points
            )
            parts.append(f'<polyline points="{path}" fill="none" stroke="#111" stroke-width="3"/>')
        parts.append("</g>")
        parts.append(
            f'<text x="{paper_w / 2:.0f}" y="{paper_h + 70:.0f}" font-size="46" text-anchor="middle"'
            f' fill="#fff">sheet {index + 1}</text>'
        )
        parts.append("</g>")
    parts.append("</svg>")

    with open(out, "w") as handle:
        handle.write("\n".join(parts))
    print(f"{out}: {len(tiles)} sheets, {len(items) - 1} entities, {strategy}")


if __name__ == "__main__":
    render(sys.argv[1], sys.argv[2], float(sys.argv[3]), sys.argv[4], sys.argv[5])
