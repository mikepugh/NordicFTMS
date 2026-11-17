# NordicRower development

Build, dependency and release-maintenance information for contributors.
This document is not included in release packages.

## Build and verify

Requires JDK 17 and Android SDK 35 (`ANDROID_HOME`):

```sh
cd nordicrower
bash setup-vendor.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. Vendor and
reference source trees are ignored; setup fetches a pinned Hyperborea revision
and applies the tracked patch. See [dependency notes](hyperborea-dependency.md)
and [protocol research](qz-oct27-findings.md).

## Independent releases

Use tags **nordicrower-v1.0.0**, **nordicrower-v1.0.1**, etc. They trigger only
`.github/workflows/nordicrower-release.yml`; NordicFTMS keeps its existing `v*`
tags. NordicRower's VERSION_NAME property accepts major.minor.patch, and Gradle
derives a monotonically increasing Android version code (1.0.0 is 1000009, above
alpha.4's 4). Future components are bounded: minor <=999, patch <=99.

To build signed assets in CI, configure repository secrets NORDICROWER_KEYSTORE_BASE64,
NORDICROWER_KEYSTORE_PASSWORD, NORDICROWER_KEY_ALIAS and NORDICROWER_KEY_PASSWORD
from the existing alpha signing keystore. Keep that key private and backed up.
The packaging script checks its public certificate fingerprint against alpha.4
and refuses an incompatible signer. No temporary CI-generated signing identity
is accepted. The first release retains the existing alpha identity; changing
keys would break install-over updates.

For a local signed release, set NORDICROWER_KEYSTORE_PATH and the three signing
credential environment variables, then run:

```sh
./gradlew -PVERSION_NAME=1.0.0 :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
python3 package-release.py --version 1.0.0
```

The script validates signature, package, version and non-debuggable status,
then writes the APK, curated ZIP and SHA256SUMS.txt to `build/release/`.
With signing secrets configured, the workflow creates a **draft** GitHub release
for review before publication. Without them, it runs tests and lint only; the
maintainer can publish locally signed and verified assets with the GitHub CLI.
Publish NordicRower with `--latest=false` to preserve NordicFTMS's existing
repository-wide latest-release designation; use NordicRower's own tag links.
It needs no GlassOS certificates, NordicFTMS secrets or NordicFTMS build.

Public release builds disable Android debugging. Save/Share generates the
complete app report without run-as; Save also creates an ADB-readable copy at
`/sdcard/Android/data/com.nordicrower.app/files/diagnostics/latest-report.txt`.
This snapshot refreshes only on an export request. The Windows collector v5
includes that copy and additional read-only system evidence; errors for private
run-as reads are expected on release builds. See the installation guides.

Synthetic BLE fixtures exist only in the separate instrumentation test APK,
never in the shipped APK. Two-device radio tests and emulator UI checks do not
establish real receiving-app compatibility.
