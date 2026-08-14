# Image OCR study — reading pictures with PP-OCRv5, without OpenCV

Run 2026-08-13, on the desktop, before a line of the Android implementation was written. It answers
the questions that were expensive to get wrong on a tablet: whether a **detection** model is needed
at all, what the DB post-processing has to do when there is no OpenCV to do it, and which constants
the Kotlin port should be born with.

Applied to the app in `ai/ImageTextRecognizer.kt` and `model/ocr/TextDetection.kt`.

## What it answers

| Decision | Finding |
|---|---|
| Detection model needed? | **Yes.** The bundled `en_pp-ocrv5_mobile_rec` graph reads *one line*. A whole picture squeezed into its 48-pixel input reads as nothing. |
| Score a box by its bounding box or by filling the quad? | **Fill the quad.** Mean CER 0.343 → 0.010; the photographed page went from **0 lines found to all 4**. |
| Resize policy | `limit_type=max, limit_side_len=960`, each side rounded to a multiple of 32. |
| Binarize / box / unclip thresholds | PaddleOCR's own 0.3 / 0.6 / 1.5. This corpus does not separate them. |
| Batch the recognizer? | **No.** Padding every crop in a batch to the widest one costs more than the batch saves: 6-wide batches are **30% slower** than one at a time, 16-wide are **2.2× slower**. |
| Recognizer threads | 4. Eight threads is no better than four on 24 cores; one thread is 3× worse. |
| Recognizer minimum width | Leave the existing 320. Lowering it to 32 changed neither accuracy nor time. |
| Junk readings | Drop any reading with no alphanumeric character. Three of the slide's seven "lines" were its bullet ellipses. |

Mean character error rate over the corpus: **0.000** with everything above applied, from 0.343 for
the first working version.

## The one that mattered

`box_score` decides whether a detected region survives. PaddleOCR fills the polygon and averages the
probability inside it; the obvious simplification is to average its bounding box instead, and for
horizontal text the two are the same number.

They are not the same number for a photograph. A line of text shot at 3.5° fills a little over half
of its own axis-aligned bounds, so the bounding box averages the line together with the paper beside
it, every score lands under `box_thresh`, and the picture returns **nothing at all** — not bad text,
no text, which is the failure that looks like a broken model rather than a wrong constant.

Filling the quad is one scanline loop. It is in `dbpost.box_score` here and
`TextDetection.boxScore` there.

## Why batching lost

The intuition was that six lines through one `Run` would beat six `Run`s. The recognizer takes a
fixed-width tensor, so a batch has to be padded to its widest member — and a page mixes a 46-pixel
crop with a 940-pixel one. At four threads, over 22 real crops:

```
batch    1      2      4      6      8     16
ms     145    136    146    191    217    322
```

Sorting by width before batching (which this does) is what keeps 2 and 4 from being worse still.
Even so, nothing beats simply running them in order, and doing that keeps the app's inference path a
single tensor at a time — which is also the shape that lets one mutex serialize every model.

## What the port found that the study could not

The Kotlin port of all of the above passed its own tests and then read **one line out of three** on a
device. Detection was not at fault — the probe logged three quads at 0.93–0.94, the same three the
Python finds — and the one line it did return was perfect.

The survivor was the only crop wider than 320 pixels. `preprocessText` builds a tensor
`max(320, …)` wide and copies a narrower resized bitmap into it, and it walked the pixel array with
a single running index: row *r* went to offset `r * resizedWidth` in a plane whose rows start every
`width`, shearing the line a few pixels further right on each of its 48 rows.

**It had been doing that to every lasso recognition since recognition shipped** — any selection under
about 6.7:1, which is most handwriting. Nothing here could have caught it, because `rec_tensor` in
`pipeline.py` writes the padded batch with a numpy slice and gets the strides right for free. The
lesson is narrow and worth keeping: a simulation validates the *algorithm*, and the port still has
to be tested against a picture with more than one line in it.

Fixed in `ai/InkRecognitionEngine.kt`, guarded by `RecognitionPreprocessTest`.

## Corpus

Six generated pictures, in `samples.py`, standing in for what actually gets pasted into a note:

| Sample | What it tests |
|---|---|
| `screenshot_light` | The common case: dark text, white ground, one column |
| `screenshot_dark` | A dark-mode terminal capture, light-on-dark, monospaced |
| `slide` | Big title over short bullets; the bullet glyphs are the junk-reading case |
| `photographed_page` | Paper shot at −3.5°, unevenly lit, slightly soft |
| `small_thumbnail` | 120×60 with 12-pixel text: unreadable, and must fail quietly |
| `photograph_no_text` | No writing at all — the majority case, and it has to be cheap |

Real user pictures cannot be checked in, so the corpus is drawn with PIL and each sample carries the
exact lines it was drawn from. That makes the error rate honest and the corpus weak in one specific
way: **every sample is clean, synthetic type at a comfortable size.** It cannot tell 0.3 from 0.4 as
a binarization threshold, and the sweeps that came out flat say only that, not that the constants do
not matter. It is a regression corpus, not a benchmark.

## Layout

```
samples.py     the six pictures and their ground truth
dbpost.py      DB post-processing with no OpenCV: flood fill, hull, calipers, unclip, quad score
pipeline.py    det tensor -> ONNX -> quads -> warp -> rec -> CTC -> lines in reading order
run.py         sweeps (baseline | resize | thresholds | unclip) and scores them
bench.py       batch size × thread count for the recognizer
results/       every sweep's raw numbers
```

`dbpost.py` is deliberately written the way Kotlin has to be written — integer flood fill, monotone
chain, rotating calipers, scanline fill — rather than the way Python could be written. It is the
reference the JVM tests in `model/ocr/TextDetectionTest.kt` were checked against.

## Running it

The models are not in the repository. Point it at the app's bundled recognizer and at a downloaded
detector:

```bash
uv venv .ocrenv && uv pip install --python .ocrenv/bin/python onnxruntime numpy pillow
curl -L -o /tmp/pp-ocrv5_mobile_det.onnx \
  https://github.com/GreatV/oar-ocr/releases/download/v0.3.0/pp-ocrv5_mobile_det.onnx
.ocrenv/bin/python run.py \
  --det /tmp/pp-ocrv5_mobile_det.onnx \
  --rec ../../app/src/main/assets/ai/en_pp-ocrv5_mobile_rec.onnx \
  --dict ../../app/src/main/assets/ai/ppocrv5_en_dict.txt \
  --sweep baseline
```
