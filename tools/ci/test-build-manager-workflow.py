#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Regression checks for Build Manager CI matrix, signing and publication boundaries."""
from __future__ import annotations
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"

def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)

def check(condition: bool, message: str) -> None:
    if not condition:
        fail(message)
    print(f"PASS: {message}")

def main() -> int:
    ci_path = WORKFLOWS / "build-manager-ci.yml"
    ci = ci_path.read_text(encoding="utf-8")
    builder_path = WORKFLOWS / "build-manager.yml"
    builder = builder_path.read_text(encoding="utf-8")
    module = (WORKFLOWS / "build-module.yml").read_text(encoding="utf-8")
    publisher = (WORKFLOWS / "app-apks.yml").read_text(encoding="utf-8")
    release = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
    gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")

    check(builder_path.is_file(), "single-variant reusable APK builder exists")
    check("workflow_call:" in builder and "require_signing:" in builder, "builder is reusable and has explicit signing policy")
    check("full_build" not in ci, "KMI and APK matrix are not gated by a manual-only full_build input")
    check("if: ${{ github.event_name == 'workflow_dispatch' && inputs.full_build == true }}" not in ci,
          "module and integrated APK jobs cannot be skipped on push/dispatch by full_build condition")
    check("uses: ./.github/workflows/build-module.yml" in ci, "Build Manager CI owns the KMI/module build")
    check("channel: both" in ci and "allow_unpublished_release: true" in ci,
          "Build Manager CI requests both module channels from one shared KMI matrix")
    check("fromJSON(inputs.channel == 'both'" in module and "package-channel:" in module,
          "module workflow dynamically fans out only requested channels after the one shared KMI matrix")
    check("uses: ./.github/workflows/build-manager.yml" in ci, "Build Manager CI fans out to the reusable APK builder")
    for channel in ("canary", "release"):
        check(f"channel: {channel}" in ci, f"central APK matrix includes {channel} channel")
        for variant in ("standard", "spoof"):
            check(f"variant: {variant}" in ci, f"central APK matrix includes {channel}/{variant} row")
            check(f"variant: {variant}" in ci and f"channel: {channel}" in ci,
                  f"the {channel}/{variant} row is independently represented in the CI matrix")
    check('TASK=":app:assemble${FLAVOR}${BUILD_TYPE}"' in builder,
          "each reusable matrix call compiles exactly its selected flavor and build type")
    check("tools/ci/verify-release-apk.sh" in builder, "signed matrix APK is verified against the configured keystore")
    check("ZK_KEYSTORE_BASE64" in builder and "ZK_KEY_ALIAS" in builder, "APK matrix uses actual signing secrets")
    check("actions/upload-artifact@v7.0.1" in builder and "zairenkai-${{ inputs.channel }}-${{ inputs.variant }}" in builder,
          "each APK matrix row uploads a distinct artifact")
    check("unsigned-validation" in builder and "PR validation artifact is intentionally unsigned" in builder,
          "only PRs can produce explicitly labeled unsigned validation artifacts")
    check('beforeVariants(selector().withBuildType("debug"))' in gradle,
          "Android debug variants are disabled at variant creation")
    check("assembleDebug" not in ci + builder + publisher + release and "bundleDebug" not in ci + builder + publisher + release,
          "Build Manager and distribution workflows do not invoke debug tasks")

    check("matrix:" in module and "android17-6.18" in module and "android12-5.10" in module,
          "module reusable workflow owns the full KMI matrix")
    check("Expected 16 KMI/architecture kernel modules" in module and "name: kmi-bundle" in module and "name: module-zip-${{ matrix.channel }}" in module,
          "module workflow verifies one KMI bundle and uploads channel-specific flashable module artifacts")
    check('(cd "module/lkm/$kmi-$abidir" && sha256sum zkfc.ko > zkfc.ko.sha256)' in module,
          "KMI checksum sidecars use valid relative paths")
    restore_step = module.split("- name: Restore module tree", 1)[1].split("- name: Stamp channel-specific module update metadata", 1)[0]
    check('unzip -t "$ARCHIVE"' in restore_step and 'unzip -q "$ARCHIVE" -d "$RESTORE_DIR"' in restore_step,
          "module base archive is validated and restored into a clean staging directory")
    check('rm -rf module' in restore_step and 'mv "$RESTORE_DIR/module" module' in restore_step,
          "module restore replaces checkout files without interactive unzip overwrite prompts")
    check("license-server-validation.invalid" in module and "smoke-license-server.py" not in module,
          "KMI/module compilation is independent of live-server availability; publishers enforce readiness")

    check("workflow_run:" in publisher and "Build Manager CI" in publisher,
          "Canary publisher consumes completed Build Manager CI runs")
    check("uses: ./.github/workflows/build-module.yml" not in publisher and "build-module.yml" not in publisher,
          "Canary publisher no longer builds KMI or flashable module")
    check("actions/download-artifact@v7.0.1" in publisher and "zairenkai-canary-standard-signed" in publisher and "zairenkai-canary-spoof-signed" in publisher,
          "Canary publisher downloads the two separately signed matrix APK artifacts")
    check("Zairenkai-KMI-modules.zip" in publisher and "module-update-canary.json" in publisher and "name: module-zip-canary" in publisher and "name: kmi-bundle" in publisher,
          "Canary publisher attaches Canary module metadata and the shared KMI artifact from Build Manager CI")
    check("uses: ./.github/workflows/build-module.yml" in release and "uses: ./.github/workflows/build-manager.yml" in release,
          "stable Release orchestrates module and APK builder workflows")
    check("name: module-zip-release" in release and "name: kmi-bundle" in release,
          "stable Release downloads the Release-channel module and shared KMI artifact")
    check("group: module-${{ github.workflow }}-${{ github.ref }}-${{ inputs.channel || 'canary' }}" in module,
          "module workflow concurrency remains isolated by caller/channel")
    check("matrix:" in release and "variant: [standard, spoof]" in release,
          "stable Release builds Standard and Spoof in separate matrix jobs")
    check(":app:assembleStandardRelease" not in release and ":app:assembleSpoofRelease" not in release,
          "release aggregator does not compile both APKs in one monolithic job")
    check("zairenkai-release-standard-signed" in release and "zairenkai-release-spoof-signed" in release,
          "Release publisher consumes separate verified APK matrix artifacts")
    check("smoke-license-server.py" in publisher and "smoke-license-server.py" in release and "smoke-license-server.py" not in module,
          "publishers gate distribution on live License Server readiness while KMI compilation remains independent")
    check("license_server_url" in builder and "license-server-validation.invalid" in builder,
          "APK validation builds still compile if the live License Server URL is not configured")
    check("module-zip-${{ inputs.channel }}" in builder and "name: kmi-bundle" in builder,
          "each APK matrix row consumes its channel-specific module and the shared verified KMI bundle")
    check("require_signing: ${{ github.event_name != 'pull_request' }}" in ci,
          "push/manual matrix rows require signing; pull request rows are explicitly validation-only")
    check(publisher.count("smoke-update-server.py") >= 2 and release.count("smoke-update-server.py") >= 2,
          "Canary and Release verify app and module updater manifests post-publication")
    check("Zairenkai-CHANGELOG.md" in publisher and "generate-changelog.py" in publisher and "generate-changelog.py" in release,
          "Canary and Release changelogs are generated from Git history")

    for name in ("android.yml", "native.yml", "atlas.yml", "clippy.yml", "rustfmt.yml", "shellcheck.yml"):
        text = (WORKFLOWS / name).read_text(encoding="utf-8")
        trigger_block = text.split("permissions:", 1)[0]
        check(re.search(r"(?m)^  workflow_call:\s*$", trigger_block) is not None,
              f"{name} remains callable by the central orchestrator")
        check("ubuntu-latest" not in text, f"{name} does not use a floating ubuntu-latest runner")
    for path in WORKFLOWS.glob("*.yml"):
        check("ubuntu-latest" not in path.read_text(encoding="utf-8"), f"{path.name} pins runner OS where declared")
    print("All Build Manager matrix/signing/publication contract checks passed.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
