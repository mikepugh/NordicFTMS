# Running Speed and Cadence treadmill support

NordicFTMS exposes an additional primary GATT service on machines recognized by
GlassOS as treadmills or incline trainers, including unknown machine types with
treadmill capabilities. FTMS and the persistent NordicFTMS identity service are
registered first, followed by RSC. Advertising starts after all services have
registered successfully.

The primary legacy advertisement contains both 16-bit service UUIDs and the
existing `NordicFTMS` name, within the 31-byte advertising limit. The identity
scan response is unchanged. RSC discovery does not depend on a scan response.

NordicFTMS uses one connectable advertisement and one GATT server. Clients can
subscribe to FTMS and RSC independently, using the same live GlassOS data source.
Normal builds include both services automatically on supported treadmills.

## Compatibility

RSC discovery and pairing depend on the receiving client. An epix Gen 2 running
software 26.09 did not discover the console during testing, even when a BLE
inspector could see its RSC service. The Android 9 test console uses rotating
resolvable private addresses; address handling is a possible interoperability
limitation, not a confirmed explanation for every Garmin pairing failure.
Garmin compatibility is not guaranteed. Bluetooth address selection remains
managed by Android.

## GATT characteristics

| Characteristic | UUID | Properties | Value |
| --- | --- | --- | --- |
| RSC Measurement | `0x2A53` | Notify, with readable/writable CCCD | Flags, speed, cadence, distance |
| RSC Feature | `0x2A54` | Read | `02 00`: total distance supported |
| SC Control Point | `0x2A55` | Write, Indicate, with readable/writable CCCD | Set Cumulative Value and procedure responses |

Measurement notifications use the existing 500 ms FTMS loop. Each 8-byte RSC
measurement contains:

| Byte offset | Type | Meaning |
| --- | --- | --- |
| 0 | uint8 | `0x02`: total distance present; no stride length or gait status |
| 1 | uint16 LE | Speed: km/h divided by 3.6, multiplied by 256, rounded |
| 3 | uint8 | Cadence: zero, since no treadmill footfall measurement is available |
| 4 | uint32 LE | Cumulative distance in decimeters |

For example, 10.8 km/h and 1.2345 km produce `02 00 03 00 39 30 00 00`.
Speed saturates at the uint16 maximum; negative or NaN speed becomes zero.
Invalid distance samples are ignored. Distance saturates at the uint32 maximum
and never rolls over. Console workout resets begin another segment of the RSC
counter instead of reducing it. The counter is retained across Bluetooth
restarts and backend retries within the same Android service, and is not
persisted across service recreation.

## Control point and subscriptions

A client must enable indications (`02 00`) on the SC Control Point CCCD before
writing a procedure. `01 <uint32 LE>` sets the RSC distance counter in decimeters.
The reply is `10 01 01` (response code, request opcode, success). This procedure
only changes the RSC counter. It never sends a command to GlassOS.

Unsupported opcodes return result `0x02`; invalid Set Cumulative Value lengths
return result `0x03`. If no valid current console distance is available yet,
Set Cumulative Value returns result `0x04` without changing the counter. Initial
placeholder distance values during backend reconnect are ignored.
Missing indication subscription returns ATT error `0x81`;
another request before the previous response completes returns ATT error `0x80`.
Prepared writes, nonzero offsets, and empty requests are rejected without
changing the counter.

CCCD values are maintained per client and characteristic, including FTMS data,
FTMS control/status, RSC measurements, and RSC control. Disabling one subscription
does not remove the client's other subscriptions. Disconnects and GATT server
restarts discard the relevant connection state. A watch disconnect does not
revoke another client's FTMS control grant.

Outgoing notifications and indications are serialized through
`onNotificationSent`. Unsent measurements for the same client/characteristic
are replaced with the latest sample; control responses and machine-status
events retain their order. A missing callback times out after 30 seconds and
disconnects that client.

## Validation

Automated checks:

```sh
./gradlew testDebugUnitTest assembleDebug
```

Unit tests cover RSC byte encoding, invalid values, unsigned distance limits,
console resets, Set Cumulative Value, independent subscriptions, notification
ordering/coalescing, cleanup, and serialized registration of three services.

Use these hardware checks to validate data and normal client behavior on the
console. Watch compatibility must be verified separately for each model:

1. Install the debug APK on a supported console and confirm that advertising
   starts. In a BLE inspector, verify services `0x1826` and `0x1814` in the primary
   advertisement and the RSC characteristics listed above in the same GATT
   server. Confirm there is one connectable `NordicFTMS` advertisement.
2. Read RSC Feature at offsets 0, 1, 2, and an invalid offset. Confirm `02 00`,
   `00`, an empty success, and an invalid-offset error respectively.
3. Subscribe only to RSC Measurement. Check speed against the console while
   stopped and at several walking/running speeds. Confirm zero at stop and
   increasing distance. Check that no FTMS data is sent to this subscription.
4. Enable SC Control Point indications, set the counter to zero, and confirm
   `10 01 01` followed by distance increasing from zero. Confirm that console and
   FTMS distance are unaffected. Repeat with a nonzero counter value, unsupported
   opcode, invalid payload, and indications disabled.
5. Connect an FTMS app on a second client while RSC remains subscribed. Check
   both data streams, FTMS incline control, independent CCCD reads, and disabling
   one characteristic while another stays subscribed. Disconnect the watch and
   verify the FTMS app retains control. Connection count depends on the console.
6. With a compatible RSC client, add `NordicFTMS` as a running sensor, record an
   indoor activity, and verify live pace and recorded distance against the
   console. For watches, record the model/firmware and whether zero RSC cadence
   affects the watch's own cadence recording.
7. Start another console workout, reconnect clients, and restart Bluetooth.
   Confirm subscriptions do not leak and distance differences remain sensible.
8. On a bike/elliptical/rower profile, confirm RSC is absent and FTMS still works.

## References

- [Bluetooth SIG Running Speed and Cadence Service 1.0.1](https://www.bluetooth.com/specifications/specs/running-speed-and-cadence-service/)
- [Bluetooth SIG GATT Specification Supplement](https://www.bluetooth.com/specifications/gss/)
