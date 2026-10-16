#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Validate the current stable Release tag against all existing Git tags."""
from __future__ import annotations

import argparse
import subprocess
import sys
from release_tag import validate_release_tag


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    try:
        output = subprocess.check_output(
            ["git", "tag", "--list", "v*"], text=True, stderr=subprocess.STDOUT
        )
    except subprocess.CalledProcessError as exc:
        print(f"error: unable to enumerate Git tags: {exc.output}", file=sys.stderr)
        return 2
    ok, message = validate_release_tag(args.tag, output.splitlines())
    if not ok:
        print(f"error: {message}", file=sys.stderr)
        return 1
    print(message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
