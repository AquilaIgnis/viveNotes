"""Confirm the finalists over more seeds, then write their SVGs and model-eye PNGs."""

import json
from dataclasses import asdict, replace

import numpy as np
from PIL import Image

import inksim
import runner
from inksim import RenderConfig

SEEDS: tuple[int, ...] = tuple(range(11))

CANDIDATES: dict[str, RenderConfig] = {
    "baseline": RenderConfig(
        label="baseline", thickness=2.0, pixels_per_unit=0, pad_value=0.0
    ),
    "A-robust": RenderConfig(
        label="A-robust",
        target_stem_px=16,
        line_tolerance=3.0,
        axis_tolerance=3.0,
        pixels_per_unit=8,
        pad_value=255.0,
    ),
    "B-exact": RenderConfig(
        label="B-exact",
        target_stem_px=10,
        arc_tolerance=1.5,
        pixels_per_unit=8,
        pad_value=255.0,
    ),
    "C-layout": RenderConfig(
        label="C-layout",
        target_stem_px=10,
        arc_tolerance=1.5,
        squeeze_gap=10.0,
        keep_aspect=False,
        pixels_per_unit=8,
        pad_value=255.0,
    ),
    "D-hybrid": RenderConfig(
        label="D-hybrid",
        target_stem_px=13,
        arc_tolerance=1.5,
        line_tolerance=3.0,
        axis_tolerance=3.0,
        squeeze_gap=14.0,
        pixels_per_unit=8,
        pad_value=255.0,
    ),
}


def main() -> None:
    configs = list(CANDIDATES.values())
    print(f"{len(configs)} candidates over {len(SEEDS)} seeds", flush=True)
    results = runner.evaluate(configs, verbose=False, seeds=SEEDS)
    runner.report(results, "final_scores.json")

    names = ["eq1", "eq2", "eq3"]
    manifest: list[dict] = []
    for key, config in CANDIDATES.items():
        for name, strokes in zip(names, runner.groups()):
            resolved = config
            if config.pixels_per_unit <= 0:
                resolved = replace(config, pixels_per_unit=runner.app_scale(strokes))
            svg, tensor = inksim.build_image(strokes, resolved)
            with open(f"final_{key}_{name}.svg", "w", encoding="utf-8") as handle:
                handle.write(svg)
            # What the network actually receives, mapped back to 0..255 for looking at.
            seen = np.clip(tensor * inksim.STD + inksim.MEAN, 0, 1) * 255
            Image.fromarray(seen.astype("uint8")).save(f"final_{key}_{name}_modelview.png")
            prediction = runner.model().run(tensor[None, ...])[0]
            manifest.append(
                {
                    "candidate": key,
                    "equation": name,
                    "svg_file": f"final_{key}_{name}.svg",
                    "prediction": prediction,
                    "canonical": inksim.canonical(prediction),
                    "truth": inksim.GROUND_TRUTH[name],
                    "score": round(inksim.score(prediction, inksim.GROUND_TRUTH[name]), 3),
                    "config": asdict(resolved),
                }
            )
            print(f"  {key:9s} {name}  {manifest[-1]['score']:.2f}  {prediction[:80]}", flush=True)

    with open("final_manifest.json", "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=1)


main()
