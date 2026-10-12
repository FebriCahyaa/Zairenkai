<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Setup flow, system check and diagnostics

This page covers how the app finds out what is installed, how it explains a
missing piece, and the first-run setup flow. It also covers the edge-to-edge
system-bar policy and the collapsing page titles.

## System check (setup step 2 and the Dashboard)

`SetupProber` (`ui/onboarding/SetupProber.kt`) runs the same read-only check
for the setup wizard and the Dashboard. **Retry** re-runs every step. It first
clears the cached root result and the cached zkfctl location
(`ZkfcRepository.invalidate()`). The check runs in this order:

1. **Root**: `su -c id -u`, with a 15 s timeout so the root manager's prompt
   can be answered. The result is classified (`classifyRoot`) as follows.

   | State | Evidence | Shown as |
   |---|---|---|
   | `GRANTED` | exit 0 and uid 0 | Root permission granted |
   | `NO_SU` | `su` cannot be started (exit 127, "not found") | No root manager (su) found |
   | `TIMEOUT` | no answer within 15 s | The root request was not answered |
   | `DENIED` | anything else (exit ≠ 0, or uid ≠ 0) | Root permission denied |

2. **Install facts**: one root shell (`InstallProbe.SCRIPT`) that only tests
   files and reads `getprop`/`uname`. Its paths match `module/customize.sh` and
   `module/service.sh`.

   | Fact | Source |
   |---|---|
   | zkfctl | first of `/data/adb/zkfc/zkfctl`, `/data/adb/modules/zairenkai/zkfctl`, `/system/bin/zkfctl`: present and executable / present but not executable / missing |
   | Framework loaded | `/dev/zkfc` exists |
   | Loadable module | `/sys/module/zkfc` exists (built-in ZKFC may not appear here) |
   | Bundled zkfc.ko | `/data/adb/modules/zairenkai/zkfc/zkfc.ko` (the installer copies it only when one matches the kernel's KMI) |
   | Module state | `/data/adb/modules/zairenkai` and its `disable` / `remove` flags; `/data/adb/modules_update/zairenkai` means reboot pending |
   | Engine | `/data/adb/zperf/zperfd` executable; `zperfd.pid` names a live process |
   | Token file | `/data/adb/zkfc/token.zkl` exists (its contents are never read by the app) |
   | ABI / kernel | `ro.product.cpu.abi`, `uname -r` |

3. **`zkfctl info`** and **`zperfd status`** run in parallel. When zkfctl
   prints no JSON, the client gives the reason a `kind` instead of passing the
   shell text on as data:

   | kind | Evidence |
   |---|---|
   | `not_found` | exit 127 or "not found" (Android's sh: "inaccessible or not found") |
   | `not_executable` | exit 126 or "Permission denied" |
   | `timeout` | the call ran past its deadline |
   | `malformed` | output that is not a JSON object |
   | `no_output` | no output and none of the above |

A missing zkfctl is not cached as a location; the next call looks again after
15 s, or at once after Retry.

### What the user is told

`setupChecks` turns the probe into four rows: root, ZKFC, engine and token.
Each row has a state (checking / ready / needs attention / not ready), a
localized reason and the next step. The ZKFC reason is chosen from the facts,
in this order:

module being removed → module disabled → reboot pending → module not installed
→ module installed but no zkfctl for this ABI → zkfctl not executable → zkfctl
works but `/dev/zkfc` is absent (`zkfc.ko` failed to load, *or* the kernel has
no ZKFC and the package has no `zkfc.ko` for it) → zkfctl timed out →
unrecognised answer.

Raw shell output never becomes a visible reason. It goes to the **technical
details** (a collapsible view, and the setup dialog) and to the copyable
report (`diagnosticsReport`). The report holds component states, versions,
paths and the raw reasons. It holds no token contents, licensee or key
fingerprints.

### Dashboard

The Dashboard shows the system check first, with Retry and the technical
details. Results that did not come back `ok` are dropped, so their defaults
are never shown as device data (API 0.0.0, hook "none", token "missing", zero
boosts, thermal "normal"). Fields with no reported value are left out.
Without the module, the Dashboard says what still works: battery and RAM in
the Monitor. CPU, GPU, thermal, the FPS recorder, profiles and tweaks need
the module.

Boot confirmation (`zkfctl safe confirm`) runs only after ZKFC answered `ok`,
safe mode is off, and 15 s later the engine and its probe answer again.

### Retry pacing (no `su` storm)

| Loop | Normal | When a dependency is known to be missing |
|---|---|---|
| Monitor `zkfctl monitor` | next sample at once; failures back off 1, 2, 4, 8 s | 60 s (`monitorNextDelayMs`) |
| zperfd battery / probe | every 5 s | every 60 s after 3 misses |
| FPS recorder | stream continuously | 10 s, 20 s, 40 s … up to 5 min (`fpsRetryDelayMs`), with a visible blocked state |

The FPS recorder reports `NO_ROOT`, `ENGINE_MISSING` or `STREAM_FAILED` (zperfd
ran but sent no line, e.g. an older engine without `frames`), both in the FPS
screen and in its notification.

## Setup flow

Four steps: welcome, system check, an optional first profile (applied only
through zperfd and reported as applied only when zperfd commits it), and done.
The screen is the stateless `SetupFlow(ui, callbacks)`. The ViewModel drives
it in the app; the tests drive it directly.

Design:

- **Backdrop**: the surface colour with two faint primary/tertiary glows
  that glide between steps.
- **Thread**: one flowing line, drawn on a Canvas from a closed-form curve.
  It lives outside the step transition, so it never restarts. Its shape
  comes from real state only (`threadShape`): curls on welcome; lively while
  the check runs and calm once it settles; energy following the chosen
  profile; drawing itself in on done. Every phase term is an integer
  multiple of one cycle, so the infinite animation wraps without a visible
  reset. Animated values are read in the draw phase only, so motion never
  recomposes the screen, and the Path is reused.
- **Emblems**: the existing Lottie files, coloured from the scheme: brand
  mark (welcome), scan / success / attention (check), tune (profile), and
  success or attention inside a one-shot completion ring (done).
- **Motion**: steps slide 1/8 of the width and fade (320 ms, no bounce).
  State badges cross-fade (250 ms). The progress bar springs. Reduced motion
  (animator scale 0) shows still frames.
- **Actions**: a pill-shaped inverse primary action and a quiet Back. The
  primary keeps a fixed leading slot for its spinner, so the label never
  jumps. Step changes within 280 ms are ignored, so a double tap cannot skip
  a step. An apply cannot start twice, and Back is disabled while applying.
- **System Back** follows the on-screen Back (check and profile steps only).

## System bars and insets

`applySystemBars(dark)` (`ui/theme/SystemBars.kt`) is the single policy.
Both bars are transparent, and icon contrast follows the **app's** resolved
theme. The old `enableEdgeToEdge()` defaults followed the system dark mode,
so choosing Dark in the app while the system was light left dark icons on a
dark page.

The policy runs in `MainActivity` before setContent (following the system
until settings load). `ZairenkaiTheme` re-applies it whenever the app's
light/dark changes. Navigation-bar contrast enforcement is off, because the
app always paints behind the bar.

Inset ownership, so nothing is padded twice:

| Area | Owner |
|---|---|
| Root (bottom navigation) | the root Scaffold, bottom only (`contentWindowInsets = 0`, then `consumeWindowInsets`) |
| Top of a tab or detail page | its top app bar (draws behind the status bar) |
| Monitor hub (no top bar) | its own `safeDrawing` top + horizontal padding, outside the scroll |
| Setup | header: top + horizontal; action bar: bottom + horizontal; backdrop draws edge to edge |

## Collapsing titles

`CollapsingScaffold` (`ui/components/CollapsingScaffold.kt`) uses Material 3's
`LargeTopAppBar` with `exitUntilCollapsedScrollBehavior`, connected to the
content through nested scroll. There is one title source and one scroll
state. The bar follows the finger, settles to a whole state on release, and
fills the status-bar area when collapsed. It is used by Settings, System,
About, Theme, Runtime, Usage history, Session detail and the Monitor detail
screens (CPU & GPU, Battery, FPS, FPS session).

## Tests and screenshots

```
./gradlew :app:testStandardDebugUnitTest
```

- **Logic:**
  - `InstallFactsTest`: probe parsing, root/zkfctl classification, read-only script.
  - `SetupChecksTest`: every install state's reason, no raw output, report contents.
  - `MonitorStateTest`: missing-dependency backoff.
  - `BatteryMathTest`: cycle-count provenance.
- **UI (Robolectric):**
  - `SetupFlowUiTest`: every step, actions, Back/system Back, no double apply, honest done screen.
  - `CollapsingTitleUiTest`: collapse while dragging, settle, stay collapsed, expand at the top; system-bar icons follow the app theme.
- **Screenshots** (light and dark) are written to `app/build/outputs/roborazzi/`.
- Robolectric's Android jar is resolved by Gradle (`robolectricRuntime`), and Robolectric runs offline.

### Manual check on a device

These steps need hardware and have not been run here.

1. Install the debug APK. Switch the system theme and the app's Theme
   (Settings → Theme) in all four combinations. The status and navigation
   icons must stay readable on every setup step and on the main tabs, in
   gesture and three-button navigation.
2. Without the module: the setup check names the missing module and its
   next step, and Retry re-runs it. The Dashboard shows no API 0.0.0, hook or
   boost values.
3. Flash the module zip without rebooting, then Retry: "reboot pending".
   Reboot, then Retry: the components turn ready as they really are.
4. On About and Settings, scroll slowly, reverse near the collapse point,
   fling both ways, leave and return, and rotate. Check the title for
   flicker, duplicates or jumps.
