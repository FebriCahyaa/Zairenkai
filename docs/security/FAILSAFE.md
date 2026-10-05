<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Zairenkai fail-safe & system stability

ZKFC changes live CPU/GPU/memory/thermal state, so it is built to fail safe:
a crash, panic or sudden reboot must never leave the device pinned in a bad
state or stuck in a bootloop.

## 1. Kernel panic / reboot notifiers (`kernel/core/zkfc_notify.c`)

ZKFC registers two notifiers:

- **Reboot / shutdown** (`register_reboot_notifier`) — runs in process context
  during an orderly shutdown. ZKFC fully resets every performance request
  (input-boost floors, per-task uclamp, cpufreq QoS) back to kernel defaults,
  so the next boot starts clean.
- **Panic / oops** (`panic_notifier_list`, highest priority) — runs in atomic
  crash context. ZKFC does **only** atomic-safe work: it flips the input-boost
  and task-boost "suspended" flags so any surviving code path stops boosting,
  and emits a `pr_emerg` marker for post-mortem. It never calls anything that
  can sleep (cpufreq/freq_qos, workqueues) from here.

`zkfc_is_going_down()` lets other paths cheaply bail once teardown has begun.

## 2. Thermal guard (`kernel/thermal/zkfc_thermal.c`)

An independent watchdog polls the configured thermal zones and, on reaching the
limit, suspends every ZKFC boost (with hysteresis before resuming). The limit
is clamped in-kernel to `[35 °C, 95 °C]`, so a buggy or hostile manager cannot
configure an unsafe value.

## 3. Boot rollback / safe mode (sudden reboot & bootloop)

The kernel notifiers handle a crash that is *happening*; this handles a boot
that *already* crashed.

```
boot ─▶ module service.sh:
          count = read(boot_pending)
          if count >= 2 → SAFE MODE: create safe_mode, SKIP boot profile
          else          → boot_pending = count+1, apply boot profile
app starts ─▶ zkfctl safe confirm ─▶ delete boot_pending   (boot was healthy)
```

- A boot that never reaches the app (kernel panic, sudden reboot, bootloop)
  never clears `boot_pending`, so the count climbs.
- Two unconfirmed boots ⇒ **safe mode**: the boot profile is not re-applied and
  the app shows a *"Mode Aman aktif"* banner on the dashboard.
- `zkfctl safe status` reports `{safe_mode, pending}`; `zkfctl safe confirm`
  clears the pending marker; `zkfctl safe clear` also clears safe mode after the
  user has reviewed their tweaks.

State lives in `/data/adb/zkfc/{boot_pending,safe_mode}` (root-only).

## 4. Why a bad token or tweak can't destabilise the kernel

- API tokens are Ed25519-verified in-kernel; a forged token is rejected, and
  repeated rejects are rate-limited (`kernel/security/zkfc_license.c`).
- Every tweak value is range-checked; cpufreq requests go through `freq_qos`,
  which the cpufreq core aggregates with thermal and vendor limits, so ZKFC can
  raise a floor but never exceed what the hardware/thermal framework allows.
- `/dev/zkfc` re-checks the caller's UID/GID/capabilities on every ioctl, and
  mutating calls require `CAP_SYS_ADMIN`.
