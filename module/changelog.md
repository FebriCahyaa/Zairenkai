## Unreleased
- Harden zperfd path writes with component-by-component `openat()` resolution and `O_NOFOLLOW`.
- Remove pseudo-locking of sysfs permissions; serialize writes through the durable transaction lock instead.
- Add crash-corrupt journal rejection, boot-id fail-closed behavior, and same-boot dynamic-node baseline capture.
- Add observed-state reconciliation for vendor/HAL drift and conservative Auto-mode hysteresis.
- Bound `dumpsys`/`getprop` scene command execution and discard interrupted token staging at boot.

## v1.0.0
- First release: ZKFC API 1.0.0.
- Loads the matching GKI `zkfc.ko` at boot, or detects a built-in ZKFC.
- Installs the `zkfctl` engine to `/data/adb/zkfc/`.
- Re-applies a saved boot profile.
