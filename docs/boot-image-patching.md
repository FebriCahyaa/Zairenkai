<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Boot-image patching: implementation and safety contract

## Supported native patch path

The experimental Rust backend under `native/bootpatch` modifies a supported
Android v3/v4 image that already contains a **generic init ramdisk**. It injects
`zkinit`, `zkfc.ko`, and a diagnostic manifest, preserves the original `/init`
as `/init.real`, repacks to a new file, then parses the output again. It never
writes a partition or flashes an image.

### Select the right image pair

- **Android 12 and older / a `boot.img` that includes the generic ramdisk:**
  select that `boot.img` as the patch target. A kernel reference is optional.
- **Android 13 and newer, where the generic ramdisk is normally in
  `init_boot.img`:** select `init_boot.img` as the patch target and the matching
  `boot.img` from the same device and ROM/build as the kernel reference. The
  helper uses the reference only to identify the kernel release/KMI; output is
  still an `init_boot`-format image.
- `vendor_boot.img`, raw CPIO inputs, legacy boot headers v0-v2, and images
  without a generic `/init` are refused. The helper will not fabricate an empty
  ramdisk in a kernel-only `boot.img` because that can create a structurally
  valid output that is not the ramdisk the device actually boots.

### Module compatibility checks

The helper verifies the ELF architecture, selected Android KMI where the kernel
banner exposes it, module vermagic, and Linux major/minor family. It also
verifies that repacking did not change the kernel release embedded in a
`boot.img` target. A vendor release without `androidNN-X.Y` can still be paired
with a manually selected KMI, but the branch cannot be proved from that banner;
the kernel module loader must validate module vermagic/symbol CRCs at runtime.
Linux major/minor alone is not proof that two KMI branches are ABI-compatible.

### Init chainload and runtime status

The injected `zkinit` tries to load `/zkfc.ko` and then executes the preserved
`/init.real` directly. The patcher refuses to run when `/init` is absent or when
`init.real` is already present (likely already patched). It does not attempt to
execute `/system/bin/init` during early boot because `/system` may not yet be
mounted.

The root-manager installer prefers a module from the detected KMI and retains
same-family candidates when the kernel's KMI marker is absent. At boot, service
tries candidates for the device ABI and Linux family, letting the kernel loader
reject incompatible vermagic/symbol versions; it stops when `/dev/zkfc` appears
or when a module inserted successfully but failed to publish the device. It
logs failures, skips starting `zperfd` when the endpoint is absent, and persists
daemon startup output to `/data/adb/zperf/zperfd.log`.

## Explicit limitations and recovery

This backend is not full MagiskBoot or KernelSU-Next parity. It does not perform
KMI/`vermagic` rewriting, symbol relocation, module-signature bypass, direct
partition flashing, or AVB signing. It cannot make a module ABI-compatible by
renaming a file or changing a metadata string. A successful structural check
is not proof that a device will boot or that the module will load.

Changing boot images can make the device unbootable if the image pair, module,
AVB state, or bootloader configuration is wrong. Keep a known-good stock
`boot.img` and `init_boot.img`, and validate on a device with a recovery path
before relying on the patched image. Do not flash the generated output until
its kernel release and module provenance have been confirmed.

## Verification

Run from the repository root:

```sh
cargo test --manifest-path native/bootpatch/Cargo.toml
cargo fmt --manifest-path native/bootpatch/Cargo.toml -- --check
bash -n module/customize.sh module/service.sh
./gradlew :app:assembleStandardCanary -PlicenseServerUrl=https://<your-worker-origin>
```

Native unit tests cover KMI extraction, kernel-family validation, CPIO init
preservation, and rejection of a ramdisk without `/init`. Android build and
real-device boot validation remain separate steps. Synthetic tests cannot prove
that a module matches the kernel configuration or that AVB accepts an image.
