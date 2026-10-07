#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
import json
import sys
from pathlib import Path

REQUIRED_PROVENANCE = {"run_id", "device_id", "kernel_release", "profile_id", "workload", "tool_version", "collected_at_unix_s"}
REQUIRED = {"metric", "value", "unit", "sample_index", "provenance"}


def validate(path: Path) -> int:
    total = 0
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        total += 1
        obj = json.loads(raw)
        missing = REQUIRED - obj.keys()
        if missing:
            raise ValueError(f"{path}:{line_no}: missing {sorted(missing)}")
        prov = obj["provenance"]
        if REQUIRED_PROVENANCE - prov.keys():
            raise ValueError(f"{path}:{line_no}: incomplete provenance")
        if not isinstance(obj["value"], (int, float)):
            raise ValueError(f"{path}:{line_no}: metric value must be numeric")
        if not obj["unit"] or not obj["metric"]:
            raise ValueError(f"{path}:{line_no}: metric and unit are required")
    return total


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: measurement_validate.py <jsonl>")
    path = Path(sys.argv[1])
    print(f"measurement validation passed: {validate(path)} records")


if __name__ == "__main__":
    main()
