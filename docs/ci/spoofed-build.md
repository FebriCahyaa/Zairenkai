# Spoofed package build

Zairenkai's `spoofed` product flavor uses a randomized Android application ID
for CI builds and publishes a separate APK alongside the normal build.

The Kotlin/Android namespace (`com.zairenkai.app`) remains stable. Changing
`applicationId` changes the installed package identity without rewriting Kotlin
packages, generated `R`/`BuildConfig` references, or manifest component names.

## Local build

```bash
SPOOFED_APP_ID="$(python3 tools/ci/generate-spoofed-app-id.py)"
./gradlew :app:assembleSpoofedDebug "-PspoofedAppId=$SPOOFED_APP_ID"
```

For a signed release, configure the existing `ZK_KEYSTORE_*` environment
variables or `keystore.properties`, then run:

```bash
SPOOFED_APP_ID="$(python3 tools/ci/generate-spoofed-app-id.py)"
./gradlew :app:assembleSpoofedRelease "-PspoofedAppId=$SPOOFED_APP_ID"
```

Each CI build job generates a new application ID. A spoofed APK can be installed
alongside the standard app, but the next spoofed build has a different package
ID and will not update the previous spoofed installation in place. Install
spoofed APKs manually; use the standard build for normal in-app updates.
