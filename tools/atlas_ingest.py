#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Verify a local Atlas provider bundle before it enters the database."""
from __future__ import annotations

import argparse
import hashlib
import time
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("bundle", type=Path)
    ap.add_argument("--database", type=Path, default=ROOT / "database")
    args = ap.parse_args()
    data = tomllib.loads(args.bundle.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise SystemExit("FAIL atlas ingest: unsupported bundle schema")
    source_id = data.get("source_id")
    if not isinstance(source_id, str) or not source_id:
        raise SystemExit("FAIL atlas ingest: source_id is required")
    registry = tomllib.loads((args.database / "registry/sources.toml").read_text(encoding="utf-8"))
    sources = {x["id"]: x for x in registry.get("source", [])}
    if source_id not in sources:
        raise SystemExit(f"FAIL atlas ingest: unknown source {source_id}")
    if data.get("trust_override"):
        raise SystemExit("FAIL atlas ingest: trust_override is forbidden")
    revision = data.get("revision")
    digest = data.get("snapshot_sha256")
    payload = data.get("payload")
    if not isinstance(revision, str) or not revision:
        raise SystemExit("FAIL atlas ingest: immutable revision is required")
    if not isinstance(digest, str) or len(digest) != 64 or any(c not in "0123456789abcdefABCDEF" for c in digest):
        raise SystemExit("FAIL atlas ingest: snapshot_sha256 must be a SHA-256 digest")
    if not isinstance(payload, str) or Path(payload).is_absolute() or ".." in Path(payload).parts:
        raise SystemExit("FAIL atlas ingest: payload must be a safe relative path")
    payload_path = args.bundle.parent / payload
    if not payload_path.is_file():
        raise SystemExit(f"FAIL atlas ingest: missing payload {payload}")
    actual = sha256_file(payload_path)
    if actual.lower() != digest.lower():
        raise SystemExit("FAIL atlas ingest: snapshot digest mismatch")
    expires = data.get("expires_at_unix_s")
    if expires is not None and (not isinstance(expires, int) or expires <= int(time.time())):
        raise SystemExit("FAIL atlas ingest: provider bundle is expired")
    print(f"PASS atlas ingest: source={source_id} revision={revision} sha256={actual}")
    return 0


if __name__ == "__main__":
    main()
