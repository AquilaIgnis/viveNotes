"""PP-OCRv5 detection + recognition over a whole picture, in the shape the app will run it.

Everything here has a counterpart in the app: `det_tensor` is `preprocessDetection`, `crop` is the
`setPolyToPoly` warp in `ImageTextRecognizer`, and `recognize_batch` is the batched rec call. The
point of running it on the desktop first is that the constants below — resize policy, thresholds,
unclip ratio, batch width — are cheaper to sweep here than on a tablet.
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass

import numpy as np
import onnxruntime as ort
from PIL import Image

import dbpost

MEAN: np.ndarray = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD: np.ndarray = np.array([0.229, 0.224, 0.225], dtype=np.float32)

REC_HEIGHT: int = 48
REC_MIN_WIDTH: int = 320
REC_MAX_WIDTH: int = 1600


@dataclass
class Line:
    text: str
    confidence: float
    quad: tuple[tuple[float, float], ...]

    def top(self) -> float:
        return min(point[1] for point in self.quad)

    def left(self) -> float:
        return min(point[0] for point in self.quad)

    def height(self) -> float:
        ys = [point[1] for point in self.quad]
        return max(ys) - min(ys)


@dataclass
class Timing:
    detect_ms: float = 0.0
    recognize_ms: float = 0.0
    post_ms: float = 0.0
    crops: int = 0
    batches: int = 0


def det_size(width: int, height: int, limit: int, limit_type: str) -> tuple[int, int]:
    """PaddleOCR's `DetResizeForTest`: scale to a side limit, then round each side to a multiple of 32."""
    if limit_type == "max":
        ratio: float = limit / max(width, height) if max(width, height) > limit else 1.0
    else:
        ratio = limit / min(width, height) if min(width, height) < limit else 1.0
    resized_w: int = max(int(round(width * ratio / 32)) * 32, 32)
    resized_h: int = max(int(round(height * ratio / 32)) * 32, 32)
    return resized_w, resized_h


def det_tensor(image: Image.Image, limit: int, limit_type: str) -> tuple[np.ndarray, int, int]:
    width, height = image.size
    target_w, target_h = det_size(width, height, limit, limit_type)
    resized: Image.Image = image.convert("RGB").resize((target_w, target_h), Image.BILINEAR)
    pixels: np.ndarray = np.asarray(resized, dtype=np.float32) / 255.0
    pixels = (pixels - MEAN) / STD
    return pixels.transpose(2, 0, 1)[None, ...], target_w, target_h


def crop(image: Image.Image, quad: tuple[tuple[float, float], ...]) -> Image.Image:
    """Warp one quad to a horizontal strip 48 pixels tall."""
    (ax, ay), (bx, by), (cx, cy), (dx, dy) = quad
    width: float = max(math.hypot(bx - ax, by - ay), math.hypot(cx - dx, cy - dy))
    height: float = max(math.hypot(dx - ax, dy - ay), math.hypot(cx - bx, cy - by))
    if width < 2 or height < 2:
        return Image.new("RGB", (8, REC_HEIGHT), (255, 255, 255))
    target_w: int = max(4, int(round(REC_HEIGHT * width / height)))
    # PIL's QUAD source order is upper-left, lower-left, lower-right, upper-right.
    warped: Image.Image = image.convert("RGB").transform(
        (target_w, REC_HEIGHT),
        Image.QUAD,
        (ax, ay, dx, dy, cx, cy, bx, by),
        Image.BILINEAR,
    )
    if height / max(width, 1e-6) >= 1.5:
        return warped.rotate(90, expand=True)
    return warped


def rec_tensor(crops: list[Image.Image]) -> np.ndarray:
    """One padded batch. Every crop is 48 tall already, so only width has to agree."""
    width: int = min(REC_MAX_WIDTH, max(REC_MIN_WIDTH, max(item.size[0] for item in crops)))
    batch: np.ndarray = np.zeros((len(crops), 3, REC_HEIGHT, width), dtype=np.float32)
    for index, item in enumerate(crops):
        usable: int = min(item.size[0], width)
        pixels: np.ndarray = np.asarray(item.convert("RGB"), dtype=np.float32)[:, :usable, :] / 255.0
        pixels = (pixels - 0.5) / 0.5
        batch[index, :, :, :usable] = pixels.transpose(2, 0, 1)
    return batch


def decode_ctc(logits: np.ndarray, characters: list[str]) -> tuple[str, float]:
    best: np.ndarray = logits.argmax(axis=1)
    scores: np.ndarray = logits.max(axis=1)
    out: list[str] = []
    total: float = 0.0
    count: int = 0
    previous: int = 0
    for index, value in enumerate(best):
        value = int(value)
        if value != 0 and value != previous and value < len(characters):
            out.append(characters[value])
            total += float(scores[index])
            count += 1
        previous = value
    return "".join(out), (total / count if count else 0.0)


class Pipeline:
    def __init__(self, det_path: str, rec_path: str, dict_path: str, threads: int = 4) -> None:
        options: ort.SessionOptions = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.det: ort.InferenceSession = ort.InferenceSession(det_path, options)
        self.rec: ort.InferenceSession = ort.InferenceSession(rec_path, options)
        with open(dict_path, encoding="utf-8") as handle:
            entries: list[str] = [line.rstrip("\n") for line in handle]
        self.characters: list[str] = [""] + entries + [" "]

    def detect(
        self,
        image: Image.Image,
        limit: int = 960,
        limit_type: str = "max",
        thresh: float = 0.3,
        box_thresh: float = 0.6,
        unclip_ratio: float = 1.5,
        timing: Timing | None = None,
    ) -> list[tuple[tuple[float, float], ...]]:
        tensor, target_w, target_h = det_tensor(image, limit, limit_type)
        started: float = time.perf_counter()
        prob: np.ndarray = self.det.run(None, {self.det.get_inputs()[0].name: tensor})[0][0, 0]
        if timing:
            timing.detect_ms += (time.perf_counter() - started) * 1000
        started = time.perf_counter()
        quads: list[dbpost.Quad] = dbpost.detect(
            prob, thresh=thresh, box_thresh=box_thresh, unclip_ratio=unclip_ratio
        )
        if timing:
            timing.post_ms += (time.perf_counter() - started) * 1000
        width, height = image.size
        scale_x: float = width / target_w
        scale_y: float = height / target_h
        return [
            tuple((x * scale_x, y * scale_y) for x, y in quad.points) for quad in quads
        ]

    def recognize_batch(
        self, crops: list[Image.Image], batch_size: int = 6, timing: Timing | None = None
    ) -> list[tuple[str, float]]:
        """Widest-first batching: crops are grouped by aspect so padding stays cheap."""
        order: list[int] = sorted(range(len(crops)), key=lambda index: crops[index].size[0])
        out: list[tuple[str, float]] = [("", 0.0)] * len(crops)
        for start in range(0, len(order), batch_size):
            chunk: list[int] = order[start : start + batch_size]
            tensor: np.ndarray = rec_tensor([crops[index] for index in chunk])
            began: float = time.perf_counter()
            logits: np.ndarray = self.rec.run(None, {self.rec.get_inputs()[0].name: tensor})[0]
            if timing:
                timing.recognize_ms += (time.perf_counter() - began) * 1000
                timing.batches += 1
            for position, index in enumerate(chunk):
                out[index] = decode_ctc(logits[position], self.characters)
        return out

    def read(
        self,
        image: Image.Image,
        min_confidence: float = 0.5,
        timing: Timing | None = None,
        **detect_kwargs: float | str,
    ) -> list[Line]:
        quads = self.detect(image, timing=timing, **detect_kwargs)  # type: ignore[arg-type]
        if not quads:
            return []
        crops: list[Image.Image] = [crop(image, quad) for quad in quads]
        if timing:
            timing.crops += len(crops)
        results: list[tuple[str, float]] = self.recognize_batch(crops, timing=timing)
        lines: list[Line] = [
            Line(text.strip(), confidence, quad)
            for (text, confidence), quad in zip(results, quads)
            if searchable(text.strip()) and confidence >= min_confidence
        ]
        return sort_reading_order(lines)


def searchable(text: str) -> bool:
    """Whether a reading is worth indexing at all.

    A bullet glyph, a rule or a stray tick is detected as a text region and recognised as one or two
    marks. It is a true reading and it is useless to search, so it is dropped here rather than
    stored: on the slide sample, three of seven "lines" were the bullet ellipses.
    """
    return any(character.isalnum() for character in text)


def sort_reading_order(lines: list[Line]) -> list[Line]:
    """Top to bottom, left to right, with boxes on the same visual row kept together."""
    if not lines:
        return []
    remaining: list[Line] = sorted(lines, key=lambda line: (line.top(), line.left()))
    rows: list[list[Line]] = []
    for line in remaining:
        placed: bool = False
        for row in rows:
            reference: Line = row[0]
            overlap: float = min(reference.height(), line.height())
            if abs(line.top() - reference.top()) <= 0.5 * overlap:
                row.append(line)
                placed = True
                break
        if not placed:
            rows.append([line])
    out: list[Line] = []
    for row in rows:
        out.extend(sorted(row, key=lambda line: line.left()))
    return out
