<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
<div align="center">

# Zairenkai

**Root-powered Android performance & tweaks suite, driven by a signed kernel API.**

Android 12 → 17 · GKI & non-GKI · userspace arm64 / x86_64 · kernel arm64 / x86_64 / riscv64 · Material 3 Expressive

</div>

Zairenkai is a tweaks application for rooted Android, in the spirit of manager
apps like KernelSU-Next — but instead of patching root into the kernel, it
drives **performance, thermal and security** features through its own kernel
framework, the **Zairenkai Kernel Framework Core (ZKFC)**. The heavy features
are gated behind an Ed25519-signed **ZKFC API Token** issued only by the owner,
so the API can't be used carelessly.

## What's inside

| Layer | Path | Language | License |
|------|------|----------|---------|
| Android app (Material 3 Expressive) | [`app/`](app) | Kotlin / Compose | proprietary |
| Userspace engine (`zkfctl` + libzkfc) | [`userspace/`](userspace) | C | proprietary |
| Kernel framework (ZKFC) | [`kernel/`](kernel) | C | GPL-2.0-only |
| Signed-token verifier (Ed25519/SHA-512) | [`kernel/crypto/`](kernel/crypto) | C | GPL-2.0 OR MIT |
| Boot module (Magisk/KernelSU/APatch) | [`module/`](module) | shell | proprietary |
| Owner token tool | [`tools/zkfc-license/`](tools/zkfc-license) | Python | proprietary |

See [`docs/architecture/CORE_INVARIANTS.md`](docs/architecture/CORE_INVARIANTS.md) for non-negotiable runtime invariants.

See [`COPYRIGHT`](COPYRIGHT) for the full licensing map and [`NOTICE`](NOTICE)
for third-party attributions.

## Zairenkai Core Platform

Zairenkai is organized around a stable **Zairenkai Core Platform** identity.
ZKFC is the kernel authority, zperfd is the runtime orchestrator, **Atlas** is
the device/system knowledge plane, and **Sentinel** is the runtime safety plane.
The Core Authority maps semantic operations to least-privilege capabilities.

The system follows:

```text
identity -> capability -> authority -> Atlas evidence -> policy -> Sentinel
-> transaction -> observation -> reconciliation -> audit
```

No static SoC profile is treated as proof that a kernel interface exists.
Qualcomm, MediaTek, Samsung Exynos and Google Tensor data are provider hints;
the live kernel/device surface remains authoritative. Atlas stores provenance and
evidence classes, while Sentinel can fail closed before a risky mutation.

### Atlas + Sentinel

**Zairenkai Atlas** maintains versioned device, OEM, SoC, kernel, thermal, storage,
memory, networking, graphics, security and measurement metadata. Supplier and
upstream data enters through a trust registry and immutable snapshot contract;
static knowledge can never override runtime capability discovery.

**Zairenkai Sentinel** constrains authorized operations using kernel compatibility,
persistent state, SAFE MODE, thermal/power state, storage health, boot integrity
and evidence strength. Root access is not treated as blanket application authority.

### Local API

Zairenkai exposes **ZLP v1 (Zairenkai Local Control Protocol)** over a root-only
Unix domain socket for structured introspection. API v1 is observation-only; future
mutation endpoints must reuse Core Authority, Sentinel, ZKFC validation and the
durable transaction engine rather than introducing a second mutation path.

## Features

- **Dashboard** — device identity, ZKFC/API state, live performance status.
- **Monitor** — realistic FPS graph with drop/jank markers, CPU & GPU line
  charts, per-core load/frequency grid, and live thermal zones.
- **Tweaks** — CPU/GPU governor, I/O scheduler, zram, RAM (swappiness, dirty
  ratios, VFS), network (TCP), refresh/scheduler, display color, battery, and a
  broad "old-SoC" optimization set — all multi-SoC with graceful fallback.
- **Profiles** — Game, Harian, Media, Hemat Baterai, Seimbang, and an
  **Otomatis** mode that adapts conservatively to charging, battery level and
  temperature with hysteresis to avoid rapid profile flapping.
- **Tweaks engine in-kernel** — input boost, per-task uclamp boost with thread
  inheritance, cpufreq QoS, and a thermal guard that backs boosts off when hot.
- **System & security** — UID/GID/groups, kernel integrity, ZKFC license,
  access policy, and ZKFC API Token install.
- **Settings** — Lite mode, device mitigation, Material You, and a **live log**
  with selectable level; plus **sulog** auditing.

## How the pieces talk

```
Android app ──su──▶ zperfd ──libzkfc/ioctl──▶ /dev/zkfc (ZKFC)
    Compose          state + transaction          kernel policy/boost/thermal
       │
       └───────────────▶ zkfctl (diagnostic / compatibility CLI)
```

`zperfd` is the authoritative root-side performance engine. Its public core introspection surface is `zperfd core --json` and `zperfd core permissions --json`. It owns profiles,
Auto mode, durable transactions, baseline snapshots, rollback and device-aware
capability discovery. The Android app is a client and never mutates managed
sysfs state directly. `zkfctl` remains the low-level diagnostic and license
management CLI, while `/dev/zkfc` remains the kernel authority for licensed
performance controls.

## Build

- **App:** `./gradlew :app:assembleDebug` (AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20,
  SDK 37). See [`app/`](app).
- **Engine:** `rust/zperfd` (resident Rust daemon) plus `userspace/zkfctl` (C CLI/compatibility engine).
  See [`rust/zperfd/`](rust/zperfd) and [`userspace/`](userspace). CI/release builds pin Rust
  to the reviewed stable toolchain declared in [`rust-toolchain.toml`](rust-toolchain.toml).
- **Kernel (GKI LKM):** `kernel/gki/build_lkm.sh <kmi-branch>`. See
  [`kernel/README.md`](kernel/README.md) for the KMI matrix and non-GKI paths.

## Multi-SoC data plane

The framework keeps vendor-specific knowledge out of the kernel core.
Versioned family/device data under `database/` currently covers Qualcomm,
MediaTek, Samsung Exynos and Google Tensor, with 16 indexed device profiles and
10 supplier SoC product records in the current seed registry. The database is an
ingestion foundation rather than a claim of exhaustive market coverage: Atlas can
accept new OEM/device records without changing the runtime engine. Live
kernel/sysfs capability discovery decides what is actually usable. Benchmark
values are never fabricated into static profiles: measured runs are stored as
artifacts and analyzed separately.

## Supported kernels

GKI: `android12-5.10`, `android13-5.10`, `android13-5.15`, `android14-5.15`,
`android14-6.1`, `android15-6.6`, `android16-6.12`, `android17-6.18`
(see [`kernel/gki/kmi_matrix.txt`](kernel/gki/kmi_matrix.txt)).
non-GKI: 4.14 → 6.x vendor kernels (built-in or module). Hooks: Hybrid
(kprobes, no source changes) or Manual (patched source).

## ZKFC API Tokens

Performance features need a token the owner signs for your kernel. Request one
via the *ZKFC API Token request* issue template; details in
[`docs/security/API_TOKENS.md`](docs/security/API_TOKENS.md).

## License

Mixed, per component — see [`COPYRIGHT`](COPYRIGHT). The kernel is GPL-2.0; the
app, engine, module and tooling are under the Zairenkai Proprietary License
(redistribution, forks, and API tokens require the owner's written permission).


### Thermal & system property safety

**Zairenkai ThermoGuard** is the kernel-backed thermal safety subsystem. It derives its guard envelope from runtime thermal trip points when available, scales Zairenkai-owned boost requests continuously as thermal headroom changes, and disables boosts when required telemetry is unavailable.

Zairenkai treats thermal control as a safety envelope, not a performance override. Runtime boost budgets can only be reduced by thermal state. Android system properties are read-only by default; mutation is restricted to an explicit, registered `persist.zairenkai.*` allowlist. Vendor properties are never writable through the generic broker.


## Thermal & Performance Control Plane

Zairenkai ThermoGuard derives thermal decisions from live Linux thermal-zone temperatures and trip-point topology. Static Atlas thermal records are provider/evidence hints only; they never become universal Celsius thresholds. The runtime exposes normalized thermal headroom, a zone-local control trip, critical-trip state, and an explicit incomplete-telemetry state. Incomplete telemetry enters a limited performance envelope rather than selecting optimistic performance.

CPU/GPU governor choices and TCP congestion-control choices are validated against runtime allowlists. GKI/NonGKI scheduler backends are selected from live capability surfaces, while unsupported controls fail closed instead of being emulated through unrelated knobs. Storage classification and health are also based on runtime topology/evidence, with critical storage excluded from queue tuning.
