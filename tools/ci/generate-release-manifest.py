#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Generate deterministic hash metadata for the files prepared by the release workflow."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def api_major() -> int:
    source = ROOT / "tools/zkfc-license/zkfc_license.py"
    match = re.search(r"^API_MAJOR\s*=\s*(\d+)\s*$", source.read_text(encoding="utf-8"), re.M)
    if not match:
        raise ValueError("API_MAJOR not found in tools/zkfc-license/zkfc_license.py")
    return int(match.group(1))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="Zairenkai-release-manifest.json")
    parser.add_argument("--version", default=os.environ.get("GITHUB_REF_NAME", "local"))
    parser.add_argument("--commit", default=os.environ.get("GITHUB_SHA", "local"))
    parser.add_argument("--run-id", default=os.environ.get("GITHUB_RUN_ID", "local"))
    parser.add_argument("--run-attempt", default=os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
    args = parser.parse_args()

    # app-apks.yml publishes the stable updater name, while the tag-driven
    # release.yml uses an explicit "standard" name. Bind whichever is present.
    standard_apk = ROOT / "Zairenkai-standard-release.apk"
    if not standard_apk.is_file():
        standard_apk = ROOT / "Zairenkai-release.apk"
    candidates = [
        ROOT / "Zairenkai-module.zip",
        standard_apk,
        ROOT / "Zairenkai-spoofed-release.apk",
        ROOT / "Zairenkai-module-SHA256SUMS",
    ]
    files = []
    for path in candidates:
        if not path.is_file():
            continue
        files.append({
            "path": path.relative_to(ROOT).as_posix(),
            "size_bytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    required = {
        "Zairenkai-module.zip",
        standard_apk.name,
        "Zairenkai-spoofed-release.apk",
        "Zairenkai-module-SHA256SUMS",
    }
    missing = required - {item["path"] for item in files}
    if missing:
        print("error: required release files are missing: " + ", ".join(sorted(missing)), file=sys.stderr)
        return 2

    manifest = {
        "schema_version": 1,
        "product": "Zairenkai",
        "version": args.version,
        "api": {"major": api_major()},
        "build": {
            "commit": args.commit,
            "run_id": str(args.run_id),
            "run_attempt": str(args.run_attempt),
            "workflow": os.environ.get("GITHUB_WORKFLOW", "local"),
        },
        "hash_algorithm": "SHA-256",
        "artifacts": files,
    }
    out = (ROOT / args.output).resolve()
    if ROOT not in out.parents:
        print("error: output must remain inside the repository checkout", file=sys.stderr)
        return 2
    out.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Wrote {out.relative_to(ROOT)} with {len(files)} artifact entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
