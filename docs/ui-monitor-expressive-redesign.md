# Monitor UI — Material 3 Expressive Redesign

## Goals

- Put the highest-value signal (frame rate) first, with readable hierarchy and a single dominant card.
- Remove large empty graph canvases when a source has not produced valid samples.
- Distinguish an unavailable sensor from a measured `0%` value.
- Make CPU/GPU glanceable in a two-column layout, while keeping detailed per-core data collapsible.
- Make thermal availability and ZKFC runtime state explicit instead of treating missing data as a visual failure.
- Use Material 3 theme tokens and motion, while respecting the selected/dynamic color scheme and the existing glass preference.

## Layout

1. **Monitor header** — page title, live/sync/paused state, display refresh-rate chip, history action.
2. **Frame-rate hero** — current FPS, refresh rate, bounded chart, average, 5% low, and jank count.
3. **CPU/GPU glance cards** — current values, sensor-specific supporting text, compact history charts only when available.
4. **Per-core detail** — collapsible core cards to keep the default scroll concise.
5. **Thermal card** — hottest valid reading, a short history graph, and the five hottest zones; a compact diagnostic empty state is used when no valid zones exist.
6. **ZKFC runtime footer** — input boost/thermal guard state and boosted-task count when the service reports it.

## Data correctness

Sources, units, cadence and unavailable states for every metric are listed in
[`monitor-metrics.md`](monitor-metrics.md). `MonitorViewModel` appends CPU, GPU
and thermal points only after a valid sample, so a missing sensor never becomes
a zero-valued flatline. A failed sample clears the headline, shows the reason
with a "TIDAK TERSEDIA" state and backs off (1/2/4/8 s) instead of re-spawning
`su` in a tight loop. Starting a new monitor session keeps rolling history but
waits for a fresh sample before showing LIVE.

## FPS source boundary

The app-layer sampler uses `Choreographer` callbacks from the Monitor UI. The
visible metric is therefore labeled **FPS UI Monitor** and must not be
interpreted as a foreground game's FPS. The daemon owns the lower-overhead
frame-analysis path; exposing those readings to the Android UI requires a
bounded, read-only telemetry contract and is intentionally not simulated here.

## Theme

`ZairenkaiTheme` uses the stable `MaterialTheme` (the Expressive theme and
motion scheme are internal in the Compose material3 release the app pins). The
Monitor uses large rounded shapes, emphasized headline type, tonal containers,
semantic accent colors and visibility/content-size transitions. Dynamic color
remains supported.
