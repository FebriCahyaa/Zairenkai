# Home dashboard layout update

## Goals

- Keep system health and recovery actions near the top of Home.
- Make component chips understandable without relying on color alone.
- Group performance controls separately from device/framework facts.
- Use available space on wide displays without stretching individual cards across the whole window.
- Keep long labels readable on small phones and split-screen windows.
- Respect the safe-drawing area at both the top and bottom of the page.

## Reading order

1. Brand row and greeting.
2. Visible status chips with a text state (checking, ready, needs attention, or not ready).
3. Safe-mode / outdated-API alerts, if applicable.
4. System status: compact and actionable when healthy; expanded with individual checks when attention is needed.
5. ZKFC installation guidance when the framework is known to be missing.
6. Performance section: active profile, thermal headroom, and boost activity.
7. Device & framework details.

## Responsive rules

- The content column is capped at 760 dp and centered on wider windows.
- At a window width of 760 dp or more, the active profile and thermal cards share a two-column row when both have trustworthy data. Other cards remain full width.
- On narrower windows, performance cards retain a one-column order.
- The thermal gauge shrinks from 128 dp to 104 dp when the card's available content width is below 320 dp.
- Boost metrics change from three equal columns to a two-column row plus a full-width third metric below 320 dp of card content width.
- Bottom safe-drawing insets keep the final controls clear of gesture/navigation bars.

## Status correctness

Safe mode and an outdated ZKFC API now override the normal ready headline. The detailed warning is placed before the normal system-status card so an all-green compact status cannot visually bury an active warning. The dashboard continues to display only device information that has a corresponding source result.

## Validation performed

- All 116 Android resource XML files parse successfully.
- `strings_home.xml` keys were checked for uniqueness and parity across English, Indonesian, Spanish, Japanese, and Simplified Chinese; existing intentionally non-translatable chip labels remain English.
- `tests/app_architecture_check.sh` passes.
- The Gradle UI/unit-test task could not run in this environment: Gradle 9.8.1 is not cached and `services.gradle.org` is unreachable. No claim is made that Android compilation or Robolectric UI tests passed.
- The broader `tests/foundation_check.sh` is not a Home UI test and does not pass on the supplied working tree; it fails on an existing shell-log marker check (`zperfd started`) before reaching its remaining checks. The baseline archive also fails later because the script expects old locale directories that are not in the repository.
