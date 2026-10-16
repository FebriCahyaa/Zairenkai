#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Fail CI/builds when the license issuer and kernel UAPI API majors diverge."""
from __future__ import annotations
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LICENSE_SCRIPT = ROOT / "tools/zkfc-license/zkfc_license.py"
UAPI_HEADER = ROOT / "kernel/include/uapi/linux/zkfc.h"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--print", dest="print_major", action="store_true", help="print the verified API major for workflow use")
    args = parser.parse_args()
    script = LICENSE_SCRIPT.read_text(encoding="utf-8")
    header = UAPI_HEADER.read_text(encoding="utf-8")
    sm = re.search(r"^API_MAJOR\s*=\s*(\d+)\s*$", script, re.M)
    hm = re.search(r"^#define\s+ZKFC_API_MAJOR\s+(\d+)\s*$", header, re.M)
    if not sm or not hm:
        print("error: API major definition is missing from the license issuer or kernel UAPI", file=sys.stderr)
        return 2
    script_major, kernel_major = int(sm.group(1)), int(hm.group(1))
    if script_major < 1 or kernel_major < 1 or script_major != kernel_major:
        print(f"error: API major mismatch: issuer={script_major}, kernel UAPI={kernel_major}", file=sys.stderr)
        return 2
    if args.print_major:
        print(script_major)
    else:
        print(f"ZKFC API major consistent: {script_major}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
