#!/usr/bin/env bash
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Generate a release keystore locally and upload its values as GitHub Actions secrets.
set -euo pipefail
umask 077

REPO="${GITHUB_REPOSITORY:-FebriCahyaa/Zairenkai}"
KEYSTORE_PATH="${HOME}/.local/share/zairenkai/zairenkai-release.p12"
REPLACE_EXISTING=false

usage() {
  cat <<'USAGE'
Usage: tools/release/setup-signing-key.sh [--repo OWNER/REPO] [--keystore PATH] [--replace-existing-secrets]

Creates a PKCS#12 Android release keystore on this machine and uploads these
repository Actions secrets with GitHub CLI (gh):
  ZK_KEYSTORE_BASE64, ZK_KEYSTORE_PASSWORD, ZK_KEY_ALIAS, ZK_KEY_PASSWORD

The script refuses to replace an existing signing identity unless explicitly
requested. Replacing a published app's key can prevent updates over existing installs.
USAGE
}

while (($#)); do
  case "$1" in
    --repo)
      [[ $# -ge 2 ]] || { echo "Missing value for --repo" >&2; exit 2; }
      REPO="$2"; shift 2 ;;
    --keystore)
      [[ $# -ge 2 ]] || { echo "Missing value for --keystore" >&2; exit 2; }
      KEYSTORE_PATH="$2"; shift 2 ;;
    --replace-existing-secrets)
      REPLACE_EXISTING=true; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2 ;;
  esac
done

for tool in gh keytool base64; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Required command is missing: $tool" >&2
    exit 1
  }
done

gh auth status >/dev/null
gh repo view "$REPO" --json nameWithOwner --jq .nameWithOwner >/dev/null

SECRET_NAMES="$(gh secret list --repo "$REPO" --json name --jq '.[].name')"
EXISTING=()
for name in ZK_KEYSTORE_BASE64 ZK_KEYSTORE_PASSWORD ZK_KEY_ALIAS ZK_KEY_PASSWORD; do
  if grep -Fxq "$name" <<< "$SECRET_NAMES"; then
    EXISTING+=("$name")
  fi
done
if ((${#EXISTING[@]})); then
  if [[ "$REPLACE_EXISTING" != true ]]; then
    printf 'Refusing to replace existing signing secrets in %s: %s\n' \
      "$REPO" "${EXISTING[*]}" >&2
    echo 'Use the existing keystore for subsequent releases. Only use --replace-existing-secrets if you intentionally need a new app-signing identity.' >&2
    exit 1
  fi
  echo 'WARNING: overwriting signing secrets can stop existing installs from accepting updates.' >&2
  read -r -p 'Type REPLACE to replace the signing identity: ' CONFIRM
  [[ "$CONFIRM" == 'REPLACE' ]] || { echo 'Cancelled.' >&2; exit 1; }
fi

if [[ -e "$KEYSTORE_PATH" ]]; then
  echo "Refusing to overwrite existing keystore: $KEYSTORE_PATH" >&2
  echo 'Back it up and choose another --keystore path, or keep using the existing key.' >&2
  exit 1
fi
mkdir -p "$(dirname "$KEYSTORE_PATH")"

read -r -p 'Repository secret key alias [zairenkai-release]: ' KEY_ALIAS
KEY_ALIAS="${KEY_ALIAS:-zairenkai-release}"
[[ "$KEY_ALIAS" =~ ^[A-Za-z0-9._-]+$ ]] || {
  echo 'Alias may contain only letters, digits, dot, underscore and hyphen.' >&2
  exit 1
}
read -r -s -p 'New keystore password (at least 12 chars, no whitespace): ' STORE_PASSWORD
printf '\n'
read -r -s -p 'Repeat keystore password: ' STORE_PASSWORD_CONFIRM
printf '\n'
[[ "$STORE_PASSWORD" == "$STORE_PASSWORD_CONFIRM" ]] || {
  echo 'Passwords do not match.' >&2; exit 1;
}
[[ ${#STORE_PASSWORD} -ge 12 && "$STORE_PASSWORD" != *[[:space:]]* ]] || {
  echo 'Password must be at least 12 characters and contain no whitespace.' >&2
  exit 1
}

# Android PKCS#12 key entries use the same key and store password.
export ZK_KEYSTORE_PASSWORD="$STORE_PASSWORD"
export ZK_KEY_PASSWORD="$STORE_PASSWORD"
keytool -genkeypair -noprompt \
  -alias "$KEY_ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname 'CN=Zairenkai Release, OU=Android, O=Zairenkai, C=ID' \
  -keystore "$KEYSTORE_PATH" -storetype PKCS12 \
  -storepass:env ZK_KEYSTORE_PASSWORD \
  -keypass:env ZK_KEY_PASSWORD
chmod 600 "$KEYSTORE_PATH"

# Validate alias/password and compute the public certificate fingerprint for records.
keytool -list -keystore "$KEYSTORE_PATH" -storepass:env ZK_KEYSTORE_PASSWORD -alias "$KEY_ALIAS" >/dev/null
FINGERPRINT="$(keytool -exportcert -alias "$KEY_ALIAS" -keystore "$KEYSTORE_PATH" \
  -storepass:env ZK_KEYSTORE_PASSWORD | sha256sum | awk '{print toupper($1)}')"
[[ "$FINGERPRINT" =~ ^[A-F0-9]{64}$ ]] || { echo 'Could not calculate certificate fingerprint.' >&2; exit 1; }

# Stream every secret over stdin; no password or base64 value is printed to stdout.
base64 "$KEYSTORE_PATH" | tr -d '\r\n' | gh secret set ZK_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set ZK_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$KEY_ALIAS" | gh secret set ZK_KEY_ALIAS --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set ZK_KEY_PASSWORD --repo "$REPO"

unset ZK_KEYSTORE_PASSWORD ZK_KEY_PASSWORD STORE_PASSWORD STORE_PASSWORD_CONFIRM
printf '\nSigning secrets uploaded successfully to %s.\n' "$REPO"
printf 'Alias: %s\nCertificate SHA-256: %s\nLocal keystore: %s\n' \
  "$KEY_ALIAS" "$FINGERPRINT" "$KEYSTORE_PATH"
echo 'Back up the local keystore and remember its password in a password manager. Never commit this file.'
