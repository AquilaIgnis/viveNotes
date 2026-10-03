"""Should background OCR run several inferences at once, and how many threads should each get?

`bench.py` settled the batch question: one crop per `Run`. It ran those crops one after another on
one four-thread session, which is the shape the app shipped. This asks the next question — whether
several `Run`s in flight at once, each on fewer intra-op threads, read a notebook's pictures faster
than one wide `Run` at a time — and what that costs the single interactive line a lasso recognizes.

Every configuration shares **one session per model** between its lanes: ONNX Runtime's `Run` is
thread-safe, so extra lanes cost activations, not a second copy of the graph. Tensors are prepared
before the clock starts, so this times inference and nothing else.

Pin it to a tablet's core count — `taskset -c 0-7` — or the desktop's 24 cores flatter every row.
"""

from __future__ import annotations

import argparse
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import onnxruntime as ort

import pipeline
import samples

CONFIGS: tuple[tuple[int, int], ...] = (
    (1, 4),
    (1, 8),
    (2, 1),
    (2, 2),
    (2, 4),
    (3, 1),
    (3, 2),
    (4, 1),
    (4, 2),
    (6, 1),
    (8, 1),
)


def session(path: str, threads: int) -> ort.InferenceSession:
    options: ort.SessionOptions = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(path, options)


def workload(det_path: str, rec_path: str, dict_path: str) -> tuple[list[np.ndarray], list[np.ndarray]]:
    """Every picture's detection tensor and every detected line's recognition tensor, unbatched."""
    engine: pipeline.Pipeline = pipeline.Pipeline(det_path, rec_path, dict_path)
    det_inputs: list[np.ndarray] = []
    rec_inputs: list[np.ndarray] = []
    for sample in samples.corpus():
        tensor, _, _ = pipeline.det_tensor(sample.image, 960, "max")
        det_inputs.append(tensor)
        for quad in engine.detect(sample.image):
            rec_inputs.append(pipeline.rec_tensor([pipeline.crop(sample.image, quad)]))
    return det_inputs, rec_inputs


def run_all(
    det: ort.InferenceSession,
    rec: ort.InferenceSession,
    det_inputs: list[np.ndarray],
    rec_inputs: list[np.ndarray],
    lanes: int,
) -> float:
    det_name: str = det.get_inputs()[0].name
    rec_name: str = rec.get_inputs()[0].name

    def detect(tensor: np.ndarray) -> None:
        det.run(None, {det_name: tensor})

    def recognize(tensor: np.ndarray) -> None:
        rec.run(None, {rec_name: tensor})

    began: float = time.perf_counter()
    with ThreadPoolExecutor(max_workers=lanes) as pool:
        list(pool.map(detect, det_inputs))
        list(pool.map(recognize, rec_inputs))
    return (time.perf_counter() - began) * 1000


def single_line_ms(rec: ort.InferenceSession, tensor: np.ndarray) -> float:
    name: str = rec.get_inputs()[0].name
    timings: list[float] = []
    for _ in range(15):
        began: float = time.perf_counter()
        rec.run(None, {name: tensor})
        timings.append((time.perf_counter() - began) * 1000)
    return float(np.median(timings))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--det", required=True)
    parser.add_argument("--rec", required=True)
    parser.add_argument("--dict", required=True)
    parser.add_argument("--repeat", type=int, default=5, help="copies of the corpus per timed pass")
    parser.add_argument("--out", default="results")
    args = parser.parse_args()

    det_inputs, rec_inputs = workload(args.det, args.rec, args.dict)
    det_inputs = det_inputs * args.repeat
    rec_inputs = rec_inputs * args.repeat
    # A typical lasso: one line of a few words, a little wider than the recognizer's minimum.
    def distance_from_lasso_width(tensor: np.ndarray) -> int:
        return abs(tensor.shape[3] - 480)

    lasso: np.ndarray = min(rec_inputs, key=distance_from_lasso_width)
    cores: int = len(os.sched_getaffinity(0))
    print(f"{cores} cores, {len(det_inputs)} pictures, {len(rec_inputs)} lines")

    report: dict[str, object] = {"cores": cores, "pictures": len(det_inputs), "lines": len(rec_inputs)}
    rows: dict[str, dict[str, float]] = {}
    baseline: float | None = None
    for lanes, threads in CONFIGS:
        det: ort.InferenceSession = session(args.det, threads)
        rec: ort.InferenceSession = session(args.rec, threads)
        run_all(det, rec, det_inputs[:2], rec_inputs[:4], lanes)
        elapsed: float = min(run_all(det, rec, det_inputs, rec_inputs, lanes) for _ in range(3))
        if baseline is None:
            baseline = elapsed
        row: dict[str, float] = {
            "pass_ms": round(elapsed, 1),
            "speedup": round(baseline / elapsed, 2),
            "lasso_line_ms": round(single_line_ms(rec, lasso), 2),
        }
        rows[f"lanes{lanes}_threads{threads}"] = row
        print(
            f"lanes={lanes} threads={threads}: pass {row['pass_ms']:.0f} ms "
            f"(x{row['speedup']:.2f}), one lasso line {row['lasso_line_ms']:.1f} ms"
        )
    report["configs"] = rows

    os.makedirs(args.out, exist_ok=True)
    path: str = os.path.join(args.out, f"lanes_{cores}cores.json")
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2)
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
