<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Boot-image patching: implementation and safety contract

## Supported native patch path

The experimental Rust backend under `native/bootpatch` patches a supported
Android v3/v4 image that already contains a generic init ramdisk. It adds
`zkfc.ko` and a diagnostic manifest, preserves Android's original `/init`
byte-for-byte, installs a `post-fs-data` init action, repacks to a new file, then
parses and verifies the result again. It never writes to a partition or flashes
an image.

The patcher does **not** replace PID 1, does not install `zkinit`, and does not
try to insert a kernel module while Android init is bootstrapping the device.
Instead, it appends an `on post-fs-data` action to the ramdisk's `init.rc` that asks Android init to load `/zkfc.ko` after userdata is mounted and before `sys.boot_completed=1`. This is early enough for Zairenkai services to observe `/dev/zkfc`, while avoiding a custom PID-1 chainloader. If the module load fails, Android init logs the failure and the root-manager service can still report/retry its own validated module load. This timing does not prove that the LKM itself is safe for a particular kernel or eliminate bugs caused by incompatible hooks.

### Why this init strategy

Zairenkai follows KernelSU-Next's boot-image CPIO parsing and generic-ramdisk selection approach, but intentionally does not transplant KernelSU-Next's `ksuinit` PID-1 chainloader. ZKFC is loaded by Android init from a normal `init.rc` action, leaving the original `/init` binary byte-for-byte unchanged. APatch's boot flow is a different design: its `boot_patch.sh` uses `kptools` to patch the kernel image, so it is a reference for kernel-image patching rather than a template for replacing Android PID 1. See [KernelSU-Next boot patcher](https://github.com/KernelSU-Next/KernelSU-Next/blob/dev/userspace/ksud/src/boot_patch.rs), [KernelSU-Next init entrypoint](https://github.com/KernelSU-Next/KernelSU-Next/blob/dev/userspace/ksuinit/src/init.rs), [APatch boot patch script](https://github.com/bmax121/APatch/blob/main/app/src/main/assets/boot_patch.sh), and the [AOSP init trigger sequence](https://android.googlesource.com/platform/system/core/+/refs/heads/main/init/README.md).

The `post-fs-data` trigger runs after `/data` setup and before `zygote-start` on Android init's documented trigger sequence. That gives Zairenkai earlier module readiness than a `sys.boot_completed=1` action, but it also means an incompatible module can affect startup; the runtime hook and module ABI still require real-device validation.

### Select the right image pair

- **Android versions where `boot.img` contains the generic ramdisk:** select
  that `boot.img` as the patch target. A kernel reference is optional.
- **Android 13 and newer, where the generic ramdisk is normally in
  `init_boot.img`:** select `init_boot.img` as the patch target and the matching
  `boot.img` from the exact same device and ROM build as the kernel reference.
  Output remains an `init_boot`-format image.
- The backend also supports the selected generic ramdisk fragment in
  `vendor_boot.img`; it does not synthesize an empty ramdisk into a kernel-only
  `boot.img`. Raw CPIO inputs and legacy boot headers v0-v2 are refused.
- Only use unmodified source images from the same firmware build. Images that
  contain the older `init.real` PID-1 patch marker are refused; restore a clean
  stock image instead of stacking patches.

### KMI/module compatibility checks

The helper checks ELF architecture, module `vermagic`, KMI branch where
available, and Linux major/minor family. It also verifies that repacking did not
change the kernel release embedded in a `boot.img` target.

A kernel release such as `5.10.269-gki-g18fe99112273` does not itself prove an
Android KMI branch. For such a kernel, manually picking `android13-5.10` (or
another branch with the same Linux major/minor) is not accepted as proof. The
operator must provide the matching kernel image or a vendor `.ko` from the exact
same firmware build so the KMI can be inferred from its `vermagic`. The helper
can check the metadata it is given; it cannot independently prove that an
arbitrary vendor module truly came from the same firmware. Kernel symbol CRC,
configuration, signature policy, and runtime checks remain authoritative.

### Runtime status and recovery

The patcher retains the original `init.rc` as
`init.zairenkai.original.rc`, installs a modified `init.rc` with the post-fs-data
trigger, and verifies both the byte-for-byte original `/init` and backup
`init.rc`. The format-5 diagnostic manifest records KMI source, kernel release, patcher
version, and the post-fs-data load trigger. `/dev/zkfc` may still be absent if the kernel rejects the
module or its initialization fails; in that case Zairenkai should not report
ZKFC as ready, and `zperfd` should not be started.

This backend is not full MagiskBoot or KernelSU-Next parity. It does not perform
KMI/`vermagic` rewriting, symbol relocation, module-signature bypass, direct
partition flashing, or AVB signing. A structurally valid output is not proof
that a device will boot or that the module will work correctly.

If status bar, SystemUI, Google apps, or other system components become unstable
after booting a patched image, stop using that image and restore the exact stock
`boot.img`/`init_boot.img` from the same installed ROM build. Do not keep
reboot-testing the suspect image. Keep a known-good recovery path, and do not
flash a newly generated image until its kernel/module provenance and device
boot have been validated.

## Verification

Run from the repository root in a fully provisioned build environment:

```sh
cargo test --locked --manifest-path native/bootpatch/Cargo.toml
cargo fmt --manifest-path native/bootpatch/Cargo.toml --check
python3 tools/ci/test-bootpatch-kmi-kernelsu-next.py
bash -n module/customize.sh module/service.sh tools/bootpatch/build-android.sh
./gradlew :app:assembleStandardCanary -PlicenseServerUrl=https://<your-worker-origin>
```

Synthetic tests can verify image structure and safety checks, but only a real
matching device can validate the module's kernel ABI, init policy, SELinux
behavior, AVB acceptance, and runtime stability.
