"""Int8 dynamic quantization of the exported UniMERNet-T graphs, MatMul and Gather weights only.

Convolutions stay float: dynamic quantization turns them into `ConvInteger`, which ONNX Runtime's
ARM kernels run slower than the float convolution it replaces. The weight that matters is in the
decoder anyway — two 50k×512 tables, the embedding and the output projection.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True, type=Path)
    args = parser.parse_args()
    for part in ("encoder", "decoder"):
        source = args.directory / f"unimernet-tiny-{part}.onnx"
        target = args.directory / f"unimernet-tiny-{part}-int8.onnx"
        quantize_dynamic(
            str(source),
            str(target),
            weight_type=QuantType.QInt8,
            op_types_to_quantize=["MatMul", "Gather"],
        )
        print(f"{target.name}: {source.stat().st_size:,} → {target.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
