<!-- SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary -->
# Play Protect warning and safe distribution

The screenshot's **“App scan recommended”** dialog means Play Protect has not
seen this APK before and asks the user to send it for scanning. This is not, by
itself, a malware verdict. Google documents this exact dialog and recommends
following Android security best practices and reviewing whether APIs and
permissions are used for their intended purposes:

- https://developers.google.com/android/play-protect/warning-dev-guidance?hl=en
- https://support.google.com/googleplay/answer/2812853?hl=en

## Changes made in this repository

- Publish only the stable `com.zairenkai.app` Release identity and the separate,
  stable `com.zairenkai.app.canary` identity; randomized package IDs are removed.
- Sign both distribution channels with the configured application signing key.
  CI verifies the APK's certificate fingerprint and v1/v2/v3 signature schemes.
- Publish signed update manifests, APK SHA-256 checksums, and GitHub build
  attestations. The in-app updater verifies the manifest signature and downloaded
  APK hash before opening Android's package installer.
- Route update-manifest retrieval through the configured HTTPS License Server
  Worker, while keeping the APK itself on the official GitHub Releases page.

These checks strengthen provenance and update integrity, but they cannot force
Play Protect to recognize a new or privately distributed APK. Do not disable,
patch, or evade Play Protect to hide the prompt. For a new installation, choose
**“Pindai aplikasi”** and let Android check the APK. If you want to reduce the
unknown-app prompt for a broader audience, publish through an established trusted
store (such as Google Play) and follow Google's developer guidance. Keep the same
application ID and signing identity for subsequent updates; Android uses them to
recognize an in-place update.

## Before distributing a build

1. Configure the repository variable `ZKFC_LICENSE_SERVER_URL` to the HTTPS
   origin of the deployed production Worker (no path or trailing slash).
2. Configure the signing secrets and required reviewers for the
   `canary-signing` and `production-signing` environments.
3. Verify the published APK's SHA-256 and signer certificate before sharing it.
4. Prefer Release for normal use; Canary is for early testing and may contain
   unfinished features.
