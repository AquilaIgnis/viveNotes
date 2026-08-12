# Formula render study — how ink should be drawn for PP-FormulaNet

Run 2026-08-11 against page 3 of the testing database (`\sum`, `\int_0^4`, `\int_1^\infty`).
Findings and the candidate plates: **`report.html`** — open it in a browser.

## What it answers

Recognition hands PP-FormulaNet-S a bitmap of the lassoed ink. *How* that bitmap is drawn turns out
to matter more than anything else in the pipeline:

| Knob | Δ mean token accuracy |
|---|---|
| stem width in the 384 frame | **+0.23** |
| pad the square white, not black | **+0.08** |
| fit arcs / straighten near-lines | +0.03 |
| close horizontal whitespace | ±0.10 (helps a square formula, hurts a wide one) |
| stabilisation / smoothing | **0.00** |
| render resolution | **0.00** |
| butt line caps | −0.06 |

Baseline (what the app rendered before) **0.615**, best candidate **0.902**, exact readings 0/33 → 13/33.
`pp-formulanet_plus-s` scored **0.700** against 0.877 for the shipped model and was dropped.

Applied to the app in `ai/InkSelectionRenderer.kt` (`RECOGNITION_STEM_PX`) and
`ai/InkRecognitionEngine.kt` (`FORMULA_PAD_LEVEL`).

## Layout

```
inksim.py            the pipeline: decode -> geometry -> SVG -> raster -> 384 tensor -> ONNX -> score
inkdecode.py         androidx.ink stroke blobs (gzip + protobuf, zigzag deltas) -> polylines
runner.py            batches configs across the three formulas, with perturbation seeds
sweep_geometry.py    smoothing / simplification / line + arc fitting
sweep_layout.py      whitespace, aspect, resolution, caps
finalize.py          re-scores the finalists over 11 seeds and writes svg/ + results/final_*
ensemble.py          does rendering the same ink several ways and comparing readings help?
build_report.py      assembles report.html from svg/ + results/final_manifest.json
validate.py          checks the decoder against each row's stored bounding box
probe_proto.py       dumps one stroke blob's protobuf structure, for when the encoding changes
svg/                 the candidate plates, exactly as handed to the rasteriser
results/             every score from every sweep
```

## Reproducing

The two ONNX graphs are **not** checked in — 222 MB each. Pull the one the app already downloaded:

```bash
adb exec-out run-as com.vivenotes cat files/ai_models/pp-formulanet-s-v1/pp-formulanet-s.onnx > pp-formulanet-s.onnx
adb exec-out run-as com.vivenotes cat files/ai_models/pp-formulanet-s-v1/pp-formulanet-tokenizer.json > tokenizer.json
```

`pp-formulanet_plus-s.onnx` comes from the same oar-ocr release as the shipped one
(`v0.3.0`), and the tokenizer is shared by every FormulaNet size.

The ink comes from a copy of the testing database beside the scripts as `live.db`:

```bash
adb exec-out run-as com.vivenotes cat databases/notes.db > live.db
```

Then:

```bash
uv venv --python 3.12 sim-env
uv pip install --python sim-env/bin/python onnxruntime pillow numpy cairosvg
./sim-env/bin/python sweep_geometry.py
./sim-env/bin/python finalize.py && ./sim-env/bin/python build_report.py
```

About 0.13 s per render on CPU, so a hundred configurations is a couple of minutes.

## Two things that will bite whoever picks this up

**The strokes in the table are not what is on the page.** Object erases do not delete rows — the app
replays `ink_erases` on load and drops every stroke component the mask touched. Read `ink_strokes`
without joining `ink_erase_targets` and page 3 renders two integral signs and a scribbled-over
exponent that nobody can see on screen. `inksim.load_strokes` does the join.

**Three formulas in one hand is a small corpus.** Neighbouring settings were swinging half a point
on a single formula, which is the model sitting on a decision boundary rather than a preference —
hence `runner.perturb`, which scores every configuration over the true ink plus ten small rotations
and rescalings of it. That guards against tuning to a lucky render. It does not substitute for
somebody else's handwriting, and the numbers above should be re-measured on another page before
they are trusted much further than they already are.
