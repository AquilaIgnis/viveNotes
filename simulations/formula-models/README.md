# Formula model study — can anything read this hand better than PP-FormulaNet-S?

Run 2026-10-06 against the bundled **Recognition Test** page
(`app/src/main/assets/default_notebook/recognition_page_2.json`), scored against the
ground-truth ledger in the project notes. No device needed.

## Result

Each model at its own best stem width; 8 expressions × 11 rasters (true ink + 10 small
rotation/scale jitters from `formula-render/runner.perturb`). Desktop CPU, 8 threads.

| Model | Licence | Shipped size | Best stem | Token score | Exact | Median |
|---|---|---:|---:|---:|---:|---:|
| PP-FormulaNet-S (shipped) | Apache-2.0 | 232 MB ONNX | 10 | **0.958** | 54/88 | 42 ms |
| Pix2Text MFR 1.5 | MIT | 120 MB ONNX | 3 | 0.875 | 63/88 | 98 ms |
| UniMERNet-T | Apache-2.0 | 430 MB `.pth`, no ONNX | 3–4 | 0.876 | **66/88** | 420 ms (torch) |

Per expression, exact readings out of 11:

| # | Truth | FormulaNet-S @10 | Pix2Text @3 | UniMERNet-T @4 |
|---|---|---|---|---|
| 1 | `\sqrt{x^2+1}` | 0 — always `X` | 11 | 11 |
| 2 | `x^2-4=0` | 11 | 11 | 11 |
| 3 | `\sin(x)` | 11 | 11 | 11 |
| 4 | `\int_0^1 x^2 dx` | 8 | 11 | 11 |
| 5 | 2×2 bmatrix | **9** | 0 — `[1 7]` | 0 — `[1, 7]` |
| 6 | `\sum \frac{4k+3}{3k-5}` | 4 — `3k-6` | 8 | 11 |
| 7 | `\sum \frac{\cos n}{n^2}` | 11 | 11 | 11 |
| 8 | `\sum e^{-7n}` | 0 — `-iv` | 0 — `-7N` | 0 — `-7\pi` |

What it says:

- **Both alternatives read ordinary handwriting better.** Each is exact on every raster of 5–6
  expressions, where FormulaNet-S is on 3. FormulaNet's misses are systematic: it always
  capitalises the `x` in #1, which SymPy treats as a different symbol.
- **Neither alternative reads the matrix.** UniMERNet-T drops the second row at every stem
  width. Pix2Text reads it at stem 6 (8/11) but not at stem 3, which is where its other
  expressions do best. FormulaNet-S is the only one that is reliable on it. Its higher token score
  comes mostly from this one expression, because the others score near 0.07 on it.
- **Stem width is per model.** FormulaNet wants the 10 px the app uses, while the other two want
  3–4 px and get worse as strokes thicken. Pix2Text scores 0/88 at 16 px.
- **#8 defeats everything.** The hooked `n` in the exponent is read as `v`, `N` or `π`.
  Treat it as a hard case for the handwriting rather than evidence against any one model.
- Eight expressions in one hand is a small corpus; the formula-render warning applies.

## UniMERNet-T in the app

UniMERNet-T is the app's default formula model, downloaded on first run (`FormulaEngine` and
`AiModelStore` in the app), with FormulaNet-S kept as an optional download. The two int8 graphs are
served from a model release on the app's own repository, which `AiModelStore` pins by URL, size
and SHA-256:

```bash
gh release create models-unimernet-tiny-v1 --latest=false \
  --title "UniMERNet-T int8 (formula model)" \
  app/src/debug/assets/ai/dev/unimernet-tiny-encoder-int8.onnx \
  app/src/debug/assets/ai/dev/unimernet-tiny-decoder-int8.onnx
```

`--latest=false` keeps the app's own `v*` release as Latest. Debug builds still bundle the pair in
`ai/dev`, so an emulator needs no network. These scripts produce it:

```
export_unimernet.py    PyTorch → unimernet-tiny-encoder.onnx + unimernet-tiny-decoder.onnx
quantize_unimernet.py  int8 weights in MatMul/Gather → the *-int8.onnx pair the app bundles
```

The encoder maps `pixels [1,1,192,672]` to `memory [1,126,512]`. The decoder takes one token, the
memory and the self-attention cache, and returns the next token (its argmax) and the grown cache.
The greedy loop runs in Kotlin (`OnnxInkRecognitionEngine.decodeUniMerNet`) and in Python
(`models.UniMerNetTinyOnnx`), which run the same steps.

| Graphs | Size | Exact @4 | Same reading as | Median |
|---|---:|---:|---|---:|
| PyTorch (package processor) | 430 MB `.pth` | 66/88 | — | 420 ms |
| ONNX fp32, app preprocessing | 431 MB | 66/88 | PyTorch on 264/264 | 265 ms |
| ONNX int8, app preprocessing | **111 MB** | 66/88 | fp32 on 261/264 | 134 ms |

"App preprocessing" means `models.unimernet_tensor`: one bilinear scale into 192×672, padded black.
That is what `preprocessUniMerNet` does, in place of the package's resize-then-thumbnail. The three
int8 differences are all at stem 6, not the 4 the app uses.

The export is deterministic: a second run writes the same bytes. `AiModelStore` pins both files'
size and SHA-256, so a fresh export that differs means something upstream moved. Re-run the parity
rows above before updating the pins. To put them in a clone that lacks them:

```bash
env/bin/python export_unimernet.py --unimernet-config <yaml> --output <dir>
env/bin/python quantize_unimernet.py --directory <dir>
cp <dir>/unimernet-tiny-{encoder,decoder}-int8.onnx ../../app/src/debug/assets/ai/dev/
env/bin/python compare.py --models unimernet-tiny-onnx-int8 --unimernet-onnx <dir> \
  --formulanet-tokenizer ../../app/src/debug/assets/ai/dev/pp-formulanet-tokenizer.json --stems 3 4 6
```

The `.onnx` files under `ai/dev` are gitignored. A debug build without them downloads the model
from the release like any other build.

**The plates here are cairo polylines, so they hold for the app only while its renderer draws the
same thing.** It did not at first. `renderInkSelection` kept each stroke's stored mesh tolerance,
which at a 4 px stem turned caps and curves into polygons, and UniMERNet read page two's
`x² - 4 = 0` as `-1` on the tablet. The tolerance is now set from the bitmap, and the app's bitmap
matches `results/plates/expr2-stem4.png`. Before trusting a new number from here, dump an app
bitmap and compare it with the plate.

## Scoring

`compare.score` is token edit similarity after normalising what cannot change the formula:

- `inksim.canonical`: spacing, `\limits`, `\left` and `\right`.
- Font wrappers (`\mathrm{d}` → `d`) and `\dfrac` → `\frac`.
- Braces around a single token, so `x^{2}` equals `x ^ 2`.
- `\left[\begin{matrix}…\end{matrix}\right]` is a bmatrix, and a trailing `\\` is dropped.

"Exact" means the token sequences match after that normalisation. Case is not normalised, because
the math engine would not normalise it either.

`inksim.canonical` strips `\ ` as a spacing command, which also eats half of a `\\ ` row break.
`normalized_tokens` protects row breaks first. Anything else scored through `canonical` mis-scores
matrices for the same reason.

## Layout

```
replay.py             fixture → visible polylines (centreline approximation of erase/move replay)
check_erases.py       draws what the replay erased in red, to audit it
models.py             one adapter per model, each with its own project's preprocessing
compare.py            segment, render, read, score; writes results/<model>.json
export_unimernet.py   UniMERNet-T → two ONNX graphs, greedy loop left to the caller
quantize_unimernet.py int8 weights for the pair the dev build bundles
results/         page.png, erased.png, plates/ (seed-0 rasters), per-model readings
```

`replay.py` cannot reuse the app's replay, which runs on androidx.ink meshes. Instead it cuts
centrelines within the eraser's radius and moves lassoed pieces the way `replayMove` and
`replayResize` select them. `results/page.png` is the check: all eight expressions and none of
the 330 erased strokes. The fixture has no margin numbers, so expressions are numbered top to
bottom.

## Reproducing

The models are not checked in.

```bash
uv venv --python 3.12 env
uv pip install --python env/bin/python --index-url https://download.pytorch.org/whl/cpu torch torchvision
uv pip install --python env/bin/python "unimernet==0.2.3" onnxruntime==1.27.0 numpy pillow cairosvg

# PP-FormulaNet-S, the app's pinned file (SHA-256 0ee32c7b…6dcc)
curl -LO https://github.com/GreatV/oar-ocr/releases/download/v0.3.0/pp-formulanet-s.onnx
# Pix2Text: encoder_model.onnx, decoder_model.onnx, tokenizer.json, generation_config.json
#   from https://huggingface.co/breezedeus/pix2text-mfr-1.5
# UniMERNet-T: unimernet_tiny.pth, config.json, tokenizer*.json, preprocessor_config.json
#   from https://huggingface.co/wanderkid/unimernet_tiny, plus a config yaml shaped like the
#   repo's configs/demo.yaml with model_name/pretrained/tokenizer_config pointing at it,
#   device: cpu and distributed: False

env/bin/python compare.py --models pp-formulanet-s pix2text-mfr-1.5 unimernet-tiny \
  --formulanet pp-formulanet-s.onnx \
  --formulanet-tokenizer ../../app/src/debug/assets/ai/dev/pp-formulanet-tokenizer.json \
  --pix2text <pix2text dir> --unimernet-config <yaml> --stems 2 3 4 6 10 16
```

`unimernet` pins transformers 4.42.4, whose `tokenizers` cannot parse Pix2Text's newer
`tokenizer.json`. So `Pix2TextMfr` decodes the byte-level BPE from the vocabulary by hand.
