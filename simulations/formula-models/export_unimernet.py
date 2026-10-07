"""Export UniMERNet-T to the ONNX the app runs, and check it reads like the PyTorch model.

Two graphs, because greedy decoding is a loop and the loop belongs in Kotlin:

- `unimernet-tiny-encoder.onnx`: `pixels [1, 1, 192, 672]` → `memory [1, S, 512]`. The grey channel
  is repeated to three inside the graph, as `DonutEncoderDecoder.generate` does.
- `unimernet-tiny-decoder.onnx`: the last `token [1, 1]` + `memory` + the self-attention cache of
  the `P` tokens before it → `next [1]` (int64, the argmax) + the cache grown to `P + 1`. Only one
  position reaches the 50k-wide output layer, and the cache keeps every step from re-reading the
  whole prefix.

The cache travels as one tensor per kind, `[layers, 1, heads, P, dim]`, so the Kotlin side carries
two tensors rather than sixteen. Keys and values have different `dim`: UniMERNet's decoder squeezes
queries and keys to half width (`MBartSqueezeAttention`) and leaves values whole. Step one passes an
empty cache (`P = 0`).

Cross-attention keys and values are recomputed each step from `memory`. A layer reads its cross
cache as `past_key_value[-2:]`, which on a self-only pair is the self cache; it then takes the
recompute branch because that cache is not as long as `memory`. That test is a Python `if`, so it
is fixed at trace time, where the cache is 2 long and `memory` is 126. The graph always recomputes.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import torch
from torch import nn

from models import UniMerNetTiny

HEIGHT: int = 192
WIDTH: int = 672


class Encoder(nn.Module):
    def __init__(self, model: nn.Module) -> None:
        super().__init__()
        self.encoder = model.encoder

    def forward(self, pixels: torch.Tensor) -> torch.Tensor:
        return self.encoder(pixel_values=pixels.repeat(1, 3, 1, 1)).last_hidden_state


class Decoder(nn.Module):
    def __init__(self, model: nn.Module) -> None:
        super().__init__()
        self.decoder = model.decoder

    def forward(
        self,
        token: torch.Tensor,
        memory: torch.Tensor,
        past_keys: torch.Tensor,
        past_values: torch.Tensor,
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        layers = past_keys.shape[0]
        past = tuple((past_keys[i], past_values[i]) for i in range(layers))
        output = self.decoder.model.decoder(
            input_ids=token,
            encoder_hidden_states=memory,
            past_key_values=past,
            use_cache=True,
            return_dict=True,
        )
        logits = self.decoder.lm_head(output.last_hidden_state[:, -1, :])
        keys = torch.stack([layer[0] for layer in output.past_key_values])
        values = torch.stack([layer[1] for layer in output.past_key_values])
        return logits.argmax(dim=-1), keys, values


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--unimernet-config", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    wrapper = UniMerNetTiny(args.unimernet_config)
    model = wrapper.model.model.model  # UniMERNet → DonutEncoderDecoder → VisionEncoderDecoder
    model.eval()
    args.output.mkdir(parents=True, exist_ok=True)

    encoder = Encoder(model).eval()
    pixels = torch.zeros(1, 1, HEIGHT, WIDTH)
    torch.onnx.export(
        encoder,
        (pixels,),
        str(args.output / "unimernet-tiny-encoder.onnx"),
        input_names=["pixels"],
        output_names=["memory"],
        opset_version=17,
        dynamo=False,
    )

    memory = encoder(pixels)
    attention = model.decoder.model.decoder.layers[0].self_attn
    layers = len(model.decoder.model.decoder.layers)
    heads = attention.num_heads
    decoder = Decoder(model).eval()
    token = torch.zeros(1, 1, dtype=torch.long)
    past_keys = torch.zeros(layers, 1, heads, 2, attention.squeeze_head_dim)
    past_values = torch.zeros(layers, 1, heads, 2, attention.head_dim)
    torch.onnx.export(
        decoder,
        (token, memory, past_keys, past_values),
        str(args.output / "unimernet-tiny-decoder.onnx"),
        input_names=["token", "memory", "past_keys", "past_values"],
        output_names=["next", "keys", "values"],
        dynamic_axes={
            "past_keys": {3: "past"},
            "past_values": {3: "past"},
            "keys": {3: "length"},
            "values": {3: "length"},
        },
        opset_version=17,
        dynamo=False,
    )
    print(
        f"memory {tuple(memory.shape)}; layers {layers}, heads {heads}, "
        f"key dim {attention.squeeze_head_dim}, value dim {attention.head_dim}"
    )


if __name__ == "__main__":
    main()
