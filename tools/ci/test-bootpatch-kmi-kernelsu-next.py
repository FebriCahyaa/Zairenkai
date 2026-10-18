#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Regression checks for KernelSU-Next-compatible KMI detection and boot patch flow."""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXPECTED_KMIS = [
    "android12-5.10",
    "android13-5.10",
    "android13-5.15",
    "android14-5.15",
    "android14-6.1",
    "android15-6.6",
    "android16-6.12",
    "android17-6.18",
]


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def check(condition: bool, message: str) -> None:
    if not condition:
        fail(message)
    print(f"PASS: {message}")


def main() -> int:
    matrix_path = ROOT / "kernel/gki/kmi_matrix.txt"
    matrix_text = matrix_path.read_text(encoding="utf-8")
    matrix = [
        line.split()[0]
        for line in matrix_text.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    check(matrix == EXPECTED_KMIS, "KMI database matches the eight supported KernelSU-Next DDK branches")

    workflow = (ROOT / ".github/workflows/build-lkm.yml").read_text(encoding="utf-8")
    orchestrator = (ROOT / ".github/workflows/build-manager-ci.yml").read_text(encoding="utf-8")
    check("python3 tools/ci/test-bootpatch-kmi-kernelsu-next.py" in orchestrator,
          "CI executes the KMI/boot-patch compatibility regression checks")
    block = re.search(r"(?ms)^\s*kmi:\s*\n(?P<body>.*?)(?=^\s{4}uses:)", workflow)
    if not block:
        fail("build-lkm.yml has a readable KMI matrix")
    workflow_kmis = re.findall(r"(?m)^\s*-\s*(android\d+-\d+\.\d+)\s*$", block.group("body"))
    check(workflow_kmis == EXPECTED_KMIS, "DDK workflow matrix stays aligned with the canonical KMI database")

    rust = (ROOT / "native/bootpatch/src/main.rs").read_text(encoding="utf-8")
    supported = re.search(r"(?ms)const SUPPORTED_KMIS: &\[&str\] = &\[(.*?)\];", rust)
    if not supported:
        fail("bootpatch exposes an explicit supported KMI list")
    rust_kmis = re.findall(r'"(android\d+-\d+\.\d+)"', supported.group(1))
    check(rust_kmis == EXPECTED_KMIS, "native boot patcher accepts exactly the supported KMI matrix")
    check('Regex::new(r"(\\d+\\.\\d+)(?:\\S+)?(android\\d+)")' in rust,
          "KMI parsing follows KernelSU-Next Android branch + Linux major/minor normalization")
    check("fn parse_kmi_from_module(data: &[u8])" in rust and 'DETECTED_KMI_SOURCE={}' in rust,
          "vendor module vermagic can supply KMI fallback and reports detection provenance")

    extract = rust.split("fn extract_ramdisk(", 1)[1].split("fn verify_output(", 1)[0]
    check('entry.get_name_raw() == b"init_boot"' in extract and "entry.get_name_raw().is_empty()" in extract,
          "vendor_boot selects init_boot-named ramdisk first, then unnamed generic ramdisk")
    check("replace_vendor_ramdisk(index" in rust and "BootImageVersion::Vendor(_)" in rust,
          "vendor_boot repacking replaces only the selected generic ramdisk fragment")
    check("allow_unverified_kmi" in rust and 'Ok("manual")' in rust and "--allow-unverified-kmi" in rust,
          "branchless kernels permit manual KMI only with explicit opt-in and matching Linux major/minor")
    check("trusted_bundled_module" in rust and "--trusted-bundled-module" in rust,
          "native helper has a distinct trust marker for KMI-specific bundled module assets")
    check("init_wrapper=none" in rust and "sys.boot_completed=1" in rust and "INIT_RC_BACKUP_ENTRY" in rust,
          "patcher preserves PID 1 and delays ZKFC loading until Android boot completion")
    check("never flashes a" in rust and "block device" in rust,
          "file patching remains non-destructive and does not silently flash a partition")

    catalog = (ROOT / "app/src/main/java/com/zairenkai/app/data/KernelKmiCatalog.kt").read_text(encoding="utf-8")
    catalog_kmis = re.findall(r'KernelKmiTarget\("(android\d+-\d+\.\d+)"', catalog)
    check(catalog_kmis == EXPECTED_KMIS, "both Android KMI UI flows share the canonical KMI matrix")
    installer_app = (ROOT / "app/src/main/java/com/zairenkai/app/data/EmbeddedModuleInstaller.kt").read_text(encoding="utf-8")
    check("target-kmi.pending" in installer_app and "ZAIRENKAI_TARGET_KMI" in installer_app,
          "app passes the selected KMI through both root-manager environment and protected handoff file")
    check('KSU_NEXT_PACKAGE = "com.rifsxd.ksunext"' in installer_app and 'KSU_NEXT_CLI_NAME = "libksud.so"' in installer_app,
          "module installer discovers the version-matched KernelSU-Next Manager CLI")
    check("KernelSU-Next-Manager-libksud" in installer_app and "module-install.log" in installer_app and 'tail -n 160' in installer_app,
          "module install captures the root manager's real error tail in a protected diagnostic log")

    repository = (ROOT / "app/src/main/java/com/zairenkai/app/data/boot/BootPatchRepository.kt").read_text(encoding="utf-8")
    sheet = (ROOT / "app/src/main/java/com/zairenkai/app/ui/home/ZairenkaiBootPatchSheet.kt").read_text(encoding="utf-8")
    check("--kmi-reference-module" in repository and "DETECTED_KMI_SOURCE=" in repository,
          "Android repository passes vendor .ko reference to the native detector and reads KMI provenance")
    check("--trusted-bundled-module" in repository,
          "Android repository marks only KMI/ABI-specific bundled module assets as trusted sources")
    service = (ROOT / "module/service.sh").read_text(encoding="utf-8")
    installer = (ROOT / "module/customize.sh").read_text(encoding="utf-8")
    check("refusing speculative same-family module loads" in service and "refusing cross-KMI fallback" in service,
          "runtime service refuses speculative same-family module loads when KMI is unknown")
    check("target-kmi.pending" in installer and "DETECTED_VENDOR_KMIS" in installer and "conflicts with vendor module KMI" in installer and 'module_kmi "$PRIMARY"' in installer,
          "installer consumes the app KMI handoff or safely detects a unique vendor-module KMI")
    check('grep -Fxq "$REQUESTED_KMI"' in installer and "Explicit KMI matches an installed vendor-module vermagic" in installer,
          "installer permits an explicit KMI only when it exactly matches one of several vendor-module candidates")
    check('PRIMARY_KMI="$(module_kmi "$PRIMARY")"' in installer and "Module vermagic omits androidNN" in installer,
          "installer accepts an unmarked vermagic only from the exact selected, hash-verified KMI/ABI payload")
    check('candidate_kmi:-unmarked' in service and "module vermagic omits androidNN" in service,
          "boot service permits the exact checksum-verified staged payload with unmarked vermagic but rejects conflicting markers")
    installer_case = installer.split('case "$REQUESTED_KMI" in', 1)[1].split('*) abort', 1)[0]
    installer_kmis = re.findall(r"android\d+-\d+\.\d+", installer_case)
    check(installer_kmis == EXPECTED_KMIS, "module installer accepts exactly the canonical KMI matrix")
    service_case = service.split('case "$STORED_KMI" in', 1)[1].split('*)', 1)[0]
    service_kmis = re.findall(r"android\d+-\d+\.\d+", service_case)
    check(service_kmis == EXPECTED_KMIS, "boot service validates exactly the canonical KMI matrix")
    app_case = installer_app.split("case \"${'$'}TARGET_KMI\" in", 1)[1].split('*) echo', 1)[0]
    app_kmis = re.findall(r"android\d+-\d+\.\d+", app_case)
    check(app_kmis == EXPECTED_KMIS, "root-manager handoff validates exactly the canonical KMI matrix")
    check("target-kmi" in service and "refusing cross-KMI fallback" in service,
          "boot service persists and revalidates the selected KMI rather than selecting a same-family module")
    check("SUPPORTED_KERNEL_KMIS" in sheet and "kmiReferenceModulePicker" in sheet and "patch_flow_pick_kmi_reference_module" in sheet,
          "boot patch UI exposes vendor module fallback instead of requiring privileged /proc reads")
    check("manualKmiAllowed" in sheet and "canPatchKmi" in sheet and "allowUnverifiedKmi = manualKmiAllowed" in sheet,
          "boot patch UI allows explicit manual KMI only when a real kernel release and matching Linux family are known")
    check("BootPatchRepository.patch(" in sheet and "kmiReferenceModuleUri" in sheet,
          "selected KMI reference is forwarded into the actual image patch request")

    expected_strings = {
        "patch_flow_pick_kmi_reference_module",
        "patch_flow_selected_kmi_reference_module",
        "patch_flow_kmi_source",
        "patch_flow_kmi_source_kernel_image",
        "patch_flow_kmi_source_vendor_module",
        "patch_flow_kmi_source_manual",
    }
    resource_paths = [
        ROOT / "app/src/main/res/values/strings_boot_patch_flow.xml",
        ROOT / "app/src/main/res/values-in/strings_boot_patch_flow.xml",
        ROOT / "app/src/main/res/values-id/strings_boot_patch_flow.xml",
        ROOT / "app/src/main/res/values-es/strings_boot_patch_flow.xml",
        ROOT / "app/src/main/res/values-ja/strings_boot_patch_flow.xml",
        ROOT / "app/src/main/res/values-zh-rCN/strings_boot_patch_flow.xml",
    ]
    for path in resource_paths:
        try:
            tree = ET.parse(path)
        except ET.ParseError as exc:
            fail(f"{path.relative_to(ROOT)} is valid XML: {exc}")
        names = {item.attrib.get("name") for item in tree.getroot().findall("string")}
        missing = sorted(expected_strings - names)
        check(not missing, f"{path.relative_to(ROOT)} contains all KMI detection UI strings")
    print("All KernelSU-Next KMI/boot-patch contract checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
