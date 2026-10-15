<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Boot-image patching: implementation and safety contract

## Current capability

Zairenkai can replace the **kernel payload** in a supported Android `boot.img`
using its own Kotlin boot-image codec and save the result to a new user-selected
file. The implementation models `ANDROID!` header versions 0–4. It does not
modify the ramdisk, inspect/decompress kernel compression, install a kernel
module, write a partition, or sign an image.

The flow is deliberately conservative:

1. Read both inputs through Android's Storage Access Framework with input-size
   limits.
2. Parse the boot header and verify the original image can be reconstructed
   byte-for-byte.
3. Replace only the kernel section. Update the kernel size field and, for
   header versions 0–2, regenerate the legacy SHA-1 image ID and recovery-DTBO
   offset when applicable.
4. Parse the generated output again and verify exact reconstruction and that
   the embedded kernel bytes match the selected payload.
5. Calculate and display SHA-256, then write the verified bytes to the selected
   output document. No partition-flashing command exists in this path.

## Explicit refusals

Patching is refused when the input layout does not round-trip exactly, the
kernel payload is empty or too large, or the input has trailing bytes (which
may be an AVB/footer structure) or a non-empty v4 boot signature. Zairenkai
cannot recompute device-specific AVB/vbmeta signatures and must not imply that
changing the kernel makes a verified-boot image bootable. If the output is used,
the owner must understand the device's bootloader-lock and verified-boot
requirements and keep a known-good stock image for recovery.

`vendor_boot` uses a different `VNDRBOOT` format and is not accepted by this
workflow. An `init_boot` image without a kernel is not a valid target here.

## Relationship to KernelSU-Next / MagiskBoot

KernelSU-Next's userspace boot-patch path uses a dedicated boot-image parser,
CPIO manipulation and a repacker, with checks around the ramdisk and the target
operation. MagiskBoot additionally understands more compression and Android
boot formats. This implementation adopts the high-level safety ideas (explicit
input, strict format checks, output verification and no silent partition flash)
but is **not a port or replacement** for those tools. Their source is licensed
under GPL terms while Zairenkai's Android app is proprietary; this code is an
independent implementation against the public AOSP header layout instead of
copying that code into the app. Reproducing KernelSU-Next's LKM/ramdisk injection
would require a separately reviewed, license-compatible native backend and a
Zairenkai-specific early-boot loader contract; merely adding `zkfc.ko` to CPIO
would not make the module load reliably.

## Manual verification

Run from the repository root:

```sh
bash ./gradlew :app:testDebugUnitTest
bash ./gradlew :app:assembleDebug
```

The boot codec tests include round-trip checks for legacy and modern headers,
replacement verification, legacy-ID regeneration, and refusal of unsupported
trailer/signature cases. Device-specific booting still needs to be tested on an
unlocked test device with a recoverable stock image; synthetic unit tests cannot
prove a given kernel matches a given device.

## Native KernelSU-style path (experimental)

The native patch flow is implemented as a separate GPL-3.0-only Rust command
under `native/bootpatch`, using the pinned `android-bootimg` parser, CPIO
reader/writer and repacker. The Compose sheet selects a boot image, KMI and a
matching `zkfc.ko` (or a KMI-specific bundled module), then invokes the helper
to inject the module and the standalone `zkinit` adapter into the selected
ramdisk and repack a new image.

`zkinit` attempts to load `/zkfc.ko` and then chainloads `/init.real`, falling
back to `/system/bin/init` when needed. This is an experimental early-boot
adapter, not full KernelSU-Next parity: it does not implement kallsyms
relocation, vermagic repair/retry, direct partition flashing, or AVB signing.
A structurally validated image is not proof that the device will boot or that
