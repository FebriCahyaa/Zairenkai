#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Opt-in helper: issue an owner-signed grant for this kernel tag and embed it.
# The owner private key stays on the builder and is never copied into the tree.
set -euo pipefail

if [[ "${ZKFC_AUTO_ISSUE_TOKEN:-0}" != "1" ]]; then
  echo "ZKFC token generation skipped; set ZKFC_AUTO_ISSUE_TOKEN=1 to opt in."
  exit 0
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
LICENSE_SCRIPT="tools/zkfc-license/zkfc_license.py"
OWNER_HEADER="kernel/security/zkfc_owner_key.h"
TAG="${ZKFC_TAG:-${CONFIG_ZKFC_LICENSEE_TAG:-}}"
LICENSEE="${ZKFC_LICENSEE:-}"
DAYS="${ZKFC_TOKEN_DAYS:-365}"

[[ -n "$TAG" ]] || { echo "error: set ZKFC_TAG to the exact CONFIG_ZKFC_LICENSEE_TAG" >&2; exit 2; }
[[ -n "$LICENSEE" ]] || { echo "error: set ZKFC_LICENSEE to the owner-authorized licensee name" >&2; exit 2; }
[[ "$DAYS" =~ ^[0-9]+$ ]] && (( DAYS >= 1 && DAYS <= 3650 )) || {
  echo "error: ZKFC_TOKEN_DAYS must be between 1 and 3650" >&2; exit 2;
}
command -v python3 >/dev/null 2>&1 || { echo "error: python3 is required" >&2; exit 2; }
python3 -c 'import cryptography' >/dev/null 2>&1 || {
  echo "error: Python package cryptography is required (python3 -m pip install cryptography)" >&2; exit 2;
}
[[ -f "$LICENSE_SCRIPT" && -f "$OWNER_HEADER" ]] || { echo "error: run this from a complete Zairenkai source checkout" >&2; exit 2; }

if grep -Eq '^[[:space:]]*#define[[:space:]]+ZKFC_OWNER_KEY_PROVISIONED[[:space:]]+0([[:space:]]|$)' "$OWNER_HEADER"; then
  echo "error: the repository owner public key is not provisioned; token generation is impossible" >&2
  echo "provision the correct owner public key first; never embed the private key in source or CI artifacts" >&2
  exit 3
fi
SUBKEY_FILE="${ZKFC_SUBKEY_FILE:-}"
if [[ -n "$SUBKEY_FILE" ]]; then
  [[ -f "$SUBKEY_FILE" ]] || { echo "error: ZKFC_SUBKEY_FILE does not exist" >&2; exit 2; }
  TARGET_INC="kernel/license/zkfc_bundle.inc"
  if [[ -f kernel/license/zkfc_license.inc ]]; then
    echo "error: kernel/license/zkfc_license.inc already exists; remove it intentionally before delegated refresh" >&2
    exit 3
  fi
else
  TARGET_INC="kernel/license/zkfc_license.inc"
  if [[ -f kernel/license/zkfc_bundle.inc ]]; then
    echo "error: kernel/license/zkfc_bundle.inc already exists and takes precedence over direct tokens" >&2
    echo "remove or intentionally replace the delegated bundle before enabling direct token generation" >&2
    exit 3
  fi
fi

API_MAJOR="$(python3 - "$LICENSE_SCRIPT" <<'PY'
import re, sys
text = open(sys.argv[1], encoding='utf-8').read()
match = re.search(r'^API_MAJOR\s*=\s*(\d+)\s*$', text, re.M)
if not match:
    raise SystemExit('API_MAJOR constant was not found in zkfc_license.py')
print(match.group(1))
PY
)"
COMMIT="${GITHUB_SHA:-local}"
COMMIT="${COMMIT:0:8}"
RUN_ID="${GITHUB_RUN_ID:-local}"
ATTEMPT="${GITHUB_RUN_ATTEMPT:-1}"
BUILD_ID="${COMMIT}-${RUN_ID}-${ATTEMPT}"
[[ "$BUILD_ID" != "local-local-1" ]] || BUILD_ID="$(date -u +%Y%m%dT%H%M%SZ)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PREFIX="$TMP/zkfc-auto"
TOKEN_OUT="${ZKFC_TOKEN_OUT:-$ROOT/zkfc-auto-${BUILD_ID}.zkl}"
[[ "$TOKEN_OUT" == *.zkl ]] || { echo "error: ZKFC_TOKEN_OUT must end in .zkl" >&2; exit 2; }
TOKEN_OUT_DIR="$(dirname "$TOKEN_OUT")"
mkdir -p "$TOKEN_OUT_DIR" kernel/license

# Owner tool signs a grant bound to this kernel tag. The passphrase prompt is
# interactive by default; unattended CI must inject it as a protected secret.
ISSUE_ARGS=(issue --licensee "$LICENSEE" --tag "$TAG" --api-major "$API_MAJOR" --days "$DAYS" --out "$PREFIX")
if [[ -n "$SUBKEY_FILE" ]]; then
  ISSUE_ARGS+=(--subkey "$SUBKEY_FILE")
fi
python3 "$LICENSE_SCRIPT" "${ISSUE_ARGS[@]}"
python3 "$LICENSE_SCRIPT" verify "$PREFIX.zkl" --tag "$TAG"

install -m 0600 "$PREFIX.inc" "$TARGET_INC"
install -m 0600 "$PREFIX.zkl" "$TOKEN_OUT"

echo "Embedded grant refreshed: $TARGET_INC"
echo "Runtime token for app import: $TOKEN_OUT"
echo "Build ID: $BUILD_ID; ZKFC API major: $API_MAJOR; binding tag: $TAG"
echo "The generated token is a signed license grant, not a secret API key."
if [[ -n "$SUBKEY_FILE" ]]; then
  echo "Delegated build used a scoped subkey; never commit or publish the .zksub private key."
else
  echo "Keep ZKFC_OWNER_PASSPHRASE and ~/.zkfc/owner_ed25519.pem out of source/artifacts/logs."
fi
