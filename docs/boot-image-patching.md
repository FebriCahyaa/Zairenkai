<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Boot-image patching: implementation and safety contract

## Supported native patch path

The experimental Rust backend under `native/bootpatch` patches a supported
Android v3/v4 image that already contains a generic init ramdisk. It adds
`zkfc.ko` and a diagnostic manifest, preserves Android's original `/init`
byte-for-byte, adds a late init property trigger, repacks to a new file, then
parses and verifies the result again. It never writes to a partition or flashes
an image.

The patcher does **not** replace PID 1, does not install `zkinit`, and does not
try to insert a kernel module while Android init is bootstrapping the device.
Instead, it appends a trigger to the ramdisk's `init.rc` that tries to load
`/zkfc.ko` only after `sys.boot_completed=1`. This reduces interference with
boot-critical services, but it cannot prove that the LKM itself is safe for a
particular kernel or eliminate bugs caused by incompatible hooks.

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
`init.zairenkai.original.rc`, installs a modified `init.rc` with the delayed
trigger, and verifies both the byte-for-byte original `/init` and backup
`init.rc`. The diagnostic manifest records KMI source, kernel release, and the
late-load trigger. `/dev/zkfc` may still be absent if the kernel rejects the
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
cargo test --manifest-path native/bootpatch/Cargo.toml
cargo fmt --manifest-path native/bootpatch/Cargo.toml -- --check
python3 tools/ci/test-bootpatch-kmi-kernelsu-next.py
bash -n module/customize.sh module/service.sh tools/bootpatch/build-android.sh
./gradlew :app:assembleStandardCanary -PlicenseServerUrl=https://<your-worker-origin>
```

Synthetic tests can verify image structure and safety checks, but only a real
matching device can validate the module's kernel ABI, init policy, SELinux
behavior, AVB acceptance, and runtime stability.
