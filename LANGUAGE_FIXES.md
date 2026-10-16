# Zairenkai per-app language fix

Base: `FebriCahyaa/Zairenkai`, branch `ccr-076d8b15-osiupp`, commit
`6129b2d83c13aa7b5fc4354e00c72243c3a9d955` (`fix(boot): harden manual image patching and ZKFC initialization`).

## Changes

- Run language selection in the Activity lifecycle scope so closing the modal sheet cannot cancel the coroutine before the selected locale is persisted and applied.
- Treat Android 13+ `LocaleManager.applicationLocales` as the source of truth after a successful sync. This prevents an old private preference from overriding a language changed from Android's App language settings.
- Keep a private preference fallback for Android 12 and for cases where `LocaleManager` cannot be accessed. The first launch still defaults to Indonesian.
- Recreate the Activity when the framework locale did not actually change, including the stale-wrapper/empty-framework edge case.
- Let Android handle locale configuration changes instead of declaring `locale|layoutDirection` in the `MainActivity` manifest entry.
- Add regression tests for Indonesian resources, framework/app preference precedence, and choosing Indonesian while dismissing the language sheet.

## Validation performed

- Parsed all 116 resource XML files successfully.
- Verified every translatable default string has a corresponding `values-id` resource; technical labels explicitly marked `translatable="false"` are intentionally unchanged.
- Verified the supported locale list still matches the app's `AppLanguage` declarations.
- Verified source invariants for Activity-scope selection and framework locale resolution.

The Gradle test task could not reach compilation in this environment because the Gradle 9.8.1 distribution is not cached and `services.gradle.org` could not be resolved. Run `./gradlew :app:testStandardCanaryUnitTest -PlicenseServerUrl=https://<your-worker-origin>` in an environment with the configured Gradle distribution and dependencies available before releasing an APK.
