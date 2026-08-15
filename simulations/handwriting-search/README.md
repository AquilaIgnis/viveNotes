# Handwriting-search study

This is the pre-implementation study for recognizing stored ink into the Content panel. It uses
`myTrash/hw.vive`: 344 live strokes, 14 normal erasers, seven freehand prose lines and an ink-only
3×3 table whose six non-empty cells are separate recognition regions.

The source notebook is not copied into `simulations/`; pass its path when running the scripts.

## Questions

- Can PP-OCRv5 read this hand well enough for fuzzy search?
- Does the formula renderer's 10-pixel stem help or hurt a 48-pixel text recognizer?
- What normalized stem and quiet margin work best?
- Does smoothing help?
- Should long handwritten lines be one inference or stroke-native word chunks?
- Can an ink-only table use its real cell rectangles instead of rediscovering its layout from pixels?

The primary metric is **fuzzy query recall** over each distinct ground-truth word of at least four
characters. Exact transcription is useful, but search succeeds when the existing typo-tolerant
matcher can route a query to the page. Character error rate, confidence, region count and inference
time are recorded beside it.

`GROUND_TRUTH` in `study.py` was manually transcribed from `render_fixture.py`'s page image, not from
a model result. Case and punctuation are ignored by scoring. The intended table word that visually
resembles “trample” is the first item to correct if the author confirms a different spelling.

## Running

```bash
python3.13 -m venv /tmp/vivenotes-handwriting-ort-env
/tmp/vivenotes-handwriting-ort-env/bin/pip install onnxruntime==1.27.0 numpy pillow

/tmp/vivenotes-handwriting-ort-env/bin/python render_fixture.py \
  --vive ../../myTrash/hw.vive \
  --output /tmp/vivenotes-hw-page.png

/tmp/vivenotes-handwriting-ort-env/bin/python study.py \
  --vive ../../myTrash/hw.vive \
  --rec ../../app/src/main/assets/ai/en_pp-ocrv5_mobile_rec.onnx \
  --dict ../../app/src/main/assets/ai/ppocrv5_en_dict.txt \
  --output results/sweep.json

/tmp/vivenotes-handwriting-ort-env/bin/python finalize.py \
  --vive ../../myTrash/hw.vive \
  --rec ../../app/src/main/assets/ai/en_pp-ocrv5_mobile_rec.onnx \
  --dict ../../app/src/main/assets/ai/ppocrv5_en_dict.txt \
  --output results/final.json
```

The scripts reuse the protobuf decoder from `simulations/formula-render` and the proven PP-OCRv5
preprocessing/CTC decoder from `simulations/image-ocr`. A bundle containing lasso moves is rejected:
the Android implementation will reuse the app's native replay path rather than grow a second,
approximate move implementation in Python.

## Results and selected pipeline

The full sweep is in `results/sweep.json`; the eleven-raster robustness pass is in
`results/final.json`.

- Reusing the current FormulaNet renderer (10 px normalized stem in a 48 px text input) reached
  only **38.5% fuzzy-query recall** and 0.483 CER.
- Preserving stored stroke width for a whole line reached 80.8% recall.
- A text-specific 1.5 px stem, 8% vertical quiet margin, and chunks no wider than 10:1 reached
  100% recall on the clean fixture.
- Across seeds 0–10 with small rotations, anisotropic scale changes, and translations, the 1.5 px
  renderer averaged 99.3% recall with a 96.2% worst run. A 1 px alternate made different errors.
- Running the 1 px alternate only when primary mean CTC confidence is below **0.88** recovered
  **100% mean and worst-case recall** while invoking the alternate for 48 of 165 regions (29%).
- Smoothing did not improve the search metric and is omitted.

The selected Android pipeline therefore uses the real table cell rectangles, stroke-order-aware
freehand line grouping, whitespace chunking capped at 10:1, a 1.5 px primary render, and a 1 px
low-confidence fallback. Both readings are retained for fuzzy matching when the fallback runs.

For comparison, the existing page-image detector found 14 regions and reached 92.3% recall on this
fixture. On the desktop simulation it spent about 301 ms in detection, postprocessing, and
recognition, versus roughly 65–70 ms of recognition for direct stroke regions. Ink already carries
layout and clean geometry, so the implementation intentionally does not run the detector.
