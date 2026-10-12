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
zperfd modes  [--profile F] [--json]       # list modes in the active profile
zperfd apply  <mode> [--profile F] [--json] # alias: set
zperfd status [--json]                     # desired/effective mode, hold reason
zperfd tweak  list [--json] | get ID | set ID VALUE
zperfd custom list [--json] | put custom-ID FILE.toml | delete custom-ID
zperfd reset                               # restore the boot baseline (mode = stock)
zperfd daemon [--state DIR] [--interval MS]
zperfd adaptive [--json]                  # inspect current scene/FAS decision
zperfd battery                             # read-only power-supply snapshot (JSON)
zperfd [--interval MS] frames [--watch PKG,...] [--follow] [--duration S]
                                           # read-only presented-frame stream (JSONL)
```

`battery` and `frames` only read: `battery` prints the battery supply
(capacity, cycle count, full/design charge, current, voltage, temperature,
charge type, time to full) and the online chargers. `frames` prints one JSON
line per interval while a watched package is in the foreground, on a
foreground change, and as a 10 s heartbeat: `t_ms`, `fg`, `watched`,
`source` (`surfaceflinger` or `gfxinfo`), `refresh_hz` and only the frames
not sent before as `[present_ns, render_us, jank]`. It exits when stdout
closes. The interval is clamped to 250–5000 ms.

Every managed write is read back before the transaction commits. Exact
nodes (governors, I/O scheduler, sysctl, uclamp percent, schedtune) must
report the requested value or the whole apply rolls back. Frequency limits,
`read_ahead_kb` and `nr_requests` may legitimately read back a kernel-adjusted
value (QoS, thermal caps, page rounding); those are reported as `constrained`,
and vendor multi-value nodes as `unverified`. `apply --json` prints one line
with `applied`, `skipped`, `constrained`, `unverified` and `failed`, and on
failure a `kind` (`framework_unavailable`, `license`, `sentinel`,
`not_observed`, ...).

### Custom profiles

A custom profile is a built-in mode of the resolved device profile plus
overrides of any mode field (`[cpu]`, `[gpu]`, `[mem]`, `[io]`, same keys as a
catalog mode). Fields left out follow the base, so catalog updates still
reach it:

```toml
label = "Game dingin"
base = "fast"
[cpu]
max_perf_pct = 85
governor = ["schedutil"]
[mem]
swappiness = 60
```

`custom put` rejects unknown keys, a base that is not a built-in mode of this
device profile, bad frequency syntax and anything `Mode::validate` rejects for
catalog modes, then stores the entry atomically in `<state>/custom.toml`
(at most 8; ids are `custom-` + 1..24 of `a-z 0-9 _ -`, a prefix catalog
modes may not use). Stored entries are merged into the profile on load, so
`set custom-ID` goes through the same authorization, kernel gate, Sentinel,
thermal envelope, transaction and readback as a built-in mode, and `modes
--json` lists them with `custom`, `label` and `base`. They are manual only:
`[perapp]` and Auto never resolve to one, and the adaptive layer treats a
custom mode like its base. `custom delete` refuses the currently selected
mode (`in_use`); an entry whose base later disappears is skipped and shown as
invalid by `custom list`. The daemon re-merges when `custom.toml` changes and
re-applies the active custom mode with its new values.

The daemon watches `<state>/mode` (default `/data/adb/zperf/mode`) and the
foreground app, applying per-app overrides from the profile's `[perapp]` table.
It also reconciles the live managed-node state periodically, so an external
privileged writer cannot silently leave the selected policy drifted forever.

## Adaptive Scene + FAS engine

The resident daemon now has a second control plane above the durable profile:

```text
foreground scene -> workload/thread telemetry -> frame/jank telemetry
        -> bounded FAS controller -> ZKFC task boost -> optional thread affinity
```

`scene_engine.rs` classifies the foreground workload and tracks scene entry/switch
state. `workload.rs` samples `/proc` counters, per-thread utilization, scheduler run-queue
delay, RSS/I/O and best-effort GPU utilization. `frame.rs` consumes Android
`gfxinfo framestats` opportunistically and maintains a rolling frame/jank window.
`fas.rs` converts deadline error, jank, CPU/GPU pressure and thermal headroom into a
bounded transient boost. `task_controller.rs` applies that boost via the stable ZKFC
task-boost/QoS UAPI and restores affinity changes when the scene ends.

Adaptive changes are deliberately not written into the durable profile journal: the
profile remains the baseline contract, while FAS is an ephemeral feedback loop. Frame
probes are rate-limited and only enabled for active workloads so monitoring overhead
does not become a new source of jank.
Thermal guard, license validation and Sentinel still gate every adaptive mutation.

Profiles can tune the controller with `[adaptive]`; omitted values use conservative
defaults.

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


**Zairenkai ThermoGuard** is the runtime thermal safety subsystem. The runtime thermal envelope can constrain profiles before mutation and can configure the kernel-side guard from discovered thermal trip points. Property access uses a bounded Android `getprop`/`setprop` broker; only registered Zairenkai-owned properties are mutable.
