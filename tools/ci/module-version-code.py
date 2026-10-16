#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Generate one monotonic module version code shared by Canary and Release.

Root modules have one module ID across channels, unlike the app's separate
Canary application ID. A timestamp-derived code avoids making every Canary
build newer than every stable Release build, which can otherwise prevent a
manager from switching back to the stable channel automatically.
"""
from __future__ import annotations

import argparse
import sys
import time
from module_version_code import compute


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--timestamp",
        type=int,
        help="Unix timestamp for reproducible tests; defaults to the current UTC epoch second",
    )
    args = parser.parse_args()
    timestamp = int(time.time()) if args.timestamp is None else args.timestamp
    try:
        print(compute(timestamp))
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
