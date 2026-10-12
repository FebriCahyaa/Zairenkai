<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Monitor and dashboard metrics

This page lists every number the Monitor (hub, CPU & GPU, Battery and FPS
screens), the usage history and the Dashboard show: where it comes from, its unit, how often it is sampled, and how
"not reported" is encoded and displayed. Every value here is **observation
only**. Reading a metric never changes a tunable; mutations go through zperfd.

## Sampling loop

The Monitor hub and its detail screens (CPU & GPU, Battery) share one
sampler, `LiveMonitor` (`app/.../data/monitor/LiveMonitor.kt`), held by the
`AppContainer`. Each visible screen acquires it through `MonitorViewModel`;
it stops **4 s** after the last screen releases it, so opening a card keeps
the charts instead of restarting them. It runs three independent loops, so a
missing root grant only empties what needs root:

1. **zkfctl monitor** (root): `zkfctl monitor 500` (`ZkfctlClient.monitor`,
   6.5 s timeout). zkfctl reads `/proc/stat`, sleeps the **500 ms window**,
   then reads it again along with the other nodes below. The result is folded
   by `withResult` and `LiveState.withMonitor`, both pure and unit-tested
   (`MonitorStateTest`, `LiveStateTest`). After a good sample the next one
   starts right away (about 0.6 to 1 s cadence); after a failed one it waits
   1, 2, 4, then 8 s (`monitorRetryDelayMs`).
2. **Android** (no root), every **1 s**: battery (`AndroidBatteryReader`) and
   memory (`ActivityManager.getMemoryInfo`).
3. **zperfd** (root, read-only), every **5 s**: `zperfd battery` (and
   `zperfd probe` once, for the cluster layout and GPU range). After three
   misses it is asked once a minute, so a device without the module does not
   spawn `su` every few seconds.

Starting again keeps the chart history but waits for a fresh sample before
it shows **LIVE**. When root telemetry fails and nothing has come in since,
the hub shows **DATA DASAR** and a small dismissable banner; CPU, GPU and
thermal cards show "Butuh modul Zairenkai" while battery, RAM and FPS keep
working.

Charts hold the last **90 valid points** (CPU, GPU, per core, per cluster,
temperature, memory) and **1800** battery readings (30 minutes at 1 s). Only
measured values are appended. When a sensor is not reported, its chart
simply gets no point, so a missing sensor never draws a flat line at 0.

## Monitor (zkfctl monitor)

| Metric | Source | Unit | Not reported | UI when missing |
|---|---|---|---|---|
| CPU load, per core | `/proc/stat` delta over the window. `idle + iowait` count as idle; the total is user+nice+system+idle+iowait+irq+softirq+steal | % (0–100) | `load_pct = -1` (no jiffies delta, e.g. core offline) | core shows "—" |
| CPU online | `/sys/devices/system/cpu/cpuN/online`. A core without the node counts as online | bool | n/a | offline cores are excluded from the average |
| CPU frequency, per core | `cpuN/cpufreq/scaling_cur_freq` | kHz, shown as MHz | `cur_khz = -1` | "Frekuensi n/a" |
| CPU headline + chart | mean `load_pct` of online cores with `0 ≤ load ≤ 100` | % | no valid core | "—", "Tidak tersedia" / "Menunggu sampel pertama" |
| GPU busy | Adreno only: `/sys/class/kgsl/kgsl-3d0/gpubusy` (`busy total` in the driver's window) | % | `busy_pct = -1` (node missing, unparsable, or `total = 0`) | "n/a"; no chart point |
| GPU frequency | first existing of `kgsl-3d0/devfreq/cur_freq`, `/sys/class/devfreq/13000000.mali/cur_freq` | Hz, shown as MHz | key absent → `-1` | MHz line hidden |
| Thermal zones | every `/sys/class/thermal/thermal_zone*/temp` with a non-empty read, plus its `type` | m°C, shown as °C | zone omitted when unreadable | empty state "…belum mengekspos zona thermal yang valid" |
| Hottest + chart | max of zones in the plausible range **1–150 °C** (`PLAUSIBLE_CELSIUS`) | °C | no plausible zone | no point; empty state |
| Input boost / thermal guard / boosted tasks | `ZKFC_IOC_PERF_STATUS` via `zkfc_perf_status`, only when `/dev/zkfc` opens | bool / count | `zkfc` object absent | runtime footer hidden |

Notes:

- **Thermal plausibility:** zones outside 1–150 °C are treated as not reported.
  This drops disabled zones (−273 °C) and drivers that report whole degrees
  instead of millidegrees (such as `45`, read as 0.045 °C). It filters bad
  data only; it is not a safety threshold.
- **Thermal colours:** the zone colours in the Thermal card (warn from 42 °C,
  hot from 48 °C) are a display aid that ignores the zone type. The thermal
  guard and the zperfd envelope use each device's own trip points and do not
  read these colours.
- **GPU busy on Mali:** only frequency is available; busy reads "n/a".

## CPU clusters (zperfd probe)

`zperfd probe` lists each cpufreq policy with its `cpus` (from
`related_cpus`, which is space-separated), `governor`, and hardware
`min_hw`/`max_hw` (kHz). `CpuClusters.group` (tested in `CpuClustersTest`)
orders policies by `max_hw` and names them: one policy is "all cores"; two
are efficiency + performance; three add prime; four or more put balanced
and performance clusters between efficiency and prime. Without zperfd all
cores form one cluster.

| Metric | Definition | Unit |
|---|---|---|
| Cluster frequency | highest `cur_khz` among the cluster's cores | kHz → MHz/GHz |
| Frequency share | `(cur − min_hw) / (max_hw − min_hw)`, truncated | % |
| Cluster load | mean `load_pct` of its online cores with a valid load | % |
| GPU range | first and last of the devfreq available frequencies (`probe.gpu.min/max`); Hz on most GPUs, values below 10 000 000 are read as kHz | MHz |

## Battery (Battery screen)

`BatteryMath.merge` (tested in `BatteryMathTest`) combines Android's
`BatteryManager` (no root) with `zperfd battery`, a read-only snapshot of
`/sys/class/power_supply` (the `battery` supply or the one typed `Battery`,
with `bms` as fallback for capacity and cycle nodes; chargers are the online
supplies typed `USB*`, `Mains` or `Wireless`).

| Metric | Source | Unit | Not reported |
|---|---|---|---|
| Level, status, plug, health, technology, voltage, temperature | sticky `ACTION_BATTERY_CHANGED` (zperfd `capacity`/`status`/`voltage_now`/`temp` as fallback) | %, V, °C | "—" |
| Current | `BATTERY_PROPERTY_CURRENT_NOW`, else zperfd `current_now`. Values with magnitude ≥ 20 000 are µA, smaller ones mA. The sign is aligned to the charge status: **+ = into the battery** | mA | "—" |
| Power | voltage × current, signed like the current | W | "—" |
| Remaining charge | `BATTERY_PROPERTY_CHARGE_COUNTER`, else zperfd `charge_counter` | µAh → mAh | "—" |
| Time to full | `computeChargeTimeRemaining()`, else zperfd `time_to_full_now`; only while charging | min | hidden |
| Cycle count | `EXTRA_CYCLE_COUNT` (Android 14+), else zperfd `cycle_count` | count | "—" |
| Health % (wear) | zperfd `charge_full / charge_full_design`, kept only within 30–120 % | % | "not reported" / "needs module" note |
| Charger input | zperfd online charger: `voltage_now`, `current_now` (or `input_current_now`), type from `real_type`/`usb_type`/`type` | V, A, W | "—" with a note |
| Charge phase | zperfd `charge_type` (Fast, Taper, Trickle...) | text | hidden |

The charging diagram plots power, current, level, temperature or voltage
from the last 30 minutes, with minimum, average and maximum of the series.

## Memory (Monitor hub)

`ActivityManager.MemoryInfo`: `totalMem`, `availMem`, `threshold`,
`lowMemory`. Used = total − available, shown in GB and % of total.

## FPS recorder (FPS screen)

FPS is measured for the apps the user picks, not for Zairenkai's own UI (the
old Choreographer "FPS UI MONITOR" card is gone). The opt-in foreground
service `FpsMonitorService` runs the read-only stream

```
zperfd --interval 1000 frames --follow --watch <pkg,...>
```

zperfd reads the foreground app (`dumpsys activity activities`) and, while it
is a watched package, its presented frames from `dumpsys SurfaceFlinger
--frametimeline` (falling back to `dumpsys gfxinfo <pkg> framestats`). Each
line carries only frames not sent before. `FpsSessionBuilder` (tested) turns
the stream into sessions: one per continuous stretch of a watched app in the
foreground; leaving it for over **3 s** ends the session, which is kept when
it has at least **120 frames over 10 s**. Battery temperature and power are
attached to each second. Sessions are saved in app storage
(`fps_sessions.json`, newest 100).

`FpsAnalysis` (tested in `FpsAnalysisTest`) computes, from the present
timestamps:

| Metric | Definition |
|---|---|
| Frame interval | time between consecutive presented frames; gaps over 1 s are idle pauses and are not counted |
| Average FPS | frames / active time |
| Median, min, max FPS | over the per-second FPS |
| 1 %, 0.1 %, 5 % low | 1000 / mean of the slowest 1 %, 0.1 %, 5 % of intervals |
| Frame time avg, p95, p99, max | over the intervals, ms |
| Stability | share of active seconds within ±10 % of the median FPS |
| FPS variation | standard deviation of per-second FPS |
| Frame pacing | standard deviation of the intervals, ms |
| Jank | interval > 2 × the mean of the previous 3 and > 2 display refreshes |
| Stutter | interval > 100 ms |
| Dropped frames | Σ round(interval / median interval) − 1 |

**Drop events** are stretches below 80 % of the median FPS, or a stutter,
merged when under 2 s apart. Each is classified: *hitch* (one long frame),
*thermal* (battery temperature at least 4 °C above the session start; a
relative rise, not a fixed limit), *sustained* (5 s or longer) or *brief*,
and explained with the numbers of that event. **Insights** are generated per
session from the same data (stability, a frame-rate cap below the panel
refresh, low 1 % lows, uneven pacing, decline in the last third with or
without a temperature rise, stutters, jank rate, average power), so the text
differs from session to session.

## Usage history (PowerSampler, no root)

`UsageRecorder` samples `PowerSampler` every **2 s** during a recording, in
the app or in `ZkMonitorService`:

| Metric | Source | Unit | Not reported |
|---|---|---|---|
| Level | sticky `ACTION_BATTERY_CHANGED`: `EXTRA_LEVEL / EXTRA_SCALE` | % | `-1` |
| Voltage | `EXTRA_VOLTAGE` | mV → V | `0` |
| Current | `BATTERY_PROPERTY_CURRENT_NOW`, read as µA. The sign is whatever the device reports | mA | `0` |
| Power | `abs(V × I)` | W | `0` (derived) |
| Battery temperature | `EXTRA_TEMPERATURE` | 0.1 °C → °C | extra absent → `tempKnown = false` |
| Remaining energy | `ENERGY_COUNTER` (nWh), else `CHARGE_COUNTER` (µAh) × V | Wh | `0` |

Known limitation: some devices report `CURRENT_NOW` in mA. Power is then
about 1000× too small, which is obvious from the W value.

An unreported battery temperature is left out of the session and per-app
averages and maxima (`UsageAccumulator`, tested in `UsageAccumulatorTest`);
the curve holds the last reported value for that point. A session with no
reported temperature at all shows 0 °C.

## Dashboard

The Dashboard reads its state once on open and again on **Refresh**; it does
not poll.

| Item | Source |
|---|---|
| Root | `RootShell.isRootAvailable()` |
| ZKFC info / API version | `zkfctl info` |
| License / token state | `zkfctl license` |
| Boost status | `zkfctl boost status` |
| Thermal envelope | `zperfd thermal --json` |
| Safe mode | `zkfctl safe status` |

A failed read leaves its card empty (`null`) rather than showing a default.
Fifteen seconds after a healthy load, with zkfctl info, zperfd status and
probe all succeeding and safe mode off, the Dashboard confirms the boot with
`zkfctl safe confirm`. That is the only write it makes, and it changes no
tunable.
