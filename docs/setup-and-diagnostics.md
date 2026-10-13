<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Setup flow, system check and diagnostics

This page covers how the app finds out what is installed, how it explains a
missing piece, and the first-run setup flow. It also covers the edge-to-edge
system-bar policy and the collapsing page titles.

## System check (setup step 4 and the Dashboard)

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

Six steps, in order. The screen is the stateless `SetupFlow(ui, permissions,
callbacks)`; the ViewModel drives it in the app and the tests drive it
directly. Setup only checks and asks; it applies no profile or tweak (that is
the Profiles tab, through zperfd).

| Step | What happens | Next allowed when |
|---|---|---|
| 1. Welcome | The mascot pops in; "Halo" types itself into "Kenali Zairenkai" and the gradient tagline. Language and device info at the bottom. | always |
| 2. Legal information | Terms of use, risks and disclaimer, privacy, the Zairenkai licence, third-party components (opens Open-source licences), trademarks; each opens in place. The user ticks the consent box. | consent ticked; accepting stores `LEGAL_VERSION` and the time |
| 3. Appearance | Theme mode, dynamic colour and accent, saved as chosen, so the setup and the live preview follow at once. A miniature of the app plays in a phone frame (content scrolls, the gauge fills, bars move, the tab glides). The features as swipeable cards. | always |
| 4. System check | Root, ZKFC, zperfd and the token, probed read-only (see above), with animated badges and a plain alert when something is missing. | every probe answered |
| 5. Permissions | Notifications, usage access, battery optimisation (see below). | always (all optional) |
| 6. Done | The celebrating mascot inside the signature line sequence, a summary of checks and permissions, next steps when something is missing, and Start. | Start finishes setup |

Back (on screen and system) works on steps 2 to 5. Step changes within 280 ms
are ignored, so a double tap never skips a step.

### Permissions

Only what the app uses. Status is read from the system and refreshed when the
app returns to the foreground; nothing is requested silently.

| Permission | Why | How |
|---|---|---|
| Notifications (`POST_NOTIFICATIONS`) | The FPS recorder and the history recorder are foreground services and must show a notification | runtime request; after a denial, the app's notification settings |
| Usage access (`PACKAGE_USAGE_STATS`) | Per-app profiles, Auto mode and usage history need the foreground app | the system's usage-access settings |
| Battery optimisation (recommended) | So Android does not stop the background recorders | the system's battery-optimisation list (no extra permission is declared) |

Root is granted by the root manager and checked in step 4. The same list is in
**Settings → App permissions**.

### Mascot

The mascot is the owner's artwork: one pose per state (`art/mascot/*.png`),
each animated as a Lottie by `tools/lottie/gen_mascot.py`
(`res/raw/mascot_<state>.json`). The generator cuts each pose into layers so
parts move on their own (stars, confetti, hearts, the "!" marks, sparkles, the
warning sign) and embeds them as WebP, so every animation is one offline file.
Loops are seamless. State comes from the real setup state, never a timer:

| State | When | Motion |
|---|---|---|
| WELCOME | welcome page | sways with open arms; the "!" calls out; hearts bob |
| INSPECTING | a probe is still running | leans side to side with the magnifier; a glint crosses the lens; crystals sparkle |
| READY | every check passed; usable with notes at the end | thumbs up with a hop; sparkles pulse |
| WARNING | something is missing | a shake; the warning sign flashes and wiggles |
| CELEBRATING | done, and every check is OK | two jumps per loop; stars spin; confetti falls |
| IDLE | calm places (permissions, Settings) | breathes; faint sparkles |

States cross-fade (320 ms); the first appearance pops in with a spring. With
reduced motion the pose is shown still. While a composition loads (or if it
fails), `mascot_fallback.webp` holds the space. The mascot is decorative for
screen readers; the step text says the same thing.

### Character and motion

- **Backdrop**: setup's own surface (`ZkSetup`: `#F7F9FC` light, `#0B1018`
  dark) with a luminous pastel arc that breathes and moves per step.
- **Greeting**: types "Halo", erases it, types "Kenali Zairenkai", then the
  tagline in the brand gradient, with a blinking caret. Space is reserved, so
  nothing jumps. Screen readers get the final text at once (`greetingFrames`,
  tested).
- **Line art** (Lottie, `tools/lottie/gen_setup_animations.py`): the mark
  turning (home), tuning waves (home profile card) and the signature success
  sequence (done step).
- **Steps** slide and fade (360 ms, emphasized decelerate); content rises in,
  in reading order (70 ms apart).
- **Check badges** turn while checking; when the probe answers the ring
  closes and a check, cross or "!" is drawn in (420 ms). Shape and colour both
  carry the state.
- **Actions**: a pill-shaped primary action that squeezes slightly while
  pressed, with a spinner slot so the label never jumps.
- **Reduced motion** (animator scale 0): final text and still frames at once.

## Legal information and open-source licences

**Legal information** (setup step 2, and Settings → Legal information) has six
sections: terms of use, risks and disclaimer, privacy and data, the Zairenkai
licence, third-party components and trademarks. The privacy text reflects the
app as built: it declares no internet permission, stores its data in private
storage only and is excluded from backups (`allowBackup="false"`).

**Open-source licences** lists every library packaged in the build, sorted by
name, with its version, developers and licence chips, a search box, and a
summary of licences by count. A library opens a sheet with its description,
Maven id, Source and Website links, and the full licence text (paragraphs
re-flowed for the screen).

How the list stays complete:

1. `OssLicensesTask` (in `app/build.gradle.kts`) runs for every variant. It
   takes the variant's runtime classpath (BOMs excluded), matches each library
   against `tools/licenses/libraries.json`, adds the full text of each licence
   used from `tools/licenses/texts`, and writes `assets/oss_licenses.json`.
2. The build **fails** if a packaged library has no metadata, naming it.
3. To update the metadata after a dependency change:
   `./gradlew :app:writeOssModules && python3 tools/licenses/update_libraries.py`.
   The tool reads each library's POM (Gradle cache, Google Maven or Maven
   Central, with back-off) and its parents.

The typeface (Google Sans Flex, OFL-1.1) is listed from the `extra` entries.

## Icons

Every icon in the app is Zairenkai's own (`ui/icons/ZkIcons.kt`): rounded
2 dp strokes on a 24 dp grid, the same line language as the setup art. There
is no icon library; the foundation check fails if a Material icon import or
the `material-icons` dependency comes back. `SetupArtRenderTest.icons` renders
the whole set.

## Settings

Grouped like a system settings page: a hero (mascot, version; opens About),
then **Device** (About phone), **Appearance** (theme, app language, dynamic
colour), **Performance** (lite mode, device mitigation), **Permissions &
privacy** (app permissions sheet, privacy), **Diagnostics** (ZKFC log: level
and live log on its own page) and **About** (about the app, legal
information, open-source licences).

## Language switching

On Android 13+ the per-app language is applied in place: `MainActivity`
declares `configChanges="locale|layoutDirection"`, so the activity is not
recreated and Compose recomposes with the new strings. The language sheet
slides away first, then the language changes and the content fades in
(420 ms), so there is no flash. On Android 12 the activity is recreated, as
before.

## About phone and brand-aware identity

**About phone** (Settings → About phone, or the device card at the top of
the System tab) shows the device from public Android APIs and `getprop`,
without root: a brand-aware hero, storage use, then software (Android and
API level, security patch, build number, kernel, baseband, bootloader),
hardware (SoC, CPU cores and ABI, RAM, display resolution, refresh rate and
density) and identity (brand, manufacturer, model, codename, the user's
device name). A field Android does not report is left out. The copy button
copies every row.

The brand (`resolveBrand`) is the first of these that names a known brand:
the `debug.zairenkai.brand` property (to preview another brand), then
`ro.product.brand`, `Build.BRAND` and `Build.MANUFACTURER`. A POCO (brand
POCO, maker Xiaomi) is therefore POCO, and a ROM with an odd brand falls back
to the maker. Known brands: Google Pixel, Samsung, Xiaomi, Redmi, POCO,
OnePlus, realme, Nothing and Motorola; any other maker gets the Android mark
and its own name. The product name comes from the maker's marketing-name
property when there is one (`ro.product.marketname` and others), else from
the model.

The marks are Zairenkai's own: a squircle in colours associated with the
brand, a monogram in the app's typeface, and the brand name as text. The
makers' logos are trademarks and are not bundled. A brand can be given an
official logo drawable (`BrandStyle.officialLogo`) where its use is
permitted; the mark then draws it instead.

`getprop` runs once, off the main thread (`rememberDeviceInfo`); the hero
shows a spinner until then.

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
- **Visual review:** `SetupArtRenderTest` renders the line animations as filmstrips (light and dark), every icon, and the app icon in several masks. `MascotRenderTest` renders every mascot pose as a filmstrip (light and dark); `DeviceRenderTest` renders every brand mark, device heroes and About phone; `SettingsRenderTest` renders Settings.
- **Licences:** `OssLicensesTest` (the generated catalogue is complete, sorted and has every licence text; search; list and detail sheet).
- **Device and mascot logic:** `DeviceBrandTest` (brand order, product names, getprop parsing) and `MascotStateTest` (the mascot never celebrates or reports ready while a probe runs or something is missing).
- **Logic:**
  - `InstallFactsTest`: probe parsing, root/zkfctl classification, read-only script.
  - `SetupChecksTest`: every install state's reason, no raw output, report contents.
  - `MonitorStateTest`: missing-dependency backoff.
  - `BatteryMathTest`: cycle-count provenance.
- **UI (Robolectric):**
  - `SetupFlowUiTest`: every step (welcome, legal consent, appearance, check, permissions, done), actions, Back/system Back, honest done screen.
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
