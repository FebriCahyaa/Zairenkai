# ZKFC boot-image patch backend

`native/bootpatch` is a separately licensed GPL-3.0-only command-line component. It uses the public `android-bootimg` crate, pinned to commit `150425b027c76ea104c82e408571651f2181b2c2`, the same image codec dependency used by KernelSU-Next's `ksud` boot patch path.

- KernelSU-Next patching source: https://github.com/KernelSU-Next/KernelSU-Next/blob/dev/userspace/ksud/src/boot_patch.rs
- Legacy init-chainloader reference: https://github.com/KernelSU-Next/KernelSU-Next/tree/dev/userspace/ksuinit
- Image codec source: https://github.com/5ec1cff/android_bootimg
- `android-bootimg` license: GPL-3.0; the corresponding license text is in `native/bootpatch/LICENSE`.
- Current Zairenkai patcher: preserves `/init` byte-for-byte, preserves the original `init.rc`, adds `zkfc.ko` and a diagnostic manifest, defers module loading until `sys.boot_completed=1`, and reopens the output image for structural verification.
- `src/bin/zkinit.rs` is legacy-only and is not built or packaged by `tools/bootpatch/build-android.sh`. Its module autoload is intentionally disabled to prevent inserting `zkfc.ko` as PID 1.

The current Android app bundles only the `zkbootpatch` executable under the native-library directory and invokes it by path. The app-private cache is used only for image/module input and output files.

This is a file patch workflow; it does not write to boot partitions. It does not bypass AVB, sign a modified image, or guarantee that a bootloader with verification enabled will accept the output. It also cannot guarantee that a device kernel will accept an unsigned module or that its hooks are runtime-compatible; module-signature policy and the exact KMI still apply.
