<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai module (Magisk / KernelSU / APatch)

Flashable module that:

1. installs the `zkfctl` engine to `/data/adb/zkfc/zkfctl`;
2. on GKI devices, loads the matching prebuilt `zkfc.ko` at boot
   (`service.sh`, selected by KMI branch + ABI in `customize.sh`);
3. re-applies a saved boot profile if the app wrote `/data/adb/zkfc/boot_profile`.

On kernels where ZKFC is built in (`CONFIG_ZKFC=y`, typical for non-GKI),
`/dev/zkfc` already exists and no `.ko` is loaded.

## Package layout

```
module.prop            id/version/author
customize.sh           install: pick lkm/<kmi>-<abi>/zkfc.ko, install zkfctl
service.sh             boot: insmod + boot profile
uninstall.sh           cleanup /data/adb/zkfc
META-INF/.../update-binary   installer trampoline
lkm/<kmi>-<abi>/zkfc.ko      prebuilt GKI modules (added at release time)
bin/<abi>/zkfctl             prebuilt engine (added at release time)
```

The release workflow fills `lkm/` and `bin/` from the kernel LKM matrix and the
userspace build, then zips this directory into `Zairenkai-module.zip`.

Performance features stay locked until a ZKFC API Token is installed from the
app (Sistem → Lisensi API). Tokens are issued only by the project owner.
