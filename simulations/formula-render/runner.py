"""Batch a list of RenderConfigs across the three equations and score every result."""

from __future__ import annotations

import json
import math
import time
from dataclasses import asdict, replace
from typing import Sequence

import numpy as np

import inksim
from inksim import GROUND_TRUTH, FormulaModel, RenderConfig

MODEL_PATH: str = "pp-formulanet-s.onnx"
TOKENIZER_PATH: str = "tokenizer.json"

_model: FormulaModel | None = None
_groups: list[list[inksim.Stroke]] | None = None


def model() -> FormulaModel:
    global _model
    if _model is None:
        _model = FormulaModel(MODEL_PATH, TOKENIZER_PATH)
    return _model


def groups() -> list[list[inksim.Stroke]]:
    global _groups
    if _groups is None:
        _groups = inksim.group_by_row(inksim.load_strokes())
    return _groups


def app_scale(strokes: Sequence[inksim.Stroke]) -> float:
    """`InkSelectionRenderer`: 1024 over the long edge, clamped to [2, 8]."""
    xs = [x for stroke in strokes for x, _ in stroke]
    ys = [y for stroke in strokes for _, y in stroke]
    longest = max(max(xs) - min(xs), max(ys) - min(ys), 1.0)
    return min(8.0, max(2.0, 1024.0 / longest))


def perturb(strokes: Sequence[inksim.Stroke], seed: int) -> list[inksim.Stroke]:
    """A slightly different hand: small rotation, scale and offset about the centroid.

    Three equations is far too small a set to rank forty configurations on — neighbouring settings
    were swinging half a point on one formula, which is the model sitting on a decision boundary,
    not a real preference. Scoring each config over the true ink *and* a few plausible variants of
    it measures whether a setting is actually better or merely lucky on this particular ink.
    """
    if seed == 0:
        return list(strokes)
    generator = np.random.default_rng(seed)
    angle = math.radians(generator.uniform(-1.5, 1.5))
    factor = generator.uniform(0.97, 1.03)
    shift_x, shift_y = generator.uniform(-0.6, 0.6, 2)
    xs = [x for stroke in strokes for x, _ in stroke]
    ys = [y for stroke in strokes for _, y in stroke]
    center_x, center_y = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    cos, sin = math.cos(angle) * factor, math.sin(angle) * factor
    return [
        [
            (
                center_x + (x - center_x) * cos - (y - center_y) * sin + shift_x,
                center_y + (x - center_x) * sin + (y - center_y) * cos + shift_y,
            )
            for x, y in stroke
        ]
        for stroke in strokes
    ]


def evaluate(
    configs: Sequence[RenderConfig],
    batch_size: int = 6,
    keep_svg: bool = False,
    verbose: bool = True,
    seeds: Sequence[int] = (0,),
) -> list[dict]:
    names = ["eq1", "eq2", "eq3"]
    jobs: list[tuple[RenderConfig, str, np.ndarray, str]] = []
    for config in configs:
        for name, strokes in zip(names, groups()):
            for seed in seeds:
                shaken = perturb(strokes, seed)
                resolved = config
                if config.pixels_per_unit <= 0:
                    resolved = replace(config, pixels_per_unit=app_scale(shaken))
                svg, tensor = inksim.build_image(shaken, resolved)
                jobs.append(
                    (config, f"{name}" if seed == 0 else f"{name}#{seed}", tensor,
                     svg if keep_svg and seed == 0 else "")
                )

    predictions: list[str] = []
    started = time.time()
    for start in range(0, len(jobs), batch_size):
        chunk = jobs[start : start + batch_size]
        batch = np.stack([tensor for _, _, tensor, _ in chunk])
        predictions.extend(model().run(batch))
        if verbose:
            done = min(start + batch_size, len(jobs))
            rate = (time.time() - started) / done
            print(
                f"  {done}/{len(jobs)} images  ({rate:.2f}s each, "
                f"~{rate * (len(jobs) - done) / 60:.1f} min left)",
                flush=True,
            )

    results: list[dict] = []
    for (config, name, _, svg), prediction in zip(jobs, predictions):
        results.append(
            {
                "label": config.label,
                "config": asdict(config),
                "equation": name,
                "prediction": prediction,
                "truth": GROUND_TRUTH[name.split("#")[0]],
                "score": inksim.score(prediction, GROUND_TRUTH[name.split("#")[0]]),
                "exact": inksim.canonical(prediction).replace(" ", "")
                == inksim.canonical(GROUND_TRUTH[name.split("#")[0]]).replace(" ", ""),
                "svg": svg,
            }
        )
    return results


def use_model(model_path: str) -> None:
    """Point the runner at a different graph. The tokenizer is shared by every FormulaNet size."""
    global MODEL_PATH, _model
    MODEL_PATH = model_path
    _model = None


def summarize(results: Sequence[dict]) -> list[dict]:
    by_label: dict[str, list[dict]] = {}
    for row in results:
        by_label.setdefault(row["label"], []).append(row)
    summary: list[dict] = []
    for label, rows in by_label.items():
        families: dict[str, list[float]] = {}
        for row in rows:
            families.setdefault(row["equation"].split("#")[0], []).append(row["score"])
        summary.append(
            {
                "label": label,
                "mean_score": sum(r["score"] for r in rows) / len(rows),
                "exact": sum(1 for r in rows if r["exact"]),
                "of": len(rows),
                "worst": round(min(r["score"] for r in rows), 3),
                "per_equation": {
                    name: round(sum(scores) / len(scores), 3) for name, scores in families.items()
                },
            }
        )
    return sorted(summary, key=lambda entry: -entry["mean_score"])


def report(results: Sequence[dict], path: str | None = None) -> None:
    for entry in summarize(results):
        print(
            f"{entry['mean_score']:.3f}  worst {entry['worst']:.2f}  "
            f"exact {entry['exact']}/{entry['of']}  "
            f"{entry['per_equation']}  {entry['label']}"
        )
    if path:
        with open(path, "w", encoding="utf-8") as handle:
            json.dump([{k: v for k, v in r.items() if k != "svg"} for r in results], handle, indent=1)
