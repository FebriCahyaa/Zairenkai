#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Stage matching KMI modules into APK assets, analogous to KernelSU-Next's bundled LKM assets.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INPUT="${1:-}"
if [[ -z "$INPUT" ]]; then
  echo "Usage: $0 <Zairenkai-module.zip|module-directory>" >&2
  exit 2
fi
if [[ ! -e "$INPUT" ]]; then
  echo "Input does not exist: $INPUT" >&2
  exit 2
fi

TMP=""
if [[ -d "$INPUT" ]]; then
  SOURCE_ROOT="$(cd "$INPUT" && pwd)"
else
  command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 1; }
  TMP="$(mktemp -d)"
  trap '[[ -z "$TMP" ]] || rm -rf "$TMP"' EXIT
  unzip -q "$INPUT" -d "$TMP"
  SOURCE_ROOT="$TMP"
fi

if [[ -f "$INPUT" && -f "$SOURCE_ROOT/SHA256SUMS" ]]; then
  (cd "$SOURCE_ROOT" && sha256sum --check SHA256SUMS)
fi
LKM_ROOT="$SOURCE_ROOT/lkm"
[[ -d "$LKM_ROOT" ]] || { echo "No lkm/ directory found in module package" >&2; exit 1; }

DEST="$ROOT/app/src/main/assets/zkfc"
rm -rf "$DEST"
mkdir -p "$DEST"
count=0
while IFS= read -r -d '' module; do
  relative="${module#"$LKM_ROOT"/}"
  kmi_abi="${relative%%/*}"
  if [[ ! "$kmi_abi" =~ ^android[0-9]+-[0-9]+\.[0-9]+-(arm64|x86_64)$ ]]; then
    echo "Skipping unexpected KMI/ABI directory: $kmi_abi" >&2
    continue
  fi
  [[ -s "$module" ]] || { echo "Empty module rejected: $module" >&2; exit 1; }
  mkdir -p "$DEST/$kmi_abi"
  install -m 0644 "$module" "$DEST/$kmi_abi/zkfc.ko"
  count=$((count + 1))
done < <(find "$LKM_ROOT" -type f -name 'zkfc.ko' -print0 | sort -z)

(( count > 0 )) || { echo "No valid KMI-specific zkfc.ko files found" >&2; exit 1; }
echo "Staged $count KMI/ABI-specific zkfc.ko asset(s) into app/src/main/assets/zkfc."
find "$DEST" -type f -name 'zkfc.ko' -print | sort
