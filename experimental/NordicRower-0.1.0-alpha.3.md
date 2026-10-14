# NordicRower 0.1.0-alpha.3

Experimental trial build. Both capability discovery and streaming now permit
Wolf/iFIT to remain enabled. Keep NordicRower in the foreground during the test.
Separate app `com.nordicrower.app`, Bluetooth name **NordicRower**.

## What the October 10 diagnostics established

All 15 recorded connection attempts and four discovery attempts failed at
alpha.2's enabled-Wolf guard. `wolf-enabled.txt` confirmed
`package:com.ifit.standalone`. No controller opening, handshake, or NordicRower
Bluetooth registration was attempted. The partial discovery report stopped at
step 1, and the final status had zero BLE clients and notifications.

Android detected the ICON FitPro V1 HID controller (`213c:0002`), and the USB
permission list included NordicRower's UID (`10067`). Bluetooth was on, with no
GATT server registered. No crash was recorded. These reports identify the
software gate as the immediate failure; they do not establish actual USB
ownership, controller telemetry support, or third-party app compatibility.

## Changes

- Removed the enabled-Wolf gate from both connection and discovery. Enabled,
  disabled, and absent Wolf states all permit a controller access attempt.
- Added a French package-state display and hardware refresh button. Confirmation
  dialogs allow Wolf to remain enabled and explain the foreground trial.
- Logs include package state and USB inventory before opening the controller.
- After an exhausted USB claim, a French recovery dialog opens with numbered
  steps and a button to Wolf's app settings. Users choose Force stop manually,
  return to NordicRower and retry the same connection or discovery. Instructions
  remain visible and survive app restarts. Permission, handshake and BLE errors
  do not trigger this recovery.
- Collector v2 adds enabled/disabled Wolf lists, package details and services.
  Package state, services, and USB permission do not establish interface ownership.
- Preserved the GlassOS/Mithlond exclusion, rower protocol validation, explicit
  session confirmation, and normal USB claims without forcing access.

An actual occupied interface can still cause a claim failure. Android's USB
interface claim is exclusive; foreground activity alone does not prove access
is available. See [Android USB interface access documentation](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#claimInterface(android.hardware.usb.UsbInterface,%20boolean)).

BLE remains sensor-only: no Control Point or advertised target features.
Controller initialization, simultaneous Wolf operation, real USB measurements,
and EXR/RowerTrain compatibility remain unverified on the customer's rower.

## Customer workflow

See the [French installation, test, collection and return instructions](NordicRower-0.1.0-alpha.3-fr.md).

Upgrade with `adb install -r NordicRower-0.1.0-alpha.3.apk`, preserving app data
and journals. The signing certificate matches the alpha.2 package. Leave Wolf
enabled; no Wolf stop, disable, or uninstall command is required. If Wolf was
disabled during an earlier trial, enable it before testing coexistence.
If USB claiming fails, follow the in-app Force stop instructions, return to
NordicRower and tap Retry. Opening settings does not stop Wolf automatically,
and stopping Wolf does not disable or uninstall it. If Force stop is unavailable
or access still fails, collect diagnostics for support.

While idle, run capability discovery first. Then connect the real session, keep
NordicRower in the foreground, and test one Bluetooth app at a time on a separate
device. Record whether local watts, cadence and strokes appear before checking
the receiving app. Disconnect at the end and collect logs, including when USB
access fails. The read-only collector neither changes applications nor uploads
reports. The debug APK supports `run-as` collection.

## Verification for this build

- 50 unit tests passed, including Wolf/GlassOS policy and specific USB claim
  recovery, cause preservation, unrelated failures, cancellation and retry action.
- Android lint: zero errors, 15 warnings.
- Debug APK built successfully; signature, package, version name and code verified.
- Collector changes reviewed as read-only commands. PowerShell execution was
  not repeated: the available temporary runtime lacks `pwsh.dll`. Native Windows
  PowerShell 5.1 execution remains untested.
- Recovery UI instrumentation passed on a dedicated Android 15 tablet emulator:
  readable guidance, exact Wolf settings intent, retained instructions, discovery
  retry confirmation and activity recreation. The settings launch was intercepted
  by the test; no Wolf process was stopped. The dialog screenshot was inspected.
- A saved recovery was restored and its instructions verified after a full
  NordicRower process restart on the emulator.
- Existing radio tests were not rerun for this build.
- No customer's hardware, Wolf package state, controller targets or firmware
  were changed. No physical rower or simultaneous Wolf trial was performed.

The ZIP includes only the application APK, English and French guides, the
collector, Hyperborea MIT license and SHA-256 checksums. It contains no private
customer diagnostics, vendor source, iFIT APKs, or instrumentation test APK.
