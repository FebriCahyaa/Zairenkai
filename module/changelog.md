## v1.1.0
- Add Zairenkai Thermal & Performance Control Plane with kernel thermal guard integration.
- Use runtime thermal trip points and headroom instead of treating fixed temperatures as universal limits.
- Add conservative unknown-telemetry behavior and continuous thermal boost scaling.
- Harden network congestion selection with a runtime allowlist fallback and fail-closed validation.
- Harden Android property reads with a small diagnostic allowlist and keep arbitrary vendor properties read-only.
- Add storage-aware I/O safety and retain memory/ZRAM safety envelopes.

## Unreleased
- Keep fail-closed behavior when no KMI is selected; never trial-load alternate KMI modules based only on Linux major/minor.
- Accept markerless GKI `vermagic` only from the exact explicitly selected KMI/ABI catalog path after manifest/hash and Linux-family checks.
- Reject any explicit Android KMI marker that conflicts with the selected target KMI.
- Let the kernel loader perform its symbol-version and module-signature checks when `vermagic` omits `androidNN`.
- Permit manual boot-image KMI selection only after a valid kernel release is parsed and the selected KMI matches its Linux major/minor family; record the unverified source in the patch manifest.
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
