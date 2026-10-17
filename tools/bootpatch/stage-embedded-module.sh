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

# Use the same manifest verifier as module packaging. Archive-level META-INF
# installer files are consumed by the root manager and are not installed under
# MODPATH, so they must not be counted as payload files in SHA256SUMS.
python3 tools/ci/test-module-payload-manifest.py \
  --module-dir "$TMP/extracted" \
  --archive "$ZIP_PATH"
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
