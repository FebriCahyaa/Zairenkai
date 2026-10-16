# Zairenkai boot-patch/runtime integration fixes

Base source: `FebriCahyaa/Zairenkai`, branch `ccr-076d8b15-osiupp`, commit
`c8c1f21a8b5eef285f62be412e468b01b8cce658` (`fix(boot): handle vermagic without Android KMI branch`).

## What changed

- The native patcher now accepts only Android boot header v3/v4 images that
  already contain the generic init ramdisk and `/init`. It refuses kernel-only
  `boot.img`, `vendor_boot.img`, legacy headers, and ramdisks without `/init`
  instead of manufacturing an empty CPIO ramdisk.
- Android 13+ workflow now supports selecting `init_boot.img` as the ramdisk
  target and the matching `boot.img` from the same ROM/build as the kernel
  reference. When both images contain a kernel, their embedded kernel releases
  must match. The output image is saved to a new file; no partition is flashed.
- KMI selection and module validation distinguish a detected kernel KMI from a
  manually selected hint. The helper validates ELF architecture, Linux
  major/minor family, module `vermagic`, and a kernel KMI marker when available.
  It does not claim that a branchless `5.10.269-gki-...` banner proves KMI or
  symbol-version compatibility. The output CPIO manifest records the KMI source.
- `zkinit` preserves the original PID 1 as `/init.real`, attempts the early
  module load, logs errors to `/dev/kmsg`, and executes `/init.real` directly;
  it no longer depends on creating/unlinking a symlink or on `/system` existing
  during early boot.
- The root-manager installer correctly parses kernel releases shaped like
  `5.10.198-android13-9` rather than expecting `android13-5.10` inside
  `uname -r`. It retains same-family fallback candidates for branchless GKI
  banners, reports loader failures, and refuses a false-success installation
  if no viable `.ko` and no existing built-in `/dev/zkfc` endpoint are present.
- The service logs `insmod` errors and only starts `zperfd` after `/dev/zkfc`
  exists. Early daemon output is retained in `/data/adb/zperf/zperfd.log`.
- The app's file-patch flow has a kernel-reference picker and displays the
  detected full kernel release when no KMI branch can be detected.
- Documentation and Indonesian/English strings were updated to describe these
  constraints.

## Validation performed in this environment

- `bash -n` and `dash -n` pass for `module/customize.sh` and `module/service.sh`.
- All five `strings_boot_patch_flow.xml` resource files parse as valid XML.
- The shell KMI normalization fixture for
  `5.10.198-android13-9-gabcdef` resolves to `android13-5.10`.
- Kotlin files were passed through `kotlinc` for parser diagnostics; no Kotlin
  syntax errors were reported. A real Kotlin/Android compile was not possible
  because Android/Compose dependencies are not available to standalone
  `kotlinc` here.
- Rust unit tests and `cargo fmt` were not run because this environment does
  not have `cargo`, `rustc`, or `rustfmt` installed.
- Gradle/Android build was attempted, but the wrapper needs to download
  Gradle 9.8.1 from `services.gradle.org`; network resolution is unavailable
  in this environment. Thus build success is **not verified**.
- No physical device was available for a flash/boot test. These are source
  changes, not a validated patched boot image or release APK.

## Before using on a device

1. Build `Zairenkai-module.zip` through the repository's CI so it contains real
   per-KMI/ABI `zkfc.ko` binaries. A source checkout intentionally omits those
   generated artifacts.
2. Build the app only after staging the module artifacts using
   `tools/bootpatch/stage-module-assets.sh Zairenkai-module.zip`.
3. Run `cargo test --manifest-path native/bootpatch/Cargo.toml`,
   `cargo fmt --manifest-path native/bootpatch/Cargo.toml -- --check`, and
   `./gradlew :app:assembleStandardCanary -PlicenseServerUrl=https://<your-worker-origin>` in a fully provisioned build environment.
4. Keep stock `boot.img` and `init_boot.img` backups. If the device has
   `init_boot.img`, patch that image and pair it with `boot.img` from the same
   ROM/build. Do not flash until the selected `zkfc.ko` is built for a compatible
   KMI/configuration and a recovery path is ready.
5. On a branchless kernel release such as `5.10.269-gki-...`, the KMI cannot be
   derived from that release string alone. A successful repack is not proof of
   module ABI compatibility; inspect the boot/module logs and require
   `/dev/zkfc` to exist after reboot.
