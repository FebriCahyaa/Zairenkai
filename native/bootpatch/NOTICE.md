# ZKFC boot-image patch backend

`native/bootpatch` is a separately licensed GPL-3.0-only command-line component. It uses the public `android-bootimg` crate, pinned to commit `150425b027c76ea104c82e408571651f2181b2c2`, the same image codec dependency used by KernelSU-Next's `ksud` boot patch path.

- KernelSU-Next patching source: https://github.com/KernelSU-Next/KernelSU-Next/blob/dev/userspace/ksud/src/boot_patch.rs
- KernelSU-Next init-chainloader reference: https://github.com/KernelSU-Next/KernelSU-Next/tree/dev/userspace/ksuinit
- Image codec source: https://github.com/5ec1cff/android_bootimg
- `android-bootimg` license: GPL-3.0; the corresponding license text is in `native/bootpatch/LICENSE`.
- Zairenkai-specific changes: preserve the ramdisk's original `init` as `init.real` when present, inject the separately built `zkinit` PID 1 adapter and `zkfc.ko`, add a non-runtime diagnostic KMI manifest, enforce module/init ELF architecture and vermagic/KMI checks, then repack and re-open the result for structural verification.
- `zkinit` follows KernelSU-Next's GPL init-chainloading pattern: it attempts `init_module(/zkfc.ko)`, records a best-effort message in `/dev/kmsg`, and continues into the original `/init.real` or `/system/bin/init` even if module loading fails. It does not implement KernelSU-Next's advanced vermagic repair, kallsyms relocation, or other kernel-specific fallback logic.

The `zkbootpatch` and `zkinit` tools are built as standalone Android executables and are not linked into the proprietary Kotlin application. They are packaged as native-library-directory executables under `jniLibs/` and invoked by path; the app-private cache is used only for image/module input and output files.

This implementation is a file patch workflow; it does not write to boot partitions. It does not bypass AVB, sign a modified image, or guarantee that a bootloader with verification enabled will accept the output. It also cannot guarantee that a device kernel will accept an unsigned module; module-signature policy and the exact KMI still apply.
