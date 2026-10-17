<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai module (Magisk / KernelSU / APatch)

Flashable module that:

- verifies the packaged `SHA256SUMS` before install and before boot-time payload execution; the official app additionally pins the complete module ZIP SHA-256 in its signed build;

1. installs the compatibility CLI `zkfctl` to `/data/adb/zkfc/zkfctl`;
2. installs/starts the resident `zperfd` policy engine;
3. loads `zkfc.ko` only when the running kernel exposes a supported Android KMI and the exact KMI/ABI artifact passes metadata and hash checks; it refuses speculative same-family fallback when the KMI is unknown;
4. restores the persistent ZKFC token and starts the resident `zperfd` policy engine; performance state is recovered transactionally; **Zairenkai ThermoGuard** provides the kernel-side thermal boost safety envelope, and SAFE MODE prevents profile re-application after repeated failed boots.

On kernels where ZKFC is built in (`CONFIG_ZKFC=y`, typical for non-GKI),
`/dev/zkfc` already exists and no `.ko` is loaded.

## Package layout

```
module.prop            id/version/author
customize.sh           install: require a verifiable KMI + ABI module unless ZKFC is built in
service.sh             boot: insmod + token restore + zperfd + fail-safe
uninstall.sh           cleanup /data/adb/zkfc
META-INF/.../update-binary   installer trampoline
lkm/<kmi>-<abi>/zkfc.ko      prebuilt GKI modules (added at release time); runtime loader checks compatibility
bin/<abi>/zkfctl             compatibility/admin CLI (added at release time)
bin/<abi>/zperfd             resident performance engine (added at release time)
zperf/catalog/*.toml         validated device/mode profiles
```

The release workflow fills `lkm/` and `bin/` from the kernel LKM matrix and the
userspace build, then zips this directory into `Zairenkai-module.zip`.

The Scene/vtools adapter is stored in the module-owned `/data/adb/zperf/`
namespace. For legacy integrations, `/data/powercfg.sh` and
`/data/powercfg.json` are installed only when absent or already marked as
Zairenkai-owned; a different module's existing files are preserved. Uninstall
removes only marked global compatibility files. Uninstall stops/removes the
module-owned executables and transient daemon files but preserves the user's
`profile.toml`, `custom.toml`, mode preference, catalog/database cache, and
owner-signed license/CRL so reinstalling does not erase user data.

Performance features stay locked until a valid owner-signed ZKFC API Token is
installed from the app (Sistem → Lisensi API) or embedded into a kernel build
with an authorized `.inc` grant. A new app/commit build ID is metadata, not a
new cryptographic API major; only bump `API_MAJOR` for an incompatible token/API
contract. Do not embed the private signing key in the APK, module, or kernel.

`SHA256SUMS` is an integrity/corruption check, not a digital signature. A user
with full root access can replace both files and the manifest; the release APK
pin protects the initial embedded ZIP only. See `docs/security/API_TOKENS.md`
for the trust boundary and opt-in per-build token helper.

## Module updater channels

Canary packaging stamps `updateJson` to `/v1/updates/module/canary`; tagged stable releases use `/v1/updates/module/release`. The License Server returns bounded metadata that points to the corresponding official GitHub Releases module ZIP. The package version code follows the GitHub Actions run ID, shared across workflows, so a later Canary/Release package does not automatically appear to be a downgrade just because its channel uses a different version-code range. APK version codes remain independently derived from the Android app version/channel.
