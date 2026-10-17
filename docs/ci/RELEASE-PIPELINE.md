# Zairenkai CI, Canary, Release, and License Server

## Distribution contract

- `Build Manager CI` is validation-only. It compiles optimized variants and runs contracts; it does **not** upload an APK. Android debug variants are disabled in `app/build.gradle.kts`.
- A push to `ccr-076d8b15-osiupp` that touches app/build/CI inputs invokes the Canary pipeline. `workflow_dispatch` is also available on that branch. Missing signing credentials, an invalid server URL, or a failed server/D1 readiness check fails the run instead of silently skipping publication.
- A stable tag `vMAJOR.MINOR.PATCH` invokes `Release`. The tag must match `versionName` in `app/build.gradle.kts`; older stable tags are rejected.
- Each published channel produces a normal signed APK and a separately signed Spoof APK. Spoof IDs are regenerated per run as three six-character lowercase components, matching the KernelSU-Next `manager/spoof` pattern at the installed application-ID level. The Kotlin/Gradle namespace stays `com.zairenkai.app` so source-package references remain stable.
- Spoof IDs change on every build, so Spoof APKs intentionally do not use the standard in-app updater manifest. The updater remains enabled for standard APKs only.
- KMI builds are split by KMI line and architecture, then aggregated. The release gate checks that exactly 16 `zkfc.ko` files exist (8 KMI lines × `arm64`/`x86_64`) and verifies the archive's SHA-256 manifests before publication.
- Changelog generation reads actual Git history. Stable Release covers commits since the previous reachable lower semantic-version tag; Canary covers commits since the latest reachable stable tag, or the repository history if none exists.

## Required GitHub configuration

Set a **repository-level Actions variable** named `ZKFC_LICENSE_SERVER_URL` to the deployed production HTTPS origin, for example `https://zairenkai-license-server.zairenkai-lab.workers.dev`. Do not append a path. The reusable KMI/module workflow also needs this repository variable, so setting it only on an environment is not sufficient.

Configure the Canary signing environment `canary-signing` and the stable environment `production-signing` with these Actions secrets:

- `ZK_KEYSTORE_BASE64`
- `ZK_KEYSTORE_PASSWORD`
- `ZK_KEY_ALIAS`
- `ZK_KEY_PASSWORD`
- `ZKFC_METADATA_SIGNING_KEY_B64`
- `ZKFC_METADATA_SIGNING_PASSPHRASE`

The APK keystore must be the same long-lived signing identity used by previous official releases; replacing it would make Android reject updates over existing installations. The metadata signing private key must match the public key pinned in `app/src/main/assets/security/update-metadata-public-key.pem`. Never commit either private key. Canary and Release workflows verify both APK signature schemes and the certificate fingerprint, then sign and verify update metadata before publishing.

## Preflight and server limits

Before the expensive KMI matrix, and again before Canary/Release publication, CI requests `GET /v1/health` and `GET /v1/ready`. It fails closed unless health reports service `zairenkai-license-server`, API major `1`, and readiness confirms `database: connected`. After publishing, CI fetches `GET /v1/updates/{channel}` and its detached `.sig` through the configured Worker, checks version/channel/APK URL/API major, compares the manifest SHA-256 with the exact published standard APK, and verifies the Ed25519 signature with the public key pinned in the app. It also fetches `GET /v1/updates/module/{channel}` and verifies the module version, official module ZIP URL, and changelog URL. A stale cached response is retried; a persistent proxy/configuration failure makes the workflow fail. If the server is redeployed or the D1 database is not migrated/bound, distribution is blocked before packaging.

The supplied `Zairenkai-License-Server` implementation supports installation-key enrollment and proxies signed updater metadata (`/v1/updates/canary`, `/v1/updates/release` and module-update routes). Its own README explicitly states that license issuance, renewal, and revocation are **not implemented**. Enrollment is not proof that a kernel is approved and is not license activation. We must not generate pretend API tokens or claim full license activation until the issuance protocol, owner signing key, key-binding semantics, and kernel/token verifier have been designed and tested together. The CI integration verifies server/D1 readiness and the published updater endpoint end-to-end; it does not falsely declare the missing license-grant lifecycle complete.

## Verify a release

The release contains standard and Spoof APKs, SHA-256 sidecars, a standalone KMI bundle with checksums, the flashable module, signed update metadata, a signed artifact manifest, and an automatically generated `Zairenkai-CHANGELOG.md`. Verify checksums before sideloading. The normal APK is the one exposed by the in-app update manifest; the Spoof APK is a separate install identity and must be downloaded manually.
