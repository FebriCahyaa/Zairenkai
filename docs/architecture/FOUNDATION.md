# Zairenkai foundation architecture

<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->

Zairenkai is a privileged Android/Linux system framework, not only a tweak
screen. The following boundaries are the architectural source of truth.

```text
Android UI
    |
    v
ViewModel / domain state
    |
    v
ZperfClient / ZKFC repository
    |
    v
root execution boundary
    |
    +---- zperfd (resident policy engine)
    |          |
    |          +-- topology / capability discovery
    |          +-- profile resolution
    |          +-- scene adaptation
    |          +-- sysfs/procfs writes
    |
    +---- zkfctl (admin/compat CLI)
               |
               v
          libzkfc / ioctl
               |
               v
            ZKFC
               |
               v
          Linux kernel
```

## Invariants

1. The kernel is the final authority for licensed performance operations.
2. `zperfd` is the source of truth for persistent performance state; the app
   requests changes and reflects confirmed results.
3. A profile is not active until its application succeeds to completion.
4. Every privileged write is capability-gated, bounded, and observable.
5. Recovery restores the first observed state of each managed node for the
   current boot instead of guessing vendor defaults; newly appearing nodes are
   captured on first observation.
6. CI must fail on build, test, lint, ABI or packaging regressions.

## State lifecycle

```text
REQUEST -> VALIDATE -> SNAPSHOT -> APPLY -> COMMIT
                                  |
                                  +----FAIL----> ROLLBACK -> REPORT
```

`boot_pending` is only a recovery guard. It must not be used as proof that a
profile is healthy until the engine has successfully initialized and passed its
stability checks.

## Kernel/UAPI compatibility

The UAPI version is part of a compatibility contract. A manager must refuse a
kernel whose major API is incompatible or whose `api_min_supported` is newer
than the manager understands. New payload layouts require either a new ioctl
number or an explicit size/versioned payload contract; silently enlarging an
existing ioctl structure is not allowed.

## Runtime authority

`zkfctl` is retained as a compatibility/admin CLI. New Android mutations must
use `zperfd`, which owns the state lock, per-boot baseline, transactional journal,
profile resolution and readback policy. The kernel remains authoritative for
security-sensitive operations such as API-token validation and licensed boosts.

The durable state directory is `/data/adb/zperf/`. A per-boot baseline is keyed
by `/proc/sys/kernel/random/boot_id`, while `transaction.pending` and
`transaction.committed` make mutation recovery idempotent across process or
power failure. The commit marker records exactly which semantic state files
are expected; recovery accepts an already-completed atomic rename but fails
closed if a required publication target is missing. A failed apply never
becomes the active mode.

## Capability discovery

The engine does not assume that a device exposes one universal Qualcomm/MediaTek
sysfs layout. It discovers cpufreq policies and real OPPs, GPU devfreq, cgroup
uClamp, schedtune and vendor input-boost surfaces. Frequency requests are snapped
to real OPPs; integer tuning knobs are range checked before any write. Runtime status/probe
paths inspect live nodes after an apply, while write failures remain
transaction-fatal and trigger rollback. The resident daemon additionally compares
a lightweight live-state fingerprint against its last confirmed state and
re-applies the desired profile when external writers cause observable drift.

## Release invariants

A release is incomplete unless all of these pass: Android compile/lint/tests,
native userspace `-Werror` build, crypto ASan/UBSan vectors, Rust fmt/build/test/
clippy, GKI LKM build matrix, APK signer verification, module packaging and
checksum generation. Local environments without the corresponding toolchain
must report the gate as unavailable rather than converting it into a false pass.

## Multi-SoC data plane

The device database is deliberately split into three layers:

```text
SoC family hints
      |
      v
device identity/profile -----> catalog selection
      |
      v
runtime topology/capability discovery
      |
      v
backend plan
      |
      v
measured telemetry / analysis artifacts
```

Qualcomm, MediaTek, Samsung Exynos and Google Tensor have separate family
files under `database/soc/`. These files define provider ordering and discovery
hints; they do not authorize writes and they do not contain fabricated
benchmark scores, thermal ceilings or assumed sysfs paths. The live kernel and
sysfs topology remains authoritative.

Static profiles use `runtime_discovery_required = true`. Performance claims
belong in versioned JSONL measurement artifacts. `tools/zperf_analyze.py`
produces descriptive medians, p95 values, MAD, coverage, thermal slope and
relative changes. It intentionally does not report statistical significance or
invent cross-device scores.

## Kernel generation model

Zairenkai treats kernel compatibility as a matrix, not a single version check.
GKI support is keyed by Android KMI branch and matching toolchain metadata;
non-GKI support is adapter-based and must be compiled against the concrete
vendor tree. A modern vendor tree can backport interfaces, so `LINUX_VERSION_CODE`
is used only as a lower-bound compatibility hint where necessary; runtime
capabilities and actual compilation remain authoritative.


## Thermal and property control

Thermal policy is monotonic: observed thermal pressure can reduce a requested performance envelope but never increase it. Property mutation is deny-by-default and limited to explicitly registered Zairenkai-owned keys; arbitrary vendor/system `setprop` is outside the framework boundary.
