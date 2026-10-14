# Zairenkai Android release signing

## Existing Actions secrets

The release workflows use these **repository Actions secrets**:

| Secret | Value |
| --- | --- |
| `ZK_KEYSTORE_BASE64` | Base64 of the existing `.jks`/`.p12` release keystore, without quotation marks |
| `ZK_KEYSTORE_PASSWORD` | Keystore password |
| `ZK_KEY_ALIAS` | Exact alias inside the keystore |
| `ZK_KEY_PASSWORD` | Private-key password (for PKCS#12, use the same password as the keystore) |

Set them under **Settings → Secrets and variables → Actions → Repository secrets**.
Do not put values into workflow YAML, `gradle.properties`, source files, commits, or build logs.

## Important: reuse the same key

If Zairenkai has already been distributed with a release certificate, keep using that same keystore for future updates. A freshly generated key is a different application identity for Android package-signature checks and can prevent an update from installing over the existing app. Back up the keystore offline and keep its password in a password manager. GitHub Actions secrets cannot be read back after they have been saved.

The uploaded source archive contains no private keystore, so it cannot sign a release by itself. The workflow reads the four secrets above at build time.

## First-time key setup (only when no release key exists)

Run this on a trusted computer or in your Termux Ubuntu environment with Java 21 and GitHub CLI (`gh`) authenticated for the repository:

```bash
gh auth status
 ./tools/release/setup-signing-key.sh --repo FebriCahyaa/Zairenkai
```

The script creates a local PKCS#12 RSA-4096 release keystore with a validity of 10,000 days, prompts for a strong password without echoing it, and sets the four GitHub repository secrets. The local file is written outside the repository with restrictive permissions. The script refuses to overwrite existing signing secrets by default. Use `--replace-existing-secrets` only when you understand the impact on users with an already-installed release.

## Build and verify

The CI release build runs:

```bash
./gradlew :app:assembleStandardRelease :app:testStandardDebugUnitTest --stacktrace
```

It then runs `apksigner verify --verbose` and requires all three schemes to report `true`:

- v1 — JAR signing, for older Android compatibility.
- v2 — APK Signature Scheme v2.
- v3 — APK Signature Scheme v3.

CI compares the certificate digest embedded in the APK with the X.509 certificate exported from the exact keystore alias used by the build. The app's runtime `SignatureGuard` expected fingerprint is also generated from that release keystore, instead of relying on an outdated hard-coded fingerprint.

A new release must be built on a trusted push to the configured branch or by a version tag/workflow dispatch with the secrets available. Pull requests from forks do not receive signing secrets.
