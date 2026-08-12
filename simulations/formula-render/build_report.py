"""Assemble the findings page: inline every candidate SVG next to what the model read from it."""

import html
import json
import re

CANDIDATES: list[tuple[str, str, str]] = [
    ("baseline", "Baseline", "What the app renders today: 2.0&nbsp;dp stroke, black padding."),
    ("A-robust", "A · Heavy + straightened", "16&nbsp;px stem, near-straight strokes snapped to their chord and to the axes."),
    ("B-exact", "B · Heavy + arc-fitted", "10&nbsp;px stem, curved strokes replaced by their best-fit circular arc."),
    ("C-layout", "C · Arc + relaid out", "10&nbsp;px stem, arcs, whitespace closed to 10&nbsp;units, frame filled."),
]

EQUATIONS: list[tuple[str, str]] = [
    ("eq1", r"\sum_{n=1}^{\infty}\frac{10}{n(n-2)}"),
    ("eq2", r"\int_{0}^{4}\frac{1}{x^{6}}dx"),
    ("eq3", r"\int_{1}^{\infty}\frac{1}{x^{4}}dx"),
]

SCORES: dict[str, dict] = {
    "baseline": {"mean": 0.615, "worst": 0.38, "exact": 0},
    "A-robust": {"mean": 0.897, "worst": 0.82, "exact": 0},
    "B-exact": {"mean": 0.902, "worst": 0.67, "exact": 13},
    "C-layout": {"mean": 0.897, "worst": 0.46, "exact": 5},
}

KNOBS: list[tuple[str, str, str, str]] = [
    ("Stem width in the 384 frame", "+0.23", "up",
     "The whole story. 2&nbsp;px reads as noise, 10–16&nbsp;px reads as type, 24&nbsp;px blots the counters shut."),
    ("Padding colour", "+0.08", "up",
     "The app pads the square with black around a white page. Padding white instead is a two-line fix."),
    ("Arc / straight-line fitting", "+0.03", "up",
     "Perfect curves and chords, as you guessed — real but small next to stem width."),
    ("Closing whitespace", "±0.10", "mixed",
     "Rescued eq3 (0.79&nbsp;→&nbsp;0.95) and hurt eq1. Helps when a formula is nearly square, hurts when it is already wide."),
    ("Stabilisation / smoothing", "0.00", "flat",
     "Chaikin passes changed the score by nothing at all. The ink is already smooth; the raster cannot see the difference."),
    ("Render resolution", "0.00", "flat",
     "4&nbsp;px/unit and 16&nbsp;px/unit score the same. Everything is thrown away by the downscale to 384."),
    ("Round vs. butt line caps", "−0.06", "down",
     "Butt caps leave visible notches where strokes join. Keep them round."),
]


def load_svg(path: str) -> str:
    """Inline an SVG, dropping the fixed pixel size so CSS can scale it."""
    with open(path, encoding="utf-8") as handle:
        markup = handle.read()
    markup = re.sub(r'\s(width|height)="[^"]*"', "", markup, count=2)
    markup = markup.replace("<svg ", '<svg preserveAspectRatio="xMidYMid meet" ', 1)
    return markup.strip()


def specimen_grid(manifest: list[dict]) -> str:
    lookup = {(row["candidate"], row["equation"]): row for row in manifest}
    blocks: list[str] = []
    for key, title, note in CANDIDATES:
        cells: list[str] = []
        for equation, _ in EQUATIONS:
            row = lookup[(key, equation)]
            correct = row["score"] >= 0.999
            cells.append(
                f"""<figure class="specimen">
        <div class="plate">{load_svg(row["svg_file"])}</div>
        <figcaption>
          <code class="reading{' is-right' if correct else ''}">{html.escape(row["canonical"]).strip()}</code>
          <span class="mark {'right' if correct else 'off'}">{'exact' if correct else f'{row["score"]:.2f}'}</span>
        </figcaption>
      </figure>"""
            )
        stats = SCORES[key]
        blocks.append(
            f"""<section class="candidate{' is-baseline' if key == 'baseline' else ''}">
      <header class="candidate-head">
        <div>
          <h3>{title}</h3>
          <p>{note}</p>
        </div>
        <dl class="figures">
          <div><dt>mean</dt><dd>{stats['mean']:.3f}</dd></div>
          <div><dt>worst</dt><dd>{stats['worst']:.2f}</dd></div>
          <div><dt>exact</dt><dd>{stats['exact']}<span class="of">/33</span></dd></div>
        </dl>
      </header>
      <div class="plates">{"".join(cells)}</div>
    </section>"""
        )
    return "\n    ".join(blocks)


def knob_rows() -> str:
    return "\n        ".join(
        f"""<tr class="knob-{direction}">
          <th scope="row">{name}</th>
          <td class="delta">{delta}</td>
          <td>{note}</td>
        </tr>"""
        for name, delta, direction, note in KNOBS
    )


def main() -> None:
    with open("final_manifest.json", encoding="utf-8") as handle:
        manifest = json.load(handle)

    truth_row = "".join(
        f'<div><span class="eq-id">{name}</span><code>{html.escape(latex)}</code></div>'
        for name, latex in EQUATIONS
    )

    page = f"""<title>Making ink legible to PP-FormulaNet</title>
<style>
  :root {{
    --paper: #F2F3F0;
    --surface: #FFFFFF;
    --sunk: #E7E9E4;
    --ink: #171A18;
    --muted: #6C716B;
    --line: #D5D8D1;
    --accent: #1C6B49;
    --accent-soft: #DDEEE4;
    --warn: #8A3A1E;
    --warn-soft: #F3E3DA;
    --display: ui-serif, "Iowan Old Style", "Palatino Linotype", Palatino, Georgia, serif;
    --body: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    --mono: ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace;
  }}
  @media (prefers-color-scheme: dark) {{
    :root:not([data-theme="light"]) {{
      --paper: #101311;
      --surface: #191D1A;
      --sunk: #222723;
      --ink: #E7EBE6;
      --muted: #949A92;
      --line: #2C322D;
      --accent: #4ECB92;
      --accent-soft: #17301F;
      --warn: #E0916C;
      --warn-soft: #2E1C13;
    }}
  }}
  :root[data-theme="dark"] {{
    --paper: #101311;
    --surface: #191D1A;
    --sunk: #222723;
    --ink: #E7EBE6;
    --muted: #949A92;
    --line: #2C322D;
    --accent: #4ECB92;
    --accent-soft: #17301F;
    --warn: #E0916C;
    --warn-soft: #2E1C13;
  }}

  * {{ box-sizing: border-box; }}
  body {{
    margin: 0;
    background: var(--paper);
    color: var(--ink);
    font-family: var(--body);
    font-size: 16px;
    line-height: 1.6;
    -webkit-font-smoothing: antialiased;
  }}
  .wrap {{ max-width: 1120px; margin: 0 auto; padding: 0 24px 96px; }}
  .col {{ max-width: 68ch; }}

  h1, h2, h3 {{ font-family: var(--display); font-weight: 600; text-wrap: balance; margin: 0; }}
  h1 {{ font-size: clamp(2rem, 4.5vw, 3rem); line-height: 1.1; letter-spacing: -0.015em; }}
  h2 {{ font-size: 1.6rem; letter-spacing: -0.01em; }}
  h3 {{ font-size: 1.15rem; }}
  p {{ margin: 0; }}
  code {{ font-family: var(--mono); font-size: 0.86em; }}

  .eyebrow {{
    font-family: var(--mono); font-size: 0.72rem; letter-spacing: 0.14em;
    text-transform: uppercase; color: var(--accent); margin: 0 0 14px;
  }}

  header.masthead {{ padding: 72px 0 40px; border-bottom: 1px solid var(--line); }}
  header.masthead p.lede {{ margin-top: 18px; font-size: 1.12rem; color: var(--muted); max-width: 62ch; }}

  .headline {{
    display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 1px; background: var(--line); border: 1px solid var(--line);
    margin: 36px 0 0; border-radius: 3px; overflow: hidden;
  }}
  .headline div {{ background: var(--surface); padding: 18px 20px; }}
  .headline dt {{
    font-family: var(--mono); font-size: 0.7rem; letter-spacing: 0.1em;
    text-transform: uppercase; color: var(--muted);
  }}
  .headline dd {{
    margin: 6px 0 0; font-family: var(--display); font-size: 1.9rem;
    font-variant-numeric: tabular-nums; line-height: 1;
  }}
  .headline dd small {{ font-size: 0.9rem; color: var(--muted); font-family: var(--body); }}
  .rise {{ color: var(--accent); }}

  section.band {{ padding: 56px 0 0; }}
  section.band > h2 {{ margin-bottom: 10px; }}
  section.band > p.intro {{ color: var(--muted); max-width: 68ch; margin-bottom: 28px; }}

  .truths {{
    display: flex; flex-wrap: wrap; gap: 10px; margin-top: 22px;
  }}
  .truths > div {{
    display: flex; align-items: baseline; gap: 10px; background: var(--sunk);
    border: 1px solid var(--line); border-radius: 3px; padding: 8px 12px;
  }}
  .eq-id {{
    font-family: var(--mono); font-size: 0.68rem; letter-spacing: 0.08em;
    text-transform: uppercase; color: var(--accent);
  }}

  .pipeline {{
    display: flex; flex-wrap: wrap; align-items: stretch; gap: 8px; margin-top: 8px;
  }}
  .stage {{
    flex: 1 1 150px; background: var(--surface); border: 1px solid var(--line);
    border-radius: 3px; padding: 14px 16px;
  }}
  .stage.tuned {{ border-color: var(--accent); background: var(--accent-soft); }}
  .stage b {{ display: block; font-size: 0.95rem; }}
  .stage span {{ display: block; color: var(--muted); font-size: 0.82rem; margin-top: 4px; }}

  table {{ width: 100%; border-collapse: collapse; font-size: 0.95rem; }}
  .scroll {{ overflow-x: auto; }}
  th, td {{ text-align: left; padding: 12px 14px; border-bottom: 1px solid var(--line); vertical-align: top; }}
  thead th {{
    font-family: var(--mono); font-size: 0.68rem; letter-spacing: 0.1em;
    text-transform: uppercase; color: var(--muted); border-bottom: 1px solid var(--ink);
  }}
  tbody th {{ font-weight: 600; white-space: nowrap; }}
  td.delta {{
    font-family: var(--mono); font-variant-numeric: tabular-nums; white-space: nowrap; font-weight: 600;
  }}
  .knob-up td.delta {{ color: var(--accent); }}
  .knob-down td.delta {{ color: var(--warn); }}
  .knob-flat td.delta, .knob-mixed td.delta {{ color: var(--muted); }}

  .candidate {{
    margin-top: 28px; background: var(--surface); border: 1px solid var(--line); border-radius: 4px;
  }}
  .candidate.is-baseline {{ background: var(--sunk); }}
  .candidate-head {{
    display: flex; flex-wrap: wrap; gap: 20px; justify-content: space-between;
    align-items: flex-start; padding: 20px 22px 16px; border-bottom: 1px solid var(--line);
  }}
  .candidate-head p {{ color: var(--muted); font-size: 0.9rem; margin-top: 4px; max-width: 54ch; }}
  .figures {{ display: flex; gap: 22px; margin: 0; }}
  .figures dt {{
    font-family: var(--mono); font-size: 0.66rem; letter-spacing: 0.1em;
    text-transform: uppercase; color: var(--muted);
  }}
  .figures dd {{
    margin: 2px 0 0; font-family: var(--display); font-size: 1.4rem;
    font-variant-numeric: tabular-nums; line-height: 1;
  }}
  .figures .of {{ font-size: 0.8rem; color: var(--muted); }}

  .plates {{
    display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px;
    background: var(--line);
  }}
  @media (max-width: 780px) {{ .plates {{ grid-template-columns: 1fr; }} }}
  .specimen {{ margin: 0; background: var(--surface); padding: 16px; display: flex; flex-direction: column; gap: 12px; }}
  .plate {{
    background: #FFFFFF; border: 1px solid var(--line); border-radius: 2px;
    height: 190px; display: flex; align-items: center; justify-content: center; padding: 10px;
  }}
  .plate svg {{ max-width: 100%; max-height: 100%; display: block; }}
  figcaption {{ display: flex; align-items: flex-start; gap: 10px; justify-content: space-between; }}
  .reading {{
    font-size: 0.76rem; line-height: 1.5; word-break: break-all; color: var(--muted);
  }}
  .reading.is-right {{ color: var(--ink); }}
  .mark {{
    font-family: var(--mono); font-size: 0.66rem; letter-spacing: 0.06em; text-transform: uppercase;
    padding: 3px 8px; border-radius: 2px; white-space: nowrap; font-variant-numeric: tabular-nums;
  }}
  .mark.right {{ background: var(--accent-soft); color: var(--accent); }}
  .mark.off {{ background: var(--sunk); color: var(--muted); }}

  .callout {{
    border-left: 3px solid var(--accent); background: var(--accent-soft);
    padding: 20px 24px; border-radius: 0 3px 3px 0; margin-top: 24px;
  }}
  .callout h3 {{ margin-bottom: 8px; }}
  .callout p + p {{ margin-top: 10px; }}

  ol.steps {{ margin: 20px 0 0; padding: 0; list-style: none; counter-reset: step; }}
  ol.steps li {{
    counter-increment: step; position: relative; padding: 0 0 18px 44px;
    border-left: 1px solid var(--line); margin-left: 12px;
  }}
  ol.steps li::before {{
    content: counter(step); position: absolute; left: -13px; top: 0;
    width: 26px; height: 26px; border-radius: 50%; background: var(--surface);
    border: 1px solid var(--line); color: var(--accent);
    font-family: var(--mono); font-size: 0.72rem; display: grid; place-items: center;
  }}
  ol.steps li:last-child {{ border-left-color: transparent; padding-bottom: 0; }}
  ol.steps b {{ display: block; }}
  ol.steps span {{ color: var(--muted); font-size: 0.92rem; }}

  footer {{ margin-top: 64px; padding-top: 24px; border-top: 1px solid var(--line); color: var(--muted); font-size: 0.88rem; }}
</style>

<div class="wrap">
  <header class="masthead">
    <p class="eyebrow">Render study · page 3 · {len(manifest) // len(CANDIDATES)} formulas</p>
    <h1>The ink was never the problem. The rendering was.</h1>
    <p class="lede">
      Your hunch was right and it goes further than 2.0. Stroke thickness is the dominant
      variable in whether PP-FormulaNet reads your handwriting — but only once it is expressed as
      the width the stem ends up at <em>inside the model's 384&nbsp;px square</em>, which is not what
      the pen setting controls. Fixing that, and padding the square white instead of black, takes
      page 3 from a mean token accuracy of 0.615 to 0.902 and from no exact readings to thirteen.
    </p>
    <dl class="headline">
      <div><dt>Baseline today</dt><dd>0.615</dd></div>
      <div><dt>Best candidate</dt><dd class="rise">0.902</dd></div>
      <div><dt>Exact readings</dt><dd class="rise">13<small> of 33</small></dd></div>
      <div><dt>Renders scored</dt><dd>3,285</dd></div>
    </dl>
  </header>

  <section class="band">
    <h2>What was measured</h2>
    <p class="intro">
      The 64 live strokes on page 3 were read straight out of <code>notes.db</code>, decoded from
      <code>androidx.ink</code>'s delta-compressed protobuf, and replayed through the same erase
      history the app applies on load. Each formula was then rendered to SVG, rasterised, put
      through the app's own <code>preprocessFormula</code> arithmetic and handed to the real ONNX
      graph off your emulator. Scoring is token edit-distance against these three readings, with
      <code>\\limits</code> stripped the way <code>math_engine</code> strips it before parsing.
    </p>
    <div class="truths">{truth_row}</div>
  </section>

  <section class="band">
    <h2>Where the knobs live</h2>
    <p class="intro">
      Only the middle three stages were varied. The first is your ink and the last is the model;
      neither moves.
    </p>
    <div class="pipeline">
      <div class="stage"><b>Stored strokes</b><span>64 polylines, page units</span></div>
      <div class="stage tuned"><b>Geometry</b><span>smooth · simplify · fit arcs · close gaps</span></div>
      <div class="stage tuned"><b>SVG</b><span>stem width · caps · joins</span></div>
      <div class="stage tuned"><b>384 square</b><span>crop · fit · pad colour</span></div>
      <div class="stage"><b>PP-FormulaNet-S</b><span>ONNX, unchanged</span></div>
    </div>
  </section>

  <section class="band">
    <h2>What actually moved the needle</h2>
    <p class="intro">
      Change in mean token accuracy attributable to each knob, holding the rest at their best.
      Two of the things worth trying turned out to do nothing at all, which is worth knowing before
      you build them.
    </p>
    <div class="scroll">
      <table>
        <thead>
          <tr><th scope="col">Knob</th><th scope="col">Δ mean</th><th scope="col">What happened</th></tr>
        </thead>
        <tbody>
        {knob_rows()}
        </tbody>
      </table>
    </div>
  </section>

  <section class="band">
    <h2>The candidates</h2>
    <p class="intro">
      Every plate below is the actual SVG handed to the rasteriser, at the settings named. The
      caption is what the model read from it, canonicalised. Scores under each plate are for that
      exact render; the figures on the right are means over the true ink plus ten perturbed
      variants of it, so a lucky reading cannot carry a row.
    </p>
    {specimen_grid(manifest)}
  </section>

  <section class="band">
    <h2>No single setting wins</h2>
    <p class="intro">
      B reads the sum and the definite integral best; C is the only one that gets the improper
      integral exactly right, and it does so by closing the whitespace that was pushing the
      infinity symbol out of the model's reach. A never nails anything but never falls apart
      either — its worst render across 33 is still 0.82, where B's is 0.67.
    </p>
    <div class="callout">
      <h3>The useful consequence: agreement is a confidence signal</h3>
      <p>
        The ONNX batch dimension is dynamic and one image costs about 0.13&nbsp;s, so rendering the
        same selection four ways is one <code>session.run</code> and roughly one inference of
        latency. Picking the best of those four would score <strong>0.978</strong> with 22 of 33
        exact — but nothing picks it blindly, and a plain majority vote scores no better than B
        alone.
      </p>
      <p>
        What does work is reading the vote as certainty rather than as an answer. On the 15 of 33
        cases where two or more renders agreed, the agreed reading averaged <strong>0.977</strong>.
        On the 18 where they did not, the best rule found here — take the first reading SymPy will
        parse — reaches only 0.854. That is a recognition panel that can tell the difference
        between a formula it has read and one it has guessed at, and offer the alternatives for the
        second kind instead of silently committing.
      </p>
    </div>
  </section>

  <section class="band">
    <h2>On <code>pp-formulanet_plus-s</code></h2>
    <p class="intro col">
      Worth trying and worth dropping. Across the same sweep it scored <strong>0.700</strong> at its
      best setting against 0.877 for the model you already ship, and it was beaten at every stem
      width tested. The plus variants are tuned toward complex printed formulas; on this
      handwriting they read the same glyphs less reliably. The 222&nbsp;MB download stays where it is.
    </p>
  </section>

  <section class="band">
    <h2>What went into the app</h2>
    <p class="intro">
      The two that carry the result are in. Before wiring them up the stem width was re-swept on its
      own, without any of the geometry stages, to be sure the number being hard-coded was the right
      one: <strong>10&nbsp;px raw scores 0.898</strong> against 0.902 with arc fitting on top, so the
      fitting was left out — it turns out to buy almost nothing once the stem is right.
    </p>
    <ol class="steps">
      <li>
        <b>Done — the square is padded white.</b>
        <span>
          <code>InkRecognitionEngine.kt</code> filled the 384 canvas with black and then pasted a
          white page into the middle of it. It now fills with <code>FORMULA_PAD_LEVEL</code>, the
          paper the crop sits on. Worth about +0.08 on its own.
        </span>
      </li>
      <li>
        <b>Done — stroke width comes from the output frame.</b>
        <span>
          <code>InkSelectionRenderer.kt</code> drew each stroke at its stored <code>sizeDp</code>.
          It now solves <code>stem = 10 · longest / (384 − 10)</code> and re-inks the stroke at that
          width, dividing the width back out of any projection carrying a resize.
          <code>RecognitionStemTest</code> pins the property — <em>every</em> selection size lands
          at the same stem — and it runs without a device.
        </span>
      </li>
      <li>
        <b>Not done — arc and straight-line fitting.</b>
        <span>
          +0.004 over the shipped setting, and it needs the polylines rebuilt into new stroke
          inputs. The measurement is in <code>results/sweep_geometry.json</code> if it is ever worth
          revisiting.
        </span>
      </li>
      <li>
        <b>Not done — batching renders for a confidence signal.</b>
        <span>
          The largest remaining win, and the only one that changes the panel rather than a constant.
          One <code>session.run</code>, three inputs, and a recognition panel that can tell you when
          it is guessing.
        </span>
      </li>
    </ol>
  </section>

  <footer>
    <p>
      Measured on the real graph pulled from the emulator, not a re-export. Three formulas in one
      hand on one page is a small corpus: the perturbation seeds guard against tuning to a single
      lucky render, but they cannot stand in for other people's handwriting. Before any of this is
      committed, the same sweep wants running over a page or two of someone else's ink.
    </p>
  </footer>
</div>
"""
    with open("report.html", "w", encoding="utf-8") as handle:
        handle.write(page)
    print("wrote report.html", len(page), "bytes")


main()
