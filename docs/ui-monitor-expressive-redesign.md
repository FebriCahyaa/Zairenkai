# Monitor UI — Material 3 Expressive Redesign

## Goals

- Put the highest-value signal (frame rate) first, with readable hierarchy and a single dominant card.
- Remove large empty graph canvases when a source has not produced valid samples.
- Distinguish an unavailable sensor from a measured `0%` value.
- Make CPU/GPU glanceable in a two-column layout, while keeping detailed per-core data collapsible.
- Make thermal availability and ZKFC runtime state explicit instead of treating missing data as a visual failure.
- Use Material 3 Expressive theme tokens and expressive motion, while respecting the selected/dynamic color scheme and the existing glass preference.

## Layout

1. **Monitor header** — page title, live/sync/paused state, display refresh-rate chip, history action.
2. **Frame-rate hero** — current FPS, refresh rate, bounded chart, average, 5% low, and jank count.
3. **CPU/GPU glance cards** — current values, sensor-specific supporting text, compact history charts only when available.
4. **Per-core detail** — collapsible core cards to keep the default scroll concise.
5. **Thermal card** — hottest valid reading, a short history graph, and the five hottest zones; a compact diagnostic empty state is used when no valid zones exist.
6. **ZKFC runtime footer** — input boost/thermal guard state and boosted-task count when the service reports it.

## Data correctness

`MonitorViewModel` now appends CPU, GPU, and thermal points only after a valid sample is received. Missing CPU/GPU/thermal sensors are no longer converted into zero-valued time-series points, avoiding misleading flat-line graphs. Starting a new monitor session clears the headline sample while preserving rolling history, so old data is not labeled as a fresh live result.

## FPS source boundary

The existing app-layer sampler uses `Choreographer` callbacks from the Monitor UI. The visible metric is therefore labeled **FPS UI Monitor** and must not be interpreted as a foreground game's FPS. The daemon already owns the lower-overhead frame-analysis path; exposing those readings to the Android UI requires a bounded, read-only telemetry contract and is intentionally not simulated by this presentation-only change.

## Material 3 Expressive

`ZairenkaiTheme` uses `MaterialExpressiveTheme` with `MotionScheme.expressive()`. The Monitor uses large/increased rounded shapes, emphasized headline type, tonal containers, semantic accent colors, compact icon actions, and visibility/content-size transitions. Dynamic color remains supported.

## Validation notes

The project Gradle wrapper could not download its pinned Gradle distribution in the execution environment because external DNS/network access was unavailable. Source-level review and repository diff checks are performed, but an Android/Kotlin compile result cannot be claimed from this environment. Compile the debug variant in CI or a connected development environment before release.
