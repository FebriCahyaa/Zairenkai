<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai release and API trust model

This is the operating model for release builds, update metadata, and ZKFC
license issuance. It deliberately separates the keys by purpose.

## Key roles

| Key | Where its private half lives | What it may authorize | Verification |
| --- | --- | --- | --- |
| ZKFC owner Ed25519 key | Owner workstation/offline backup; `~/.zkfc` with restrictive permissions | Direct grants and owner-signed delegated-subkey certificates | Public key pinned in `kernel/security/zkfc_owner_key.h`; kernel verifies the token/certificate, API major, expiry, revocation and kernel binding |
| ZKFC delegated issuer subkey | Protected Actions environment `zkfc-token-issuer`, after owner approval | Only the features, validity, API major and kernel scope encoded in its owner-signed certificate | Kernel verifies the owner-signed certificate and the subkey signature on each grant |
| Update-metadata Ed25519 key | Encrypted PKCS#8 backup plus protected `production-signing` environment secrets | Detached signatures over exact `version-release.json` and release-manifest bytes | App pins `app/src/main/assets/security/update-metadata-public-key.pem`; CI and app check key ID, file name, payload SHA-256 and Ed25519 signature |
| Android APK signing key | Existing release keystore backup plus protected release secret | Android package identity and APK signatures | Android Package Manager, `SignatureGuard`, and CI's `apksigner` fingerprint verification |
| GitHub artifact attestation | Keyless GitHub Actions/OIDC identity; no long-lived private key | Provenance of produced release artifacts | GitHub artifact-attestation verification tools |

Never reuse one private key for these roles. Do not put the ZKFC owner key in
GitHub Actions. Actions token issuance selects a tag-specific secret named
`ZKFC_SUBKEY_<NORMALIZED_TAG>` (for example `ZKFC_SUBKEY_GARNET_SM8550`), a
scoped subkey whose certificate is already signed by the offline owner key. Keep the
`zkfc-token-issuer` environment gated with required reviewers, and allow token
issuance only from the protected default branch after a separate allowlist change
for `approved-kernel-tags.txt`.

## Metadata signatures and update behavior

The updater fetches `version-release.json` / `version-canary.json` and the
matching detached signature through the configured License Server routes
`/v1/updates/release` and `/v1/updates/canary`. The Worker proxies bounded
metadata from the official GitHub Releases assets; it never proxies APK bytes.
The signature covers the exact manifest bytes and binds the asset name, a
SHA-256 of the manifest, and the public-key identifier. Both channels fail
closed as `BAD_MANIFEST` when the signature is absent or invalid. Canary and
Release builds are non-debuggable and use the configured official APK signer.
The debug build type is disabled for distribution tasks.

Every release manifest records `apiMajor`, `buildId`, commit, version and the
SHA-256/size of artifacts. The CI consistency check requires
`tools/zkfc-license/zkfc_license.py:API_MAJOR` to equal
`kernel/include/uapi/linux/zkfc.h:ZKFC_API_MAJOR`; Gradle checks this too. The
build ID changes with the workflow attempt, but API major only changes for an
intentional protocol compatibility change.

The kernel does not fetch remote release metadata. It independently validates
the Ed25519 token or delegated certificate, kernel binding, API major, expiry,
revocation state and feature scope at the kernel boundary. This avoids putting
network trust inside a kernel driver. The app verifies release metadata and APK
signing identity, then uses `zkfctl`/ZKFC as the authoritative license verifier.

## Provisioning the update metadata key

1. Create the GitHub Actions environment `production-signing`; restrict deployment
   refs to the protected release branch/tags and require a reviewer.
2. On a trusted workstation, run:

   ```sh
   python3 -m pip install cryptography
   python3 tools/release/setup-metadata-signing-key.py --repo FebriCahyaa/Zairenkai
   ```

   The helper creates a dedicated Ed25519 key, stores an encrypted PKCS#8 backup
   outside the checkout with mode `0600`, writes only the public key to the app
   asset, and uploads the encrypted private key and passphrase as separate
   environment secrets. It refuses to overwrite an existing key. Keep the private backup and passphrase
   in separate secure locations. In-place rotation is intentionally unsupported:
   implement a staged dual-key migration (old key remains accepted until clients
   have upgraded) before rotating the metadata pin.
3. Review and commit only
   `app/src/main/assets/security/update-metadata-public-key.pem`. Never commit
   the private key, passphrase, decoded token, APK keystore, or subkey file.
4. Configure the non-secret Actions **repository variable** `ZKFC_LICENSE_SERVER_URL`
   as the HTTPS origin of the deployed production Worker (for example,
   `https://your-worker.workers.dev`, without a trailing path). The build/CI
   workflows also need this value, so do not scope it only to an environment.
5. Required secrets in `production-signing` and `canary-signing` are
   `ZKFC_METADATA_SIGNING_KEY_B64` and `ZKFC_METADATA_SIGNING_PASSPHRASE`. CI
   refuses official metadata signing when either is missing or the private key
   does not match the public key pinned in the app. Signing keys should be the
   same metadata trust anchor for both channels, unless a deliberate staged key
   rotation is underway.
6. Rotate by provisioning the replacement key, updating the pinned public key,
   and shipping an app update with the new pin before retiring the old public
   key. A rotation changes the app trust anchor; do not rotate casually.

The existing APK secrets (`ZK_KEYSTORE_BASE64`, `ZK_KEYSTORE_PASSWORD`,
`ZK_KEY_ALIAS`, `ZK_KEY_PASSWORD`) remain separate. Reuse the established APK
keystore for Android in-place updates; don't replace it with the metadata key.

## Encryption and repository visibility

Do not encrypt the whole source tree and then commit ciphertext as a substitute
for access control. CI would have to decrypt that source and any workflow allowed
to read the decryption secret could exfiltrate it. Keep source readable to the
repository's intended collaborators, restrict repository permissions, and put
only the genuinely sensitive objects (private signing keys, credentials, and
per-license grants) in encrypted secrets or encrypted local storage. A public
repository is not confidential: changing the repository to private is a
separate owner decision and is not performed by these build scripts.

## Integrity boundaries

- The module ZIP SHA-256 is pinned in the signed APK's `BuildConfig` before the
  app invokes a root-manager installer.
- `SHA256SUMS` checks the exact file set and payload bytes inside the ZIP; copied
  runtime binaries are checked at installation/boot by the module scripts.
- Stable update manifests require Ed25519 signatures before parsing. APK SHA-256
  is checked after download, and Android independently enforces the APK signer.
- GitHub Actions attestations bind artifact hashes to the workflow identity.
- A hash file alone is not a signature. A process with full root access can
  replace local payloads and hashes together; these checks do not promise to
  defeat a fully privileged local attacker.
