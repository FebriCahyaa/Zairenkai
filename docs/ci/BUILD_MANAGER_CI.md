# Zairenkai Build Manager CI

## Purpose

`Build Manager CI` is the single automatic validation entry point for Zairenkai. Its shape follows the reusable-job fan-out used by KernelSU-Next, but it keeps Zairenkai's own security, update, and distribution policies.

The workflow does not publish a Release or a signed Canary. Those remain separate, protected distribution workflows:

- `.github/workflows/app-apks.yml`: official signed Canary prerelease.
- `.github/workflows/release.yml`: stable `vMAJOR.MINOR.PATCH` Release.
- `.github/workflows/issue-zkfc-token.yml`: separately approved scoped-license workflow.

## Automatic push and pull-request runs

The workflow fans out into independent jobs:

| Job | Coverage |
|---|---|
| Android | Optimized Canary/Release validation variants, unit tests, R8 checks, lint reports and validation APK artifacts |
| Native | Crypto, UAPI/ABI, userspace, Rust tests/build and host module compile |
| Atlas | Device/profile registry and source-boundary validation |
| Rustfmt | Rust formatting |
| Clippy | Rust static analysis |
| ShellCheck | Module and boot-patch shell scripts |
| CI workflow contracts | Checks reusable-workflow wiring, runner pinning, artifact labeling and module version-code output |

The reusable component workflows keep `workflow_call` and `workflow_dispatch`, but no longer independently trigger on push/PR. This prevents duplicate automatic runs; `build-manager-ci.yml` owns the push/PR triggers.

All Ubuntu jobs use `ubuntu-24.04` rather than the moving `ubuntu-latest` alias. The single-KMI on-demand LKM workflow (`kernel-lkm.yml`), all-KMI LKM workflow (`build-lkm.yml`), and reusable single-KMI DDK workflow (`ddk-lkm-zkfc.yml`) stay separate because they serve different operations.

## Full matrix build

Use **Actions → Build Manager CI → Run workflow** and select `full_build` after setting the repository Actions variable `ZKFC_LICENSE_SERVER_URL` to the actual production Worker HTTPS origin.

With `full_build=true`, the workflow:

1. Invokes the reusable `build-module.yml` pipeline.
2. Builds `zkfc.ko` in an 8-KMI matrix, with AArch64 and x86_64 outputs per KMI.
3. Builds and tests the Rust `zperfd` workspace and cross-compiles `zperfd` and `zkfctl`.
4. Packages the module and its checksum manifest.
5. Stages KMI-specific module assets and a verified embedded module into the application.
6. Tests and builds the optimized Canary variant with the exact module package.
7. Uploads the integrated validation APK, module ZIP, checksum material and R8 mappings.

The `full_build` input defaults to `false` so normal PR validation remains quick and does not require deployment configuration. The full build validates the URL before spending resources on the KMI matrix.

The integrated APK artifact is named `Zairenkai-canary-validation-unsigned.apk`. It is a validation artifact, **not an official install/distribution build**; it is deliberately not signed or published. The separate Canary/Release workflows remain responsible for protected signing, certificate verification, metadata signatures, attestations and release publication. No spoofed application-ID build is generated.

## Artifacts and summaries

Each component emits its own CI results. The Android job retains lint reports and optimized validation APKs/mappings for seven days. The integrated full-build artifacts are retained for fourteen days. The final summary job records the component result for the run, including intentional `skipped` results when `full_build` was not requested.

## License Server URL

`ZKFC_LICENSE_SERVER_URL` is a non-secret repository Actions variable, not a secret. Set it under:

**Repository → Settings → Secrets and variables → Actions → Variables**

The value must be the deployed production Worker origin, for example the account's real `workers.dev` hostname, with no path and no trailing slash. Do not assume a preview URL is production. A full matrix build fails before the KMI jobs start if the value is missing or malformed. Routine validation-only CI allows it to be unset; in that case the app updater is fail-closed and the resulting validation APK is not for distribution.
