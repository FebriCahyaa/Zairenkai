#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Regression checks for the centralized Build Manager CI workflow layout."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
REUSABLE = (
    "android.yml",
    "native.yml",
    "atlas.yml",
    "clippy.yml",
    "rustfmt.yml",
    "shellcheck.yml",
)


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def check(condition: bool, message: str) -> None:
    if not condition:
        fail(message)
    print(f"PASS: {message}")


def main() -> int:
    orchestrator = WORKFLOWS / "build-manager-ci.yml"
    check(orchestrator.is_file(), "central Build Manager CI workflow exists")
    main_text = orchestrator.read_text(encoding="utf-8")

    for name in REUSABLE:
        path = WORKFLOWS / name
        check(path.is_file(), f"{name} exists")
        text = path.read_text(encoding="utf-8")
        trigger_block = text.split("permissions:", 1)[0]
        check(
            re.search(r"(?m)^  workflow_call:\s*$", trigger_block) is not None,
            f"{name} is reusable through workflow_call",
        )
        check(
            re.search(r"(?m)^  workflow_dispatch:\s*$", trigger_block) is not None,
            f"{name} retains manual workflow_dispatch",
        )
        check(
            re.search(r"(?m)^  (push|pull_request):", trigger_block) is None,
            f"{name} no longer duplicates central push/PR triggers",
        )
        check("ubuntu-latest" not in text, f"{name} pins runner OS where declared")

    referenced = re.findall(
        r"(?m)^\s+uses:\s+\.\/\.github\/workflows\/([A-Za-z0-9_.-]+\.yml)\s*$",
        main_text,
    )
    for name in referenced:
        check((WORKFLOWS / name).is_file(), f"orchestrator reference {name} exists")
    check(len(referenced) >= 7, "orchestrator fans out into reusable component workflows")
    check("workflow-contracts" in main_text, "orchestrator runs workflow contract tests")
    check("full_build" in main_text, "full KMI matrix is explicitly opt-in")
    check("Zairenkai-canary-validation-unsigned.apk" in main_text,
          "integrated APK is identified as unsigned CI validation output")
    check("spoof" not in main_text.lower(), "CI does not generate spoofed package variants")

    native_text = (WORKFLOWS / "native.yml").read_text(encoding="utf-8")
    atlas_text = (WORKFLOWS / "atlas.yml").read_text(encoding="utf-8")
    check("tools/atlas_validate.py" not in native_text,
          "Atlas validation is not duplicated inside native workflow")
    check("tools/profile_validate.py database" not in native_text,
          "device profile validation is not duplicated inside native workflow")
    check("tests/atlas_check.sh" in atlas_text,
          "Atlas workflow retains its dedicated validation")

    module_text = (WORKFLOWS / "build-module.yml").read_text(encoding="utf-8")
    check("module_version_code: ${{ steps.meta.outputs.module_version_code }}" in module_text,
          "module version code is exported by the validation job")
    check("Validated module channel=$CHANNEL version=$VERSION_NAME versionCode=$MODULE_VERSION_CODE" in module_text,
          "module validation logs a defined version-code variable")
    check('VERSION_CODE="${{ needs.validate.outputs.module_version_code }}"' in module_text,
          "module packaging reuses the validated version code")

    for path in WORKFLOWS.glob("*.yml"):
        check("ubuntu-latest" not in path.read_text(encoding="utf-8"),
              f"{path.name} has no floating ubuntu-latest runner label")

    print("All Build Manager CI contract checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
