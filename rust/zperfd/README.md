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

Every write is **probe-gated** (only touched if the node exists) and serialized
by the zperfd transaction lock. The engine never changes sysfs/procfs
permissions to simulate a lock: privileged vendor services can still overwrite
the same node, so external writers are treated as an observable drift rather
than something the engine pretends it can prevent.

## Usage

```
zperfd probe  [--root R] [--json]          # detected topology / flavor
zperfd modes  [--profile F]                # list modes in the active profile
zperfd apply  <mode> [--profile F]
zperfd reset                               # restore stock-ish limits
zperfd daemon [--state DIR] [--interval MS]
```

The daemon watches `<state>/mode` (default `/data/adb/zperf/mode`) and the
foreground app, applying per-app overrides from the profile's `[perapp]` table.
It also reconciles the live managed-node state periodically, so an external
privileged writer cannot silently leave the selected policy drifted forever.

## Scene / vtools interop

The module installs `/data/powercfg.sh` + `/data/powercfg.json`, the external
scheduler contract Scene uses, so Scene can drive zperfd (and auto-disables
conflicting modules such as uperf).

## Profiles

`profiles/generic.toml` works on any device; `profiles/lavender.toml` is tuned
for SDM660 (Adreno 512). The catalog is installed to `/data/adb/zperf/catalog/`
and selected by `ro.board.platform`; a user `profile.toml` overrides it.

The engine persists a current-boot baseline and a crash-safe transaction journal.
The durable commit marker records which `mode`/`effective_mode` publications
are required, so recovery remains idempotent even if power fails between atomic
renames. A failed write is rolled back; a committed journal can finish its state
update after power loss. Newly appearing managed nodes are adopted into the
current boot baseline without replacing the baseline of already-seen nodes. `auto` uses
battery percentage, charging state and thermal data with conservative handling
of missing telemetry and hysteresis around transition boundaries.

## Platform and evidence data plane

`platform.rs` records identity evidence from Android properties and the device tree. `topo.rs` inventories the live cpufreq, GPU devfreq, cgroup and thermal
surfaces. `family.rs` loads the vendor-family provider ordering from the
versioned database. `backends.rs` uses that ordering only as a preference;
absent runtime surfaces are never synthesized.

Supported family seeds currently cover Qualcomm, MediaTek, Samsung Exynos and
Google Tensor. A family seed is not a performance certification. A device
becomes scientifically comparable only after a measurement artifact has
captured the device identity, exact kernel release/flavor, workload and profile.

`tools/zperf_analyze.py` is intentionally descriptive. It reports coverage,
median, p95, MAD, thermal slope and relative changes without claiming causality
or statistical significance from short time-series samples.
