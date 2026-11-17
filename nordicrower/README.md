# NordicRower

**Bluetooth rowing measurements for compatible NordicTrack/ProForm Wolf/iFIT USB consoles.**

NordicRower is an independent Android app (`com.nordicrower.app`, version
**1.0.0**). It reads real power, stroke rate and stroke count directly from a
FitPro USB controller and offers those measurements as a Bluetooth FTMS rower
named **NordicRower**. Keep the app in the foreground during workouts.

[Installation and use](docs/install.md) · [Guide en français](docs/install-fr.md) ·
[Release notes](docs/release-notes.md) · [GitHub releases](https://github.com/mikepugh/NordicFTMS/releases)

## Choose the right app

| App | Console | Data source |
| --- | --- | --- |
| NordicRower | Compatible Wolf/iFIT USB rowers | Direct FitPro USB |
| NordicFTMS | Compatible GlassOS equipment | GlassOS gRPC |

NordicRower rejects GlassOS consoles and non-rower equipment. NordicFTMS is a
separate package with its own release workflow; neither app depends on the other.
The NordicTrack S15i is a bike and is outside NordicRower's supported scope.

## What is confirmed

Customer screenshots from November 17 running alpha.4 show real, changing power, stroke
rate and stroke count on his rower: 188 W / 27 strokes per minute / 9 strokes,
and 97 W / 23 / 4. They show USB VID 213c/PID 0002 with access granted while
Wolf/iFIT is enabled. This confirms local USB telemetry, not which claim path
succeeded or interference-free concurrent operation.

Those console screenshots show zero Bluetooth clients and zero notifications.
A separate customer screenshot shows **NordicRower** listed in **RowerTrain**
with the status **Connecté**. This confirms discovery and an app-reported
Bluetooth connection in that setup. The RowerTrain screen does not show workout
measurements, so live power/stroke reception and recording still need validation.
EXR and other receiving apps remain unverified.
Long sessions, reconnect behavior and other console/firmware variants are not
fully validated. This initial public release has a deliberately narrow scope.

## Features and limits

- FitPro V1/V2 paths with rower-only validation and actual stroke fields.
- Compact V1 polling with strict framing, checksum and completeness checks.
- Read-only BLE FTMS 1826 / Rower Data 2AD1 with real power and stroke data.
  No RPM substitution or synthetic measurements in the application.
- Explicit connection and USB permission; no boot autostart or network permission.
- Separate states for receiving data, waiting for a Bluetooth client and waiting
  for that client's measurement subscription. Stale values disappear after five seconds.
- French console UI, manual Wolf Force stop guidance only when USB claiming fails,
  and persistent bounded/redacted diagnostic journals.
- Query-only capability discovery, plus uniquely named reports with Save, Share
  and ADB fallback. No automatic uploads or messages.

There is no FTMS Control Point, remote resistance, ERG, target power, start/stop
or reset support over BLE. Speed, distance and pace are not transmitted.
Apps requiring a Control Point cannot use this release. Capability declarations
alone do not prove write acceptance; discovery sends no actuator/workout writes.
Connecting does initialize the controller's workout state using the upstream
rower sequence. Disconnect when finished.

Close QZ Fitness and its Companion before connecting. Wolf/iFIT may remain
enabled initially. Enabled state is not proof of USB ownership. NordicRower
tries a normal USB claim first, and permits kernel detachment only if a HID
interface's sysfs binding identifies usbhid. Unknown/inaccessible bindings and
usbfs never authorize detachment. That check is best effort, not atomic with
the claim; concurrent clients can interfere. On exhausted claims, open iFIT's
Android settings, choose Force stop manually, return and retry. NordicRower
never disables, uninstalls or force-stops another app itself.

## Build and verify

Requires JDK 17 and Android SDK 35 (`ANDROID_HOME`):

```sh
cd nordicrower
bash setup-vendor.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The development APK is `app/build/outputs/apk/debug/app-debug.apk`. Vendor and
reference source trees are ignored; setup fetches a pinned Hyperborea revision
and applies the tracked patch. See [dependency notes](docs/hyperborea-dependency.md)
and [QZ research](docs/qz-oct27-findings.md). No QZ source is bundled.

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
This snapshot refreshes only on an export request. The Windows collector v4
includes that copy and additional read-only system evidence; errors for private
run-as reads are expected on release builds. See the installation guides.

Synthetic BLE fixtures exist only in the separate instrumentation test APK,
never in the shipped APK. Two-device radio tests and emulator UI checks do not
establish real receiving-app compatibility.
