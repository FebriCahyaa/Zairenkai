<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai secure release runbook

This is the required setup order for signed update metadata and scoped ZKFC token issuance. The checked-in update-metadata PEM is the app's pinned trust anchor. Stable release publication must fail closed unless the matching existing private-key backup is available to the protected signing environment; never replace this public key in place without a dual-key migration.

## 1. Configure GitHub Environments before creating signing keys

Create both environments under **Settings → Environments**. Do not store these credentials as repository-wide secrets.

- `production-signing`: require at least one reviewer and use custom deployment ref policies that explicitly allow the repository's protected default branch and the `v*` release-tag pattern. Put all APK/metadata signing secrets here: `ZK_KEYSTORE_BASE64`, `ZK_KEYSTORE_PASSWORD`, `ZK_KEY_ALIAS`, `ZK_KEY_PASSWORD`, `ZKFC_METADATA_SIGNING_KEY_B64`, `ZKFC_METADATA_SIGNING_PASSPHRASE`.
- Repository **Actions variable** `ZKFC_LICENSE_SERVER_URL` must contain the deployed production Worker's HTTPS origin (no path). This non-secret variable is read by CI and both distribution workflows.
- Commit pushes run validation/build CI and upload temporary Actions artifacts only; they do not create GitHub Releases or stable tags. Canary publication is an explicit manual run of `.github/workflows/app-apks.yml` using a successful Build Manager CI run ID. Stable Release is started only through Actions → Release → Run workflow; enter the version matching `app/build.gradle.kts`, and `release.yml` creates the `vX.Y.Z` tag and GitHub Release automatically only after the full signed build and metadata checks pass.
- `zkfc-token-issuer`: require reviewer approval, prevent self-review if available, and allow only the protected default branch. This environment receives tag-scoped delegated issuer credentials below.

Protect the default branch with required reviews and prevent untrusted pull-request workflows from accessing release environments.

## 1A. Interpreting the ZKFC API License state on a device

The License Server's installation enrollment is not a ZKFC kernel API license. A UI state of `missing` means no valid `.zkl` grant is active; it cannot be fixed by setting `ZKFC_LICENSE_SERVER_URL` or by creating a new Android APK keystore. The `Owner key: Not yet` state comes from the currently running `/dev/zkfc` implementation. The current source header must pass `python3 tools/ci/test-owner-key-provisioning.py`; after correcting a module package, install the newly built `Zairenkai-module.zip` and reboot, then confirm the device's owner-key fingerprint matches the build summary. If it remains the old fingerprint, the currently running device kernel/driver is still old (or built-in) and must be rebuilt/flashed with the pinned public key; do not blindly run `init-owner` because rotating that key invalidates existing grants.

After the owner key is visible, features remain locked until a real owner-signed `.zkl` token or authorized delegated grant is issued for the exact `CONFIG_ZKFC_LICENSEE_TAG` and installed/embedded. Use the existing `tools/zkfc-license/zkfc_license.py issue` flow with the owner's private key, or the protected `Issue scoped ZKFC token` workflow for a pre-authorized delegated subkey. Never fabricate a token and never put the owner private key in GitHub Secrets.

## 2. Provision the release metadata signing key

On a trusted workstation with GitHub CLI authenticated to the repository:

```sh
python3 -m pip install cryptography
python3 tools/release/setup-metadata-signing-key.py --repo FebriCahyaa/Zairenkai
```

The helper refuses to generate a key unless the `production-signing` environment already requires reviewers and has a custom deployment policy containing the default branch plus `v*` tags. It creates an encrypted Ed25519 PKCS#8 private key outside the checkout at `~/.config/zairenkai/release-metadata-ed25519.pem` with restrictive permissions. The passphrase is requested interactively and stored as a separate GitHub Actions environment secret. The helper uploads `ZKFC_METADATA_SIGNING_KEY_B64` and `ZKFC_METADATA_SIGNING_PASSPHRASE` to `production-signing`, and writes only the public key to:

`app/src/main/assets/security/update-metadata-public-key.pem`

Review and commit that public PEM file. Never commit the private-key backup or passphrase. Keep a second encrypted backup offline. In-place key rotation is not supported by the single-key pin; ship a dual-key migration before rotating the trust anchor.

### Make the same pinned signer available to manual Canary publication

`app-apks.yml` intentionally uses the separate `canary-signing` environment. If that environment does not contain the metadata signer secrets, Canary publication must fail closed with `Missing required canary-signing secret`. Do not copy a different key or generate a second key, because the application pins one public key. With the existing private-key backup and its passphrase available on your trusted machine, sync the *same* key:

```sh
python3 tools/release/sync-metadata-signing-secrets.py \
  --repo FebriCahyaa/Zairenkai \
  --environment canary-signing
```

The helper verifies that the existing encrypted private key matches the already-pinned public PEM before uploading the two secrets. It does not generate or rotate keys. If the private-key backup is lost, a value in GitHub Secrets cannot be read back; stop and design a deliberate dual-key migration rather than replacing the trust anchor blindly.

## 3. Provision the ZKFC owner key and the per-kernel delegated issuers

If and only if the owner public key is not already provisioned, on the owner's trusted machine run:

```sh
python3 tools/zkfc-license/zkfc_license.py init-owner
```

Review and commit only `kernel/security/zkfc_owner_key.h` containing the public key and provisioned marker. Keep `~/.zkfc/owner_ed25519.pem` and its passphrase offline; never upload the owner private key to GitHub Actions.

For every approved kernel tag, issue one restricted delegated subkey from the trusted owner environment. The workflow returns **only an Age-encrypted grant artifact** because the source repository is public. The licensee must generate an Age recipient identity locally and provide its public key during dispatch:

```sh
sudo apt-get install age  # or install Age from its official package for your OS
mkdir -p ~/.config/zairenkai
chmod 700 ~/.config/zairenkai
age-keygen -o ~/.config/zairenkai/license-recipient.agekey
age-keygen -y ~/.config/zairenkai/license-recipient.agekey
```

Keep `license-recipient.agekey` private. Copy only the printed `age1...` recipient string into the workflow input `recipient_age_public_key`. After downloading the short-retention Actions artifact, verify the ciphertext using its `SHA256SUMS`, then decrypt/extract it locally:

```sh
sha256sum -c SHA256SUMS
age --decrypt --identity ~/.config/zairenkai/license-recipient.agekey ZKFC-<RUN_ID>.tar.gz.age > ZKFC-<RUN_ID>.tar.gz
tar -tzf ZKFC-<RUN_ID>.tar.gz
mkdir -m 700 -p ZKFC-grant && tar -xzf ZKFC-<RUN_ID>.tar.gz -C ZKFC-grant
(cd ZKFC-grant && sha256sum -c SHA256SUMS)
```

The artifact contains the `.zkl` grant and `.inc` kernel include only after decryption. Never publish the decrypted files to a public release or issue comment.

Example (replace the tag and scope; do not copy the sample identity blindly):

```sh
python3 tools/zkfc-license/zkfc_license.py issue-subkey \
  --holder "Zairenkai Actions issuer" \
  --tag "your-approved-kernel-tag" \
  --features input_boost,task_boost \
  --days 365 \
  --out /secure/path/zairenkai-issuer
```

Add the *exact* `CONFIG_ZKFC_LICENSEE_TAG` to `tools/zkfc-license/approved-kernel-tags.txt` in a reviewed commit. Then upload that tag's `.zksub` as a base64-encoded **environment** secret in `zkfc-token-issuer`. The workflow maps non-alphanumeric tag characters to `_`, collapses repeated underscores, strips leading/trailing underscores and uppercases the result.

```sh
TAG='your-approved-kernel-tag'
SLUG="$(python3 - "$TAG" <<'PY'
import re, sys
slug = re.sub(r"_+", "_", re.sub(r"[^A-Za-z0-9]", "_", sys.argv[1])).strip("_").upper()
print(slug)
PY
)"
base64 -w0 /secure/path/zairenkai-issuer.zksub |
  gh secret set "ZKFC_SUBKEY_${SLUG}" --env zkfc-token-issuer --repo FebriCahyaa/Zairenkai
```

Use a separate subkey for every approved kernel tag. The owner-signed subkey certificate caps the tag, API major, permitted features, flags, and validity period. Add a tag to the allowlist only after reviewing the corresponding license request. The manual **Issue scoped ZKFC token** workflow validates the selected tag, checks the API major against kernel UAPI, requires environment approval, verifies the resulting grant against the pinned owner public key, encrypts the archive to the requester's Age recipient key, and publishes only short-lived ciphertext plus its checksum.

## 4. Release flow and checks

The trusted Release workflow (`.github/workflows/release.yml`, triggered by version tags such as `v1.1.0`) and the Canary workflow (`.github/workflows/app-apks.yml`, enabled on the development branch) follow this division:

1. checks that `tools/zkfc-license/zkfc_license.py:API_MAJOR` equals `kernel/include/uapi/linux/zkfc.h:ZKFC_API_MAJOR`;
2. builds the Android app, engine binaries and module archive;
3. generates the channel-specific update manifest containing schema version, app version, API major, CI build ID, commit identity and APK SHA-256;
4. signs the exact manifest bytes with Ed25519, bound to the file name, payload digest and public-key ID;
5. verifies the detached Ed25519 signature and every artifact hash against the signed release manifest, publishes the update manifest and release artifact manifest with their `.sig` files, and emits GitHub artifact attestations;
6. fails closed for official signing when the public key is unprovisioned, a signing secret is absent, the signing key does not match the pinned key, or API major definitions disagree.

The app updater fetches signed Canary/Release metadata through the License Server's bounded read-only proxy, verifies the detached signature before parsing the manifest, and verifies APK SHA-256 after download. After downloading release assets, any user can verify the manifest and listed artifact bytes with `python3 tools/ci/verify-release-manifest.py --manifest Zairenkai-release-manifest.json --signature Zairenkai-release-manifest.json.sig`. The kernel does not consume network metadata; it independently verifies signed license tokens/certificates, binding, API major, expiry, revocation and feature scope at the kernel boundary.

## 5. Security boundaries

- Keep the repository visibility decision separate from the signing architecture. This workflow does not change the current repository visibility.
- Do not encrypt all source as a substitute for repository access control. Build runners must be able to decrypt source, so source-encryption secrets could leak from a compromised workflow. Encrypt only genuinely confidential data and private credentials.
- SHA-256 detects accidental corruption and inconsistent changes, but a checksum stored with payload is not a signature. The embedded APK pins the module ZIP digest; the signed stable update manifest is the release metadata trust anchor; GitHub attestations establish build provenance.
- Root compromise is outside the guarantee of on-device checksum files: an attacker with full root privileges can replace local payloads and their local checksum manifest together.
- The Android Gradle build and lint suite still need to run on a network-enabled GitHub Actions runner after setup. Static tests do not substitute for device tests.
