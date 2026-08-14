"""Sweep the detection knobs over the synthetic corpus and report character error rate."""

from __future__ import annotations

import argparse
import json
import os
import time
from dataclasses import asdict, dataclass

import pipeline
import samples


@dataclass
class Score:
    sample: str
    lines_found: int
    lines_expected: int
    cer: float
    exact_lines: int
    detect_ms: float
    recognize_ms: float
    post_ms: float
    text: str


def edit_distance(left: str, right: str) -> int:
    previous: list[int] = list(range(len(right) + 1))
    for i, a in enumerate(left, start=1):
        current: list[int] = [i]
        for j, b in enumerate(right, start=1):
            current.append(
                min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a != b))
            )
        previous = current
    return previous[-1]


def character_error_rate(expected: str, actual: str) -> float:
    if not expected:
        return 0.0 if not actual else 1.0
    return edit_distance(expected, actual) / len(expected)


def score_sample(engine: pipeline.Pipeline, sample: samples.Sample, **kwargs: float | str) -> Score:
    timing = pipeline.Timing()
    lines = engine.read(sample.image, timing=timing, **kwargs)  # type: ignore[arg-type]
    text: str = "\n".join(line.text for line in lines)
    expected_lines: list[str] = sample.lines
    exact: int = sum(1 for line in lines if line.text in expected_lines)
    return Score(
        sample=sample.name,
        lines_found=len(lines),
        lines_expected=len(expected_lines),
        cer=round(character_error_rate(sample.text, text), 4),
        exact_lines=exact,
        detect_ms=round(timing.detect_ms, 1),
        recognize_ms=round(timing.recognize_ms, 1),
        post_ms=round(timing.post_ms, 1),
        text=text,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--det", required=True)
    parser.add_argument("--rec", required=True)
    parser.add_argument("--dict", required=True)
    parser.add_argument("--out", default="results")
    parser.add_argument("--sweep", default="baseline")
    args = parser.parse_args()

    engine = pipeline.Pipeline(args.det, args.rec, args.dict)
    corpus = samples.corpus()
    os.makedirs(args.out, exist_ok=True)

    configurations: dict[str, dict[str, float | str]]
    if args.sweep == "baseline":
        configurations = {"baseline": {}}
    elif args.sweep == "resize":
        configurations = {
            "max-640": {"limit": 640, "limit_type": "max"},
            "max-960": {"limit": 960, "limit_type": "max"},
            "max-1280": {"limit": 1280, "limit_type": "max"},
            "min-736": {"limit": 736, "limit_type": "min"},
        }
    elif args.sweep == "thresholds":
        configurations = {
            "box-0.5": {"box_thresh": 0.5},
            "box-0.6": {"box_thresh": 0.6},
            "box-0.7": {"box_thresh": 0.7},
            "thresh-0.2": {"thresh": 0.2},
            "thresh-0.4": {"thresh": 0.4},
        }
    elif args.sweep == "unclip":
        configurations = {
            "unclip-1.2": {"unclip_ratio": 1.2},
            "unclip-1.5": {"unclip_ratio": 1.5},
            "unclip-1.8": {"unclip_ratio": 1.8},
            "unclip-2.2": {"unclip_ratio": 2.2},
        }
    else:
        raise SystemExit(f"unknown sweep {args.sweep}")

    report: dict[str, list[dict[str, object]]] = {}
    for name, options in configurations.items():
        began: float = time.perf_counter()
        scores: list[Score] = [score_sample(engine, sample, **options) for sample in corpus]
        elapsed: float = (time.perf_counter() - began) * 1000
        mean_cer: float = sum(score.cer for score in scores) / len(scores)
        print(f"== {name}: mean CER {mean_cer:.3f} over {len(scores)} samples, {elapsed:.0f} ms")
        for score in scores:
            print(
                f"   {score.sample:22s} cer={score.cer:.3f} "
                f"lines={score.lines_found}/{score.lines_expected} "
                f"det={score.detect_ms:.0f}ms rec={score.recognize_ms:.0f}ms "
                f"post={score.post_ms:.0f}ms"
            )
            for line in score.text.splitlines():
                print(f"        | {line}")
        report[name] = [asdict(score) for score in scores]

    path: str = os.path.join(args.out, f"sweep_{args.sweep}.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
