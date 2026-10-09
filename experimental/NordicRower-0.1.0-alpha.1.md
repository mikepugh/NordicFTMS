# NordicRower 0.1.0-alpha.1

Controlled experimental hardware trial, **not a confirmed EXR/RowerTrain-compatible release**.

Package: `com.nordicrower.app`. Bluetooth name: **NordicRower**, regardless of
the physical rower's model. Separate from NordicFTMS; no GlassOS dependency.

The ZIP contains the debug-signed APK, this guide, French instructions, the
Hyperborea MIT license, and APK SHA-256 checksum. It contains no vendor source,
research APKs, credentials, or instrumentation test APK.

APK SHA-256:

```text
e30b0782bcb5a117e2374b7d66cdea31f01f7cb741b37bea03490288b19f8fa4
```

## What This Build Does

- Talks directly to a FitPro USB controller; Wolf must release ownership first.
- Validates rower identity and declared power/stroke capabilities before workout initialization.
- Advertises a read-only FTMS rower service (`1826`, Rower Data `2AD1`).
- Broadcasts actual watts, stroke rate, and stroke count when all three are available.
- Does not infer strokes from RPM, estimate watts, scrape Wolf logs, or call Wolf IPC.
- Does not expose a BLE Control Point or remote resistance/workout commands.
- Never disables/uninstalls iFIT automatically, flashes firmware, or forcibly claims USB.
- Offers local diagnostic export; no network permission or automatic uploads.

Apps requiring an FTMS Control Point may reject this sensor-only build. We have
not established whether EXR or RowerTrain accepts it. Seeing a BLE advertisement
is not proof that hardware data or an app's handshake works.

## Verification On October 9, 2026

- Debug build, 18 unit tests and Android lint passed (zero errors, seven warnings).
- Real two-device BLE test: treadmill peripheral, Samsung tablet receiver.
  Discovery, connection, feature read, subscription, and decoding of the complete
  seven-byte Rower Data measurement passed: synthetic 123 W / 24 strokes/min.
- Normal service rejected a GlassOS console before USB access and preserved the error.
- Fresh dependency setup reproduced the tested patched source from the pinned revision.

Synthetic values existed only in the separate instrumentation test APK, which
is not distributed. No direct-controller session was attempted on the treadmill.
Real rower telemetry, units, physical controls, recovery, and rowing-app
compatibility remain untested. The tablet was locked during the app-specific
test window, so EXR and RowerTrain pairing screens were not exercised.

## Prepare Before Changing Anything

Use a supported legacy Wolf console, not a GlassOS treadmill/bike. Keep the
previously collected Wolf APK backup. Confirm that ADB still works and preserve
these read-only observations from the same Windows folder as `adb.exe`:

```powershell
.\adb.exe devices
.\adb.exe shell pm path com.ifit.standalone
.\adb.exe shell pm list packages -e com.ifit.standalone
.\adb.exe shell dumpsys usb > NordicRower-usb-before.txt
```

Stop here if Wolf is absent, the console is not the intended rower, or ADB is
unreliable. The steps below assume Wolf was enabled initially. Record a different
initial state rather than blindly changing unrelated packages. If several ADB
devices are listed, add `-s YOUR_DEVICE_SERIAL` to every command.

## Install And Try

These commands change package state deliberately. Do not uninstall Wolf, disable
other iFIT services, change the launcher, or use the rower during installation.
Extract the ZIP's APK beside `adb.exe`, then:

```powershell
.\adb.exe shell am force-stop com.nordicftms.app
.\adb.exe install -r NordicRower-0.1.0-alpha.1.apk
.\adb.exe shell pm disable-user --user 0 com.ifit.standalone
.\adb.exe shell am start -n com.nordicrower.app/.MainActivity
```

1. Tap **Connect Rower**, grant USB access if requested, and confirm the warning.
2. Check that real power, stroke rate, and count appear while rowing briefly.
   A controller error means the bridge is not ready; do not keep repeating takeover attempts.
3. If real data appears, look for **NordicRower** inside one rowing app at a time.
   Report whether it is visible, connects, and shows matching live watts/strokes.
4. Tap **Save Diagnostics** and share the file plus `NordicRower-usb-before.txt`.
   Capture this log before restarting or uninstalling, especially after an error:

```powershell
.\adb.exe logcat -d -v threadtime NordicRower:I AndroidRuntime:E '*:S' > NordicRower-logcat.txt
```

Stop the trial if physical controls behave unexpectedly. There is no validated
resistance-control workflow in this alpha; it is intended to test telemetry.

## Restore Wolf/iFIT

Tap **Disconnect** in NordicRower and wait for USB release. Then:

```powershell
.\adb.exe shell am force-stop com.nordicrower.app
.\adb.exe shell pm enable --user 0 com.ifit.standalone
.\adb.exe shell monkey -p com.ifit.standalone -c android.intent.category.LAUNCHER 1
```

Verify normal iFIT operation before using the equipment. If the controller does
not reconnect, power-cycle the console after Wolf is re-enabled. Do not flash
firmware or factory-reset it. NordicRower can be removed independently with
`adb uninstall com.nordicrower.app`; no iFIT package was uninstalled.

## Source And Dependencies

Source: `nordicrower/`. Hyperborea is pinned to
`b7b10f33c2890f1bbbd5caace6c8a652b4a58961` (MIT); selected transport/session
components are compiled into the APK and their license is included. Vendor and
reference source folders are gitignored; only our setup script and patch are tracked.
