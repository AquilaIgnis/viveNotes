"""Score formula recognizers on the bundled Recognition Test page.

The page is replayed (`replay.py`), split into its eight expressions by vertical gap, and each
expression is rendered by the formula study's own renderer at a few stem widths. Every model reads
every render; seeds 1+ are the study's small rotation/scale jitters of the same ink, so one lucky
raster cannot carry a model.
"""

from __future__ import annotations

import argparse
import json
import re
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol, Sequence

import numpy as np
from PIL import Image

from models import HERE, FormulaNetS, Pix2TextMfr, UniMerNetTiny, UniMerNetTinyOnnx, inksim
from replay import replay

import runner  # formula-render's, for `perturb`; on the path via models

# The ledger in the project notes. What was written by hand, never a model's output.
GROUND_TRUTH: tuple[str, ...] = (
    r"\sqrt { x ^ 2 + 1 }",
    r"x ^ 2 - 4 = 0",
    r"\sin ( x )",
    r"\int _ 0 ^ 1 x ^ 2 d x",
    r"\begin{bmatrix} 1 & 7 \\ 3 & 6 \end{bmatrix}",
    r"\sum \limits _ { k = 1 } ^ { \infty } \frac { 4 k + 3 } { 3 k - 5 }",
    r"\sum \limits _ { n = 1 } ^ { \infty } \frac { \cos ( n ) } { n ^ 2 }",
    r"\sum \limits _ { n = 1 } ^ { \infty } e ^ { - 7 n }",
)

FONT_WRAPPERS: tuple[str, ...] = (r"\mathrm", r"\mathit", r"\operatorname*", r"\operatorname")
FRACTION_ALIASES: dict[str, str] = {r"\dfrac": r"\frac", r"\tfrac": r"\frac"}
# `\left[ \begin{matrix} … \end{matrix} \right]` is a bmatrix; a row break before `\end` is nothing.
MATRIX_REWRITES: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\[\s*\\begin\s*\{\s*matrix\s*\}"), r"\\begin{bmatrix}"),
    (re.compile(r"\\end\s*\{\s*matrix\s*\}\s*\]"), r"\\end{bmatrix}"),
    (re.compile(r"\\cr\s*(\\end\s*\{)"), r"\1"),
)


class Model(Protocol):
    name: str

    def read(self, raster: np.ndarray) -> str: ...


@dataclass
class Reading:
    model: str
    stem: float
    seed: int
    expression: int
    prediction: str
    score: float
    exact: bool
    seconds: float


def normalized_tokens(text: str) -> list[str]:
    """Tokens after removing what cannot change the formula.

    On top of `inksim.canonical` (spacing, `\\limits`, `\\left`/`\\right`): font wrappers such as
    `\\mathrm{d}` become their content, `\\dfrac` is `\\frac`, and braces around a single token go,
    so `x^{2}` and `x ^ 2` agree; a bracketed `matrix` is a `bmatrix`. Applied to the truth and the
    prediction alike.
    """
    # `canonical` strips `\ ` as spacing, which would eat the second half of a `\\ ` row break.
    text = inksim.canonical(text.replace("\\\\", r" \cr "))
    for pattern, replacement in MATRIX_REWRITES:
        text = pattern.sub(replacement, text)
    tokens = inksim.tokenize_latex(text)
    tokens = [FRACTION_ALIASES.get(token, token) for token in tokens if token not in FONT_WRAPPERS]
    changed = True
    while changed:
        changed = False
        for index in range(len(tokens) - 2):
            if tokens[index] == "{" and tokens[index + 2] == "}" and tokens[index + 1] not in "{}":
                del tokens[index + 2]
                del tokens[index]
                changed = True
                break
    return tokens


def score(prediction: str, truth: str) -> tuple[float, bool]:
    predicted = normalized_tokens(prediction)
    expected = normalized_tokens(truth)
    distance = inksim.edit_distance(predicted, expected)
    similarity = max(0.0, 1.0 - distance / max(len(expected), len(predicted), 1))
    return similarity, predicted == expected


def expressions() -> list[list[inksim.Stroke]]:
    pieces = replay()
    strokes: list[inksim.Stroke] = [[(float(x), float(y)) for x, y in p.points] for p in pieces]
    groups = inksim.group_by_row(strokes, gap=30.0)
    if len(groups) != len(GROUND_TRUTH):
        raise SystemExit(f"expected {len(GROUND_TRUTH)} expressions, found {len(groups)}")
    return groups


def render(strokes: Sequence[inksim.Stroke], stem: float) -> np.ndarray:
    """The app's renderer settings: round caps, 12-unit quiet margin, stem fixed in the 384 frame."""
    config = inksim.RenderConfig(target_stem_px=stem, pixels_per_unit=8.0, pad_value=255.0)
    config = inksim.resolve_thickness(strokes, config)
    return inksim.rasterize(inksim.to_svg(strokes, config))


def build_models(args: argparse.Namespace) -> list[Model]:
    built: list[Model] = []
    for name in args.models:
        if name == FormulaNetS.name:
            built.append(FormulaNetS(args.formulanet, args.formulanet_tokenizer))
        elif name == Pix2TextMfr.name:
            built.append(Pix2TextMfr(args.pix2text))
        elif name == UniMerNetTiny.name:
            built.append(UniMerNetTiny(args.unimernet_config))
        elif name.startswith(UniMerNetTinyOnnx.name):
            suffix = name.removeprefix(UniMerNetTinyOnnx.name)
            built.append(UniMerNetTinyOnnx(args.unimernet_onnx, args.formulanet_tokenizer, suffix))
        else:
            raise SystemExit(f"unknown model {name}")
    return built


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--models", nargs="+", required=True)
    parser.add_argument("--formulanet")
    parser.add_argument("--formulanet-tokenizer")
    parser.add_argument("--pix2text")
    parser.add_argument("--unimernet-config")
    parser.add_argument("--unimernet-onnx", help="directory holding the export_unimernet.py graphs")
    parser.add_argument("--stems", nargs="+", type=float, default=[6.0, 10.0, 16.0])
    parser.add_argument("--seeds", type=int, default=11)
    args = parser.parse_args()

    groups = expressions()
    plates = HERE / "results" / "plates"
    plates.mkdir(parents=True, exist_ok=True)
    rasters: dict[tuple[float, int, int], np.ndarray] = {}
    for stem in args.stems:
        for seed in range(args.seeds):
            for index, group in enumerate(groups):
                raster = render(runner.perturb(group, seed), stem)
                rasters[(stem, seed, index)] = raster
                if seed == 0:
                    Image.fromarray(raster.astype(np.uint8)).save(
                        plates / f"expr{index + 1}-stem{stem:g}.png"
                    )

    for model in build_models(args):
        readings: list[Reading] = []
        for (stem, seed, index), raster in rasters.items():
            began = time.perf_counter()
            prediction = model.read(raster)
            elapsed = time.perf_counter() - began
            similarity, exact = score(prediction, GROUND_TRUTH[index])
            readings.append(
                Reading(model.name, stem, seed, index + 1, prediction, similarity, exact, elapsed)
            )
        output = HERE / "results" / f"{model.name}.json"
        output.write_text(json.dumps([asdict(r) for r in readings], indent=1), encoding="utf-8")
        for stem in args.stems:
            rows = [r for r in readings if r.stem == stem]
            mean = sum(r.score for r in rows) / len(rows)
            exact = sum(r.exact for r in rows)
            seconds = sorted(r.seconds for r in rows)[len(rows) // 2]
            print(
                f"{model.name:18s} stem {stem:4g}  token {mean:.3f}  exact {exact:3d}/{len(rows)}"
                f"  median {seconds * 1000:5.0f} ms",
                flush=True,
            )


if __name__ == "__main__":
    main()
