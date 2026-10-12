# Monitor UI — Material 3 Expressive Redesign

## Goals

- A hub of cards, one per subsystem, where every card opens its own screen.
- Real FPS of the games and apps the user chooses, recorded per session and
  explained from the session's own data, instead of the Monitor's own UI FPS.
- Detailed, readable CPU/GPU/thermal and battery screens with labelled charts.
- Keep working without the Zairenkai module: battery, RAM and FPS sessions
  stay; CPU, GPU and thermal show a compact "needs the module" state and a
  small dismissable banner instead of a large error.
- Distinguish an unavailable sensor from a measured `0%` value.
- Use Material 3 theme tokens, respecting the selected/dynamic color scheme
  and the existing glass preference.

## Screens

1. **Monitor hub** (`MonitorScreen`) — header with LIVE / DATA DASAR state and
   the usage-history action; FPS card (live FPS while recording, else the last
   session), CPU and GPU cards with sparklines, battery card (level ring, power,
   current, temperature, health and cycles), thermal and memory cards, and the
   ZKFC runtime footer when the service reports it.
2. **CPU & GPU** (`monitor/CpuGpuScreen`, tabs CPU / GPU / Thermal) —
   - CPU: load ring and history; one block per cluster (role, cores, governor,
     current frequency within the hardware range, load) and a multi-line
     cluster-frequency chart; per-core tiles with load sparklines and
     frequency; platform facts from `zperfd probe`.
   - GPU: busy ring, frequency and its share of the maximum, busy and
     frequency history, frequency provider and OPP range.
   - Thermal: hottest zone with history and every plausible zone.
3. **Battery** (`monitor/BatteryScreen`) — level ring, charge state and time
   to full, signed power and current; a charging diagram switchable between
   W / mA / % / °C / V with min/avg/max; the charger (input W, V, A, phase);
   battery health (full vs design capacity, cycle count, Android health,
   technology); electrical details.
4. **FPS** (`monitor/FpsScreen`) — auto-recorder switch with live state, the
   watched-app list with an installed-app picker, an overview of all
   sessions, and the session list.
5. **FPS session** (`monitor/FpsSessionScreen`) — average FPS, stability
   ring, median and 1 % / 0.1 % lows; the per-second timeline with drops
   shaded and the refresh rate marked; battery temperature during the
   session; insights; every drop with its cause and explanation; frame-time
   statistics; the FPS distribution; the full statistics table.

## Data correctness

Sources, units, cadence and unavailable states for every metric are listed in
[`monitor-metrics.md`](monitor-metrics.md). The screens share one sampler
(`LiveMonitor`), so charts survive moving between them. CPU, GPU and thermal
points are appended only after a valid sample, so a missing sensor never
becomes a zero-valued flatline. A failed sample backs off (1/2/4/8 s) instead
of re-spawning `su` in a tight loop.

## FPS source boundary

Game FPS comes from zperfd's read-only `frames` stream (SurfaceFlinger frame
timeline, gfxinfo as fallback) through an opt-in foreground service. Nothing
in the FPS path writes a tunable. The Choreographer sampler that measured the
Monitor's own UI was removed with the old hero card.

## Theme

`ZairenkaiTheme` uses the stable `MaterialTheme` (the Expressive theme and
motion scheme are internal in the Compose material3 release the app pins).
The Monitor uses asymmetric large rounded shapes (one tighter corner),
emphasized display type for headline numbers, tonal containers, ring gauges,
semantic accent colors and spring-animated values. Dynamic color remains
supported.
