"""One adapter per formula recognizer, each fed the way its own project feeds it.

Every adapter takes the same thing — a white-background greyscale raster of the ink, as
`inksim.rasterize` draws it — and does its own preprocessing from there, so a difference in the
scores is a difference in the model and not in who got the friendlier image.
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "formula-render"))
import inksim  # noqa: E402


class FormulaNetS:
    """The shipped model, through the app's own `preprocessFormula` (white pad, 384 square)."""

    name = "pp-formulanet-s"

    def __init__(self, model_path: str, tokenizer_path: str) -> None:
        self.model = inksim.FormulaModel(model_path, tokenizer_path)

    def read(self, raster: np.ndarray) -> str:
        tensor = inksim.preprocess_formula(raster, pad_value=255.0, keep_aspect=True)
        return self.model.run(tensor[None, ...])[0]


class Pix2TextMfr:
    """`breezedeus/pix2text-mfr-1.5`: TrOCRProcessor (bicubic to 384×384, mean/std 0.5), greedy.

    Greedy is what `Pix2Text.recognize` runs — its generation config sets no beams — and the
    published decoder has no KV cache, so each step re-runs the decoder over the whole prefix.
    """

    name = "pix2text-mfr-1.5"
    max_new_tokens = 300

    def __init__(self, directory: str) -> None:
        import json

        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = 8
        root = Path(directory)
        self.encoder = ort.InferenceSession(str(root / "encoder_model.onnx"), options)
        self.decoder = ort.InferenceSession(str(root / "decoder_model.onnx"), options)
        # Read by hand: the `tokenizers` that UniMERNet's pinned transformers allows predates this
        # file's format. Decoding a byte-level BPE needs only the vocabulary and the byte map.
        tokenizer = json.loads((root / "tokenizer.json").read_text(encoding="utf-8"))
        self.tokens: dict[int, str] = {
            index: text for text, index in tokenizer["model"]["vocab"].items()
        }
        self.special: set[int] = {
            entry["id"] for entry in tokenizer.get("added_tokens", []) if entry.get("special")
        }
        self.byte_decoder: dict[str, int] = inksim._byte_decoder()
        generation = json.loads((root / "generation_config.json").read_text(encoding="utf-8"))
        self.start = int(generation["decoder_start_token_id"])
        self.eos = int(generation["eos_token_id"])

    def read(self, raster: np.ndarray) -> str:
        image = Image.fromarray(raster.astype(np.uint8)).convert("RGB")
        resized = image.resize((384, 384), Image.Resampling.BICUBIC)
        pixels = (np.asarray(resized, dtype=np.float32) / 255.0 - 0.5) / 0.5
        tensor = pixels.transpose(2, 0, 1)[None, ...]
        hidden = self.encoder.run(None, {"pixel_values": tensor})[0]
        ids: list[int] = [self.start]
        for _ in range(self.max_new_tokens):
            logits = self.decoder.run(
                None,
                {
                    "input_ids": np.asarray([ids], dtype=np.int64),
                    "encoder_hidden_states": hidden,
                },
            )[0]
            token = int(logits[0, -1].argmax())
            if token == self.eos:
                break
            ids.append(token)
        text = "".join(
            self.tokens.get(token, "") for token in ids[1:] if token not in self.special
        )
        return bytes(self.byte_decoder[c] for c in text).decode("utf-8", "replace").strip()


class UniMerNetTiny:
    """`wanderkid/unimernet_tiny` through the `unimernet` package's own eval processor (192×672)."""

    name = "unimernet-tiny"

    def __init__(self, config_path: str) -> None:
        import argparse

        import torch
        import unimernet.tasks as tasks
        from unimernet.common.config import Config
        from unimernet.processors import load_processor

        torch.set_num_threads(8)
        cfg = Config(argparse.Namespace(cfg_path=config_path, options=None))
        task = tasks.setup_task(cfg)
        self.model = task.build_model(cfg).eval()
        self.processor = load_processor(
            "formula_image_eval", cfg.config.datasets.formula_rec_eval.vis_processor.eval
        )
        self.torch = torch

    def read(self, raster: np.ndarray) -> str:
        image = Image.fromarray(raster.astype(np.uint8)).convert("RGB")
        tensor = self.processor(image).unsqueeze(0)
        with self.torch.no_grad():
            output = self.model.generate({"image": tensor})
        return str(output["pred_str"][0]).strip()


UNIMERNET_HEIGHT: int = 192
UNIMERNET_WIDTH: int = 672
UNIMERNET_START: int = 0
UNIMERNET_END: int = 2
UNIMERNET_MAX_TOKENS: int = 512


def unimernet_tensor(raster: np.ndarray) -> np.ndarray:
    """The preprocessing the app runs, in numpy: what `preprocessUniMerNet` must reproduce.

    The package's own processor resizes twice — short side to 192, then `thumbnail` into 672×192 —
    with two different filters. This is the one resize that lands in the same box: margin crop as
    FormulaNet does it, one bilinear scale to fit 192×672, centred on **black**. Black because
    `ImageOps.expand` pads with 0 and that is what the model was trained on.
    """
    image = raster.astype(np.float32)
    low, high = float(image.min()), float(image.max())
    if high > low:
        mask = (image - low) / (high - low) * 255.0 < 200
        rows = np.flatnonzero(mask.any(axis=1))
        columns = np.flatnonzero(mask.any(axis=0))
        image = image[rows[0] : rows[-1] + 1, columns[0] : columns[-1] + 1]
    height, width = image.shape
    scale = min(UNIMERNET_HEIGHT / height, UNIMERNET_WIDTH / width)
    target_w = max(1, min(UNIMERNET_WIDTH, int(width * scale)))
    target_h = max(1, min(UNIMERNET_HEIGHT, int(height * scale)))
    resized = np.asarray(
        Image.fromarray(image.astype(np.uint8)).resize((target_w, target_h), Image.Resampling.BILINEAR),
        dtype=np.float32,
    )
    canvas = np.zeros((UNIMERNET_HEIGHT, UNIMERNET_WIDTH), dtype=np.float32)
    top = (UNIMERNET_HEIGHT - target_h) // 2
    left = (UNIMERNET_WIDTH - target_w) // 2
    canvas[top : top + target_h, left : left + target_w] = resized
    return (((canvas / 255.0) - 0.7931) / 0.1738)[None, None, ...]


class UniMerNetTinyOnnx:
    """The exported graphs, driven the way the app drives them: greedy, cache carried by hand."""

    name = "unimernet-tiny-onnx"

    def __init__(self, directory: str, tokenizer_path: str, suffix: str = "") -> None:
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = 8
        root = Path(directory)
        self.encoder = ort.InferenceSession(
            str(root / f"unimernet-tiny-encoder{suffix}.onnx"), options
        )
        self.decoder = ort.InferenceSession(
            str(root / f"unimernet-tiny-decoder{suffix}.onnx"), options
        )
        self.name = f"unimernet-tiny-onnx{suffix}"
        shapes = {item.name: item.shape for item in self.decoder.get_inputs()}
        self.key_shape = shapes["past_keys"]
        self.value_shape = shapes["past_values"]
        self.vocabulary = Vocabulary(tokenizer_path)

    def read(self, raster: np.ndarray) -> str:
        memory = self.encoder.run(None, {"pixels": unimernet_tensor(raster)})[0]
        layers, _, heads, _, key_dim = self.key_shape
        value_dim = self.value_shape[4]
        keys = np.zeros((layers, 1, heads, 0, key_dim), dtype=np.float32)
        values = np.zeros((layers, 1, heads, 0, value_dim), dtype=np.float32)
        token = UNIMERNET_START
        ids: list[int] = []
        for _ in range(UNIMERNET_MAX_TOKENS):
            following, keys, values = self.decoder.run(
                None,
                {
                    "token": np.asarray([[token]], dtype=np.int64),
                    "memory": memory,
                    "past_keys": keys,
                    "past_values": values,
                },
            )
            token = int(following[0])
            if token == UNIMERNET_END:
                break
            ids.append(token)
        return self.vocabulary.decode(ids)


class Vocabulary:
    """Byte-level BPE ids back to text — FormulaNet's tokenizer, which UniMERNet shares byte for byte."""

    def __init__(self, tokenizer_path: str) -> None:
        import json

        tokenizer = json.loads(Path(tokenizer_path).read_text(encoding="utf-8"))
        self.tokens: dict[int, str] = {
            index: text for text, index in tokenizer["model"]["vocab"].items()
        }
        self.special: set[int] = {
            entry["id"] for entry in tokenizer.get("added_tokens", []) if entry.get("special")
        }
        self.byte_decoder: dict[str, int] = inksim._byte_decoder()

    def decode(self, ids: list[int]) -> str:
        text = "".join(self.tokens.get(i, "") for i in ids if i not in self.special)
        return bytes(self.byte_decoder[c] for c in text).decode("utf-8", "replace").strip()
