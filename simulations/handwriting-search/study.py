"""Sweep ink segmentation and rendering choices against the handwriting in hw.vive.

The metric is shaped like the product: character error rate is reported, but the primary score is
whether the app's fuzzy matcher would find each distinct four-or-more-letter word in the ground
truth.  Search does not require a perfect transcript; it does require that a query reaches the page.
"""

from __future__ import annotations

import argparse
import json
import math
import sqlite3
import sys
import tempfile
import time
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from statistics import median
from typing import Callable

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
IMAGE_SIM = HERE.parent / "image-ocr"
sys.path.insert(0, str(IMAGE_SIM))
import pipeline  # noqa: E402

from render_fixture import (  # noqa: E402
    Cell,
    Erase,
    Point,
    Stroke,
    extract_database,
    load_cells,
    load_erases,
    load_strokes,
)


# Transcribed from render_fixture.py's lossless preview, never from a model result.  Case and
# punctuation are ignored by the score, matching search.  The table's blank header row is omitted.
GROUND_TRUTH: tuple[str, ...] = (
    "Abhor the mutant",
    "Be not merciful",
    "Be the Emperors reaper",
    "Foolish are those who fear nothing yet claim to know everything",
    "Innocentia probat nihil",
    "Only in death does duty end",
    "Suffer no impurity",
    "lack of",
    "faith is",
    "treason",
    "let faith",
    "trample",
    "reason",
)


@dataclass(frozen=True)
class Region:
    id: str
    strokes: tuple[Stroke, ...]
    cell: Cell | None = None

    @property
    def bounds(self) -> tuple[float, float, float, float]:
        bounds = [stroke.bounds for stroke in self.strokes]
        return (
            min(item[0] for item in bounds),
            min(item[1] for item in bounds),
            max(item[2] for item in bounds),
            max(item[3] for item in bounds),
        )


@dataclass(frozen=True)
class Config:
    label: str
    target_stem_px: float
    pad_fraction: float
    smooth_window: int = 0
    max_chunk_aspect: float = 10.0
    current_shared_renderer: bool = False


@dataclass(frozen=True)
class Reading:
    region: str
    text: str
    confidence: float
    width: int


@dataclass(frozen=True)
class Result:
    label: str
    cer: float
    fuzzy_words_found: int
    fuzzy_words_total: int
    fuzzy_recall: float
    missing_words: tuple[str, ...]
    mean_confidence: float
    render_ms: float
    inference_ms: float
    regions: int
    readings: tuple[Reading, ...]


def center(stroke: Stroke) -> Point:
    left, top, right, bottom = stroke.bounds
    return (left + right) / 2, (top + bottom) / 2


def cell_for(stroke: Stroke, cells: list[Cell]) -> Cell | None:
    x, y = center(stroke)
    candidates = []
    for cell in cells:
        x_slop = (cell.right - cell.left) * 0.08
        y_slop = (cell.bottom - cell.top) * 0.25
        if (
            cell.left - x_slop <= x <= cell.right + x_slop
            and cell.top - y_slop <= y <= cell.bottom + y_slop
        ):
            candidates.append(cell)
    return min(
        candidates,
        key=lambda cell: abs(x - (cell.left + cell.right) / 2)
        + abs(y - (cell.top + cell.bottom) / 2),
        default=None,
    )


def visible_strokes(strokes: list[Stroke], erases: list[Erase]) -> list[Stroke]:
    """Approximate native subtraction closely enough to crop only what remains visible.

    The stored input batch retains the points a normal eraser cut away.  Rendering those points and
    then painting the target-specific mask white produced the right preview, but using their old
    bounds still left a fully erased practice stroke hundreds of units below a real line.  This
    local mask recovers the surviving pixel bounds; Android uses the exact native mesh bounds.
    """
    by_target: dict[str, list[Erase]] = {}
    for erase in erases:
        for target in erase.targets:
            by_target.setdefault(target, []).append(erase)
    output: list[Stroke] = []
    scale = 4.0
    for stroke in strokes:
        masks = by_target.get(stroke.id)
        if not masks:
            output.append(stroke)
            continue
        raw_left, raw_top, raw_right, raw_bottom = stroke.bounds
        margin = max([stroke.size] + [erase.size for erase in masks]) + 2.0
        left, top = raw_left - margin, raw_top - margin
        right, bottom = raw_right + margin, raw_bottom + margin
        image = Image.new(
            "L",
            (max(1, math.ceil((right - left) * scale)), max(1, math.ceil((bottom - top) * scale))),
            0,
        )
        drawing = ImageDraw.Draw(image)
        points = [((x - left) * scale, (y - top) * scale) for x, y in stroke.points]
        if len(points) == 1:
            x, y = points[0]
            radius = stroke.size * scale / 2
            drawing.ellipse((x - radius, y - radius, x + radius, y + radius), fill=255)
        else:
            drawing.line(
                points, fill=255, width=max(1, round(stroke.size * scale)), joint="curve"
            )
        for erase in masks:
            erased = [((x - left) * scale, (y - top) * scale) for x, y in erase.points]
            if len(erased) == 1:
                x, y = erased[0]
                radius = erase.size * scale / 2
                drawing.ellipse((x - radius, y - radius, x + radius, y + radius), fill=0)
            else:
                drawing.line(
                    erased,
                    fill=0,
                    width=max(1, round(erase.size * scale)),
                    joint="curve",
                )
        box = image.getbbox()
        if box is None:
            continue
        output.append(
            replace(
                stroke,
                clipped_bounds=(
                    left + box[0] / scale,
                    top + box[1] / scale,
                    left + box[2] / scale,
                    top + box[3] / scale,
                ),
            )
        )
    return output


def temporal_lines(strokes: list[Stroke]) -> list[list[Stroke]]:
    """Split online ink at a downward pen return, then spatially fold delayed marks back in.

    Lines three and four in hw.vive overlap vertically; a projection profile merges them.  The
    stored stroke order supplies the missing fact: a new line begins when the pen returns far left
    and down.  Tiny delayed strokes (i dots and t bars) are assigned to the closest completed line.
    """
    if not strokes:
        return []
    heights = [item.bounds[3] - item.bounds[1] for item in strokes]
    typical = median(value for value in heights if value >= median(heights))
    downward = max(typical * 1.1, 24.0)
    return_left = max(typical * 2.0, 48.0)

    lines: list[list[Stroke]] = [[]]
    baseline_centers: list[float] = []
    rightmost = -math.inf
    for stroke in sorted(strokes, key=lambda item: item.seq):
        x, y = center(stroke)
        current = lines[-1]
        baseline = median(baseline_centers) if baseline_centers else y
        # Stroke order is useful but not reading order: this fixture's author returned to an earlier
        # blank area after filling the table.  A large vertical jump plus a pen return starts a new
        # line in either direction; final regions are sorted spatially below.
        starts_next = (
            bool(current)
            and abs(y - baseline) > downward
            and x < rightmost - return_left
        )
        if starts_next:
            lines.append([])
            baseline_centers = []
            rightmost = -math.inf
        lines[-1].append(stroke)
        # Very short crossbars and dots should not pull the running baseline away from the letters.
        if stroke.bounds[3] - stroke.bounds[1] >= typical * 0.45:
            baseline_centers.append(y)
        rightmost = max(rightmost, stroke.bounds[2])

    # A writer may add a dot after moving to the next line.  Fold single tiny runs into the closest
    # line when their vertical center is plainly inside its visual band.
    stable = [line for line in lines if len(line) > 2]
    delayed = [stroke for line in lines if len(line) <= 2 for stroke in line]
    for stroke in delayed:
        _, y = center(stroke)
        best = min(
            stable,
            key=lambda line: abs(y - median(center(item)[1] for item in line)),
        )
        best.append(stroke)
    return stable


def word_groups(strokes: list[Stroke]) -> list[list[Stroke]]:
    """Group horizontally neighbouring strokes; a real word gap scales with line height."""
    if not strokes:
        return []
    top = min(item.bounds[1] for item in strokes)
    bottom = max(item.bounds[3] for item in strokes)
    threshold = max(6.0, (bottom - top) * 0.28)
    ordered = sorted(strokes, key=lambda item: item.bounds[0])
    words: list[list[Stroke]] = []
    right = -math.inf
    for stroke in ordered:
        if words and stroke.bounds[0] - right <= threshold:
            words[-1].append(stroke)
            right = max(right, stroke.bounds[2])
        else:
            words.append([stroke])
            right = stroke.bounds[2]
    return words


def chunk_line(strokes: list[Stroke], max_aspect: float) -> list[list[Stroke]]:
    if max_aspect <= 0:
        return [strokes]
    chunks: list[list[Stroke]] = []
    current: list[Stroke] = []
    for word in word_groups(strokes):
        candidate = current + word
        left = min(item.bounds[0] for item in candidate)
        top = min(item.bounds[1] for item in candidate)
        right = max(item.bounds[2] for item in candidate)
        bottom = max(item.bounds[3] for item in candidate)
        aspect = (right - left) / max(bottom - top, 1.0)
        if current and aspect > max_aspect:
            chunks.append(current)
            current = list(word)
        else:
            current = candidate
    if current:
        chunks.append(current)
    return chunks


def regions(strokes: list[Stroke], cells: list[Cell], max_aspect: float) -> list[Region]:
    by_cell: dict[str, list[Stroke]] = {cell.id: [] for cell in cells}
    cell_lookup = {cell.id: cell for cell in cells}
    free: list[Stroke] = []
    for stroke in strokes:
        cell = cell_for(stroke, cells)
        if cell is None:
            free.append(stroke)
        else:
            by_cell[cell.id].append(stroke)

    output: list[Region] = []
    for line_index, line in enumerate(temporal_lines(free)):
        for chunk_index, chunk in enumerate(chunk_line(line, max_aspect)):
            output.append(Region(f"line-{line_index + 1}.{chunk_index + 1}", tuple(chunk)))
    for identity, grouped in by_cell.items():
        if grouped:
            output.append(Region(f"cell-{identity}", tuple(grouped), cell_lookup[identity]))
    return reading_order(output)


def reading_order(items: list[Region]) -> list[Region]:
    """Top-to-bottom rows, left-to-right within a row.

    Sorting only by `top` put the right half of a long line before its left half whenever a tall
    ascender reached a pixel higher.  This is the same row-overlap rule the picture OCR path uses.
    """
    rows: list[list[Region]] = []
    for item in sorted(items, key=lambda region: (region.bounds[1], region.bounds[0])):
        item_top, item_bottom = item.bounds[1], item.bounds[3]
        placed = False
        for row in rows:
            row_top = min(region.bounds[1] for region in row)
            row_bottom = max(region.bounds[3] for region in row)
            overlap = min(item_bottom, row_bottom) - max(item_top, row_top)
            smaller = min(item_bottom - item_top, row_bottom - row_top)
            if overlap > 0 and overlap >= smaller * 0.5:
                row.append(item)
                placed = True
                break
        if not placed:
            rows.append([item])
    return [item for row in rows for item in sorted(row, key=lambda region: region.bounds[0])]


def smooth(points: tuple[Point, ...], window: int) -> list[Point]:
    if window <= 1 or len(points) < 3:
        return list(points)
    half = window // 2
    output: list[Point] = []
    for index in range(len(points)):
        begin = max(0, index - half)
        end = min(len(points), index + half + 1)
        output.append(
            (
                sum(point[0] for point in points[begin:end]) / (end - begin),
                sum(point[1] for point in points[begin:end]) / (end - begin),
            )
        )
    output[0] = points[0]
    output[-1] = points[-1]
    return output


def render_region(region: Region, erases: list[Erase], config: Config) -> Image.Image:
    left, top, right, bottom = region.bounds
    content_width = max(right - left, 1.0)
    content_height = max(bottom - top, 1.0)
    pad = content_height * config.pad_fraction
    final_scale = 48.0 / (content_height + pad * 2)
    width = max(1, min(3200, math.ceil((content_width + pad * 2) * final_scale)))
    supersample = 4
    canvas = Image.new("L", (width * supersample, 48 * supersample), 255)
    scale = final_scale * supersample
    target_stem = config.target_stem_px
    if config.current_shared_renderer:
        # InkSelectionRenderer pins ten pixels in FormulaNet's 384-square.  Text preprocessing then
        # scales by height instead, so the actual text stem grows with line aspect ratio.
        longest = max(content_width, content_height)
        page_stem = 10.0 * longest / (384.0 - 10.0)
        target_stem = page_stem * final_scale

    by_target: dict[str, list[Erase]] = {}
    for erase in erases:
        for target in erase.targets:
            by_target.setdefault(target, []).append(erase)

    for stroke in region.strokes:
        mask = Image.new("L", canvas.size, 0)
        drawing = ImageDraw.Draw(mask)
        points = [
            ((x - left + pad) * scale, (y - top + pad) * scale)
            for x, y in smooth(stroke.points, config.smooth_window)
        ]
        stem = target_stem if target_stem > 0 else stroke.size * final_scale
        line_width = max(1, round(stem * supersample))
        if len(points) == 1:
            x, y = points[0]
            radius = line_width / 2
            drawing.ellipse((x - radius, y - radius, x + radius, y + radius), fill=255)
        else:
            drawing.line(points, fill=255, width=line_width, joint="curve")
        for erase in by_target.get(stroke.id, []):
            erased = [
                ((x - left + pad) * scale, (y - top + pad) * scale) for x, y in erase.points
            ]
            erase_width = max(1, round(erase.size * scale))
            if len(erased) == 1:
                x, y = erased[0]
                radius = erase_width / 2
                drawing.ellipse((x - radius, y - radius, x + radius, y + radius), fill=0)
            else:
                drawing.line(erased, fill=0, width=erase_width, joint="curve")
        canvas.paste(0, mask=mask)
    return canvas.resize((width, 48), Image.Resampling.LANCZOS).convert("RGB")


def canonical(text: str) -> str:
    return " ".join("".join(character.lower() if character.isalnum() else " " for character in text).split())


def edit_distance(left: str, right: str) -> int:
    previous = list(range(len(right) + 1))
    for i, a in enumerate(left, start=1):
        current = [i]
        for j, b in enumerate(right, start=1):
            current.append(min(previous[j] + 1, current[-1] + 1, previous[j - 1] + (a != b)))
        previous = current
    return previous[-1]


def fuzzy_term(term: str, corpus: str) -> bool:
    words = canonical(corpus).split()
    term = canonical(term)
    if term in canonical(corpus):
        return True
    budget = 0 if len(term) < 4 else 1 if len(term) < 7 else 2
    for word in words:
        prefix = word[: len(term)]
        if budget and edit_distance(term, prefix) <= budget:
            return True
        iterator = iter(word)
        if all(character in iterator for character in term):
            return True
    return False


def score(
    config: Config,
    groups: list[Region],
    erases: list[Erase],
    engine: pipeline.Pipeline,
    crops: Path | None,
    image_transform: Callable[[Image.Image], Image.Image] | None = None,
) -> Result:
    began = time.perf_counter()
    images = [render_region(region, erases, config) for region in groups]
    if image_transform is not None:
        images = [image_transform(image) for image in images]
    render_ms = (time.perf_counter() - began) * 1000
    began = time.perf_counter()
    predictions = engine.recognize_batch(images, batch_size=1)
    inference_ms = (time.perf_counter() - began) * 1000
    readings = tuple(
        Reading(region.id, text.strip(), confidence, image.width)
        for region, image, (text, confidence) in zip(groups, images, predictions)
        if pipeline.searchable(text.strip()) and confidence >= 0.5
    )
    if crops:
        directory = crops / config.label
        directory.mkdir(parents=True, exist_ok=True)
        for index, (region, image) in enumerate(zip(groups, images)):
            image.save(directory / f"{index:02d}-{region.id}.png")

    expected = canonical(" ".join(GROUND_TRUTH))
    actual = canonical(" ".join(reading.text for reading in readings))
    terms = sorted({word for line in GROUND_TRUTH for word in canonical(line).split() if len(word) >= 4})
    missing = tuple(term for term in terms if not fuzzy_term(term, actual))
    confidence = sum(reading.confidence for reading in readings) / len(readings) if readings else 0
    return Result(
        label=config.label,
        cer=round(edit_distance(expected, actual) / len(expected), 4),
        fuzzy_words_found=len(terms) - len(missing),
        fuzzy_words_total=len(terms),
        fuzzy_recall=round((len(terms) - len(missing)) / len(terms), 4),
        missing_words=missing,
        mean_confidence=round(confidence, 4),
        render_ms=round(render_ms, 1),
        inference_ms=round(inference_ms, 1),
        regions=len(groups),
        readings=readings,
    )


def configurations() -> list[Config]:
    output = [
        Config("stored-width", 0.0, 0.18, max_chunk_aspect=0),
        Config("current-shared-renderer", 0.0, 0.18, max_chunk_aspect=0, current_shared_renderer=True),
    ]
    for aspect in (0.0, 8.0, 10.0, 12.0):
        for stem in (1.0, 1.5, 2.0, 2.5, 3.0, 4.0):
            for padding in (0.04, 0.08, 0.12, 0.18):
                output.append(
                    Config(
                        f"a{aspect:g}-s{stem:g}-p{padding:g}",
                        stem,
                        padding,
                        max_chunk_aspect=aspect,
                    )
                )
    # Geometry treatments are judged only around the likely text optimum; a full Cartesian sweep
    # would spend most of its runs reconfirming that obviously blotted or hairline ink is bad.
    output += [
        Config("smooth3-a10-s2-p08", 2.0, 0.08, smooth_window=3, max_chunk_aspect=10),
        Config("smooth5-a10-s2-p08", 2.0, 0.08, smooth_window=5, max_chunk_aspect=10),
    ]
    return output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--vive", required=True, type=Path)
    parser.add_argument("--rec", required=True, type=str)
    parser.add_argument("--dict", required=True, type=str)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--crops", type=Path)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="vivenotes-handwriting-") as temporary:
        database = extract_database(args.vive, Path(temporary))
        connection = sqlite3.connect(database)
        page_id = connection.execute(
            "select id from pages where deletedAt is null order by sortIndex limit 1"
        ).fetchone()[0]
        move_count = connection.execute(
            "select count(*) from ink_moves where pageId = ? and deletedAt is null", (page_id,)
        ).fetchone()[0]
        if move_count:
            raise ValueError("the desktop study does not approximate lasso moves")
        strokes = load_strokes(connection, page_id)
        erases = load_erases(connection, page_id)
        cells = load_cells(connection, page_id)
        connection.close()

    strokes = visible_strokes(strokes, erases)

    pipeline.REC_MAX_WIDTH = 3200
    engine = pipeline.Pipeline(args.rec, args.rec, args.dict, threads=4)
    # Only the recognizer session is used.  Pipeline currently requires both constructor paths; the
    # duplicate session is closed when this short process exits and keeps this study on its proven
    # CTC preprocessing/decoding implementation.
    results: list[Result] = []
    for config in configurations():
        grouped = regions(strokes, cells, config.max_chunk_aspect)
        result = score(config, grouped, erases, engine, args.crops)
        results.append(result)
        print(
            f"{result.fuzzy_recall:5.1%}  CER {result.cer:.3f}  "
            f"conf {result.mean_confidence:.2f}  {result.inference_ms:6.0f} ms  "
            f"{result.regions:2d} regions  {result.label}",
            flush=True,
        )

    results.sort(key=lambda item: (-item.fuzzy_recall, item.cer, item.inference_ms))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps([asdict(result) for result in results], indent=2), encoding="utf-8"
    )
    print("\nBest configurations:")
    for result in results[:10]:
        print(
            f"  {result.fuzzy_recall:5.1%}  CER {result.cer:.3f}  "
            f"{result.inference_ms:6.0f} ms  {result.label}  "
            f"missing={','.join(result.missing_words) or '-'}"
        )
        for reading in result.readings:
            print(f"      {reading.region:16s} {reading.confidence:.2f} | {reading.text}")
    print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
