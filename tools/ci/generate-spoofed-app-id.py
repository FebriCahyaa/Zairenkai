#!/usr/bin/env python3
"""Generate a fresh three-segment package ID for Zairenkai spoofed builds."""
from __future__ import annotations
import re
import secrets
import string

SEGMENT_LENGTH = 6
SEGMENT_COUNT = 3
ALPHABET = string.ascii_lowercase


def main() -> None:
    segments = [
        "".join(secrets.choice(ALPHABET) for _ in range(SEGMENT_LENGTH))
        for _ in range(SEGMENT_COUNT)
    ]
    application_id = ".".join(segments)
    if re.fullmatch(r"[a-z]{6}\.[a-z]{6}\.[a-z]{6}", application_id) is None:
        raise SystemExit("generated an invalid Android application ID")
    print(application_id)


if __name__ == "__main__":
    main()
