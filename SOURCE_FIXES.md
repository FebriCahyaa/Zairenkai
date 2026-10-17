# Zairenkai boot-patch/runtime safety hotfix

Base source: `FebriCahyaa/Zairenkai`, branch `ccr-076d8b15-osiupp`.

## Source changes in this hotfix

- Removed the current boot-patch path's PID-1 replacement. Android `/init` is
  preserved byte-for-byte; current builds do not package `libzkinit.so`.
- The patcher now adds a delayed module-load trigger to `init.rc` that runs only
  after `sys.boot_completed=1`, keeps a copy of the original `init.rc`, and
  reopens/rechecks the repacked image before writing a new output file.
- The legacy `zkinit` source is retained for compatibility review, but it no
  longer loads `zkfc.ko`. The current patcher refuses images carrying the
  legacy `/init.real` marker so patches are not stacked on top of older images.
- Branchless kernel banners such as `5.10.269-gki-...` no longer allow a KMI to
  be selected solely by matching Linux major/minor. The app disables the patch
  action until the KMI can be inferred from the kernel image or a vendor `.ko`
  reference. Such a reference's provenance must still be confirmed by the
  operator against the exact firmware build.
- The root-manager installer and service no longer trial-load other KMI modules
  just because they share Linux 5.10/6.x. If `uname -r` does not expose a KMI
  branch, automatic LKM selection fails closed; a built-in `/dev/zkfc` endpoint
  may still be used. This avoids speculative module insertion on the user's
  branchless `5.10.269-gki-...` kernel.
- The source documentation and license inventory now describe the no-wrapper,
  late-load behavior.

## Validation status

- `python3 tools/ci/test-bootpatch-kmi-kernelsu-next.py`: PASS, including new
  no-PID-1-wrapper and no-speculative-KMI-fallback assertions.
- `bash -n tools/bootpatch/build-android.sh module/customize.sh module/service.sh`: PASS.
- Boot-patch Android string XML resources and `tools/licenses/libraries.json`: PASS parsing.
- `tests/foundation_check.sh`, `tests/control_plane_check.sh`,
  `tests/app_architecture_check.sh`, `tests/atlas_check.sh`,
  `tools/zairenkai_core_validate.py`, `tools/atlas_validate.py`, and
  `tools/profile_validate.py`: PASS.

The host environment used for this hotfix does not include `cargo`, `rustc`, or
`rustfmt`, so Rust unit tests and formatting checks could not be executed here.
The Android/Gradle build also depends on fetching Gradle and Android build
components; a successful APK build is not claimed. Run the Rust tests/fmt and
full Android build in CI or a fully provisioned build host before releasing.

No physical-device boot test or kernel ABI validation was possible. This is a
source-level risk mitigation, not a guaranteed fix for the already-flashed
image. If the current device is unstable, restore the exact stock image for
the currently installed ROM before testing a rebuilt image.
