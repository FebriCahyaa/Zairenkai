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
    check("Selected KMI {kmi} is a manual hint" in rust and 'kmi_source="manual"' not in rust,
          "manual KMI selection remains explicitly unverified instead of being reported as detected")
    check("never flashes a" in rust and "block device" in rust,
          "file patching remains non-destructive and does not silently flash a partition")

    repository = (ROOT / "app/src/main/java/com/zairenkai/app/data/boot/BootPatchRepository.kt").read_text(encoding="utf-8")
    sheet = (ROOT / "app/src/main/java/com/zairenkai/app/ui/home/ZairenkaiBootPatchSheet.kt").read_text(encoding="utf-8")
    check("--kmi-reference-module" in repository and "DETECTED_KMI_SOURCE=" in repository,
          "Android repository passes vendor .ko reference to the native detector and reads KMI provenance")
    check("kmiReferenceModulePicker" in sheet and "patch_flow_pick_kmi_reference_module" in sheet,
          "boot patch UI exposes vendor module fallback instead of requiring privileged /proc reads")
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
