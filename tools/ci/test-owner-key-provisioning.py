#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Fail CI/release if the kernel owner key is marked missing or malformed."""
from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HEADER = ROOT / "kernel/security/zkfc_owner_key.h"


def main() -> int:
    try:
        source = HEADER.read_text(encoding="utf-8")
    except OSError as exc:
        print(f"FAIL: cannot read owner key header: {exc}", file=sys.stderr)
        return 1

    if not re.search(r"(?m)^\s*#define\s+ZKFC_OWNER_KEY_PROVISIONED\s+1\s*(?:/\*.*\*/)?$", source):
        print("FAIL: ZKFC_OWNER_KEY_PROVISIONED must be 1 before a production module can be shipped.", file=sys.stderr)
        return 1

    match = re.search(r"zkfc_owner_pubkey\s*\[\s*32\s*\]\s*=\s*\{([^}]*)\}", source, re.S)
    if not match:
        print("FAIL: expected a 32-byte zkfc_owner_pubkey definition.", file=sys.stderr)
        return 1
    values = re.findall(r"0x([0-9a-fA-F]{2})", match.group(1))
    if len(values) != 32:
        print(f"FAIL: owner public key has {len(values)} bytes, expected 32.", file=sys.stderr)
        return 1
    public_key = bytes(int(value, 16) for value in values)
    if not any(public_key):
        print("FAIL: owner public key must not be all zeroes.", file=sys.stderr)
        return 1
    fingerprint = hashlib.sha512(public_key).digest()[:32].hex()
    print(f"PASS: owner public key is provisioned (Ed25519 fingerprint {fingerprint}).")
    print("NOTE: the running device must install this newly built module and reboot; this source check cannot inspect its current kernel.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
