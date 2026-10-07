"""Draw every erased stroke in red under the survivors, to see what the eraser replay removed."""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from replay import HERE, Erase, Piece, load_fixture, replay


def main() -> None:
    original, operations = load_fixture()
    survivors = replay()
    alive = {piece.stroke_id for piece in survivors}
    erased = [piece for piece in original if piece.stroke_id not in alive]
    partial_targets = {
        stroke_id
        for operation in operations
        if isinstance(operation, Erase) and operation.mode == "Normal"
        for stroke_id in operation.targets
    }
    object_targets = {
        stroke_id
        for operation in operations
        if isinstance(operation, Erase) and operation.mode == "Object"
        for stroke_id in operation.targets
    }
    print(f"strokes {len(original)}, survivors {len(alive)}, gone {len(erased)}")
    print(f"normal-erase targets {len(partial_targets)}, object-erase targets {len(object_targets)}")

    every = np.concatenate([piece.points for piece in original])
    left, top = every.min(axis=0) - 20
    right, bottom = every.max(axis=0) + 20
    scale = 0.8
    image = Image.new("RGB", (int((right - left) * scale), int((bottom - top) * scale)), "white")
    draw = ImageDraw.Draw(image)

    def plot(pieces: list[Piece], colour: str) -> None:
        for piece in pieces:
            coordinates = [((x - left) * scale, (y - top) * scale) for x, y in piece.points]
            if len(coordinates) > 1:
                draw.line(coordinates, fill=colour, width=1)

    plot(erased, "red")
    plot(survivors, "black")
    output = HERE / "results" / "erased.png"
    image.save(output)
    print(f"bounds x {left:.0f}..{right:.0f}, y {top:.0f}..{bottom:.0f}; wrote {output.name}")


if __name__ == "__main__":
    main()
