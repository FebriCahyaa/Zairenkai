#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Generate a KernelSU-Next-style randomized package ID for one Spoof build."""
from __future__ import annotations
import secrets
import string

ALPHABET = string.ascii_lowercase

def token(length: int = 6) -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(length))

def main() -> int:
    # Each ID is generated once per workflow run and passed unchanged to Gradle.
    # A different ID per official build intentionally prevents app updates from
    # replacing the standard package; therefore Spoof builds disable in-app updates.
    package_id = ".".join(token() for _ in range(3))
    print(package_id)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
