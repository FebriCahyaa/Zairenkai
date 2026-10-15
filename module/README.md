<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai module (Magisk / KernelSU / APatch)

Flashable module that:

1. installs the compatibility CLI `zkfctl` to `/data/adb/zkfc/zkfctl`;
2. installs/starts the resident `zperfd` policy engine;
3. on GKI devices, prefers the detected KMI's `zkfc.ko`; on vendor kernels without a KMI marker it tries same-family candidates and relies on kernel vermagic/symbol checks
   (`service.sh`, preferring KMI + ABI when discoverable and trying same-family fallbacks otherwise);
4. restores the persistent ZKFC token and starts the resident `zperfd` policy engine; performance state is recovered transactionally; **Zairenkai ThermoGuard** provides the kernel-side thermal boost safety envelope, and SAFE MODE prevents profile re-application after repeated failed boots.

On kernels where ZKFC is built in (`CONFIG_ZKFC=y`, typical for non-GKI),
`/dev/zkfc` already exists and no `.ko` is loaded.

## Package layout

```
module.prop            id/version/author
customize.sh           install: prefer detected KMI, keep same-family module fallbacks
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

Performance features stay locked until a ZKFC API Token is installed from the
app (Sistem → Lisensi API). Tokens are issued only by the project owner.
