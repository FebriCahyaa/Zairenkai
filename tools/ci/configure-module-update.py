#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Stamp a built Zairenkai module and generate its manager update metadata."""
from __future__ import annotations
import argparse
import json
import re
import sys
from pathlib import Path

REPO = "FebriCahyaa/Zairenkai"
CHANNELS = {"canary", "release"}
VERSION_RE = re.compile(r"v?\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module-dir", type=Path, required=True)
    parser.add_argument("--channel", choices=sorted(CHANNELS), required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--zip-url", required=True)
    parser.add_argument("--changelog-url", required=True)
    parser.add_argument("--metadata-output", type=Path, required=True)
    args = parser.parse_args()

    if not VERSION_RE.fullmatch(args.version_name):
        print("error: invalid semantic version", file=sys.stderr)
        return 2
    if not 1 <= args.version_code <= 2_100_000_000:
        print("error: version code is outside Android's supported range", file=sys.stderr)
        return 2
    server_url = args.server_url.rstrip("/")
    if not re.fullmatch(r"https://[A-Za-z0-9.-]+(?::443)?", server_url):
        print("error: server URL must be an HTTPS origin without a path", file=sys.stderr)
        return 2

    zip_url = args.zip_url
    if not zip_url.startswith(f"https://github.com/{REPO}/releases/") or not zip_url.endswith("/Zairenkai-module.zip"):
        print("error: module ZIP URL must be the official Zairenkai Releases asset", file=sys.stderr)
        return 2
    if not args.changelog_url.startswith(f"https://github.com/{REPO}/releases/"):
        print("error: changelog URL must point to official Zairenkai Releases", file=sys.stderr)
        return 2

    module_dir = args.module_dir.resolve()
    prop = module_dir / "module.prop"
    if not prop.is_file():
        print("error: module.prop not found", file=sys.stderr)
        return 2
    update_url = f"{server_url}/v1/updates/module/{args.channel}"
    replacements = {
        "version": f"v{args.version_name.lstrip('v')}",
        "versionCode": str(args.version_code),
        "updateJson": update_url,
    }
    lines = prop.read_text(encoding="utf-8").splitlines()
    found: set[str] = set()
    result: list[str] = []
    for line in lines:
        key, sep, _value = line.partition("=")
        if sep and key in replacements:
            result.append(f"{key}={replacements[key]}")
            found.add(key)
        else:
            result.append(line)
    for key in replacements:
        if key not in found:
            result.append(f"{key}={replacements[key]}")
    prop.write_text("\n".join(result) + "\n", encoding="utf-8")

    metadata = {
        "version": replacements["version"],
        "versionCode": args.version_code,
        "zipUrl": zip_url,
        "changelog": args.changelog_url,
    }
    output = args.metadata_output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Configured module channel={args.channel}, version={metadata['version']}, versionCode={args.version_code}")
    print(f"Module update metadata: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
