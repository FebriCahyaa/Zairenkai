<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai secure release runbook

This is the required setup order for signed update metadata and scoped ZKFC token issuance.
The checked-in update-metadata public key intentionally remains `UNPROVISIONED` until the owner creates and pins a real key on a trusted workstation. Official stable release publication must fail closed until then.

## 1. Configure GitHub Environments before creating signing keys

Create both environments under **Settings → Environments**. Do not store these credentials as repository-wide secrets.

- `production-signing`: require at least one reviewer and use custom deployment ref policies that explicitly allow the repository's protected default branch and the `v*` release-tag pattern. Put all APK/metadata signing secrets here: `ZK_KEYSTORE_BASE64`, `ZK_KEYSTORE_PASSWORD`, `ZK_KEY_ALIAS`, `ZK_KEY_PASSWORD`, `ZKFC_METADATA_SIGNING_KEY_B64`, `ZKFC_METADATA_SIGNING_PASSPHRASE`.
- Repository **Actions variable** `ZK_RELEASE_ENABLED=true` opts in to publishing signed stable releases. Leave it unset/false until the protected environment and signing secrets are configured. It is only an opt-in switch, not a security credential; the workflow separately validates every secret inside the protected environment.
- `zkfc-token-issuer`: require reviewer approval, prevent self-review if available, and allow only the protected default branch. This environment receives tag-scoped delegated issuer credentials below.

Protect the default branch with required reviews and prevent untrusted pull-request workflows from accessing release environments.

## 2. Provision the release metadata signing key

On a trusted workstation with GitHub CLI authenticated to the repository:

```sh
python3 -m pip install cryptography
python3 tools/release/setup-metadata-signing-key.py --repo FebriCahyaa/Zairenkai
```

The helper refuses to generate a key unless the `production-signing` environment already requires reviewers and has a custom deployment policy containing the default branch plus `v*` tags. It creates an encrypted Ed25519 PKCS#8 private key outside the checkout at `~/.config/zairenkai/release-metadata-ed25519.pem` with restrictive permissions. The passphrase is requested interactively and stored as a separate GitHub Actions environment secret. The helper uploads `ZKFC_METADATA_SIGNING_KEY_B64` and `ZKFC_METADATA_SIGNING_PASSPHRASE` to `production-signing`, and writes only the public key to:

`app/src/main/assets/security/update-metadata-public-key.pem`

Review and commit that public PEM file. Never commit the private-key backup or passphrase. Keep a second encrypted backup offline. In-place key rotation is not supported by the single-key pin; ship a dual-key migration before rotating the trust anchor.

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

The trusted build workflow:

1. checks that `tools/zkfc-license/zkfc_license.py:API_MAJOR` equals `kernel/include/uapi/linux/zkfc.h:ZKFC_API_MAJOR`;
2. builds the Android app, engine binaries and module archive;
3. generates an update manifest containing schema version, app version, API major, CI build ID, commit identity and APK SHA-256;
4. signs the exact stable manifest bytes with Ed25519, bound to the file name, payload digest and public-key ID;
5. verifies the detached Ed25519 signature and every artifact hash against the signed release manifest, publishes both the update manifest and release artifact manifest with their `.sig` files, and emits GitHub artifact attestations;
6. fails closed for official signing when the public key is unprovisioned, a signing secret is absent, the signing key does not match the pinned key, or API major definitions disagree.

The stable updater verifies the detached signature before parsing the release manifest and verifies APK SHA-256 after download. After downloading release assets, any user can verify the manifest and listed artifact bytes with `python3 tools/ci/verify-release-manifest.py --manifest Zairenkai-release-manifest.json --signature Zairenkai-release-manifest.json.sig`. The kernel does not consume network metadata; it independently verifies signed license tokens/certificates, binding, API major, expiry, revocation and feature scope at the kernel boundary.

## 5. Security boundaries

- Keep the repository visibility decision separate from the signing architecture. This workflow does not change the current repository visibility.
- Do not encrypt all source as a substitute for repository access control. Build runners must be able to decrypt source, so source-encryption secrets could leak from a compromised workflow. Encrypt only genuinely confidential data and private credentials.
- SHA-256 detects accidental corruption and inconsistent changes, but a checksum stored with payload is not a signature. The embedded APK pins the module ZIP digest; the signed stable update manifest is the release metadata trust anchor; GitHub attestations establish build provenance.
- Root compromise is outside the guarantee of on-device checksum files: an attacker with full root privileges can replace local payloads and their local checksum manifest together.
- The Android Gradle build and lint suite still need to run on a network-enabled GitHub Actions runner after setup. Static tests do not substitute for device tests.
