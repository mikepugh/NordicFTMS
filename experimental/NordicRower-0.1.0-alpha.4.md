# NordicRower 0.1.0-alpha.4

Experimental USB rower compatibility build, based on the October 27 QZ evidence.
Package `com.nordicrower.app`; Bluetooth name **NordicRower**. NordicFTMS is unchanged.

## Findings

QZ's successful trial opened FitPro V1 USB, recognized rower type 20 with
firmware 83/hardware 1, completed authentication and workout initialization, and
reported power up to 187 W and stroke rate up to 35/min. The matching public
[QZ FitPro implementation](https://github.com/cagnulein/qdomyos-zwift/blob/0ce140b6aa366bcf994c6b82f9d8a1a315e41a99/src/android/src/FitProDeviceService.kt)
ignores its host/IP argument and uses direct USB, despite retaining gRPC getter
names. No IP configuration or Companion is needed by NordicRower.

QZ also warned that its large positional response did not match the requested
fields, then continued publishing the partial decode. Alpha.3 rejects such data.
The attached FIT backups contain nonzero power/cadence but zero speed/distance;
both SQLite databases have no workout rows. These attachments do not establish
complete controller decoding or successful third-party Bluetooth reception.

The supplied files are QZ diagnostics; a fresh NordicRower report is still
needed to establish its latest failure phase.

## Changes

- **USB claim compatibility:** normal claim first, then a kernel-driver detach
  retry only for a HID interface whose sysfs binding is identified as `usbhid`.
  Unknown/inaccessible ownership, `usbfs` and other drivers do not authorize
  detachment. The probe is best effort and is not atomic with claiming; actual
  coexistence with Wolf or QZ remains unverified. Exhausted claims retain the
  French manual Wolf Force stop guide. No app is stopped automatically.
- **Compact V1 polling:** declared watts, real strokes and stroke rate, plus
  declared resistance, distance, workout mode and Start-request state. On the
  observed controller this reduces the reply from 64 to 13 payload bytes
  (69 to 18 bytes including framing), avoiding unrelated composite/console data.
- **Validated replies:** V1 framing, length, fragment sequence and checksum
  validation before decoding. Incomplete measurements remain rejected. Invalid
  startup responses cannot establish limits; irrelevant speed/incline limits
  are no longer requested.
- **Firmware-specific discovery:** allow declared query-only fields for the
  observed rower firmware 83/hardware 1, which omits base read opcode 02 from its
  optional command list. Other omitted commands and unavailable command lists
  remain gated. Discovery never authenticates or changes workout/actuator state.
- **Collector v3:** adds USB HID/userspace bindings and QZ package/service state.
  The collector remains read-only and uploads nothing.

Read-only BLE measurements remain unchanged: no Control Point, remote
resistance, ERG, target power or start/stop/reset commands.

## Customer trial

Follow the [French guide](NordicRower-0.1.0-alpha.4-fr.md). While the rower is
idle, Force stop QZ Fitness and its Companion if installed, so they cannot
compete for USB or BLE. Upgrade with `adb install -r NordicRower-0.1.0-alpha.4.apk`,
preserving NordicRower's journals. The signing certificate matches alpha.3.
Leave Wolf enabled initially.

Run capability discovery first, then connect and row briefly with NordicRower
in the foreground. Confirm local watts, stroke rate and cumulative strokes
before testing one receiving app on a separate device. Use the Wolf settings
recovery only if claiming still fails. If old recovery instructions appear after
the update, choose Retry to attempt the new build. Collect fresh NordicRower
diagnostics even if access or measurements fail.

## Verification

- 69 unit tests passed, including a complete V1 handshake-to-FTMS test with the
  observed declaration/command lists and a simulated 64-byte USB reply limit.
- Regression tests cover claim ordering and ownership filtering, sysfs matching,
  compact rower reads, firmware-specific discovery, checksum/length/fragment
  failures, incomplete telemetry, real stroke mapping and existing recovery.
- Android lint: zero errors, 15 warnings. Debug APK built and signature verified.
- A fresh checkout of pinned Hyperborea plus `setup-vendor.sh` and the tracked
  patch reproduces every dependency source used in the APK.
- Collector changes reviewed as read-only. PowerShell and Android UI/radio
  instrumentation were not rerun for alpha.4; the manual recovery UI is unchanged.
- No physical rower test, new resistance/workout-control feature or
  EXR/RowerTrain end-to-end validation was performed. The logs contain no raw USB
  reply packets; test packets are simulations, not a hardware-capture replay.

The ZIP contains the application APK, these English/French instructions,
collector v3, Hyperborea MIT license and SHA-256 checksums. No customer logs,
photos, databases, QZ code, test APKs or vendor source are included.

The full source-level analysis is in `nordicrower/docs/qz-oct27-findings.md` in
the development repository.
