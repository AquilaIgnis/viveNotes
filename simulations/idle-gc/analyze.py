"""Summarises an IdleGcCostTest run: what a GC forced at idle bought the job that followed.

Reads the CSV the instrumented test writes and prints a Markdown table per job, the two arms side by
side, plus the per-round paired difference with a bootstrap interval.

    python3 -I simulations/idle-gc/analyze.py simulations/idle-gc/results/<device>.csv
"""

from __future__ import annotations

import csv
import random
import statistics
import sys
from dataclasses import dataclass
from pathlib import Path

ARMS: tuple[str, str] = ("Idle", "Collected")
BOOTSTRAP_RESAMPLES: int = 10_000
SEED: int = 20261024


@dataclass(frozen=True)
class Sample:
    round: int
    job: str
    arm: str
    wall_ms: float
    gcs: int
    gc_ms: int
    blocking_gcs: int
    blocking_gc_ms: int
    waited_for_gc_ms: float
    allocated_mb: float
    free_before_mb: float
    used_before_mb: float
    idle_gc_wall_ms: float
    idle_gc_cpu_ms: float
    idle_gc_freed_mb: float
    cycle_cpu_ms: int


def read_samples(path: Path) -> list[Sample]:
    with path.open(newline="") as handle:
        return [parse_row(row) for row in csv.DictReader(handle)]


def parse_row(row: dict[str, str]) -> Sample:
    return Sample(
        round=int(row["round"]),
        job=row["job"],
        arm=row["arm"],
        wall_ms=float(row["wallMs"]),
        gcs=int(row["gcs"]),
        gc_ms=int(row["gcMs"]),
        blocking_gcs=int(row["blockingGcs"]),
        blocking_gc_ms=int(row["blockingGcMs"]),
        waited_for_gc_ms=float(row["waitedForGcMs"]),
        allocated_mb=float(row["allocatedMb"]),
        free_before_mb=float(row["freeBeforeMb"]),
        used_before_mb=float(row["usedBeforeMb"]),
        idle_gc_wall_ms=float(row["idleGcWallMs"]),
        idle_gc_cpu_ms=float(row["idleGcCpuMs"]),
        idle_gc_freed_mb=float(row["idleGcFreedMb"]),
        cycle_cpu_ms=int(row["cycleCpuMs"]),
    )


def quantile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    position = q * (len(ordered) - 1)
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def bootstrap_median_interval(values: list[float]) -> tuple[float, float]:
    """95% percentile interval of the median, resampling rounds with replacement."""
    generator = random.Random(SEED)
    medians = sorted(
        statistics.median(generator.choices(values, k=len(values)))
        for _ in range(BOOTSTRAP_RESAMPLES)
    )
    return quantile(medians, 0.025), quantile(medians, 0.975)


def paired(samples: list[Sample], job: str, value_of: str) -> list[float]:
    """Idle minus Collected for [value_of], one number per round that has both arms."""
    by_round: dict[int, dict[str, Sample]] = {}
    for sample in samples:
        if sample.job == job:
            by_round.setdefault(sample.round, {})[sample.arm] = sample
    differences: list[float] = []
    for arms in by_round.values():
        if all(arm in arms for arm in ARMS):
            differences.append(getattr(arms["Idle"], value_of) - getattr(arms["Collected"], value_of))
    return differences


def describe(values: list[float]) -> str:
    return f"{statistics.median(values):.1f} ({quantile(values, 0.25):.1f}–{quantile(values, 0.75):.1f})"


def arm_rows(samples: list[Sample], job: str) -> list[str]:
    rows: list[tuple[str, str, str]] = []
    runs: dict[str, list[Sample]] = {
        arm: [sample for sample in samples if sample.job == job and sample.arm == arm] for arm in ARMS
    }
    metrics: list[tuple[str, str]] = [
        ("job wall ms, median (IQR)", "wall_ms"),
        ("GCs during the job, mean", "gcs"),
        ("blocking GCs during the job, total", "blocking_gcs"),
        ("time threads waited for GC, ms total", "waited_for_gc_ms"),
        ("Java MB allocated by the job, median", "allocated_mb"),
        ("heap left before ART's next GC at job start, MB", "free_before_mb"),
        ("the idle GC itself, wall ms", "idle_gc_wall_ms"),
        ("the idle GC itself, CPU ms", "idle_gc_cpu_ms"),
        ("process CPU ms, idle second + job, median", "cycle_cpu_ms"),
    ]
    for label, field in metrics:
        cells: list[str] = []
        for arm in ARMS:
            values = [float(getattr(sample, field)) for sample in runs[arm]]
            if field in ("gcs",):
                cells.append(f"{statistics.mean(values):.2f}")
            elif field in ("blocking_gcs", "waited_for_gc_ms"):
                cells.append(f"{sum(values):.1f}")
            elif field == "wall_ms":
                cells.append(describe(values))
            else:
                cells.append(f"{statistics.median(values):.1f}")
        rows.append((label, cells[0], cells[1]))
    return [f"| {label} | {idle} | {collected} |" for label, idle, collected in rows]


def report(samples: list[Sample]) -> str:
    lines: list[str] = []
    for job in sorted({sample.job for sample in samples}):
        n = len([sample for sample in samples if sample.job == job and sample.arm == ARMS[0]])
        lines.append(f"### {job} — {n} rounds")
        lines.append("")
        lines.append("| | no idle GC | idle `Runtime.gc()` |")
        lines.append("|---|---|---|")
        lines.extend(arm_rows(samples, job))
        wall = paired(samples, job, "wall_ms")
        cpu = paired(samples, job, "cycle_cpu_ms")
        low, high = bootstrap_median_interval(wall)
        faster = sum(1 for difference in wall if difference > 0)
        lines.append("")
        lines.append(
            f"Paired by round, the job without the idle GC took **{statistics.median(wall):+.1f} ms** "
            f"versus with it (median; 95% bootstrap interval {low:+.1f} to {high:+.1f} ms). "
            f"The collected arm was faster in {faster} of {len(wall)} rounds. "
            f"Whole-process CPU, idle second plus job: {statistics.median(cpu):+.1f} ms "
            f"(no-GC minus GC; negative means the idle GC cost CPU overall)."
        )
        lines.append("")
    return "\n".join(lines)


def main(arguments: list[str]) -> None:
    for name in arguments:
        path = Path(name)
        print(f"## {path.stem}\n")
        print(report(read_samples(path)))


if __name__ == "__main__":
    main(sys.argv[1:])
