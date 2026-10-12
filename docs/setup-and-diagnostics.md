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

### Home (Dashboard)

`HomeScreen` (`ui/home/HomeScreen.kt`) is stateless; `DashboardScreen` connects
it to `OverviewViewModel`. It has the setup's character: the brand backdrop
(`BrandBackdrop`), a greeting for the time of day that types itself, and a
one-line summary in the brand gradient (checking / root needed / all ready /
*n* components need attention). Below the summary, a strip shows root, ZKFC,
zperfd and the token at a glance.

Then, in order (each card rises in after the previous one):

1. **System check**: first while something needs attention, with every
   reason, next step, Retry and the technical details. When everything is
   ready it moves to the bottom as one line with Retry.
2. Safe-mode and outdated-API banners.
3. **Active profile**: the profile zperfd runs (desired, and the effective
   mode when it differs), Auto, any baseline hold, and a shortcut to
   Profiles. Shown only when `zperfd status` answered `ok`.
4. **Thermal**: the engine's headroom as a gauge and its band (normal, warm,
   hot, critical), with the hottest sensor, control zone and critical limit
   when reported. The app sets no thresholds; band and colour come from
   zperfd.
5. **Active performance**: input boost, boosted tasks, cpufreq requests and
   thermal mitigation, from ZKFC.
6. **Device**: ZKFC and licence state, architecture, kernel type, hook mode,
   API, licensee, kernel and build.

Results that did not come back `ok` are dropped, so their defaults are never
shown as device data (API 0.0.0, hook "none", token "missing", zero boosts,
thermal "normal"). Fields with no reported value are left out, and so are
the thermal card without the engine and the boost card without ZKFC. Without the
module, the home says what still works: battery and RAM in the Monitor. CPU,
GPU, thermal, the FPS recorder, profiles and tweaks need the module.

Status colours used as text (`statusInk`) are adjusted per theme to 4.5:1
on every container and in their tinted pills; graphics (`statusGraphic`)
to 3:1. `ContrastTest` checks both.

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

Design (the setup's character):

- **Backdrop**: the surface colour with a luminous pastel arc across the
  top (scheme containers blended with peach, pink and lavender) that breathes
  slowly and moves per step, plus a soft glow at the bottom.
- **Greeting**: the welcome page types "Halo", erases it, types the app
  name, then types the tagline in the brand gradient (blue → violet → pink →
  amber, tuned separately for light and dark), followed by a blinking caret.
  Space for the final text is reserved, so nothing jumps while it types.
  Screen readers get the final text at once. The script is a pure function
  (`greetingFrames`, tested). The completion title types itself the same way.
- **Line art** (Lottie, `tools/lottie/gen_setup_animations.py`): gradient
  strokes named `brandGradient`, recoloured at runtime.
  - welcome: the Zairenkai mark, a four-petal pinwheel that turns slowly
  - check: orbit rings while checking; success or a pulse line when settled
  - profile: a bundle of waves
  - done: the signature sequence (particles gather, burst into rays, a line
    bundle opens, orbit rings turn in, a coil draws through, and the mark
    resolves; the last frame is the still)
- **Reveal**: content rises in, in reading order (70 ms apart, emphasized
  decelerate). On the welcome page it starts once the greeting has typed.
- **Language and device info**: our own icons (`ZkIcons.Globe`,
  `ZkIcons.Info`), shown only at the bottom of the welcome page, next to a
  compact start pill. After setup, the language is changed in
  **Settings → App language** (`LanguageSheet`, shared).
- **Actions**: a pill-shaped inverse primary action and a quiet Back. A
  spinner shows while busy, without the label jumping. Step changes within
  280 ms are ignored, an apply cannot start twice, and Back is disabled
  while applying.
- **System Back** follows the on-screen Back (check and profile steps only).
- **Reduced motion** (animator scale 0): the final text and still frames
  show at once.

## Typography and colour

- **Typeface**: Google Sans Flex, the Material 3 Expressive variable face
  (OFL-1.1, `LICENSES/OFL-1.1.txt`).
  - Bundled as a subset: Latin, Latin Extended, Greek, Cyrillic, symbols.
    Japanese and Chinese use the system font.
  - Axes: GRAD and slnt pinned to 0, wdth limited to 75–125, wght to 100–900.
    The subset is about 1.2 MB, smaller than the Roboto Flex it replaces.
  - Each role sets weight, optical size and roundness (ROND): display text is
    fully rounded, headlines mostly rounded, body text crisp.
- **Contrast**: `ContrastTest` checks every accent in light and dark.
  - Body, secondary and primary-coloured text reach 4.5:1 on surface and
    containers.
  - White on primary and the inverse pill reach 4.5:1.
  - Each gradient stop of the large greeting reaches 3:1.
  - Light accents that were too bright are darkened along their own hue
    (`readableOn`).

## App icon

`tools/icon/gen_app_icon.py` generates the adaptive icon from the same mark
geometry. Never edit the generated files by hand; the foundation check
compares them with the generator output.

- **Background**: a luminous diagonal gradient with a soft highlight.
- **Foreground**: the white pinwheel mark, inside the 66 dp safe zone.
- **Monochrome**: a layer for Android 13+ themed icons.
- **Splash**: a gradient disc with the mark.

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

- **Home:** `HomeUiTest` (ready, missing module, no root, checking, safe mode; light and dark, viewport and full page) and `HomeModelTest` (greeting, summary, thermal bands).
- **Visual review:** `SetupArtRenderTest` renders every animation as a filmstrip (light and dark), the custom icons, and the app icon in several masks.
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
