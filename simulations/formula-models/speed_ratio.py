"""Times UniMERNet's encoder plus a fixed number of decoder steps, as the app's loop runs them."""

import argparse
import statistics
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

PAD = (0.0 - 0.7931) / 0.1738


def open_session(path: Path, threads: int) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.intra_op_num_threads = threads
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def empty_cache(decoder: ort.InferenceSession, name: str) -> np.ndarray:
    for node in decoder.get_inputs():
        if node.name == name:
            shape = [dimension if isinstance(dimension, int) else 0 for dimension in node.shape]
            return np.zeros(shape, dtype=np.float32)
    raise KeyError(name)


def run_once(encoder: ort.InferenceSession, decoder: ort.InferenceSession, steps: int) -> float:
    pixels = np.full((1, 1, 192, 672), PAD, dtype=np.float32)
    start = time.perf_counter()
    memory = encoder.run(None, {"pixels": pixels})[0]
    keys = empty_cache(decoder, "past_keys")
    values = empty_cache(decoder, "past_values")
    token = np.zeros((1, 1), dtype=np.int64)
    for _ in range(steps):
        next_token, keys, values = decoder.run(
            None, {"token": token, "memory": memory, "past_keys": keys, "past_values": values}
        )
        token = np.asarray(next_token, dtype=np.int64).reshape(1, 1)
    return (time.perf_counter() - start) * 1000.0


def measure(encoder_path: Path, decoder_path: Path, threads: int, steps: int, repeats: int) -> float:
    encoder = open_session(encoder_path, threads)
    decoder = open_session(decoder_path, threads)
    run_once(encoder, decoder, steps)
    return statistics.median(run_once(encoder, decoder, steps) for _ in range(repeats))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tiny", type=Path, required=True)
    parser.add_argument("--small", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--repeats", type=int, default=7)
    parser.add_argument("--steps", type=int, nargs="+", default=[0, 24])
    arguments = parser.parse_args()
    for steps in arguments.steps:
        tiny = measure(
            arguments.tiny / "unimernet-tiny-encoder-int8.onnx",
            arguments.tiny / "unimernet-tiny-decoder-int8.onnx",
            arguments.threads, steps, arguments.repeats,
        )
        small = measure(
            arguments.small / "unimernet-small-encoder-int8.onnx",
            arguments.small / "unimernet-small-decoder-int8.onnx",
            arguments.threads, steps, arguments.repeats,
        )
        print(f"steps={steps:3d} tiny={tiny:8.1f} ms small={small:8.1f} ms ratio={small / tiny:.2f}")


if __name__ == "__main__":
    main()
