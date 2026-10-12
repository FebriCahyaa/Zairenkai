<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Monitor and dashboard metrics

This page lists every number the Monitor, the usage history and the Dashboard
show: where it comes from, its unit, how often it is sampled, and how
"not reported" is encoded and displayed. Every value here is **observation
only**. Reading a metric never changes a tunable; mutations go through zperfd.

## Sampling loop

`MonitorViewModel` (in `app/.../ui/Vm.kt`) runs while the Monitor screen is
visible:

1. It calls `zkfctl monitor 500` as root (`ZkfctlClient.monitor`, 6.5 s timeout).
   zkfctl reads `/proc/stat`, sleeps the **500 ms window**, then reads it again
   along with the other nodes below.
2. The result is folded into `MonitorUi` by `withResult`, which is a pure,
   unit-tested function (`MonitorStateTest`).
3. After a good sample the next one starts right away, so the cadence is
   about 500 ms plus root-shell overhead (around 0.6 to 1 s). After a failed
   sample it waits 1, 2, 4, then 8 s (`monitorRetryDelayMs`).

Leaving the screen cancels the loop. Coming back starts a new loop that keeps
the chart history but waits for a fresh sample before it shows **LIVE**.

Charts hold the last **60 valid points**. Only measured values are appended.
When a sensor is not reported, its chart simply gets no point, so a missing
sensor never draws a flat line at 0.

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

## FPS (Monitor hero card)

`rememberFrameStats` (`ui/components/FrameMonitor.kt`) measures the
**Monitor screen's own frame cadence** with `Choreographer` frame callbacks.
It is labelled "FPS UI MONITOR" and is **not** a foreground game's FPS.

| Metric | Definition | Unit |
|---|---|---|
| Current | `1e9 / Δt` of the last frame, capped at 1.2 × refresh | fps |
| Average, min, max | over the whole time the screen is open | fps |
| 5% low | 5th-percentile frame, from a 1-fps histogram | fps |
| Jank | frames with `Δt > 1.5 ×` the refresh budget | count |
| Refresh rate | `Display.getRefreshRate()` (falls back to 60) | Hz |

Every frame is measured. The summary is published at most every 250 ms,
because publishing on every frame made the card recompose 60–120 times a
second and so caused the jank it was measuring. Before two frames have been
measured, the chart shows "Menunggu sampel frame" and values show "—".

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
