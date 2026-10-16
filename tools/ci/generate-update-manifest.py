#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Create the exact JSON payload used by the signed in-app update channel."""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--apk-url", required=True)
    parser.add_argument("--api-major", required=True, type=int)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    apk = args.apk.resolve()
    out = args.output.resolve()
    if not apk.is_file() or apk.stat().st_size <= 0:
        print("error: APK does not exist or is empty", file=sys.stderr)
        return 2
    if args.version_code < 1 or args.api_major < 1:
        print("error: version-code and API major must be positive", file=sys.stderr)
        return 2
    if not re.fullmatch(r"v?\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?", args.version_name):
        print("error: version name must be a semantic version", file=sys.stderr)
        return 2
    if not re.fullmatch(r"[0-9a-fA-F]{7,64}", args.commit):
        print("error: commit must be a Git SHA", file=sys.stderr)
        return 2
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,160}", args.build_id):
        print("error: invalid build ID", file=sys.stderr)
        return 2
    if not args.apk_url.startswith("https://github.com/") or "/releases/" not in args.apk_url:
        print("error: APK URL must point to a GitHub Releases asset", file=sys.stderr)
        return 2
    digest_state = hashlib.sha256()
    with apk.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest_state.update(chunk)
    digest = digest_state.hexdigest()
    data = {
        "schemaVersion": 1,
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": args.apk_url,
        "sha256": digest,
        "apiMajor": args.api_major,
        "buildId": args.build_id,
        "commit": args.commit.lower(),
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Wrote {out.name}: version={args.version_name}, apiMajor={args.api_major}, sha256={digest}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
