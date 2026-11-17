# NordicRower

Independent experimental Android app: `com.nordicrower.app`, version
`0.1.0-alpha.4`. It does not modify, extend, or depend on NordicFTMS/GlassOS.

## Current Prototype

- Direct FitPro USB controller access, using pinned Hyperborea components.
- FitPro1 and FitPro2 transport/handshake paths with rower-only validation before
  workout initialization. Controllers must declare watts and actual stroke data.
- Compact V1 rower polling: at most 13 payload bytes for declared rowing/session
  fields, checked framing/checksums and strict rejection of incomplete telemetry.
- FTMS service `1826`, Rower Data `2AD1`, power feature, Training Status and
  Machine Status characteristics, correct rower advertising type.
- Complete measurements from real watts, stroke rate, and stroke count. No RPM
  substitution, synthetic production measurements, log scraping, or Wolf IPC.
- Local explicit session start and USB permission request; no boot autostart.
- Read-only measurements: no BLE Control Point, remote resistance, target power,
  start/stop, or reset commands. No unsupported controls are advertised.
- French console UI, automatic bounded persistent logs across app restarts,
  serial/address redaction, full exceptions and USB/BLE/telemetry phase evidence.
- Explicit query-only capability discovery, separate from workout sessions:
  full declared field/feature inventory, identity, resistance limits and
  target/mode readbacks. No actuator writes, authentication unlock, calibration,
  workout start/stop or firmware commands in this probe path.
- Windows read-only ADB collector in `experimental/`, included with the APK.
  No network permission or automatic uploads.

**A controlled experimental trial, not a supported Rower700 solution.** Controller behavior,
physical button handling, telemetry units/update cadence, disconnect recovery,
and EXR/RowerTrain compatibility still need end-to-end validation. Apps that
require an FTMS Control Point cannot use this sensor-only build. Initialization
uses the upstream rower workout-state sequence, which has not been tested on
this customer's hardware. Resistance controls are deliberately not exposed.

## Build

```bash
cd nordicrower
bash setup-vendor.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Set `ANDROID_HOME` to an installed Android SDK. Requires JDK 17 or newer.
The private APK is at `app/build/outputs/apk/debug/app-debug.apk`.

All `vendor/` and `references/` folders are gitignored. Only our source, build
files, setup script, pinned revision description, and small upstream patch
belong in Git. See [dependency notes](docs/hyperborea-dependency.md).

## Hardware Ownership

Alpha.4 permits both discovery and streaming while Wolf/iFIT remains enabled.
Keep NordicRower in the foreground during the trial. Wolf package state is
logged for diagnostics; it is not proof of USB ownership. NordicRower uses
a normal USB interface claim first. On HID interfaces only, if sysfs identifies
the binding as `usbhid`, it can retry with kernel-driver detachment. `usbfs`,
other bindings and unknown/inaccessible ownership never authorize detachment.
The driver probe is best effort and is not an atomic ownership guarantee;
concurrent clients can still interfere. Connection failures remain logged.
Concurrent operation and controller behavior remain unverified on the rower.
An exhausted USB claim opens a French recovery guide with a button to Wolf's
Android app settings. The user can choose Force stop there, return and retry
the failed connection or discovery. Instructions remain visible and are
restored after app restarts until another attempt or an explicit disconnect.

The app still rejects a GlassOS/Mithlond console. It never disables or uninstalls
iFIT itself, requests root, alters launcher settings, or flashes firmware.
Connecting requires a visible user confirmation and USB access. Preserve Wolf's
installed APK before a customer trial and disconnect NordicRower at the end.
No package disable/enable commands are required for the default alpha.4 trial.
No takeover commands have been executed on the local treadmill or customer's
equipment.

## App Handshake Research

The earlier EXR capture established that Bluetooth connection and GATT discovery
can succeed but the app then fails to find a valid `RowerCommunicator`. Its APK
has explicit FTMS rower parsing/subscription code. RowerTrain's Flutter AOT
symbols likewise reference FTMS rower `2AD1`; its complete application logic has
not been reconstructed. Neither app is proven compatible with this prototype.

A synthetic fixture exists only in the separate instrumentation test APK, not
in the distributed app. It is labeled `NordicRower Test`, supplies known values
of 123 W and 24 strokes/min, and never opens USB. Two-device radio tests verify
advertising, connection, discovery, subscriptions and measurement bytes. They
do not validate real controller data or either rowing app's full handshake.

## Capability Evidence

Disconnect the streaming session before starting discovery; the probe must not
compete for the USB interface. It asks only for declared, known readback fields.
V1 reads may be blocked by security; this is logged without unlocking. The
observed firmware 83/hardware 1 rower omits core opcode 02 from its optional
command list despite using it; discovery permits declared readbacks for that
confirmed profile. Other omitted commands remain gated. V2 lists
must complete before subscriptions are attempted, and the rower type must be
confirmed before reading targets/limits. Missing or malformed data is UNKNOWN.
Declared resistance/ERG fields are candidates, not proof of writable features
or working native ERG. Actual command acceptance requires a later controlled
hardware test; this build never advertises remote control.

The debug APK saves `files/diagnostics/{discovery,status,journal}.txt` plus two
rotated journals (~3 MiB total). The collector reads only these app files via
`adb shell run-as com.nordicrower.app cat ...`, recent logcat and relevant dumps.
It does not start/stop apps, clear logs, connect to new devices or change settings.
Run-as relies on the experimental APK being debuggable; other build types may
deny it. Collect before uninstalling or clearing app data. Reports are private
support material and system dumps may contain incidental identifiers.

See [October 27 QZ findings](docs/qz-oct27-findings.md) for the evidence,
remaining uncertainties and alpha.4 changes.

See [experimental installation notes](../experimental/NordicRower-0.1.0-alpha.4.md)
for the test package, reversible setup, and validation limits.
