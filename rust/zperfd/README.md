<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# zperfd — Zairenkai universal performance engine

A probe-driven userspace performance daemon that applies 4-mode profiles
(`powersave` / `balance` / `performance` / `fast`) on **both GKI and non-GKI**
kernels. Inspired by the uperf/Scene approach; written fresh for Zairenkai.

## Why probe-driven

Profiles are **device-agnostic**: frequencies are keywords or percentages
(`"max"`, `"50%"`, `"-300MHz"`, `1800000`) that are resolved against the
device's real OPP tables at apply time. The engine detects the kernel flavor and
picks the right mechanism automatically:

| concern | non-GKI (e.g. 4.19 sdm660) | GKI (5.10 / 5.15 / 6.1 / 6.6 / 6.12) |
|---|---|---|
| CPU cap | `policyN/scaling_{min,max}_freq` + `msm_performance` mirror | `policyN/scaling_{min,max}_freq` |
| top-app boost | `schedtune.boost` | `cpu.uclamp.min` (cgroup) |
| input boost | `cpu_boost` (module or per-cpu path) | same, if present |
| GPU | KGSL / Mali devfreq | KGSL / Mali devfreq |

Every write is **probe-gated** (only touched if the node exists) and sysfs
writes are **locked** (`chmod 0666 → echo → 0444`) so a vendor perf-HAL cannot
silently revert them.

## Usage

```
zperfd probe  [--root R] [--json]          # detected topology / flavor
zperfd modes  [--profile F]                # list modes in the active profile
zperfd apply  <mode> [--profile F] [--no-lock]
zperfd reset                               # restore stock-ish limits
zperfd daemon [--state DIR] [--interval MS]
```

The daemon watches `<state>/mode` (default `/data/adb/zperf/mode`) and the
foreground app, applying per-app overrides from the profile's `[perapp]` table.

## Scene / vtools interop

The module installs `/data/powercfg.sh` + `/data/powercfg.json`, the external
scheduler contract Scene uses, so Scene can drive zperfd (and auto-disables
conflicting modules such as uperf).

## Profiles

`profiles/generic.toml` works on any device; `profiles/lavender.toml` is tuned
for SDM660 (Adreno 512). The catalog is installed to `/data/adb/zperf/catalog/`
and selected by `ro.board.platform`; a user `profile.toml` overrides it.

The engine persists a current-boot baseline and a crash-safe transaction journal.
A failed write is rolled back; a committed journal can finish its state update
after power loss. `auto` uses battery percentage, charging state, thermal data,
and the profile's per-app overrides.
