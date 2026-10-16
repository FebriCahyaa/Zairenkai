#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Validate stable release tag monotonicity against existing stable tags."""
from __future__ import annotations

import re

STABLE_TAG = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


def parse_stable_tag(tag: str) -> tuple[int, int, int] | None:
    match = STABLE_TAG.fullmatch(tag)
    if not match:
        return None
    return tuple(map(int, match.groups()))


def validate_release_tag(tag: str, existing_tags: list[str]) -> tuple[bool, str]:
    candidate = parse_stable_tag(tag)
    if candidate is None:
        return False, "Release tags must be stable vMAJOR.MINOR.PATCH without prerelease suffixes."

    stable = [parsed for item in existing_tags if item != tag
              if (parsed := parse_stable_tag(item)) is not None]
    if not stable:
        return True, "No previous stable tag found; allowing the first stable release."

    latest = max(stable)
    if candidate <= latest:
        latest_tag = max(
            (item for item in existing_tags if item != tag and parse_stable_tag(item) == latest),
            default="v" + ".".join(map(str, latest)),
        )
        return False, f"Release tag {tag} must be newer than the latest stable tag {latest_tag}."
    return True, "Release tag is newer than all existing stable tags."
