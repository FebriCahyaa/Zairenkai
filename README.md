<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
<div align="center">

# Zairenkai

**Root-powered Android performance & tweaks suite, driven by a signed kernel API.**

Android 12 → 17 · GKI & non-GKI · arm64 / x86_64 / riscv64 · Material 3 Expressive

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

See [`COPYRIGHT`](COPYRIGHT) for the full licensing map and [`NOTICE`](NOTICE)
for third-party attributions.

## Features

- **Dashboard** — device identity, ZKFC/API state, live performance status.
- **Monitor** — realistic FPS graph with drop/jank markers, CPU & GPU line
  charts, per-core load/frequency grid, and live thermal zones.
- **Tweaks** — CPU/GPU governor, I/O scheduler, zram, RAM (swappiness, dirty
  ratios, VFS), network (TCP), refresh/scheduler, display color, battery, and a
  broad "old-SoC" optimization set — all multi-SoC with graceful fallback.
- **Profiles** — Game, Harian, Media, Hemat Baterai, Seimbang, and an
  **Otomatis** mode that adapts to charging, battery level and temperature.
- **Tweaks engine in-kernel** — input boost, per-task uclamp boost with thread
  inheritance, cpufreq QoS, and a thermal guard that backs boosts off when hot.
- **System & security** — UID/GID/groups, kernel integrity, ZKFC license,
  access policy, and ZKFC API Token install.
- **Settings** — Lite mode, device mitigation, Material You, and a **live log**
  with selectable level; plus **sulog** auditing.

## How the pieces talk

```
Android app ──su──▶ zkfctl ──ioctl──▶ /dev/zkfc (ZKFC kernel module)
     (Compose)        (C engine)         (signed API, policy, boosts, thermal)
```

The app shells out to `zkfctl`, which prints JSON. `zkfctl` performs sysfs
tweaks directly and calls `/dev/zkfc` for the licensed in-kernel features.

## Build

- **App:** `./gradlew :app:assembleDebug` (AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20,
  SDK 37). See [`app/`](app).
- **Engine:** `userspace/build.sh android` (NDK). See [`userspace/`](userspace).
- **Kernel (GKI LKM):** `kernel/gki/build_lkm.sh <kmi-branch>`. See
  [`kernel/README.md`](kernel/README.md) for the KMI matrix and non-GKI paths.

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
