#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Validate a release module ZIP and stage it for APK asset/hash generation.
set -euo pipefail

ZIP_PATH="${1:-Zairenkai-module.zip}"
DEST="app/src/main/assets/embedded/Zairenkai-module.zip"

[[ -f "$ZIP_PATH" ]] || { echo "error: module ZIP not found: $ZIP_PATH" >&2; exit 2; }
command -v unzip >/dev/null 2>&1 || { echo "error: unzip is required" >&2; exit 2; }
command -v sha256sum >/dev/null 2>&1 || { echo "error: sha256sum is required" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "error: python3 is required for safe ZIP validation" >&2; exit 2; }
unzip -t "$ZIP_PATH" >/dev/null

# Reject traversal, symlinks, duplicate normalized paths, and ZIP bombs before
# extraction. ZIPs from the release workflow contain regular payload files only.
python3 - "$ZIP_PATH" <<'PY'
import stat
import sys
import zipfile

archive = sys.argv[1]
max_entry = 128 * 1024 * 1024
max_total = 256 * 1024 * 1024
seen = set()
total = 0

def normalized(raw, is_dir):
    if not raw or raw.startswith("/") or "\\" in raw:
        raise ValueError(f"unsafe ZIP path: {raw!r}")
    while raw.startswith("./"):
        raw = raw[2:]
    if is_dir:
        raw = raw.rstrip("/")
    if not raw and is_dir:
        return ""  # The root directory entry emitted by `zip -r .`.
    parts = raw.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError(f"unsafe ZIP path: {raw!r}")
    return "/".join(parts)

try:
    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            is_dir = info.is_dir()
            name = normalized(info.filename, is_dir)
            if not name:
                continue
            if name in seen:
                raise ValueError(f"duplicate normalized ZIP path: {name}")
            seen.add(name)
            unix_mode = (info.external_attr >> 16) & 0xFFFF
            if stat.S_ISLNK(unix_mode):
                raise ValueError(f"symbolic links are not permitted in module ZIPs: {name}")
            if is_dir:
                continue
            if info.file_size < 0 or info.file_size > max_entry:
                raise ValueError(f"invalid/oversized ZIP entry: {name}")
            total += info.file_size
            if total > max_total:
                raise ValueError("module ZIP expands beyond 256 MiB")
except (OSError, zipfile.BadZipFile, ValueError) as exc:
    raise SystemExit(f"error: ZIP preflight failed: {exc}")
PY

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/extracted"
unzip -q "$ZIP_PATH" -d "$TMP/extracted"
[[ -s "$TMP/extracted/SHA256SUMS" ]] || { echo "error: SHA256SUMS is missing from module ZIP" >&2; exit 3; }

# Hashes must cover the exact file set; an extra/unlisted executable or a
# missing listed file is rejected before Gradle pins this archive into the APK.
python3 - "$TMP/extracted" <<'PY'
import hashlib
import re
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
manifest = root / "SHA256SUMS"
pattern = re.compile(r"^([0-9a-fA-F]{64})\s+(.+?)\s*$")
expected = set()

def normalize(raw):
    raw = raw.removeprefix("*")
    while raw.startswith("./"):
        raw = raw[2:]
    if not raw or raw.startswith("/") or "\\" in raw:
        raise ValueError(f"unsafe checksum path: {raw!r}")
    parts = raw.split("/")
    if any(part in ("", ".", "..") for part in parts) or raw == "SHA256SUMS":
        raise ValueError(f"unsafe checksum path: {raw!r}")
    return "/".join(parts)

try:
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        match = pattern.fullmatch(line)
        if not match:
            raise ValueError("malformed SHA256SUMS line")
        name = normalize(match.group(2))
        if name in expected:
            raise ValueError(f"duplicate manifest entry: {name}")
        expected.add(name)
    actual = set()
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"symbolic link is not permitted: {path.relative_to(root)}")
        if path.is_file() and path.name != "SHA256SUMS":
            actual.add(path.relative_to(root).as_posix())
    if expected != actual:
        raise ValueError(
            f"manifest does not cover exact file set; unlisted={sorted(actual - expected)[:8]}, "
            f"missing={sorted(expected - actual)[:8]}"
        )
except (OSError, ValueError) as exc:
    raise SystemExit(f"error: manifest coverage check failed: {exc}")
PY
(
  cd "$TMP/extracted"
  sha256sum --check SHA256SUMS
)
for required in module.prop customize.sh service.sh; do
  [[ -s "$TMP/extracted/$required" ]] || { echo "error: required module file missing: $required" >&2; exit 3; }
done
for required in bin/arm64-v8a/zkfctl bin/arm64-v8a/zperfd bin/x86_64/zkfctl bin/x86_64/zperfd; do
  [[ -s "$TMP/extracted/$required" ]] || { echo "error: userspace payload missing: $required" >&2; exit 3; }
done
find "$TMP/extracted/lkm" -type f -name zkfc.ko -print -quit | grep -q . || {
  echo "error: no zkfc.ko payload is present" >&2; exit 3;
}

mkdir -p "$(dirname "$DEST")"
install -m 0644 "$ZIP_PATH" "$DEST"
echo "Staged verified module ZIP: $DEST"
sha256sum "$DEST"
