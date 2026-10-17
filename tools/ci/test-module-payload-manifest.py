#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Validate that the module checksum manifest matches the payload installed under MODPATH.

META-INF is archive-level installer metadata. Root managers consume it before
copying module payload files, so it must not be listed in module/SHA256SUMS.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
import tempfile
import zipfile
from pathlib import Path


def checksum_entries(manifest: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line_no, raw in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
        if not raw.strip():
            continue
        match = re.fullmatch(r"([0-9a-fA-F]{64})[ \t]+[* ]?(.+?)", raw)
        if not match:
            raise ValueError(f"invalid checksum line {line_no}: {raw!r}")
        digest, name = match.groups()
        name = name.lstrip("*")
        normalized = name[2:] if name.startswith("./") else name
        normalized = normalized.replace("\\\\", "/")
        if normalized == "META-INF" or normalized.startswith("META-INF/"):
            raise ValueError(f"archive-level installer metadata must not be checksummed: {name}")
        if normalized == "SHA256SUMS" or normalized.endswith("/SHA256SUMS"):
            raise ValueError("SHA256SUMS must not include itself")
        if normalized in entries:
            raise ValueError(f"duplicate payload path in manifest: {normalized}")
        entries[normalized] = digest.lower()
    return entries


def verify(module_dir: Path, archive: Path) -> None:
    manifest = module_dir / "SHA256SUMS"
    if not manifest.is_file() or not archive.is_file():
        raise ValueError("module SHA256SUMS and archive must both exist")
    entries = checksum_entries(manifest)
    if not entries:
        raise ValueError("payload manifest is empty")

    archive_files: set[str] = set()
    with zipfile.ZipFile(archive) as zf:
        bad = zf.testzip()
        if bad:
            raise ValueError(f"corrupt ZIP member: {bad}")
        for name in zf.namelist():
            clean = name.removeprefix("./").rstrip("/")
            if not clean or clean == "SHA256SUMS" or clean == "META-INF" or clean.startswith("META-INF/"):
                continue
            if not name.endswith("/"):
                archive_files.add(clean)

    expected_files = {
        path.relative_to(module_dir).as_posix()
        for path in module_dir.rglob("*")
        if path.is_file()
        and path.relative_to(module_dir).as_posix() != "SHA256SUMS"
        and not path.relative_to(module_dir).as_posix().startswith("META-INF/")
    }
    if set(entries) != expected_files:
        missing = sorted(expected_files - set(entries))
        extra = sorted(set(entries) - expected_files)
        raise ValueError(f"manifest/payload mismatch; missing={missing[:5]}, extra={extra[:5]}")
    if set(entries) != archive_files:
        missing = sorted(set(entries) - archive_files)
        extra = sorted(archive_files - set(entries))
        raise ValueError(f"archive/manifest mismatch; missing={missing[:5]}, extra={extra[:5]}")

    for name, expected in entries.items():
        actual = hashlib.sha256((module_dir / name).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError(f"SHA-256 mismatch for {name}")

    # Match the Android installer count: archive-level META-INF has already been
    # consumed, SHA256SUMS is not payload, and the KMI-selected copy is created
    # after verification rather than packaged at the ZIP root.
    with tempfile.TemporaryDirectory(prefix="zkfc-module-manifest-") as tmp:
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(tmp)
        root = Path(tmp)
        for path in root.rglob("*"):
            if path.is_file() and path.relative_to(root).as_posix().startswith("META-INF/"):
                # In real installation META-INF is handled by the installer, not MODPATH.
                pass
        installed_payload = {
            p.relative_to(root).as_posix()
            for p in root.rglob("*")
            if p.is_file()
            and p.relative_to(root).as_posix() != "SHA256SUMS"
            and not p.relative_to(root).as_posix().startswith("META-INF/")
        }
        if len(installed_payload) != len(entries):
            raise ValueError(f"installer payload count mismatch: manifest={len(entries)} files={len(installed_payload)}")

    print(f"PASS: module payload manifest matches {len(entries)} installed files; META-INF is excluded.")


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="zkfc-module-payload-test-") as tmp:
        root = Path(tmp)
        module_dir = root / "module"
        (module_dir / "META-INF/com/google/android").mkdir(parents=True)
        (module_dir / "bin/arm64-v8a").mkdir(parents=True)
        (module_dir / "lkm/android14-6.1-arm64").mkdir(parents=True)
        (module_dir / "module.prop").write_text("id=zairenkai\n", encoding="utf-8")
        (module_dir / "customize.sh").write_text("#!/system/bin/sh\n", encoding="utf-8")
        (module_dir / "META-INF/com/google/android/update-binary").write_text("installer\n", encoding="utf-8")
        (module_dir / "META-INF/com/google/android/updater-script").write_text("#MAGISK\n", encoding="utf-8")
        (module_dir / "bin/arm64-v8a/zkfctl").write_bytes(b"zkfctl-test")
        (module_dir / "lkm/android14-6.1-arm64/zkfc.ko").write_bytes(b"zkfc-test")
        lines = []
        for path in sorted(module_dir.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(module_dir).as_posix()
            if relative.startswith("META-INF/"):
                continue
            lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  ./{relative}")
        (module_dir / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="ascii")
        archive = root / "Zairenkai-module.zip"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            for path in module_dir.rglob("*"):
                if path.is_file():
                    zf.write(path, path.relative_to(module_dir).as_posix())
        verify(module_dir, archive)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--module-dir", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--self-test", action="store_true", help="exercise META-INF exclusion with a synthetic package")
    args = parser.parse_args()
    if args.self_test:
        try:
            self_test()
        except (OSError, ValueError, zipfile.BadZipFile) as exc:
            print(f"FAIL: {exc}", file=sys.stderr)
            return 1
        print("PASS: self-test confirms archive-level META-INF is excluded from installed payload checksums.")
        return 0
    if not args.module_dir or not args.archive:
        parser.error("--module-dir and --archive are required unless --self-test is used")
    try:
        verify(args.module_dir.resolve(), args.archive.resolve())
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
