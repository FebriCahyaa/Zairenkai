#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Importable implementation shared by the CLI and tests."""
from __future__ import annotations

VERSION_CODE_BASE = 1_000_000_000
TIMESTAMP_BASE = 1_500_000_000
MAX_ANDROID_VERSION_CODE = 2_100_000_000


def compute(timestamp: int) -> int:
    if timestamp < TIMESTAMP_BASE:
        raise ValueError("timestamp predates the supported module version-code epoch")
    value = VERSION_CODE_BASE + (timestamp - TIMESTAMP_BASE)
    if value > MAX_ANDROID_VERSION_CODE:
        raise ValueError("module version code exceeds Android's supported limit")
    return value
