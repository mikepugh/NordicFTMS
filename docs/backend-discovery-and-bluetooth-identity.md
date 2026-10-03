# Backend discovery and Bluetooth identity

## Backend startup diagnosis

Creating a gRPC channel does not establish a working connection to GlassOS.
NordicFTMS waits for usable console information from `GetConsole`,
`GetKnownConsoleInfo`, or the `ConsoleChanged` stream before exposing FTMS.
It continues reconnecting with the existing backoff if discovery fails.

The earlier `grpc_backend_not_ready ... via GetKnownConsoleInfo` warning only
named the last attempted method. It did not identify the cause. In particular,
an optional method returning `UNIMPLEMENTED` could hide a connection failure
from `GetConsole`. The status screen now includes the latest outcome for each
method and the underlying transport cause. The exception sent to Sentry also
retains the original failures as suppressed exceptions. An empty successful
response is distinguished from an RPC failure.

Methods returning `UNIMPLEMENTED` are skipped for the rest of that connection
attempt, then reconsidered on the next connection. `ConsoleChanged` is
resubscribed after transient startup errors or normal completion, including
before the backend reaches ready state. Retired connection generations no
longer apply console info or metric updates to the new connection.

Retry Backend reconnects NordicFTMS's client; it does **not** restart GlassOS or
iFit. The reconnect is queued on the startup executor so the button does not
block the UI behind an ongoing discovery attempt. The ongoing RPC deadlines
can still delay the retry. Bluetooth restart also cannot repair a stopped
GlassOS service, certificate rejection, or incompatible RPC interface.

For a console that remains unavailable, collect the new full discovery warning,
the app version, console model, Android/iFit versions, and whether iFit itself
can read/control the equipment. Detailed diagnostic logging adds breadcrumbs
to subsequent Sentry reports; the high-frequency `NordicFTMS-Trace` output
remains local logcat data. The reported warning alone is insufficient to
determine the affected user's root cause.

## Stable identity protocol (version 1)

Apple assigns `CBPeripheral.identifier`; Android manages the advertiser's BLE
address. Changing a GATT service UUID or deriving a number from `Build.SERIAL`
does not set either of those values. NordicFTMS's previous hardware-derived
MAC/serial code belongs to DIRCON, not BLE.

NordicFTMS now exposes an application identity that clients can use when the
OS Bluetooth identity changes:

| Item | Value |
|---|---|
| Identity service UUID | `2d3f96c1-45e8-4ebd-aad2-30d98c02d2b3` |
| Read-only identity characteristic | `d219c59d-5319-42bd-b65d-1a97bd15df83` |
| Identity value | Exactly 12 opaque bytes, generated once with `SecureRandom` |
| Android 8+ scan response | Service Data under the identity service UUID, containing the same 12 bytes |
| Android 5–7 | Read the identity characteristic after connecting; these Android versions truncate service-data UUIDs to 16 bits |

The fixed UUIDs define this protocol, not one shared device ID. The 96 random
bits distinguish installations. Among one million installations the approximate
probability of any accidental identity collision is `6.3 × 10^-18`.

The identity is atomically saved and synced before being used, in Android's
`noBackupFilesDir`. It survives service restarts, reboots, and in-place APK
updates. Uninstalling, clearing app data, or resetting the console creates a
new identity. Backups cannot clone it onto another console. Storage errors
prevent advertising an unpersisted identity; corrupt identity data is reported
instead of silently publishing a different device.

The primary advertisement still includes the standard FTMS UUID and the
`NordicFTMS` name. The Android 8+ scan response uses 30 of its 31 available bytes
(length/type: 2, service UUID: 16, identity: 12). It omits TX power to stay within
the legacy packet limit. Existing FTMS clients can ignore the identity service.
DIRCON's existing identifiers are unchanged.

## Required PowerTread integration

NordicFTMS alone cannot fix a client that only matches `CBPeripheral.identifier`.
PowerTread's current `BluetoothSensorManager.attemptAutoReconnect` does that.
The following companion change is required before claiming auto-connect fixed:

1. Parse the identity service's entry in `CBAdvertisementDataServiceDataKey`.
   Accept exactly 12 bytes. Keep this separate from the current Core Bluetooth
   UUID used for connection operations and peripheral dictionaries.
2. Save an optional NordicFTMS identity with each user-selected sensor
   preference. Preserve existing preferences and their normal UUID matching.
   If no advertisement identity is available, discover the identity service
   and read the characteristic once connected. Treat the bytes as opaque,
   for example storing them as 24 hexadecimal digits.
3. While scanning for a saved NordicFTMS identity, match the advertised identity
   and connect to the newly discovered `CBPeripheral`. Update the saved/current
   transport UUID and any synthetic `-FTMS` speed/distance association together.
   Do not replace internal Core Bluetooth dictionary keys with the application ID.
4. For Android 5–7, identity matching requires a GATT discovery/read on candidate
   NordicFTMS devices before selecting one. Do not send workout/control commands
   before the identity matches. Do not auto-select solely by device name.
5. An older saved preference with no application identity can acquire one when
   its existing transport UUID still matches. Otherwise the user must select
   the console once after upgrading both apps. Two nearby consoles sharing
   the name `NordicFTMS` must never be guessed interchangeable.

Acceptance checks on real hardware: manually pair once; restart the NordicFTMS
service, restart Bluetooth, reboot the console, and update the APK in place;
verify the application identity stays constant and PowerTread reconnects even
when the Core Bluetooth UUID changes. Repeat with two nearby consoles and
confirm that selection and synthetic FTMS speed/distance follow the saved
console. Verify an uninstall/data clear creates a new identity and requires
selection again. JVM tests cannot validate Android advertising or iOS scanning.

References: [Apple's peer identifier documentation](https://developer.apple.com/documentation/corebluetooth/cbpeer/identifier),
[Android advertiser permissions](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser),
[Android 9 advertisement encoding](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/android-9.0.0_r1/src/com/android/bluetooth/gatt/AdvertiseHelper.java),
[Android 7 advertisement encoding](https://android.googlesource.com/platform/packages/apps/Bluetooth/+/android-7.1.2_r1/src/com/android/bluetooth/gatt/AdvertiseManager.java).
