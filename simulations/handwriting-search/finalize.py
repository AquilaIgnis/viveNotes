"""Re-score the useful handwriting renderers under small raster perturbations.

The formula study used eleven nearby rotations/scales so one lucky raster did not choose a model
setting.  This applies the same discipline to the text finalists.  The source segmentation remains
fixed; each 48-pixel crop is rotated, translated and anisotropically scaled a little before OCR.
"""

from __future__ import annotations

import argparse
import json
import random
import sqlite3
import sys
import tempfile
from dataclasses import asdict
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
IMAGE_SIM = HERE.parent / "image-ocr"
sys.path.insert(0, str(IMAGE_SIM))
import pipeline  # noqa: E402

from render_fixture import extract_database, load_cells, load_erases, load_strokes  # noqa: E402
from study import Config, regions, score, visible_strokes  # noqa: E402


CANDIDATES: tuple[Config, ...] = (
    Config("whole-s1.5-p08", 1.5, 0.08, max_chunk_aspect=0),
    Config("chunk12-s1-p08", 1.0, 0.08, max_chunk_aspect=12),
    Config("chunk10-s1-p08", 1.0, 0.08, max_chunk_aspect=10),
    Config("chunk10-s1.5-p04", 1.5, 0.04, max_chunk_aspect=10),
    Config("chunk10-s1.5-p08", 1.5, 0.08, max_chunk_aspect=10),
    Config("chunk8-s1.5-p08", 1.5, 0.08, max_chunk_aspect=8),
)


def perturbation(seed: int):
    if seed == 0:
        return lambda image: image
    randomizer = random.Random(seed)
    angle = randomizer.uniform(-1.5, 1.5)
    scale_x = randomizer.uniform(0.97, 1.03)
    scale_y = randomizer.uniform(0.97, 1.03)
    shift_x = randomizer.uniform(-0.6, 0.6)
    shift_y = randomizer.uniform(-0.6, 0.6)

    def apply(image: Image.Image) -> Image.Image:
        width, height = image.size
        # PIL consumes an inverse transform: output -> source.  Keep the crop dimensions fixed so
        # this changes only the nearby raster the network sees, not the batching policy.
        cx, cy = width / 2, height / 2
        a = 1.0 / scale_x
        e = 1.0 / scale_y
        stretched = image.transform(
            image.size,
            Image.Transform.AFFINE,
            (a, 0, cx - a * (cx + shift_x), 0, e, cy - e * (cy + shift_y)),
            resample=Image.Resampling.BICUBIC,
            fillcolor=(255, 255, 255),
        )
        return stretched.rotate(
            angle,
            resample=Image.Resampling.BICUBIC,
            expand=False,
            fillcolor=(255, 255, 255),
        )

    return apply


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vive", required=True, type=Path)
    parser.add_argument("--rec", required=True)
    parser.add_argument("--dict", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="vivenotes-handwriting-") as temporary:
        database = extract_database(args.vive, Path(temporary))
        connection = sqlite3.connect(database)
        page_id = connection.execute(
            "select id from pages where deletedAt is null order by sortIndex limit 1"
        ).fetchone()[0]
        erases = load_erases(connection, page_id)
        strokes = visible_strokes(load_strokes(connection, page_id), erases)
        cells = load_cells(connection, page_id)
        connection.close()

    pipeline.REC_MAX_WIDTH = 3200
    engine = pipeline.Pipeline(args.rec, args.rec, args.dict, threads=4)
    raw: dict[str, list[dict]] = {}
    for candidate in CANDIDATES:
        grouped = regions(strokes, cells, candidate.max_chunk_aspect)
        rows = []
        for seed in range(11):
            result = score(
                candidate,
                grouped,
                erases,
                engine,
                crops=None,
                image_transform=perturbation(seed),
            )
            rows.append(asdict(result))
        raw[candidate.label] = rows
        mean_recall = sum(row["fuzzy_recall"] for row in rows) / len(rows)
        mean_cer = sum(row["cer"] for row in rows) / len(rows)
        print(
            f"{mean_recall:5.1%} mean / {min(row['fuzzy_recall'] for row in rows):5.1%} worst  "
            f"CER {mean_cer:.3f}  {candidate.label}"
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(raw, indent=2), encoding="utf-8")
    print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
