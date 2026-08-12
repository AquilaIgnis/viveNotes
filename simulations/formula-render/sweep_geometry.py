"""Stage two: hold the stem width near its optimum and vary the geometry treatment."""

from dataclasses import replace

import runner
from inksim import RenderConfig

SEEDS: tuple[int, ...] = (0, 1, 2, 3, 4)
STEMS: tuple[int, ...] = (8, 10, 12, 16)

TREATMENTS: dict[str, dict] = {
    "raw": {},
    "chaikin1": {"chaikin_passes": 1},
    "chaikin2": {"chaikin_passes": 2},
    "ma3": {"smooth_window": 3},
    "ma5": {"smooth_window": 5},
    "ma9": {"smooth_window": 9},
    "resample+ma5": {"resample_spacing": 1.0, "smooth_window": 5},
    "resample+ma9": {"resample_spacing": 1.0, "smooth_window": 9},
    "rdp0.4": {"rdp_epsilon": 0.4},
    "rdp1.0": {"rdp_epsilon": 1.0},
    "line1.0": {"line_tolerance": 1.0},
    "line2.0": {"line_tolerance": 2.0},
    "line2+axis2": {"line_tolerance": 2.0, "axis_tolerance": 2.0},
    "line3+axis3": {"line_tolerance": 3.0, "axis_tolerance": 3.0},
    "arc1.5": {"arc_tolerance": 1.5},
    "arc3.0": {"arc_tolerance": 3.0},
    "arc3+line2+axis2": {"arc_tolerance": 3.0, "line_tolerance": 2.0, "axis_tolerance": 2.0},
    "ideal-all": {
        "resample_spacing": 1.0,
        "smooth_window": 5,
        "arc_tolerance": 3.0,
        "line_tolerance": 2.0,
        "axis_tolerance": 2.0,
    },
}


def main() -> None:
    base = RenderConfig(pixels_per_unit=8, pad_value=255.0)
    configs = [
        replace(base, label=f"{name} @stem{stem}", target_stem_px=stem, **options)
        for stem in STEMS
        for name, options in TREATMENTS.items()
    ]
    print(f"{len(configs)} configs, {len(configs) * 3 * len(SEEDS)} images", flush=True)
    results = runner.evaluate(configs, verbose=False, seeds=SEEDS)
    runner.report(results, "sweep_geometry.json")


main()
