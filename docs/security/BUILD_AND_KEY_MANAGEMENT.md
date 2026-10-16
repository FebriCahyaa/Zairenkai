<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai Build Trust and Key Management

## Trust boundaries

Zairenkai deliberately separates keys by purpose. Do not reuse one private key for API licensing, Android APK signing, release provenance, or source encryption.

- **Owner Ed25519 key:** keep the master private key offline in the owner's controlled environment. Its public key is committed in `kernel/security/zkfc_owner_key.h`; it is not a secret.
- **Delegated ZKFC issuer subkey:** Actions may receive a *scoped* `.zksub` file through a tag-specific `zkfc-token-issuer` environment secret named `ZKFC_SUBKEY_<NORMALIZED_TAG>` (for example `ZKFC_SUBKEY_GARNET_SM8550`). The owner creates the delegated key and caps its kernel tag, API major, features, flags, and expiry. Do not put the master private key in Actions.
- **Android upload key:** use the existing `ZK_KEYSTORE_BASE64`, `ZK_KEYSTORE_PASSWORD`, `ZK_KEY_ALIAS`, and `ZK_KEY_PASSWORD` secrets in the protected `production-signing` environment. Do not use the licensing key for APK signing.
- **Release provenance:** the release workflow generates a SHA-256 manifest and GitHub artifact attestations using GitHub's OIDC-backed signing mechanism. This avoids introducing a long-lived release-signing private key just for provenance.

## Required GitHub configuration

Before enabling the issuer workflow:

1. Provision the correct owner public key locally with `tools/zkfc-license/zkfc_license.py init-owner` only if there is no existing owner key. Commit only `kernel/security/zkfc_owner_key.h` after reviewing it. Never rotate an established owner key casually; it invalidates grants tied to the previous key.
2. Create a delegated key **on the owner's trusted machine**, for example:

   ```sh
   python3 tools/zkfc-license/zkfc_license.py issue-subkey \
     --holder "Zairenkai Actions issuer" \
     --tag "your-approved-kernel-tag" \
     --features input_boost,task_boost \
     --days 365 \
     --out /secure/path/zairenkai-actions
   ```

   The example intentionally uses a kernel-specific tag and a limited feature set. Choose the actual approved scope; do not copy the sample tag blindly.
3. Add the matching tag to `tools/zkfc-license/approved-kernel-tags.txt` through a reviewed commit.
4. In **Settings → Environments**, create `zkfc-token-issuer`. Configure required reviewers, prevent self-review where available, and restrict deployment branches to the repository's protected default branch. Add a tag-specific secret named `ZKFC_SUBKEY_<NORMALIZED_TAG>` (for example `ZKFC_SUBKEY_GARNET_SM8550`) as an **environment secret**, not a repository-wide secret. Its value is the base64 encoding of that tag-specific `.zksub` file. Use a separate subkey for each approved kernel tag.
5. Protect the default branch with review requirements and prevent untrusted pull-request workflows from accessing environment secrets.
6. Create a separate `production-signing` environment containing `ZK_KEYSTORE_BASE64`, `ZK_KEYSTORE_PASSWORD`, `ZK_KEY_ALIAS`, and `ZK_KEY_PASSWORD`. Require approval and allow only the protected default branch plus `v*` release tags as deployment refs. Do not rely on a YAML `if` condition alone as the security boundary; the Environment's deployment branch/tag rules and reviewer approval are also required.

The issuer workflow is manual and checks the requested tag against the committed allowlist. The token it produces is a *signed grant*, not a signing key. The generated grant is bound to the selected kernel tag and constrained by the delegated certificate. Because the repository is public, the workflow requires a recipient Age public key and uploads only an Age-encrypted archive plus its ciphertext checksum; the private recipient identity is retained by the licensee. Never publish a decrypted grant.

Ordinary app builds get a new `ZKFC_BUILD_ID`, not a newly issued license. `API_MAJOR` changes only for an intentionally incompatible protocol revision. Because a delegated certificate is pinned to its permitted API major, an API-major upgrade requires the owner to issue a replacement scoped subkey; until then, token issuance fails closed. This prevents a normal app patch from needlessly expiring all user grants.

## Release integrity and provenance

For each release, `tools/ci/generate-release-manifest.py` records SHA-256 hashes, sizes, the Git commit, workflow run, release version, and ZKFC API major. `Zairenkai-release-SHA256SUMS` lets users detect accidental corruption. The release workflow also creates GitHub artifact attestations for release artifacts; consumers can verify them with GitHub CLI, for example:

```sh
gh attestation verify Zairenkai-module.zip -R FebriCahyaa/Zairenkai
gh attestation verify Zairenkai-release-manifest.json -R FebriCahyaa/Zairenkai
```

The APK's Android signing certificate, its embedded module hash pin, and a GitHub provenance attestation serve different purposes. SHA-256 alone is not a signature. The module's internal checksum manifest detects inconsistent payload changes, while the APK's signed BuildConfig binds the embedded ZIP hash to the APK signer. GitHub artifact attestations provide build provenance and can be verified separately.

## Do not encrypt the entire Git repository as a security substitute

A build runner must eventually decrypt source to compile it, so an encrypted source bundle does not protect the source from a compromised build job. It also does not erase source from prior Git history, forks, caches, or previously downloaded archives. Keep ordinary source in a repository with the intended visibility and access controls. Encrypt only specific confidential data or distribution artifacts where there is a clear recipient key and decryption workflow.

The current `FebriCahyaa/Zairenkai` repository is public. These changes do not change repository visibility. If source confidentiality is a requirement, move to or explicitly convert to a private repository and review GitHub plan limitations before relying on artifact attestations; public source cannot be made confidential with a ciphertext sidecar alone.
