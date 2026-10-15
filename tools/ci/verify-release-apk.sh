#!/usr/bin/env bash
set -euo pipefail

APK="${1:?usage: verify-release-apk.sh <apk> <keystore>}"
KEYSTORE="${2:?usage: verify-release-apk.sh <apk> <keystore>}"
BT="${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/37.0.0"
APKSIGNER="$BT/apksigner"

[[ -f "$APK" ]] || { echo "::error::APK not found: $APK"; exit 1; }
[[ -f "$KEYSTORE" ]] || { echo "::error::Release keystore not found: $KEYSTORE"; exit 1; }
[[ -x "$APKSIGNER" ]] || { echo "::error::apksigner not found: $APKSIGNER"; exit 1; }

if keytool -list -storetype PKCS12 -keystore "$KEYSTORE" \
    -storepass:env ZK_KEYSTORE_PASSWORD -alias "$ZK_KEY_ALIAS" >/dev/null 2>&1; then
  KS_TYPE=PKCS12
elif keytool -list -storetype JKS -keystore "$KEYSTORE" \
    -storepass:env ZK_KEYSTORE_PASSWORD -alias "$ZK_KEY_ALIAS" >/dev/null 2>&1; then
  KS_TYPE=JKS
else
  echo "::error::Release keystore cannot be opened as PKCS12 or JKS"
  exit 1
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-type "$KS_TYPE" \
  --ks-key-alias "$ZK_KEY_ALIAS" \
  --ks-pass env:ZK_KEYSTORE_PASSWORD \
  --key-pass env:ZK_KEY_PASSWORD \
  --min-sdk-version 23 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  "$APK"

VERIFY_OUTPUT="$("$APKSIGNER" verify --verbose --min-sdk-version 23 "$APK" 2>&1)" || {
  printf '%s\n' "$VERIFY_OUTPUT"
  echo "::error::apksigner could not verify $APK"
  exit 1
}
printf '%s\n' "$VERIFY_OUTPUT"
for scheme in \
  'Verified using v1 scheme (JAR signing): true' \
  'Verified using v2 scheme (APK Signature Scheme v2): true' \
  'Verified using v3 scheme (APK Signature Scheme v3): true'; do
  grep -Fq "$scheme" <<< "$VERIFY_OUTPUT" || {
    echo "::error::Required APK signature scheme is missing: $scheme"
    exit 1
  }
done

EXPECTED="$(keytool -exportcert -alias "$ZK_KEY_ALIAS" -keystore "$KEYSTORE" \
  -storepass:env ZK_KEYSTORE_PASSWORD | sha256sum | awk '{print toupper($1)}')"
CERT_OUTPUT="$("$APKSIGNER" verify --print-certs "$APK" 2>&1)" || {
  printf '%s\n' "$CERT_OUTPUT"
  echo "::error::Could not inspect APK signing certificate"
  exit 1
}
ACTUAL="$(printf '%s\n' "$CERT_OUTPUT" | awk '/certificate SHA-256 digest:/ {
  sub(/^.*certificate SHA-256 digest:[[:space:]]*/, "")
  gsub(/[^[:xdigit:]]/, "")
  if (length($0) == 64) { print toupper($0); exit }
}')"

[[ "$EXPECTED" =~ ^[A-F0-9]{64}$ ]] || { echo "::error::Could not calculate keystore certificate SHA-256"; exit 1; }
[[ "$ACTUAL" =~ ^[A-F0-9]{64}$ ]] || { echo "::error::Could not read APK certificate SHA-256"; exit 1; }
[[ "$ACTUAL" == "$EXPECTED" ]] || {
  echo "::error::APK signer does not match the configured release keystore certificate"
  echo "Keystore SHA-256: $EXPECTED"
  echo "APK SHA-256:      $ACTUAL"
  exit 1
}
echo "Verified APK signature schemes v1/v2/v3 and configured release certificate: $APK"
