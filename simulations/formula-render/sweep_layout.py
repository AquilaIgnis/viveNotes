"""Stage three: layout and raster options on top of the two stem widths that survived."""

from dataclasses import replace

import runner
from inksim import RenderConfig

SEEDS: tuple[int, ...] = (0, 1, 2, 3, 4)

BASES: dict[str, dict] = {
    "s10-line2": {"target_stem_px": 10, "line_tolerance": 2.0},
    "s10-arc": {"target_stem_px": 10, "arc_tolerance": 1.5},
    "s16-line3axis3": {"target_stem_px": 16, "line_tolerance": 3.0, "axis_tolerance": 3.0},
    "s13-line3axis3": {"target_stem_px": 13, "line_tolerance": 3.0, "axis_tolerance": 3.0},
}

VARIANTS: dict[str, dict] = {
    "plain": {},
    "squeeze6": {"squeeze_gap": 6.0},
    "squeeze10": {"squeeze_gap": 10.0},
    "squeeze14": {"squeeze_gap": 14.0},
    "squeeze20": {"squeeze_gap": 20.0},
    "butt-cap": {"linecap": "butt", "linejoin": "miter"},
    "lowres4": {"pixels_per_unit": 4},
    "hires16": {"pixels_per_unit": 16},
    "stretch": {"keep_aspect": False},
    "squeeze10+stretch": {"squeeze_gap": 10.0, "keep_aspect": False},
}


def main() -> None:
    base = RenderConfig(pixels_per_unit=8, pad_value=255.0)
    configs = [
        replace(base, label=f"{name} {variant}", **options, **tweak)
        for name, options in BASES.items()
        for variant, tweak in VARIANTS.items()
    ]
    print(f"{len(configs)} configs, {len(configs) * 3 * len(SEEDS)} images", flush=True)
    runner.report(runner.evaluate(configs, verbose=False, seeds=SEEDS), "sweep_layout.json")


main()
