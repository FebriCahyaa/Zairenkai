#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Validate Zairenkai Atlas source/provider/device metadata contracts."""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path
from urllib.parse import urlparse
import tomllib

ROOT = Path(__file__).resolve().parents[1]
SOURCE_TRUST = {"community", "upstream", "official", "runtime"}
SOURCE_CLASS = {"community", "upstream", "supplier", "runtime"}
ALLOWED_HOST_SUFFIXES = (
    "source.android.com",
    "android.googlesource.com",
    "docs.kernel.org",
    "qualcomm.com",
    "mediatek.com",
    "semiconductor.samsung.com",
    "samsung.com",
    "blog.google",
    "developers.google.com",
    "store.google.com",
    "github.com",
)


def fail(msg: str) -> None:
    raise ValueError(msg)


def load_toml(path: Path) -> dict:
    try:
        return tomllib.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # pragma: no cover - diagnostic path
        fail(f"{path}: invalid TOML: {exc}")


def check_source_registry() -> set[str]:
    path = ROOT / "database/registry/sources.toml"
    data = load_toml(path)
    if data.get("schema_version") != 1:
        fail(f"{path}: schema_version must be 1")
    ids: set[str] = set()
    for src in data.get("source", []):
        sid = src.get("id")
        if not isinstance(sid, str) or not sid or sid in ids:
            fail(f"{path}: duplicate/invalid source id: {sid!r}")
        ids.add(sid)
        if src.get("trust") not in SOURCE_TRUST:
            fail(f"{path}: invalid trust for {sid}")
        if src.get("class") not in SOURCE_CLASS:
            fail(f"{path}: invalid class for {sid}")
        url = src.get("url", "")
        parsed = urlparse(url)
        if parsed.scheme == "local":
            if src.get("host") != "local":
                fail(f"{path}: local source {sid} must use host=local")
        else:
            if parsed.scheme != "https":
                fail(f"{path}: non-local source {sid} must use HTTPS")
            host = (parsed.hostname or "").lower()
            if not any(host == h or host.endswith("." + h) for h in ALLOWED_HOST_SUFFIXES):
                fail(f"{path}: source {sid} uses unapproved host {host!r}")
    if not ids:
        fail(f"{path}: registry is empty")
    return ids


def check_vendors(source_ids: set[str]) -> None:
    path = ROOT / "database/registry/vendors.toml"
    data = load_toml(path)
    seen: set[str] = set()
    for vendor in data.get("vendor", []):
        vid = vendor.get("id")
        if not isinstance(vid, str) or not vid or vid in seen:
            fail(f"{path}: invalid/duplicate vendor id: {vid!r}")
        seen.add(vid)
        for sid in vendor.get("source_ids", []):
            if sid not in source_ids:
                fail(f"{path}: vendor {vid} references unknown source {sid}")


def check_products(source_ids: set[str]) -> set[str]:
    path = ROOT / "database/products/soc-products.toml"
    data = load_toml(path)
    seen: set[str] = set()
    for product in data.get("product", []):
        pid = product.get("id")
        if not isinstance(pid, str) or not pid or pid in seen:
            fail(f"{path}: invalid/duplicate product id: {pid!r}")
        seen.add(pid)
        if product.get("source_id") not in source_ids:
            fail(f"{path}: product {pid} references unknown source")
        if product.get("status") != "verified-supplier":
            fail(f"{path}: product {pid} must declare verified-supplier status")
    return seen


def check_family_indexes(source_ids: set[str], product_ids: set[str]) -> None:
    for path in sorted((ROOT / "database/soc").glob("*/products.toml")):
        data = load_toml(path)
        if data.get("schema_version") != 1:
            fail(f"{path}: schema_version must be 1")
        if data.get("source_id") not in source_ids:
            fail(f"{path}: unknown source_id")
        for pid in data.get("products", []):
            if pid not in product_ids:
                fail(f"{path}: unknown product {pid}")


def check_devices(source_ids: set[str]) -> int:
    root = ROOT / "database/devices"
    count = 0
    ids: set[str] = set()
    for path in sorted(root.rglob("*.toml")):
        data = load_toml(path)
        did = data.get("id")
        if not isinstance(did, str) or not did or did in ids:
            fail(f"{path}: invalid/duplicate device id {did!r}")
        ids.add(did)
        count += 1
        if data.get("schema_version") not in (1, 3):
            fail(f"{path}: unsupported profile schema")
        if data.get("runtime_discovery_required") is not True:
            fail(f"{path}: runtime_discovery_required must be true")
        if data.get("architecture") not in {"arm64", "x86_64", "riscv64", "unknown"}:
            fail(f"{path}: invalid architecture")
        if data.get("source_id") and data["source_id"] not in source_ids:
            fail(f"{path}: unknown source_id")
        if data.get("schema_version") == 3 and not data.get("source_id"):
            fail(f"{path}: v3 profile requires source_id")
    return count


def check_index(source_ids: set[str]) -> int:
    path = ROOT / "database/index.toml"
    data = load_toml(path)
    if data.get("schema_version") != 1:
        fail(f"{path}: schema_version must be 1")
    seen: set[str] = set()
    count = 0
    db = ROOT / "database"
    for entry in data.get("device", []):
        did = entry.get("id")
        if not isinstance(did, str) or not did or did in seen:
            fail(f"{path}: invalid/duplicate index id {did!r}")
        seen.add(did)
        count += 1
        for key in ("metadata", "catalog"):
            raw = entry.get(key)
            if not isinstance(raw, str) or Path(raw).is_absolute() or ".." in Path(raw).parts:
                fail(f"{path}: unsafe {key} for {did}")
        if not (db / entry["metadata"]).is_file():
            fail(f"{path}: missing metadata file for {did}")
        if entry["catalog"] not in {"generic.toml", "lavender.toml"}:
            fail(f"{path}: unexpected catalog for {did}")
    return count


def check_bundle_schema() -> None:
    path = ROOT / "database/schema/provider-bundle-v1.toml"
    data = load_toml(path)
    if data.get("schema_version") != 1 or data.get("name") != "zairenkai-provider-bundle":
        fail(f"{path}: invalid provider bundle schema")
    if data.get("maximum_records", 0) > 10000:
        fail(f"{path}: maximum_records exceeds guardrail")


def check_no_embedded_secrets() -> None:
    patterns = [re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"), re.compile(r"(?:api[_-]?key|token)\s*=\s*['\"][A-Za-z0-9+/=_-]{32,}['\"]", re.I)]
    for path in (ROOT / "database").rglob("*"):
        if not path.is_file() or path.suffix not in {".toml", ".json", ".yaml", ".md", ".py"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for pattern in patterns:
            if pattern.search(text):
                fail(f"{path}: secret-like material detected")


def main() -> int:
    source_ids = check_source_registry()
    check_vendors(source_ids)
    product_ids = check_products(source_ids)
    check_family_indexes(source_ids, product_ids)
    devices = check_devices(source_ids)
    index = check_index(source_ids)
    check_bundle_schema()
    check_no_embedded_secrets()
    print(f"PASS atlas: sources={len(source_ids)} products={len(product_ids)} devices={devices} index={index}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"FAIL atlas: {exc}", file=sys.stderr)
        raise SystemExit(1)
