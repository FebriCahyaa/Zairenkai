#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Compute monotonic Android version codes for isolated Release and Canary IDs."""
from __future__ import annotations
import argparse
import re
import sys

MAX_ANDROID_VERSION_CODE = 2_100_000_000
CANARY_OFFSET = 1_000_000_000
VERSION_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def compute(version_name: str, channel: str, run_number: int | None = None) -> int:
    if channel == "canary":
        if run_number is None or run_number < 1:
            raise ValueError("Canary requires a positive GitHub Actions run number")
        value = CANARY_OFFSET + run_number
    else:
        match = VERSION_RE.fullmatch(version_name)
        if not match:
            raise ValueError("Release version must be a stable semantic version X.Y.Z without a prerelease suffix")
        major, minor, patch = map(int, match.groups())
        if major > 999 or minor > 999 or patch > 999:
            raise ValueError("Release version components must each be <= 999")
        value = major * 1_000_000 + minor * 1_000 + patch
        if value < 1 or value >= CANARY_OFFSET:
            raise ValueError("Release version code is outside the supported range")
    if value < 1 or value > MAX_ANDROID_VERSION_CODE:
        raise ValueError("Computed version code exceeds Android's supported limit")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--channel", choices=("canary", "release"), required=True)
    parser.add_argument("--run-number", type=int)
    args = parser.parse_args()
    try:
        print(compute(args.version_name, args.channel, args.run_number))
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
