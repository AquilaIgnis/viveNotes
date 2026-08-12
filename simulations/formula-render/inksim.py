"""Ink -> SVG -> raster -> PP-FormulaNet pipeline, with every stage a knob.

The point of the module is that one `RenderConfig` fully determines the bytes the model sees,
so a sweep is a list of configs and nothing else varies underneath it.
"""

from __future__ import annotations

import io
import math
import sqlite3
from dataclasses import dataclass, replace
from typing import Callable, Iterable, Sequence

import numpy as np

from inkdecode import decode_points

Point = tuple[float, float]
Stroke = list[Point]

PAGE_ID: str = "019fedda-2d27-7c7d-b796-86df1fa6aaeb"

GROUND_TRUTH: dict[str, str] = {
    "eq1": r"\sum_{n=1}^{\infty}\frac{10}{n(n-2)}",
    "eq2": r"\int_{0}^{4}\frac{1}{x^{6}}dx",
    "eq3": r"\int_{1}^{\infty}\frac{1}{x^{4}}dx",
}


# --------------------------------------------------------------------------------------
# Loading and grouping
# --------------------------------------------------------------------------------------

def load_strokes(database: str = "live.db", page_id: str = PAGE_ID) -> list[Stroke]:
    """Live strokes for a page, with Object-erase victims replayed out.

    An Object erase does not delete the row — the app replays `ink_erases` on load and drops every
    stroke *component* the mask touched (`PageStroke.eraseObjects`). None of page 3's strokes has
    ever been partially cut, so each is a single component and being a target means being gone.
    Read the table without this and erased first attempts come back: page 3 renders two integral
    signs and a scribbled-over exponent that are not on the screen.
    """
    connection = sqlite3.connect(database)
    rows = connection.execute(
        "select s.points from ink_strokes s "
        "where s.pageId = ? and s.deletedAt is null "
        "and s.id not in (select t.strokeId from ink_erase_targets t "
        "                 join ink_erases e on e.id = t.eraseId where e.pageId = ?) "
        "order by s.seq",
        (page_id, page_id),
    ).fetchall()
    connection.close()
    strokes: list[Stroke] = []
    for (blob,) in rows:
        runs = decode_points(blob)
        strokes.append(list(zip(runs[1], runs[2])))
    return strokes


def group_by_row(strokes: Sequence[Stroke], gap: float = 30.0) -> list[list[Stroke]]:
    """Split strokes into horizontal bands separated by a vertical gap of at least [gap]."""
    ordered = sorted(strokes, key=lambda s: min(y for _, y in s))
    groups: list[list[Stroke]] = []
    current: list[Stroke] = []
    current_bottom: float = -1e9
    for stroke in ordered:
        top = min(y for _, y in stroke)
        bottom = max(y for _, y in stroke)
        if current and top - current_bottom > gap:
            groups.append(current)
            current = []
            current_bottom = -1e9
        current.append(stroke)
        current_bottom = max(current_bottom, bottom)
    if current:
        groups.append(current)
    return groups


# --------------------------------------------------------------------------------------
# Geometry stages
# --------------------------------------------------------------------------------------

def resample(stroke: Stroke, spacing: float) -> Stroke:
    """Uniform arc-length resampling. Keeps endpoints; a degenerate stroke passes through."""
    if spacing <= 0 or len(stroke) < 2:
        return stroke
    out: Stroke = [stroke[0]]
    carry: float = 0.0
    for (x0, y0), (x1, y1) in zip(stroke, stroke[1:]):
        segment = math.hypot(x1 - x0, y1 - y0)
        if segment <= 0:
            continue
        position = spacing - carry
        while position <= segment:
            ratio = position / segment
            out.append((x0 + (x1 - x0) * ratio, y0 + (y1 - y0) * ratio))
            position += spacing
        carry = (carry + segment) % spacing
    if out[-1] != stroke[-1]:
        out.append(stroke[-1])
    return out


def moving_average(stroke: Stroke, window: int) -> Stroke:
    """Centred box filter over the samples, endpoints pinned."""
    if window <= 1 or len(stroke) < 3:
        return stroke
    half = window // 2
    xs = np.array([p[0] for p in stroke])
    ys = np.array([p[1] for p in stroke])
    padded_x = np.pad(xs, half, mode="edge")
    padded_y = np.pad(ys, half, mode="edge")
    kernel = np.ones(half * 2 + 1) / (half * 2 + 1)
    smooth_x = np.convolve(padded_x, kernel, mode="valid")
    smooth_y = np.convolve(padded_y, kernel, mode="valid")
    smooth_x[0], smooth_y[0] = xs[0], ys[0]
    smooth_x[-1], smooth_y[-1] = xs[-1], ys[-1]
    return list(zip(smooth_x.tolist(), smooth_y.tolist()))


def chaikin(stroke: Stroke, iterations: int) -> Stroke:
    """Corner cutting. Each pass replaces every segment with its 1/4 and 3/4 points."""
    result = stroke
    for _ in range(max(0, iterations)):
        if len(result) < 3:
            break
        cut: Stroke = [result[0]]
        for (x0, y0), (x1, y1) in zip(result, result[1:]):
            cut.append((x0 * 0.75 + x1 * 0.25, y0 * 0.75 + y1 * 0.25))
            cut.append((x0 * 0.25 + x1 * 0.75, y0 * 0.25 + y1 * 0.75))
        cut.append(result[-1])
        result = cut
    return result


def rdp(stroke: Stroke, epsilon: float) -> Stroke:
    """Ramer-Douglas-Peucker simplification."""
    if epsilon <= 0 or len(stroke) < 3:
        return stroke
    start, end = stroke[0], stroke[-1]
    dx, dy = end[0] - start[0], end[1] - start[1]
    span = math.hypot(dx, dy)
    worst_index, worst = 0, -1.0
    for index in range(1, len(stroke) - 1):
        px, py = stroke[index]
        if span == 0:
            distance = math.hypot(px - start[0], py - start[1])
        else:
            distance = abs(dy * px - dx * py + end[0] * start[1] - end[1] * start[0]) / span
        if distance > worst:
            worst_index, worst = index, distance
    if worst <= epsilon:
        return [start, end]
    left = rdp(stroke[: worst_index + 1], epsilon)
    right = rdp(stroke[worst_index:], epsilon)
    return left[:-1] + right


def straightness(stroke: Stroke) -> float:
    """Max deviation from the chord, in page units. Zero for a perfect straight line."""
    if len(stroke) < 3:
        return 0.0
    start, end = stroke[0], stroke[-1]
    dx, dy = end[0] - start[0], end[1] - start[1]
    span = math.hypot(dx, dy)
    if span == 0:
        return max(math.hypot(x - start[0], y - start[1]) for x, y in stroke)
    return max(
        abs(dy * x - dx * y + end[0] * start[1] - end[1] * start[0]) / span for x, y in stroke
    )


def snap_line(stroke: Stroke, tolerance: float, axis_tolerance: float) -> Stroke:
    """A near-straight stroke becomes its chord; a near-axis chord becomes exactly axis-aligned."""
    if len(stroke) < 2 or straightness(stroke) > tolerance:
        return stroke
    (x0, y0), (x1, y1) = stroke[0], stroke[-1]
    if axis_tolerance > 0:
        if abs(y1 - y0) <= axis_tolerance:
            mid = (y0 + y1) / 2
            y0 = y1 = mid
        elif abs(x1 - x0) <= axis_tolerance:
            mid = (x0 + x1) / 2
            x0 = x1 = mid
    return [(x0, y0), (x1, y1)]


def fit_arc(stroke: Stroke, tolerance: float) -> Stroke | None:
    """Least-squares circle through the samples; returns a resampled arc when it fits."""
    if len(stroke) < 5:
        return None
    xs = np.array([p[0] for p in stroke])
    ys = np.array([p[1] for p in stroke])
    matrix = np.column_stack([xs, ys, np.ones(len(xs))])
    target = xs**2 + ys**2
    try:
        solution, *_ = np.linalg.lstsq(matrix, target, rcond=None)
    except np.linalg.LinAlgError:
        return None
    center_x = solution[0] / 2
    center_y = solution[1] / 2
    radius_squared = solution[2] + center_x**2 + center_y**2
    if radius_squared <= 0:
        return None
    radius = math.sqrt(radius_squared)
    residual = np.abs(np.hypot(xs - center_x, ys - center_y) - radius)
    if residual.max() > tolerance:
        return None
    angles = np.unwrap(np.arctan2(ys - center_y, xs - center_x))
    sampled = np.linspace(angles[0], angles[-1], max(8, len(stroke)))
    return [
        (center_x + radius * math.cos(a), center_y + radius * math.sin(a)) for a in sampled
    ]


# --------------------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class RenderConfig:
    """Everything that decides the pixels. One config, one deterministic image."""

    thickness: float = 2.0
    """SVG stroke-width, in page units (the app's `sizeDp`)."""

    resample_spacing: float = 0.0
    smooth_window: int = 0
    chaikin_passes: int = 0
    rdp_epsilon: float = 0.0

    line_tolerance: float = 0.0
    """Above zero: straighten strokes whose chord deviation is under this, in page units."""

    axis_tolerance: float = 0.0
    arc_tolerance: float = 0.0

    target_stem_px: float = 0.0
    """Above zero: overrides [thickness] so the stem lands this wide in the 384-square.

    The knob that actually matters, and the one the app does not have. `preprocessFormula` scales
    every crop to fit 384, so a stroke width fixed in page units becomes a *different* stem width
    for every equation — a wide one is squeezed thin, a small one comes out fat. Solving for the
    final width instead makes thickness mean the same thing on any formula:

        t * 384 / (longest + t) = target   ->   t = target * longest / (384 - target)
    """

    squeeze_gap: float = 0.0
    """Above zero: horizontal whitespace between symbol clusters is capped at this many units.

    Handwriting on a big canvas leaves gaps a typesetter never would — page 3 has 40 units of
    nothing between the integral sign and its integrand. After the fit-to-384 those gaps eat the
    frame, and the symbols that carry the meaning are left small and far apart.
    """

    normalize_height: float = 0.0
    """Above zero: scale the group so its bounding box is this tall, in page units."""

    padding: float = 12.0
    """Quiet margin around the ink, in page units — the app uses 12."""

    pixels_per_unit: float = 8.0
    linecap: str = "round"
    linejoin: str = "round"
    pad_value: float = 255.0
    """Grey level the 384-square is padded with. The app uses 0 (black); print is 255."""

    keep_aspect: bool = True
    label: str = ""


def apply_geometry(strokes: Sequence[Stroke], config: RenderConfig) -> list[Stroke]:
    processed: list[Stroke] = []
    for stroke in strokes:
        current = list(stroke)
        if config.resample_spacing > 0:
            current = resample(current, config.resample_spacing)
        if config.smooth_window > 1:
            current = moving_average(current, config.smooth_window)
        if config.chaikin_passes > 0:
            current = chaikin(current, config.chaikin_passes)
        if config.arc_tolerance > 0:
            arc = fit_arc(current, config.arc_tolerance)
            if arc is not None:
                current = arc
        if config.line_tolerance > 0:
            current = snap_line(current, config.line_tolerance, config.axis_tolerance)
        if config.rdp_epsilon > 0:
            current = rdp(current, config.rdp_epsilon)
        processed.append(current)
    return processed


def squeeze_horizontal_gaps(strokes: Sequence[Stroke], limit: float) -> list[Stroke]:
    """Close horizontal whitespace wider than [limit], moving whole strokes left.

    Clusters are found by projecting every stroke onto the x axis and merging overlaps, so a
    fraction bar that spans its numerator and denominator keeps them together and nothing is ever
    torn apart. Only the space *between* clusters is compressed; inside one, nothing moves.
    """
    if limit <= 0 or not strokes:
        return list(strokes)
    spans = [(min(x for x, _ in s), max(x for x, _ in s), i) for i, s in enumerate(strokes)]
    spans.sort()

    clusters: list[tuple[float, float, list[int]]] = []
    for left, right, index in spans:
        if clusters and left <= clusters[-1][1]:
            start, end, members = clusters[-1]
            clusters[-1] = (start, max(end, right), members + [index])
        else:
            clusters.append((left, right, [index]))

    shifts: dict[int, float] = {}
    accumulated: float = 0.0
    for previous, current in zip(clusters, clusters[1:]):
        gap = current[0] - previous[1]
        if gap > limit:
            accumulated += gap - limit
        for index in current[2]:
            shifts[index] = accumulated

    return [
        [(x - shifts.get(index, 0.0), y) for x, y in stroke]
        for index, stroke in enumerate(strokes)
    ]


def resolve_thickness(strokes: Sequence[Stroke], config: RenderConfig) -> RenderConfig:
    """Turn [RenderConfig.target_stem_px] into a page-unit stroke width for this group."""
    if config.target_stem_px <= 0:
        return config
    xs = [x for stroke in strokes for x, _ in stroke]
    ys = [y for stroke in strokes for _, y in stroke]
    longest = max(max(xs) - min(xs), max(ys) - min(ys), 1e-6)
    target = min(config.target_stem_px, FORMULA_SIZE - 1.0)
    return replace(config, thickness=target * longest / (FORMULA_SIZE - target))


def normalize(strokes: Sequence[Stroke], config: RenderConfig) -> list[Stroke]:
    if config.normalize_height <= 0:
        return list(strokes)
    ys = [y for stroke in strokes for _, y in stroke]
    height = max(ys) - min(ys)
    if height <= 0:
        return list(strokes)
    factor = config.normalize_height / height
    return [[(x * factor, y * factor) for x, y in stroke] for stroke in strokes]


# --------------------------------------------------------------------------------------
# SVG and raster
# --------------------------------------------------------------------------------------

def to_svg(strokes: Sequence[Stroke], config: RenderConfig) -> str:
    if not strokes:
        return '<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1"></svg>'
    xs = [x for stroke in strokes for x, _ in stroke]
    ys = [y for stroke in strokes for _, y in stroke]
    margin = config.padding + config.thickness / 2
    left, top = min(xs) - margin, min(ys) - margin
    width = (max(xs) - min(xs)) + margin * 2
    height = (max(ys) - min(ys)) + margin * 2
    scale = config.pixels_per_unit

    paths: list[str] = []
    for stroke in strokes:
        if len(stroke) == 1:
            x, y = stroke[0]
            paths.append(
                f'<circle cx="{x - left:.3f}" cy="{y - top:.3f}" '
                f'r="{config.thickness / 2:.3f}" fill="black"/>'
            )
            continue
        commands = [f"M {stroke[0][0] - left:.3f} {stroke[0][1] - top:.3f}"]
        commands.extend(f"L {x - left:.3f} {y - top:.3f}" for x, y in stroke[1:])
        paths.append(f'<path d="{" ".join(commands)}"/>')

    body = "\n  ".join(paths)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width * scale:.0f}" '
        f'height="{height * scale:.0f}" viewBox="0 0 {width:.3f} {height:.3f}">\n'
        f'  <rect width="100%" height="100%" fill="white"/>\n'
        f'  <g fill="none" stroke="black" stroke-width="{config.thickness}" '
        f'stroke-linecap="{config.linecap}" stroke-linejoin="{config.linejoin}">\n'
        f"  {body}\n  </g>\n</svg>\n"
    )


def rasterize(svg: str) -> np.ndarray:
    """SVG -> greyscale array, white background. cairosvg is the renderer the SVG is judged by."""
    import cairosvg
    from PIL import Image

    png = cairosvg.svg2png(bytestring=svg.encode("utf-8"), background_color="white")
    image = Image.open(io.BytesIO(png)).convert("L")
    return np.asarray(image, dtype=np.float32)


# --------------------------------------------------------------------------------------
# Model preprocessing — the app's `preprocessFormula`, with the pad value exposed
# --------------------------------------------------------------------------------------

FORMULA_SIZE: int = 384
MEAN: float = 0.7931
STD: float = 0.1738


def preprocess_formula(image: np.ndarray, pad_value: float, keep_aspect: bool = True) -> np.ndarray:
    from PIL import Image

    low, high = float(image.min()), float(image.max())
    if high > low:
        stretched = (image - low) / (high - low) * 255.0
        mask = stretched < 200
    else:
        mask = np.zeros_like(image, dtype=bool)
    if mask.any():
        rows = np.where(mask.any(axis=1))[0]
        columns = np.where(mask.any(axis=0))[0]
        cropped = image[rows[0] : rows[-1] + 1, columns[0] : columns[-1] + 1]
    else:
        cropped = image

    height, width = cropped.shape
    if keep_aspect:
        scale = FORMULA_SIZE / max(height, width)
        target_w = max(1, min(FORMULA_SIZE, int(width * scale)))
        target_h = max(1, min(FORMULA_SIZE, int(height * scale)))
    else:
        target_w = target_h = FORMULA_SIZE

    resized = np.asarray(
        Image.fromarray(cropped.astype(np.uint8)).resize(
            (target_w, target_h), Image.BILINEAR
        ),
        dtype=np.float32,
    )

    canvas = np.full((FORMULA_SIZE, FORMULA_SIZE), pad_value, dtype=np.float32)
    y_offset = (FORMULA_SIZE - target_h) // 2
    x_offset = (FORMULA_SIZE - target_w) // 2
    canvas[y_offset : y_offset + target_h, x_offset : x_offset + target_w] = resized
    return ((canvas / 255.0) - MEAN) / STD


# --------------------------------------------------------------------------------------
# Model
# --------------------------------------------------------------------------------------

class FormulaModel:
    """PP-FormulaNet ONNX plus the byte-level BPE vocabulary needed to read its ids."""

    def __init__(self, model_path: str, tokenizer_path: str) -> None:
        import json

        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = 8
        self.session = ort.InferenceSession(
            model_path, options, providers=["CPUExecutionProvider"]
        )
        self.input_name = self.session.get_inputs()[0].name
        with open(tokenizer_path, encoding="utf-8") as handle:
            tokenizer = json.load(handle)
        vocabulary: dict[str, int] = tokenizer["model"]["vocab"]
        self.tokens: dict[int, str] = {index: text for text, index in vocabulary.items()}
        self.special: set[int] = {
            entry["id"] for entry in tokenizer.get("added_tokens", []) if entry.get("special")
        }
        self.byte_decoder: dict[str, int] = _byte_decoder()

    def run(self, batch: np.ndarray) -> list[str]:
        tensor = batch.reshape(-1, 1, FORMULA_SIZE, FORMULA_SIZE).astype(np.float32)
        ids = self.session.run(None, {self.input_name: tensor})[0]
        return [self.decode(row) for row in ids]

    def decode(self, ids: Iterable[int]) -> str:
        collected: list[str] = []
        for raw in ids:
            value = int(raw)
            if value == 2:  # eos
                break
            if value in (0, 1) or value in self.special:
                continue
            token = self.tokens.get(value)
            if token is None:
                continue
            collected.append(token)
        text = "".join(collected)
        try:
            return bytes(self.byte_decoder[c] for c in text).decode("utf-8", "replace").strip()
        except KeyError:
            return text.strip()


def _byte_decoder() -> dict[str, int]:
    visible = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    mapping = {chr(b): b for b in visible}
    extra = 0
    for byte in range(256):
        if byte not in visible:
            mapping[chr(256 + extra)] = byte
            extra += 1
    return mapping


# --------------------------------------------------------------------------------------
# Scoring
# --------------------------------------------------------------------------------------

SPACING_COMMANDS: tuple[str, ...] = (
    r"\limits",
    r"\nolimits",
    r"\,",
    r"\;",
    r"\:",
    r"\!",
    r"\ ",
    r"\quad",
    r"\qquad",
    r"\left",
    r"\right",
    r"\displaystyle",
)


def canonical(text: str) -> str:
    """Strip what the app's parser strips before it reads a formula — `docs/calculator.md`.

    `\\limits` is the big one: PP-FormulaNet writes `\\sum\\limits_{...}` where the truth here says
    `\\sum_{...}`, and SymPy is handed neither — the math engine removes it before parsing. Scoring
    it as an error would rank configurations by a token that cannot reach the answer.
    """
    result = text
    for command in SPACING_COMMANDS:
        result = result.replace(command, "")
    return result


def tokenize_latex(text: str) -> list[str]:
    tokens: list[str] = []
    index = 0
    while index < len(text):
        char = text[index]
        if char.isspace():
            index += 1
            continue
        if char == "\\":
            end = index + 1
            while end < len(text) and text[end].isalpha():
                end += 1
            tokens.append(text[index : max(end, index + 2)])
            index = max(end, index + 2)
            continue
        tokens.append(char)
        index += 1
    return tokens


def edit_distance(left: Sequence[str], right: Sequence[str]) -> int:
    previous = list(range(len(right) + 1))
    for i, a in enumerate(left, start=1):
        current = [i]
        for j, b in enumerate(right, start=1):
            current.append(
                min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a != b))
            )
        previous = current
    return previous[-1]


def score(prediction: str, truth: str) -> float:
    """1.0 is an exact token match; 0.0 is nothing in common."""
    predicted = tokenize_latex(canonical(prediction))
    expected = tokenize_latex(canonical(truth))
    if not expected:
        return 0.0
    distance = edit_distance(predicted, expected)
    return max(0.0, 1.0 - distance / max(len(expected), len(predicted), 1))


# --------------------------------------------------------------------------------------
# End to end
# --------------------------------------------------------------------------------------

def build_image(strokes: Sequence[Stroke], config: RenderConfig) -> tuple[str, np.ndarray]:
    processed = apply_geometry(strokes, config)
    processed = squeeze_horizontal_gaps(processed, config.squeeze_gap)
    processed = normalize(processed, config)
    config = resolve_thickness(processed, config)
    svg = to_svg(processed, config)
    raster = rasterize(svg)
    tensor = preprocess_formula(raster, config.pad_value, config.keep_aspect)
    return svg, tensor
