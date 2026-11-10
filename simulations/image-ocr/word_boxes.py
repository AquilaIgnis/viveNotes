"""Where inside a recognized line a word is, from the recognizer's own CTC columns.

The detector gives one box per line. Highlighting the word somebody searched for needs a position
inside that box, and the recognizer already knows one: every character it emits came from a run of
output columns. This measures how good that position is against the cheap alternative of dividing
the box by character count, using pictures whose word positions are known exactly.

Everything is scored only on lines read exactly right, because a character position means nothing
for a character that was misread.

Run from this directory:

    uv run --no-project --python /usr/bin/python3 --with onnxruntime --with numpy --with pillow \
        python word_boxes.py \
        --det ../../app/src/main/assets/ai/pp-ocrv5_mobile_det.onnx \
        --rec ../../app/src/main/assets/ai/en_pp-ocrv5_mobile_rec.onnx \
        --dict ../../app/src/main/assets/ai/ppocrv5_en_dict.txt \
        --out results/word_boxes.json
"""

from __future__ import annotations

import argparse
import json
import math
import random
import statistics
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageDraw, ImageFont

import pipeline

REC_BASE_WIDTH: int = 320

FONTS: dict[str, str] = {
    "sans": "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Regular.ttf",
    "sans_bold": "/usr/share/fonts/liberation-sans-fonts/LiberationSans-Bold.ttf",
    "serif": "/usr/share/fonts/liberation-serif-fonts/LiberationSerif-Regular.ttf",
    "mono": "/usr/share/fonts/liberation-mono-fonts/LiberationMono-Regular.ttf",
    "noto": "/usr/share/fonts/NotoSans-Regular.ttf",
}

SENTENCES: list[str] = [
    "Invoice total due on the first of March",
    "Quarterly revenue grew by twelve percent",
    "Meeting notes from the design review",
    "Remember to renew the parking permit",
    "Warranty expires after three years of use",
    "The quick brown fox jumps over a lazy dog",
    "Fixed a crash when importing large notebooks",
    "Search now covers table cells and titles",
    "Please send the signed contract by Friday",
    "Illustrated guide to minimal illumination",
    "Wall mill will fill little tall pillows",
    "Move 42 boxes to warehouse B7 before noon",
]


@dataclass
class TruthWord:
    text: str
    left: float
    right: float
    top: float
    bottom: float


@dataclass
class TruthLine:
    text: str
    words: list[TruthWord]


@dataclass
class Picture:
    name: str
    image: Image.Image
    lines: list[TruthLine]


@dataclass
class Runs:
    text: str
    first: list[int]
    last: list[int]
    columns: int


def rendered(name: str, font_path: str, size: int, dark: bool, angle: float) -> Picture:
    """One picture of several lines, with each word's ink box known to the pixel."""
    font: ImageFont.FreeTypeFont = ImageFont.truetype(font_path, size)
    rows: list[str] = random.sample(SENTENCES, 6)
    left: int = 36
    leading: int = int(size * 2.1)
    width: int = 2 * left + int(max(font.getlength(row) for row in rows))
    height: int = 2 * left + leading * len(rows)
    ground: tuple[int, int, int] = (28, 28, 32) if dark else (255, 255, 255)
    ink: tuple[int, int, int] = (232, 232, 236) if dark else (24, 24, 28)
    image: Image.Image = Image.new("RGB", (width, height), ground)
    draw: ImageDraw.ImageDraw = ImageDraw.Draw(image)

    lines: list[TruthLine] = []
    for index, row in enumerate(rows):
        top: int = left + index * leading
        draw.text((left, top), row, font=font, fill=ink)
        words: list[TruthWord] = []
        cursor: int = 0
        for word in row.split(" "):
            start: int = row.index(word, cursor)
            cursor = start + len(word)
            advance: float = font.getlength(row[:start])
            box: tuple[float, float, float, float] = font.getbbox(word)
            words.append(
                TruthWord(
                    word,
                    left + advance + box[0],
                    left + advance + box[2],
                    top + box[1],
                    top + box[3],
                )
            )
        lines.append(TruthLine(row, words))

    if angle != 0.0:
        image = image.rotate(angle, Image.BICUBIC, expand=False, fillcolor=ground)
        lines = [rotated_line(line, angle, width, height) for line in lines]
    return Picture(name, image, lines)


def rotated_point(x: float, y: float, angle: float, width: int, height: int) -> tuple[float, float]:
    """Where PIL's counter-clockwise rotation about the centre sends a point."""
    radians: float = math.radians(angle)
    cx: float = width / 2.0
    cy: float = height / 2.0
    dx: float = x - cx
    dy: float = y - cy
    return cx + dx * math.cos(radians) + dy * math.sin(radians), cy - dx * math.sin(radians) + dy * math.cos(radians)


def rotated_line(line: TruthLine, angle: float, width: int, height: int) -> TruthLine:
    """The same words with their ends carried through the rotation; heights are left unrotated."""
    words: list[TruthWord] = []
    for word in line.words:
        middle: float = (word.top + word.bottom) / 2.0
        ax, ay = rotated_point(word.left, middle, angle, width, height)
        bx, by = rotated_point(word.right, middle, angle, width, height)
        words.append(TruthWord(word.text, ax, bx, ay, by))
    return TruthLine(line.text, words)


def decode_runs(logits: np.ndarray, characters: list[str]) -> Runs:
    """The app's CTC decode, also recording the columns each emitted character occupied."""
    best: np.ndarray = logits.argmax(axis=1)
    out: list[str] = []
    first: list[int] = []
    last: list[int] = []
    previous: int = 0
    for column, value in enumerate(best):
        symbol: int = int(value)
        if symbol != 0 and symbol != previous and symbol < len(characters):
            out.append(characters[symbol])
            first.append(column)
            last.append(column)
        elif symbol != 0 and symbol == previous and last:
            last[-1] = column
        previous = symbol
    return Runs("".join(out), first, last, int(logits.shape[0]))


def read_runs(ocr: pipeline.Pipeline, strip: Image.Image) -> tuple[Runs, float]:
    """One strip through the recognizer, returning its runs and the column width as a fraction."""
    content: int = strip.size[0]
    tensor_width: int = max(REC_BASE_WIDTH, content)
    tensor: np.ndarray = np.zeros((1, 3, pipeline.REC_HEIGHT, tensor_width), dtype=np.float32)
    pixels: np.ndarray = np.asarray(strip.convert("RGB"), dtype=np.float32) / 255.0
    pixels = (pixels - 0.5) / 0.5
    tensor[0, :, :, :content] = pixels.transpose(2, 0, 1)
    logits: np.ndarray = ocr.rec.run(None, {ocr.rec.get_inputs()[0].name: tensor})[0][0]
    runs: Runs = decode_runs(logits, ocr.characters)
    return runs, (tensor_width / runs.columns) / content


def trim(runs: Runs) -> Runs:
    """Drops leading and trailing spaces and their runs, as the app does when it keeps a line."""
    start: int = 0
    end: int = len(runs.text)
    while start < end and runs.text[start] == " ":
        start += 1
    while end > start and runs.text[end - 1] == " ":
        end -= 1
    return Runs(runs.text[start:end], runs.first[start:end], runs.last[start:end], runs.columns)


def edges_proportional(runs: Runs, column: float) -> list[float]:
    """The cheap alternative: every character the same width."""
    count: int = len(runs.text)
    return [index / count for index in range(count + 1)]


def edges_runs(runs: Runs, column: float) -> list[float]:
    """Each boundary is the start of the run after it: tight on the left, loose on the right."""
    count: int = len(runs.text)
    edges: list[float] = [runs.first[index] * column for index in range(count)]
    edges.append((runs.last[count - 1] + 1) * column)
    return edges


def edges_midpoint(runs: Runs, column: float, outer: float | None = None) -> list[float]:
    """Each inner boundary is halfway across the blank between two runs.

    `outer` is how far past the first and last run the line's ends sit, in columns. None leaves the
    ends at the line box itself.
    """
    count: int = len(runs.text)
    edges: list[float] = [0.0] * (count + 1)
    for index in range(1, count):
        edges[index] = (((runs.last[index - 1] + 1) + runs.first[index]) / 2.0) * column
    if outer is None:
        edges[0] = 0.0
        edges[count] = 1.0
    else:
        edges[0] = max(0.0, (runs.first[0] - outer) * column)
        edges[count] = min(1.0, (runs.last[count - 1] + 1 + outer) * column)
    return edges


def edges_padded(runs: Runs, column: float, line_share: float, pad_lines: float) -> list[float]:
    """Midpoints, with a boundary next to a space pushed into that space by `pad_lines` line heights.

    Never past the middle of the space's own run, so the two sides of one space cannot cross. Only a
    word's outside edges move: a boundary between two letters of one word stays put, so a match that
    stops part way through a word still stops where it should.
    """
    edges: list[float] = edges_midpoint(runs, column, None)
    pad: float = pad_lines * line_share
    for index, character in enumerate(runs.text):
        if character != " ":
            continue
        middle: float = ((runs.first[index] + runs.last[index] + 1) / 2.0) * column
        edges[index] = min(max(edges[index], min(edges[index] + pad, middle)), 1.0)
        edges[index + 1] = max(min(edges[index + 1], max(edges[index + 1] - pad, middle)), 0.0)
    return edges


def edges_midpoint_ends_half(runs: Runs, column: float) -> list[float]:
    return edges_midpoint(runs, column, 0.5)


def edges_midpoint_ends_one(runs: Runs, column: float) -> list[float]:
    return edges_midpoint(runs, column, 1.0)


def edges_midpoint_ends_box(runs: Runs, column: float) -> list[float]:
    return edges_midpoint(runs, column, None)


ESTIMATORS: dict[str, object] = {
    "proportional": edges_proportional,
    "run_starts": edges_runs,
    "midpoint_ends_box": edges_midpoint_ends_box,
    "midpoint_ends_half": edges_midpoint_ends_half,
    "midpoint_ends_one": edges_midpoint_ends_one,
}


PADDINGS: tuple[float, ...] = (0.0, 0.05, 0.1, 0.15, 0.2, 0.3)


def word_spans(text: str) -> list[tuple[int, int]]:
    """Character ranges of the words of a line, split on its single spaces."""
    spans: list[tuple[int, int]] = []
    cursor: int = 0
    for word in text.split(" "):
        spans.append((cursor, cursor + len(word)))
        cursor += len(word) + 1
    return spans


def along(
    x: float,
    y: float,
    quad: tuple[tuple[float, float], ...],
) -> float:
    """How far along the line's reading axis a point sits, as a fraction of that axis."""
    (ax, ay), (bx, by) = quad[0], quad[1]
    dx: float = bx - ax
    dy: float = by - ay
    return ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)


def iou(a0: float, a1: float, b0: float, b1: float) -> float:
    overlap: float = max(0.0, min(a1, b1) - max(a0, b0))
    union: float = max(a1, b1) - min(a0, b0)
    return overlap / union if union > 0.0 else 0.0


def line_height(quad: tuple[tuple[float, float], ...]) -> float:
    return math.dist(quad[0], quad[3])


def line_length(quad: tuple[tuple[float, float], ...]) -> float:
    return math.dist(quad[0], quad[1])


def score_picture(
    ocr: pipeline.Pipeline,
    picture: Picture,
    scores: dict[str, list[dict[str, float]]],
    padded: dict[float, list[dict[str, float]]],
) -> tuple[int, int]:
    """Adds one row per estimator per word. Returns (lines scored, lines available)."""
    truth: dict[str, TruthLine] = {line.text: line for line in picture.lines}
    scored: int = 0
    for quad in ocr.detect(picture.image):
        strip: Image.Image = pipeline.crop(picture.image, quad)
        runs, column = read_runs(ocr, strip)
        runs = trim(runs)
        line: TruthLine | None = truth.get(runs.text)
        if line is None or not runs.text:
            continue
        scored += 1
        spans: list[tuple[int, int]] = word_spans(runs.text)
        length: float = line_length(quad)
        height: float = line_height(quad)
        for pad in PADDINGS:
            edges_pad: list[float] = edges_padded(runs, column, height / length, pad)
            for index, ((start, end), word) in enumerate(zip(spans, line.words)):
                middle_y: float = (word.top + word.bottom) / 2.0
                true_left_pad: float = along(word.left, middle_y, quad)
                true_right_pad: float = along(word.right, middle_y, quad)
                left_pad: float = edges_pad[start]
                right_pad: float = edges_pad[end]
                covered: float = max(0.0, min(right_pad, true_right_pad) - max(left_pad, true_left_pad))
                coverage: float = covered / max(true_right_pad - true_left_pad, 1e-9)
                bleed: bool = False
                if index + 1 < len(line.words):
                    following: TruthWord = line.words[index + 1]
                    bleed = bleed or right_pad > along(following.left, (following.top + following.bottom) / 2.0, quad)
                if index > 0:
                    preceding: TruthWord = line.words[index - 1]
                    bleed = bleed or left_pad < along(preceding.right, (preceding.top + preceding.bottom) / 2.0, quad)
                padded[pad].append(
                    {
                        "iou": iou(left_pad, right_pad, true_left_pad, true_right_pad),
                        "coverage": coverage,
                        "bleed": 1.0 if bleed else 0.0,
                    }
                )
        for (start, end), word in zip(spans, line.words):
            true_left: float = along(word.left, (word.top + word.bottom) / 2.0, quad)
            true_right: float = along(word.right, (word.top + word.bottom) / 2.0, quad)
            for name, estimator in ESTIMATORS.items():
                edges: list[float] = estimator(runs, column)  # type: ignore[operator]
                left: float = edges[start]
                right: float = edges[end]
                scores[name].append(
                    {
                        "iou": iou(left, right, true_left, true_right),
                        "left_error_lines": (left - true_left) * length / height,
                        "right_error_lines": (right - true_right) * length / height,
                    }
                )
    return scored, len(truth)


def summary(rows: list[dict[str, float]]) -> dict[str, float]:
    ious: list[float] = sorted(row["iou"] for row in rows)
    left: list[float] = [row["left_error_lines"] for row in rows]
    right: list[float] = [row["right_error_lines"] for row in rows]
    return {
        "words": len(rows),
        "iou_mean": round(statistics.fmean(ious), 4),
        "iou_p10": round(ious[len(ious) // 10], 4),
        "iou_p50": round(ious[len(ious) // 2], 4),
        "iou_min": round(ious[0], 4),
        "left_bias_lines": round(statistics.fmean(left), 4),
        "right_bias_lines": round(statistics.fmean(right), 4),
        "left_abs_lines": round(statistics.fmean(abs(value) for value in left), 4),
        "right_abs_lines": round(statistics.fmean(abs(value) for value in right), 4),
    }


def padding_summary(rows: list[dict[str, float]]) -> dict[str, float]:
    coverage: list[float] = sorted(row["coverage"] for row in rows)
    return {
        "iou_mean": round(statistics.fmean(row["iou"] for row in rows), 4),
        "coverage_mean": round(statistics.fmean(coverage), 4),
        "coverage_p10": round(coverage[len(coverage) // 10], 4),
        "whole_word_covered": round(statistics.fmean(1.0 if value >= 0.98 else 0.0 for value in coverage), 4),
        "reaches_neighbouring_word": round(statistics.fmean(row["bleed"] for row in rows), 4),
    }


def main() -> None:
    parser: argparse.ArgumentParser = argparse.ArgumentParser()
    parser.add_argument("--det", required=True)
    parser.add_argument("--rec", required=True)
    parser.add_argument("--dict", required=True)
    parser.add_argument("--out", required=True)
    arguments: argparse.Namespace = parser.parse_args()

    random.seed(7)
    ocr: pipeline.Pipeline = pipeline.Pipeline(arguments.det, arguments.rec, arguments.dict)
    scores: dict[str, list[dict[str, float]]] = {name: [] for name in ESTIMATORS}
    groups: dict[str, dict[str, list[dict[str, float]]]] = {}
    padded: dict[float, list[dict[str, float]]] = {pad: [] for pad in PADDINGS}
    lines_scored: int = 0
    lines_total: int = 0

    for font_name, font_path in FONTS.items():
        for size in (18, 26, 40):
            for dark in (False, True):
                for angle in (0.0, -3.5):
                    picture: Picture = rendered(
                        f"{font_name}_{size}_{'dark' if dark else 'light'}_{angle}",
                        font_path,
                        size,
                        dark,
                        angle,
                    )
                    group: str = "rotated" if angle != 0.0 else "straight"
                    bucket: dict[str, list[dict[str, float]]] = groups.setdefault(
                        group, {name: [] for name in ESTIMATORS}
                    )
                    before: dict[str, int] = {name: len(scores[name]) for name in ESTIMATORS}
                    done, total = score_picture(ocr, picture, scores, padded)
                    lines_scored += done
                    lines_total += total
                    for name in ESTIMATORS:
                        bucket[name].extend(scores[name][before[name]:])

    results: dict[str, object] = {
        "lines_scored": lines_scored,
        "lines_total": lines_total,
        "overall": {name: summary(rows) for name, rows in scores.items()},
        "by_group": {
            group: {name: summary(rows) for name, rows in bucket.items()}
            for group, bucket in groups.items()
        },
        "padding": {str(pad): padding_summary(rows) for pad, rows in padded.items()},
    }
    with open(arguments.out, "w", encoding="utf-8") as handle:
        json.dump(results, handle, indent=2)

    print(f"{'pad (line heights)':20s} {'IoU':>7s} {'cover':>7s} {'cover p10':>10s} {'whole word':>11s} {'bleeds':>8s}")
    for pad, rows in padded.items():
        row: dict[str, float] = padding_summary(rows)
        print(
            f"{pad:20.2f} {row['iou_mean']:7.3f} {row['coverage_mean']:7.3f} {row['coverage_p10']:10.3f} "
            f"{row['whole_word_covered']:11.3f} {row['reaches_neighbouring_word']:8.3f}"
        )
    print(f"lines exactly right: {lines_scored}/{lines_total}")
    print(f"{'estimator':22s} {'IoU mean':>9s} {'p10':>7s} {'p50':>7s} {'min':>7s} {'|L| lines':>10s} {'|R| lines':>10s}")
    for name, rows in scores.items():
        row = summary(rows)
        print(
            f"{name:22s} {row['iou_mean']:9.3f} {row['iou_p10']:7.3f} {row['iou_p50']:7.3f} "
            f"{row['iou_min']:7.3f} {row['left_abs_lines']:10.3f} {row['right_abs_lines']:10.3f}"
        )


if __name__ == "__main__":
    main()
