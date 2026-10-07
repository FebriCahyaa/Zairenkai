#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Analyze Zairenkai v1 JSONL measurement runs.

The tool is intentionally evidence-first: it never invents a device score or
claims a tuning is faster without measured samples. It produces robust summary
statistics and compares two runs under the same workload/device identity.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Iterable

METRICS = {
    "fps_p50": "higher",
    "fps_p95": "higher",
    "jank_percent": "lower",
    "frame_time_p95_ms": "lower",
    "package_power_mw": "lower",
    "hottest_mdeg": "lower",
    "cpu_psi_some_avg10": "lower",
    "memory_psi_some_avg10": "lower",
    "io_psi_some_avg10": "lower",
}


def load(path: Path) -> list[dict]:
    rows: list[dict] = []
    with path.open(encoding="utf-8") as fh:
        for line_no, line in enumerate(fh, 1):
            if not line.strip():
                continue
            obj = json.loads(line)
            if not isinstance(obj, dict):
                raise ValueError(f"{path}:{line_no}: sample must be an object")
            if obj.get("schema_version") != 1:
                raise ValueError(f"{path}:{line_no}: schema_version must be 1")
            if not isinstance(obj.get("device_id"), str) or not obj["device_id"]:
                raise ValueError(f"{path}:{line_no}: device_id is required")
            if not isinstance(obj.get("kernel_release"), str) or not obj["kernel_release"]:
                raise ValueError(f"{path}:{line_no}: kernel_release is required")
            if obj.get("kernel_flavor") not in {"gki", "non-gki", "unknown"}:
                raise ValueError(f"{path}:{line_no}: invalid kernel_flavor")
            if not isinstance(obj.get("workload"), str) or not obj["workload"]:
                raise ValueError(f"{path}:{line_no}: workload is required")
            if not isinstance(obj.get("profile"), str) or not obj["profile"]:
                raise ValueError(f"{path}:{line_no}: profile is required")
            t = obj.get("t_ms")
            if not isinstance(t, (int, float)) or not math.isfinite(float(t)) or t < 0:
                raise ValueError(f"{path}:{line_no}: t_ms must be a finite non-negative number")
            rows.append(obj)
    if not rows:
        raise ValueError(f"{path}: no samples")
    return rows


def numeric(rows: Iterable[dict], key: str) -> list[float]:
    out = []
    for row in rows:
        value = row.get(key)
        if isinstance(value, (int, float)) and math.isfinite(float(value)):
            out.append(float(value))
    return out


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    idx = (len(values) - 1) * p
    lo = math.floor(idx)
    hi = math.ceil(idx)
    if lo == hi:
        return values[lo]
    return values[lo] + (values[hi] - values[lo]) * (idx - lo)


def slope_per_min(rows: list[dict], key: str) -> float | None:
    pairs = [(float(r["t_ms"]) / 60000.0, float(r[key])) for r in rows if isinstance(r.get(key), (int, float))]
    if len(pairs) < 2:
        return None
    xs = [x for x, _ in pairs]
    ys = [y for _, y in pairs]
    xm = statistics.mean(xs)
    ym = statistics.mean(ys)
    den = sum((x - xm) ** 2 for x in xs)
    if den == 0:
        return 0.0
    return sum((x - xm) * (y - ym) for x, y in pairs) / den


def identity(rows: list[dict]) -> dict:
    keys = ["device_id", "kernel_release", "kernel_flavor", "workload"]
    return {k: rows[0][k] if all(r.get(k) == rows[0].get(k) for r in rows) else "mixed" for k in keys}


def summary(rows: list[dict]) -> dict:
    times = numeric(rows, "t_ms")
    out: dict = {
        "schema_version": 1,
        "samples": len(rows),
        "identity": identity(rows),
        "duration_ms": (max(times) - min(times)) if times else 0.0,
        "metrics": {},
        "analysis_policy": "descriptive-not-statistical-significance",
    }
    for key in METRICS:
        values = numeric(rows, key)
        if not values:
            continue
        median = statistics.median(values)
        mad = statistics.median([abs(v - median) for v in values])
        out["metrics"][key] = {
            "count": len(values),
            "coverage_pct": len(values) / len(rows) * 100.0,
            "mean": statistics.fmean(values),
            "median": median,
            "mad": mad,
            "p95": percentile(values, 0.95),
            "min": min(values),
            "max": max(values),
        }
    slope = slope_per_min(rows, "hottest_mdeg")
    if slope is not None:
        out["thermal_slope_mdeg_per_min"] = slope
    return out


def compatible(a: dict, b: dict) -> tuple[bool, str]:
    for key in ("device_id", "kernel_release", "workload"):
        if a["identity"].get(key) != b["identity"].get(key):
            return False, f"{key} differs"
    return True, "compatible"


def compare(a: dict, b: dict) -> dict:
    ok, reason = compatible(a, b)
    if not ok:
        return {"comparable": False, "reason": reason}
    deltas: dict[str, dict] = {}
    for key, direction in METRICS.items():
        av = a["metrics"].get(key, {}).get("median")
        bv = b["metrics"].get(key, {}).get("median")
        if av is None or bv is None or av == 0:
            continue
        delta = (bv - av) / abs(av) * 100.0
        better = (delta > 0) if direction == "higher" else (delta < 0)
        deltas[key] = {"baseline_median": av, "candidate_median": bv, "change_pct": delta, "better": better}
    improved = [k for k, v in deltas.items() if v["better"]]
    regressed_or_flat = [k for k, v in deltas.items() if not v["better"]]
    worse = [
        key for key, value in deltas.items()
        if (value["change_pct"] < 0 if METRICS[key] == "higher" else value["change_pct"] > 0)
    ]
    no_power_regression = "package_power_mw" not in deltas or "package_power_mw" not in worse
    no_thermal_regression = "hottest_mdeg" not in deltas or "hottest_mdeg" not in worse
    if "fps_p50" in deltas and deltas["fps_p50"]["better"] and "package_power_mw" not in deltas:
        verdict = "performance_improvement_without_power_measurement"
    elif "fps_p50" in deltas and deltas["fps_p50"]["better"] and no_thermal_regression and no_power_regression:
        verdict = "candidate_improves_measured_performance_with_no_measured_thermal_or_power_regression"
    elif worse:
        verdict = "measured_regression_or_tradeoff_requires_review"
    else:
        verdict = "insufficient_evidence"
    return {
        "comparable": True,
        "comparison_policy": "descriptive-median-change",
        "deltas": deltas,
        "improved_metrics": improved,
        "regressed_or_not_better": regressed_or_flat,
        "measured_worse_metrics": worse,
        "verdict": verdict,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    p_sum = sub.add_parser("summary")
    p_sum.add_argument("run", type=Path)
    p_cmp = sub.add_parser("compare")
    p_cmp.add_argument("baseline", type=Path)
    p_cmp.add_argument("candidate", type=Path)
    args = ap.parse_args()
    if args.command == "summary":
        print(json.dumps(summary(load(args.run)), indent=2, sort_keys=True))
        return 0
    baseline = summary(load(args.baseline))
    candidate = summary(load(args.candidate))
    result = compare(baseline, candidate)
    result["baseline"] = baseline
    result["candidate"] = candidate
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["comparable"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
