"""Does batching the recognizer pay, and how many threads should it get?

The answer decides two constants in the app: `REC_BATCH` and the session's intra-op thread count.
Desktop numbers do not transfer to a tablet, but the *shape* of the curve does — whether batching
helps at all, and whether threads past four still buy anything.
"""

from __future__ import annotations

import argparse
import json
import os
import time

import numpy as np
import onnxruntime as ort
from PIL import Image

import pipeline
import samples


def crops_for(engine: pipeline.Pipeline, corpus: list[samples.Sample]) -> list[Image.Image]:
    out: list[Image.Image] = []
    for sample in corpus:
        for quad in engine.detect(sample.image):
            out.append(pipeline.crop(sample.image, quad))
    return out


def time_batches(session: ort.InferenceSession, crops: list[Image.Image], size: int) -> float:
    name: str = session.get_inputs()[0].name
    order: list[int] = sorted(range(len(crops)), key=lambda index: crops[index].size[0])
    began: float = time.perf_counter()
    for start in range(0, len(order), size):
        chunk = [crops[index] for index in order[start : start + size]]
        session.run(None, {name: pipeline.rec_tensor(chunk)})
    return (time.perf_counter() - began) * 1000


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--det", required=True)
    parser.add_argument("--rec", required=True)
    parser.add_argument("--dict", required=True)
    parser.add_argument("--out", default="results")
    args = parser.parse_args()

    engine = pipeline.Pipeline(args.det, args.rec, args.dict)
    crops = crops_for(engine, samples.corpus())
    print(f"{len(crops)} crops, widths {min(c.size[0] for c in crops)}..{max(c.size[0] for c in crops)}")

    report: dict[str, dict[str, float]] = {}
    for threads in (1, 2, 4, 8):
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        session = ort.InferenceSession(args.rec, options)
        session.run(None, {session.get_inputs()[0].name: pipeline.rec_tensor(crops[:2])})
        row: dict[str, float] = {}
        for size in (1, 2, 4, 6, 8, 16):
            runs = [time_batches(session, crops, size) for _ in range(3)]
            row[f"batch{size}"] = round(min(runs), 1)
        report[f"threads{threads}"] = row
        print(f"threads={threads}: " + "  ".join(f"{k}={v:.0f}ms" for k, v in row.items()))

    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, "bench_batching.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
