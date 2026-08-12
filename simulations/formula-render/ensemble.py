"""Would rendering the same ink several ways and choosing between the readings help?

The batch dimension of the ONNX graph is dynamic and one image costs ~0.13 s, so four renders is
one `session.run` and roughly the latency of one. The question is whether anything can pick the
right reading without knowing the answer.
"""

import json
from collections import Counter
from dataclasses import replace

import inksim
import runner
from inksim import RenderConfig

SEEDS: tuple[int, ...] = tuple(range(11))

MEMBERS: dict[str, RenderConfig] = {
    "arc10": RenderConfig(target_stem_px=10, arc_tolerance=1.5, pixels_per_unit=8, pad_value=255.0),
    "line10": RenderConfig(
        target_stem_px=10, line_tolerance=3.0, axis_tolerance=3.0, pixels_per_unit=8, pad_value=255.0
    ),
    "layout10": RenderConfig(
        target_stem_px=10,
        arc_tolerance=1.5,
        squeeze_gap=10.0,
        keep_aspect=False,
        pixels_per_unit=8,
        pad_value=255.0,
    ),
    "line16": RenderConfig(
        target_stem_px=16, line_tolerance=3.0, axis_tolerance=3.0, pixels_per_unit=8, pad_value=255.0
    ),
}


def main() -> None:
    configs = [replace(config, label=name) for name, config in MEMBERS.items()]
    results = runner.evaluate(configs, verbose=False, seeds=SEEDS)

    by_case: dict[str, dict[str, dict]] = {}
    for row in results:
        by_case.setdefault(row["equation"], {})[row["label"]] = row

    totals: dict[str, float] = {name: 0.0 for name in MEMBERS}
    oracle_total = vote_total = 0.0
    exact_counts: dict[str, int] = {name: 0 for name in MEMBERS}
    oracle_exact = vote_exact = 0
    records: list[dict] = []

    for case, members in sorted(by_case.items()):
        for name in MEMBERS:
            totals[name] += members[name]["score"]
            exact_counts[name] += int(members[name]["exact"])

        oracle = max(members.values(), key=lambda row: row["score"])
        oracle_total += oracle["score"]
        oracle_exact += int(oracle["exact"])

        # Majority over the canonical string, ties broken by the member that scored best overall.
        counts = Counter(inksim.canonical(row["prediction"]).replace(" ", "") for row in members.values())
        winner, hits = counts.most_common(1)[0]
        chosen = next(
            row
            for name in ["arc10", "line10", "line16", "layout10"]
            for row in [members[name]]
            if inksim.canonical(row["prediction"]).replace(" ", "") == winner
        )
        vote_total += chosen["score"]
        vote_exact += int(chosen["exact"])
        records.append(
            {
                "case": case,
                "agreement": hits,
                "vote_score": chosen["score"],
                "oracle_score": oracle["score"],
                "oracle_member": oracle["label"],
                "predictions": {n: members[n]["prediction"] for n in MEMBERS},
            }
        )

    count = len(by_case)
    print(f"{count} cases (3 equations x {len(SEEDS)} seeds)\n")
    for name in MEMBERS:
        print(f"  {name:9s} mean {totals[name] / count:.3f}   exact {exact_counts[name]}/{count}")
    print(f"  {'vote':9s} mean {vote_total / count:.3f}   exact {vote_exact}/{count}")
    print(f"  {'oracle':9s} mean {oracle_total / count:.3f}   exact {oracle_exact}/{count}")

    agree = [r for r in records if r["agreement"] >= 2]
    print(
        f"\n  at least two members agreed on {len(agree)}/{count} cases; "
        f"when they did, mean {sum(r['vote_score'] for r in agree) / max(len(agree), 1):.3f}"
    )
    with open("ensemble.json", "w", encoding="utf-8") as handle:
        json.dump(records, handle, indent=1)


main()
