#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Validate the versioned Zairenkai device-data plane.

The validator enforces structure and evidence discipline. It does not certify
any device's performance characteristics; those require device probe data.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import tomllib

VENDORS = {"qualcomm", "mediatek", "exynos", "tensor", "unknown"}
PROVIDERS = {"kgsl", "mali", "powervr", "devfreq", "linux-thermal", "qualcomm-thermal", "mediatek-thermal", "exynos-thermal", "tensor-thermal", "uclamp", "schedtune", "walt", "cpufreq", "vendor-performance"}
FAMILY_VENDORS = {"qualcomm", "mediatek", "exynos", "tensor"}
GKI_BRANCHES = {
    "android12-5.10", "android13-5.10", "android13-5.15",
    "android14-5.15", "android14-6.1", "android15-6.6",
    "android16-6.12", "android17-6.18",
}


def load(path: Path) -> dict:
    with path.open("rb") as fh:
        return tomllib.load(fh)


def validate_device(path: Path) -> list[str]:
    d = load(path)
    errors: list[str] = []
    version = d.get("schema_version")
    required = ("schema_version", "id", "vendor", "soc", "architecture", "runtime_discovery_required")
    for key in required:
        if key not in d:
            errors.append(f"{path}: missing {key}")
    if version not in {1, 3}:
        errors.append(f"{path}: unsupported schema_version={version!r}")
    if d.get("vendor") not in VENDORS:
        errors.append(f"{path}: invalid vendor")
    if d.get("runtime_discovery_required") is not True:
        errors.append(f"{path}: runtime_discovery_required must be true")
    if d.get("architecture") not in {"arm64", "x86_64", "riscv64", "unknown"}:
        errors.append(f"{path}: invalid architecture")
    if version == 1 and path.stem != d.get("soc"):
        errors.append(f"{path}: filename must match soc={d.get('soc')!r}")
    if version == 3:
        if not d.get("oem"):
            errors.append(f"{path}: v3 profile requires oem")
        if not d.get("source_id"):
            errors.append(f"{path}: v3 profile requires source_id")
        if path.stem not in {d.get("soc"), str(d.get("id", "")).split(".")[-1]}:
            errors.append(f"{path}: v3 filename must identify the device")
    kernel = d.get("kernel", {})
    flavors = kernel.get("preferred_flavors", [])
    if not flavors or any(v not in {"gki", "non-gki"} for v in flavors):
        errors.append(f"{path}: preferred_flavors must contain only gki/non-gki")
    match = d.get("match", {})
    if not any(isinstance(match.get(k), list) and match[k] for k in ("platform_tokens", "compatible_tokens", "model_tokens")):
        errors.append(f"{path}: at least one non-empty match token list is required")
    for section in ("cpu", "gpu", "thermal", "measurement"):
        if section not in d:
            errors.append(f"{path}: missing [{section}]")
    cpu = d.get("cpu", {})
    if not isinstance(cpu.get("surfaces"), list) or not cpu["surfaces"]:
        errors.append(f"{path}: cpu.surfaces must be non-empty")
    gpu = d.get("gpu", {})
    thermal = d.get("thermal", {})
    if any(x not in PROVIDERS for x in gpu.get("providers", [])):
        errors.append(f"{path}: invalid GPU provider")
    if any(x not in PROVIDERS for x in thermal.get("providers", [])):
        errors.append(f"{path}: invalid thermal provider")
    metrics = d.get("measurement", {}).get("metrics", [])
    if not metrics or d.get("measurement", {}).get("interpretation") != "measured-only":
        errors.append(f"{path}: measurements must be explicit and measured-only")
    if version == 1:
        status = d.get("status", {})
        if status.get("validation") != "runtime-probe-required":
            errors.append(f"{path}: device profiles must require runtime probing")
    return errors



def safe_relative_path(raw: str) -> Path | None:
    p = Path(raw)
    if not raw or p.is_absolute():
        return None
    if any(part == ".." for part in p.parts):
        return None
    return p

def validate_index(path: Path) -> list[str]:
    d = load(path)
    errors: list[str] = []
    if d.get("schema_version") != 1:
        errors.append(f"{path}: unsupported schema_version")
    seen: set[str] = set()
    for entry in d.get("device", []):
        for key in ("id", "vendor", "soc", "metadata", "catalog"):
            if key not in entry:
                errors.append(f"{path}: device entry missing {key}")
        if entry.get("id") in seen:
            errors.append(f"{path}: duplicate device id {entry.get('id')}")
        seen.add(entry.get("id"))
        if entry.get("vendor") not in VENDORS:
            errors.append(f"{path}: invalid vendor for {entry.get('id')}")
        meta_raw = entry.get("metadata", "")
        catalog_raw = entry.get("catalog", "")
        meta = path.parent / meta_raw
        if not meta.is_file():
            errors.append(f"{path}: missing metadata file {meta_raw}")
        else:
            try:
                metadata = load(meta)
                if metadata.get("id") != entry.get("id"):
                    errors.append(f"{path}: metadata id mismatch for {entry.get('id')}")
                if metadata.get("vendor") != entry.get("vendor") or metadata.get("soc") != entry.get("soc"):
                    errors.append(f"{path}: metadata identity mismatch for {entry.get('id')}")
                if metadata.get("schema_version") == 1 and entry.get("id") != f"{entry.get('vendor')}.{entry.get('soc')}":
                    errors.append(f"{path}: legacy profile id must equal vendor.soc for {entry.get('id')}")
            except Exception as exc:
                errors.append(f"{path}: invalid metadata {meta_raw}: {exc}")
        if safe_relative_path(meta_raw) is None or safe_relative_path(catalog_raw) is None:
            errors.append(f"{path}: unsafe relative path for {entry.get('id')}")
    return errors


def validate_family(path: Path) -> list[str]:
    d = load(path)
    errors: list[str] = []
    vendor = path.parent.name
    if vendor not in FAMILY_VENDORS:
        errors.append(f"{path}: unknown family directory")
    if d.get("schema_version") != 1:
        errors.append(f"{path}: unsupported schema_version")
    if d.get("vendor") != vendor:
        errors.append(f"{path}: vendor mismatch")
    order = d.get("provider_order", {})
    for kind in ("cpu", "gpu", "thermal", "boost"):
        values = order.get(kind)
        if not isinstance(values, list) or not values:
            errors.append(f"{path}: provider_order.{kind} must be a non-empty list")
        elif any(v not in PROVIDERS for v in values):
            errors.append(f"{path}: provider_order.{kind} contains unknown provider")
    discovery = d.get("discovery", {})
    for key in ("platform_tokens", "compatible_tokens", "thermal_type_patterns"):
        if not isinstance(discovery.get(key), list) or not discovery[key]:
            errors.append(f"{path}: discovery.{key} must be a non-empty list")
    if d.get("notes", {}).get("policy") != "runtime-capability-first":
        errors.append(f"{path}: policy must remain runtime-capability-first")
    return errors


def validate_kernels(path: Path) -> list[str]:
    d = load(path)
    errors: list[str] = []
    if d.get("schema_version") != 1:
        errors.append(f"{path}: unsupported schema_version")
    if path.name == "gki.toml":
        seen = set()
        for entry in d.get("branch", []):
            branch_id = entry.get("id")
            if branch_id in seen:
                errors.append(f"{path}: duplicate branch {branch_id}")
            seen.add(branch_id)
            if branch_id not in GKI_BRANCHES:
                errors.append(f"{path}: unknown GKI branch {branch_id}")
            if entry.get("kernel") != entry.get("kmi"):
                errors.append(f"{path}: kernel/KMI mismatch for {branch_id}")
        if seen != GKI_BRANCHES:
            errors.append(f"{path}: GKI registry does not exactly match supported branch matrix")
    elif path.name == "nongki.toml":
        generations = d.get("generations", [])
        if not generations or len(generations) != len(set(generations)):
            errors.append(f"{path}: non-GKI generations must be non-empty and unique")
        for value in generations:
            parts = value.split(".")
            if len(parts) != 2 or any(not x.isdigit() for x in parts):
                errors.append(f"{path}: malformed kernel generation {value!r}")
        hints = d.get("api_hints", {})
        for key in ("uclamp_min", "freq_qos_min"):
            value = hints.get(key, "")
            if not isinstance(value, str) or len(value.split(".")) != 2 or any(not x.isdigit() for x in value.split(".")):
                errors.append(f"{path}: malformed {key}")
    return errors

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?", default="database")
    args = ap.parse_args()
    root = Path(args.root)
    errors: list[str] = []
    errors.extend(validate_index(root / "index.toml"))
    for path in sorted((root / "devices").glob("**/*.toml")):
        errors.extend(validate_device(path))
    for path in sorted((root / "soc").glob("*/family.toml")):
        errors.extend(validate_family(path))
    for path in (root / "kernels" / "gki.toml", root / "kernels" / "nongki.toml"):
        errors.extend(validate_kernels(path))
    catalog_root = root.parent / "module" / "zperf" / "catalog"
    for entry in load(root / "index.toml").get("device", []):
        catalog = safe_relative_path(entry.get("catalog", ""))
        if catalog is None or not (catalog_root / catalog).is_file():
            errors.append(f"{root / 'index.toml'}: missing catalog file {entry.get('catalog')}")
    if errors:
        print("profile validation failed:")
        print("\n".join(f"- {e}" for e in errors))
        return 1
    print(f"profile validation passed: {len(list((root / 'devices').glob('**/*.toml')))} device profiles")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
