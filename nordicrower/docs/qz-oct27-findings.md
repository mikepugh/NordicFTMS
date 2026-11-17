# October 27 QZ rower evidence and alpha.4 changes

Scope: NordicRower only. NordicFTMS's Android app, GlassOS client, protos and
release configuration are unchanged.

## What the attachments establish

The second trial log has both ProForm rower options disabled and no FitPro
session. In the third trial (`15_52_25`), QZ requested USB permission and logged
that it was denied; all 480 samples of every queried metric remained zero.
The earlier fourth-trial log (`15_58_15`) opened USB, identified a rower and
started a V1 session, but the queried measurements stayed zero.

The final fourth-trial log (`15_59_44`) provides the useful controller evidence:

- Line 1184: USB transport opened.
- Lines 1202–1234: equipment type 20 (rower), firmware 83, hardware 1,
  model 15121, part number 427244, master-library build 470. The controller
  declared actual watts (3), cumulative strokes (109) and stroke rate (110).
- Lines 1251–1267: authentication succeeded and the controller completed the
  IDLE → WARM_UP → RUNNING sequence. The workout-state sequence already used
  by NordicRower is therefore supported by this QZ trace.
- Line 1276: a 23-field response-layout warning. Under the pinned field widths,
  those declarations request **64 payload bytes / 69 framed bytes**. The decoder
  stopped after the stroke-rate field and did not decode the final three fields.
  The logs do not include the raw USB reply, so the exact wire-level reason for
  the mismatch remains unproven.
- Across 1,274 queried snapshots: reported power 0–187 W, stroke rate 0–35/min,
  stroke count 0–35 and distance 0–0.276 km. Reported speed, pace and resistance
  stayed zero. Representative lines 7623–7625 report 155 W and 35 strokes;
  the photo shows 155 W and 33 strokes/min.
- During pause, reported watts/stroke rate/distance reset to zero while stroke
  count remained 31; later it increased to 35. Do not infer physical distance
  resets or complete workout support from QZ's cached values alone.

The FIT exports repeat QZ's power/cadence results: the third-trial export has
60 all-zero records; the fourth-trial backups have 160 and 220 records, with
peaks of 187 W and 35/min. All recorded FIT speed and distance values are zero,
despite QZ's separate distance getter advancing. They corroborate QZ's exported
values, not the correctness of raw controller decoding. Both SQLite attachments
contain the workout schema but zero workout rows, so they add no telemetry.

QZ also logged a BLE advertising failure and local virtual-rower characteristic
updates. These do not establish successful EXR/RowerTrain reception. The photo's
"Connecting..." label likewise does not prove that any third-party client received
or interpreted a measurement.

These are QZ logs, not fresh NordicRower alpha.3 diagnostics. They identify
concrete compatibility issues to address, but do not prove which phase the most
recent NordicRower trial reached.

## Which QZ implementation matters

The linked [classic Companion repository](https://github.com/cagnulein/QZCompanionNordictrackTreadmill/tree/ccf358c61e2897af6a7921aa0b6d1be2d2f0f8f8)
is not the code path named in these logs. The public QZ FitPro branch has the
matching native classes and warning text. The APK's exact source commit was
not supplied, so the source comparison is a matching implementation, not a
verified reconstruction of that binary.

- [FitProDeviceService](https://github.com/cagnulein/qdomyos-zwift/blob/0ce140b6aa366bcf994c6b82f9d8a1a315e41a99/src/android/src/FitProDeviceService.kt)
  directly opens FitPro USB-HID using vendored MIT Hyperborea components. Its
  `initialize(host)` ignores the host parameter. JNI getters retain their old
  gRPC names; their log names do not imply a working GlassOS/gRPC server.
- [QZ USB transport](https://github.com/cagnulein/qdomyos-zwift/blob/0ce140b6aa366bcf994c6b82f9d8a1a315e41a99/src/android/fitpro/com/nettarion/hyperborea/hardware/fitpro/transport/UsbHidTransport.kt)
  permits kernel-driver detachment using `claimInterface(..., true)`.
- [QZ V1 session](https://github.com/cagnulein/qdomyos-zwift/blob/0ce140b6aa366bcf994c6b82f9d8a1a315e41a99/src/android/fitpro/com/nettarion/hyperborea/hardware/fitpro/v1/V1Session.kt)
  logs the positional-layout warning but continues publishing the partial decode.
  NordicRower intentionally rejects that data, explaining why reproducing QZ's
  large polling request would still yield no complete NordicRower measurements.

Changing localhost to a LAN IP was therefore probably incidental to USB
permission, rower activity or startup timing in this particular debug build.
NordicRower does not need a new network permission, companion service or IP field.
No QZ application code or new QZ dependency has been incorporated.

## Alpha.4 implementation

1. **Guarded kernel-HID claim fallback.** Try the normal claim first. If it fails,
   match the USB bus/device/interface to sysfs and detach only an identified
   `usbhid` binding on a HID interface. `usbfs`, other drivers and unreadable or
   unknown ownership do not authorize detachment. Keep the manual Wolf Force stop
   guide after exhausted claims. Android documents the force flag as allowing
   [kernel-driver disconnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection#claimInterface(android.hardware.usb.UsbInterface,%20boolean)).
   The sysfs inspection is best effort and is not atomic with claiming; it does
   not guarantee coexistence with a restarting Wolf or QZ client.
2. **Compact V1 rower polling.** Read declared watts, strokes and stroke rate,
   plus declared resistance, distance, workout mode and Start-request state.
   For the observed controller the reply is 13 payload bytes / 18 framed bytes.
   Leave out composite keypad, split/pace and unrelated console fields; preserve
   the declared Start-request path and existing rower initialization. This avoids
   the oversized request instead of accepting a suspect partial decode.
3. **Reply validation.** Validate framing, lengths, fragment order and checksum
   before V1 decoding. Continue rejecting incomplete telemetry. Avoid irrelevant
   speed/incline startup limits and reject mismatched startup data.
4. **Known-firmware discovery exception.** The trace's complete optional-command
   list omits base opcode 02 even though the session uses it. Permit declared
   query-only fields for the observed type-20 / firmware-83 / hardware-1 profile.
   Other omitted commands and missing command lists remain gated. Discovery does
   not authenticate, start a workout, change resistance or alter power targets.
5. **Fresh diagnostics.** Collector v3 adds kernel HID/userspace bindings and
   QZ package/services. No collector command changes packages or device settings.

## Validation and next hardware trial

Regression tests use the observed declaration/command lists and simulated
controller packets; raw USB packets were not attached. A full V1 session test
models a controller limited to 64-byte USB replies and verifies real-field
mapping into a complete FTMS frame. These simulations are unit tests only and
are not part of the shipped APK. The tracked dependency patch is applied to
the exact pinned upstream baseline and its result compared with the build sources.

On the next trial, first Force stop QZ Fitness (and its Companion if installed)
so it cannot compete for USB or BLE. Leave Wolf enabled initially. While idle,
run discovery, then connect NordicRower and row briefly. Check the local watts,
stroke rate and stroke count before pairing one receiving app on another device.
Use the guided Wolf Force stop step only if the claim still fails. Retrieve a
fresh NordicRower diagnostic ZIP even if no measurements appear. We need the
selected driver/claim path, handshake, requested field shape, complete-frame
counter and BLE results to distinguish the remaining failure phase.

Physical hardware validation, native resistance/ERG, simultaneous Wolf operation
and EXR/RowerTrain end-to-end compatibility remain unverified. Alpha.4 remains a
sensor-only experimental build and does not advertise a BLE Control Point.
