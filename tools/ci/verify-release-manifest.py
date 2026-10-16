#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Verify signed release metadata and every artifact hash listed within it."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

# Hyphenated script filenames are not importable through a normal Python import.
# Load the shared verifier explicitly so this tool works both in CI and standalone.
_verifier_spec = importlib.util.spec_from_file_location(
    "zairenkai_verify_metadata", Path(__file__).with_name("verify-metadata.py")
)
if _verifier_spec is None or _verifier_spec.loader is None:
    raise SystemExit("could not load verify-metadata.py")
_verifier_module = importlib.util.module_from_spec(_verifier_spec)
_verifier_spec.loader.exec_module(_verifier_module)
verify_signature = _verifier_module.verify

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PUBLIC_KEY = ROOT / "app/src/main/assets/security/update-metadata-public-key.pem"


def verify_manifest(manifest_path: Path, signature_path: Path, public_key_path: Path) -> int:
    verify_signature(manifest_path, signature_path, public_key_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("hash_algorithm") != "SHA-256":
        raise ValueError("unsupported release manifest schema or hash algorithm")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise ValueError("release manifest has no artifact entries")
    root = manifest_path.resolve().parent
    seen: set[str] = set()
    for entry in artifacts:
        if not isinstance(entry, dict):
            raise ValueError("malformed artifact entry")
        relative = entry.get("path")
        expected_hash = entry.get("sha256")
        expected_size = entry.get("size_bytes")
        if not isinstance(relative, str) or not relative or relative in seen:
            raise ValueError("missing or duplicate artifact path")
        seen.add(relative)
        path = (root / relative).resolve()
        if root not in path.parents or path == root:
            raise ValueError(f"artifact path escapes release directory: {relative}")
        if not isinstance(expected_hash, str) or len(expected_hash) != 64 or any(c not in "0123456789abcdef" for c in expected_hash):
            raise ValueError(f"invalid SHA-256 in manifest for {relative}")
        if not isinstance(expected_size, int) or expected_size < 0:
            raise ValueError(f"invalid size in manifest for {relative}")
        if not path.is_file():
            raise ValueError(f"release artifact is missing: {relative}")
        if path.stat().st_size != expected_size:
            raise ValueError(f"artifact size does not match manifest: {relative}")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != expected_hash:
            raise ValueError(f"artifact SHA-256 does not match manifest: {relative}")
    print(f"Verified signed release manifest and {len(artifacts)} artifact(s).")
    return len(artifacts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--signature", type=Path)
    parser.add_argument("--public-key", type=Path, default=DEFAULT_PUBLIC_KEY)
    args = parser.parse_args()
    signature_path = args.signature or Path(str(args.manifest) + ".sig")
    try:
        verify_manifest(args.manifest, signature_path, args.public_key)
    except Exception as exc:
        print(f"release verification failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
