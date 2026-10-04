Implement stable NordicFTMS device recognition, remembered-device reconnection, and removal of stale duplicate scan entries in BOTH PowerTread for iOS and PowerTread for Android.

## Authorization and coordinator workflow

Create the coordinated feature package, then proceed into a separate implementation phase and validate both native applications. This request explicitly authorizes application-code changes, builds, and tests in:

- Coordinator: /Users/mikepugh/code/GitHub/PowerTread-Coordinator
- iOS: /Users/mikepugh/code/GitHub/PowerTread
- Android: /Users/mikepugh/code/GitHub/PowerTread-Android

Keep /Users/mikepugh/code/GitHub/NordicFTMS read-only; it supplies the peripheral protocol. Do not build, install, or modify NordicFTMS as part of this task. Do not perform releases, uploads, workflow dispatches, release tags, or production merges.

Read each repository's current AGENTS.md, documentation index, Git status, branch, and build settings. Preserve existing work and follow current branch conventions. Use the primary checkouts; do not create additional worktrees. If using separate platform agents, limit each agent's writes to its assigned app repository; the coordinator owns package artifacts and parity tracking.

Use the coordinator's canonical package structure, requirement IDs, manifests, platform prompts, verification files, and parity checklist. Resolve the target release from current context; if ambiguous, record the work as unscheduled instead of inventing a release version. Capture source baselines yourself. Repeat repository boundaries in delegated prompts. State the normal precedence: shared behavior/acceptance contracts, platform prompt, current target AGENTS.md/build configuration, target architecture/tests, sibling read-only behavior, then historical documents. Surface conflicts.

Do not stop after producing implementation prompts. Complete authorized native implementation and automated validation, and record physical-device validation as pending where hardware is unavailable.

## Problem and desired outcome

NordicFTMS is an Android BLE FTMS peripheral running on NordicTrack/ProForm consoles. Android can rotate its advertising address, including when the advertiser is recreated. iOS can consequently expose the same console under a new CBPeripheral.identifier; Android central apps can discover a new BluetoothDevice.address.

Users currently see multiple NordicFTMS entries with different identifiers. Old entries can remain unconnectable, and remembered-device auto-connect fails. NordicFTMS previously advertised a fixed name and the standard FTMS service UUID, without a persistent device-specific BLE identity. Its hardware-derived DIRCON identifiers were unrelated to BLE identity.

The desired flow is: select a console once, then recognize and reconnect to that same installation despite transport-ID changes. Show one logical console per relevant sensor-role list, while distinguishing two nearby consoles with the same name. Preserve the user's chosen treadmill and speed/distance source.

Do not try to set Apple's peripheral UUID, disable Android BLE privacy, require rooting/bonding, or match consoles by name alone. Implement the stable application identity described below. This feature concerns BLE; leave DIRCON and backend startup diagnostics outside its scope.

## Peripheral protocol: NordicFTMS identity v1

Read these files from the NordicFTMS WORKING TREE, not only its last commit:

- app/src/main/java/com/nordicftms/app/PeripheralIdentity.java
- app/src/main/java/com/nordicftms/app/FTMSService.java
- docs/backend-discovery-and-bluetooth-identity.md

The protocol was implemented locally but has not been established as released or validated on hardware. Do not invent a minimum released NordicFTMS version. Record the exact source baseline and any uncommitted protocol changes. If the working-tree implementation conflicts with this contract, report the discrepancy before changing wire semantics.

Wire contract:

- Standard FTMS service: 00001826-0000-1000-8000-00805f9b34fb.
- Identity service: 2d3f96c1-45e8-4ebd-aad2-30d98c02d2b3.
- Read-only identity characteristic, under that service: d219c59d-5319-42bd-b65d-1a97bd15df83.
- Identity value: exactly 12 opaque bytes, generated once with SecureRandom and persisted by NordicFTMS.
- These UUIDs identify the protocol; neither UUID is the unique console ID.
- There is no version byte, length prefix, string encoding, UUID header, or checksum inside the 12-byte value. Do not interpret it as a 16-byte UUID, integer, MAC address, or reverse its byte order.
- Canonical stored representation: 24 lowercase hexadecimal digits, preserving leading zeros. Namespace the identity as NordicFTMS v1 in the model/key so other vendor identity schemes cannot collide semantically.
- NordicFTMS on console Android 8+ includes the same 12 bytes as Service Data under the identity service UUID in the scan response. Its primary advertisement still includes the name NordicFTMS and standard FTMS service. The identity service need not also appear in the advertised service-UUID list.
- NordicFTMS on console Android 5–7 exposes the GATT identity characteristic but omits this service-data advertisement because those Android versions truncate service-data UUIDs. This refers to the CONSOLE's Android version, not the phone running PowerTread.
- Identity survives NordicFTMS service restarts, console reboots, and in-place APK updates. Uninstall, app-data clear, and factory reset create a new identity. It is excluded from Android backup.
- Older NordicFTMS without this protocol remains usable through existing manual selection and transport-ID matching.

Shared parser vectors:

- Bytes 00 11 22 33 44 55 66 77 88 99 aa bb => 00112233445566778899aabb.
- Bytes 00 11 22 33 44 55 66 77 88 99 aa bc => a DIFFERENT console identity.
- Twelve zero bytes are structurally valid; do not invent restrictions the protocol does not specify.
- Missing service data, the wrong service-data UUID, and payload lengths 0, 11, or 13 do not provide a valid v1 identity. Handle without crashing or erasing a previously learned identity.

## Required shared behavior

1. Separate logical identity from transport identity. Keep the current CBPeripheral UUID/Bluetooth address for native connection operations and existing transport maps. Add an optional typed NordicFTMS identity to discovery and saved-device models, plus the mapping needed to associate a logical console with its current transport endpoint. Never pass the 24-digit application ID to a native Bluetooth API expecting a UUID or address.

2. Merge partial discovery observations. Identity service data may arrive after the first FTMS/name observation; a later packet without service data must not erase a learned identity. Refresh signal strength and useful metadata. Keep scanning for standard FTMS so legacy devices remain discoverable; do not require an identity-service scan filter. Bound caches and tie asynchronous results to the applicable connection/scan generation.

3. Learn identity when the user selects/connects to a console. Use valid advertisement data and discover/read the identity characteristic as needed. Persist it through the existing saved-sensor preference owner. If an identity is learned after selection was saved, enrich that same selection only if it is still current. Late callbacks must not resurrect a forgotten device or overwrite a newly selected one.

4. Reconnect by saved stable identity when available. A matching identity at a different transport endpoint is the same logical console. Use the fresh native peripheral/device, retire stale pending connections and callbacks, and update saved/current transport references together after successful identity resolution. Apply this to startup auto-connect, a selected-but-disconnected device, and existing active-workout recovery. Preserve existing retry bounds and manual-selection precedence; do not introduce indefinite scanning or automatic belt starts.

5. When a saved stable identity exists, a conflicting identity must never be accepted merely because the name or an old transport ID matches. Missing advertisement identity is unknown, not proof of a match or a mismatch. Resolve it by GATT where appropriate. Read and confirm the expected identity before promoting an automatic identity-recovery/probe connection into a controllable treadmill connection. If advertisement, saved identity, and GATT identity disagree, abandon automatic recovery without replacing the saved choice or issuing control commands. This identity is public identification, not cryptographic authentication.

6. Support the GATT-only case. During an existing bounded reconnect attempt for a saved NordicFTMS identity, permit serialized, bounded discovery/read probes of plausible NordicFTMS FTMS candidates. Name can help choose a candidate to inspect; it cannot establish a match. A probe must not select the candidate, request FTMS control, start a workout, send speed/incline/resistance commands, replace another sensor, or report it as connected before identity verification. Integrate with existing connection/GATT queues, cancellation, timeout, and resource cleanup. Do not probe arbitrary sensors continuously. Preserve working legacy manual connections if the identity service is absent.

7. Deduplicate by stable identity within each sensor role, not by name or across all roles. Consolidate old/new transport entries into one logical NordicFTMS row. Preserve an established working connection instead of switching it on every advertisement. Stale callbacks or cached observations must not move the logical console back to a retired endpoint. Two different stable IDs must remain separate even when both advertise NordicFTMS. Do not silently choose between genuinely conflicting live devices claiming the same identity. Unidentified historical rows cannot safely be merged by name; expire stale unselected/unconnected discoveries using a bounded policy, without deleting saved preferences or active selections. State the freshness policy explicitly in the shared contract and test it with a controllable clock.

8. Keep treadmill and synthetic FTMS speed/distance associations consistent. Current apps use a transport ID/address plus '-FTMS' for the synthetic speed source. When the parent console's transport changes, migrate that association if and only if the user had selected/saved that console's FTMS speed/distance source. Preserve the logical parent identity and sensor role separately. Do not replace a selected footpod or other independent speed source. Preserve machine capability/profile handling and unrelated sensor types.

9. Backward-compatible preferences: add optional/defaulted fields so old JSON decodes unchanged and Stryd Duo metadata survives every save/copy/migration path. An old selection can acquire identity automatically when its previously saved transport endpoint still matches and is successfully resolved, or when the user explicitly selects the console. If that endpoint has already changed and there is no saved application identity, require one normal manual selection; never guess by name, proximity, RSSI, or the fact that only one console is visible. Clearing/forgetting a selection removes its identity association according to existing sensor-role semantics. A NordicFTMS reinstall with a new identity requires explicit selection again. Malformed new identity metadata must not clear the entire saved-sensor collection.

10. UX: reuse existing device selection and connection states. The remembered console should retain a stable row/selection while its underlying transport changes. If the UI displays an identifying suffix, derive it consistently from the stable identity when available (last eight hex digits, uppercase for display only); retain existing platform display rules otherwise. Never use that shortened suffix as a matching key. Keep two same-name consoles distinguishable. No new mandatory setup screen. Localize any added/changed text and accessibility descriptions in English, French, Spanish, and Italian and reuse native design-system components.

11. Keep stable IDs, raw Bluetooth addresses/UUIDs, and device-owner information out of new logs, Sentry events, and analytics. Use existing diagnostic gates and non-identifying event categories/counters. Do not add permissions or change store privacy declarations incidentally.

## Platform implementation guidance

iOS: inspect these repository-relative files and current tests:

- PowerTread/Services/Sensors/BluetoothSensorManager.swift
- PowerTread/Models/Sensors/DiscoveredPeripheral.swift
- PowerTread/Models/Sensors/SavedSensorPreferences.swift
- PowerTread/Views/Devices/DeviceSelectionOverlay.swift
- PowerTreadTests/DiscoveredPeripheralTests.swift
- PowerTreadTests/BluetoothSensorManagerBatteryProbeTests.swift

Current discovery keys by peripheral.identifier.uuidString and appends new IDs. attemptAutoReconnect compares savedDevice.peripheralUUID with discoveredPeripheral.id. SavedSensorDevice is Codable and SavedSensorPreferences owns UserDefaults JSON. Read CBAdvertisementDataServiceDataKey as [CBUUID: Data]; the map value is only the 12-byte payload. Do not use retrievePeripherals with the new application ID. Handle selected-device guards and workout-reconnect dictionaries that currently assume an unchanged UUID. Preserve Swift 5 architecture, existing serialized lifecycle, battery probing, Stryd Duo behavior, native service discovery, and FTMS injection. Add pure parsing/matching/migration tests and simulated callback-sequence tests at existing seams; do not require real CBPeripheral objects for all logic tests.

Android: inspect the current files under app/src/main/java/com/novasolutionsgroup/powertread/sensor/:

- BluetoothSensorManager.kt
- BluetoothConstants.kt
- DiscoveredPeripheral.kt
- SavedSensorPreferences.kt

Relevant tests include sensor/DiscoveredPeripheralTest.kt, sensor/SavedSensorDeviceCompatibilityTest.kt, and existing BluetoothSensorManager tests under app/src/test. Recheck actual path casing.

Current handleScanResult and connection maps use BluetoothDevice.address. attemptAutoReconnect compares saved.peripheralAddress; reconnectSelectedIfDisconnected and workout recovery also need review. Read ScanRecord.getServiceData(ParcelUuid(identityServiceUUID)); preserve the live BluetoothDevice/address for connectGatt and transport maps. Extend the established Hilt/coroutine/StateFlow lifecycle and serialized GATT operation queue, including read callbacks, watchdogs, and cancellation. SavedSensorPreferences currently uses EncryptedSharedPreferences with a legacy migration, not the generic app DataStore owner; retain that boundary. New kotlinx.serialization fields need backward-compatible defaults. Do not add a Room migration unless the implementation actually changes a Room model.

## Required automated and device validation

Give both platforms the same behavior vectors and trace each requirement to native evidence. At minimum test:

- Parser vectors above, leading-zero preservation, missing/malformed/unknown data, partial scan-response updates, and no identity loss on later incomplete packets.
- Same stable ID under endpoint A then B: one logical row, connection uses B, saved mapping updates, late A events do not revert it, and repeated B discoveries do not reconnect repeatedly.
- Two NordicFTMS consoles with distinct stable IDs remain separate; a saved choice never moves to the other console.
- Known-ID conflict on a matching old endpoint is rejected; unknown identity is resolved without unsafe fallback; failed/mismatching GATT probes send zero control commands.
- Old preferences, new-field round trips, malformed optional metadata, Stryd Duo preservation, late identity enrichment, explicit replacement, forget/clear, and reinstall/new-identity behavior.
- GATT-only recovery, absent identity service on legacy NordicFTMS, read/connect timeout, cancellation, Bluetooth-off, scan-window expiration, and manual selection during a probe.
- Treadmill plus saved '-FTMS' speed source migrate together; independent speed source stays selected; startup and active-workout reconnect follow existing state semantics.
- UI row identity, stable display suffix, duplicate cleanup, accessibility, localization, and native phone/tablet layouts where changed.

Run the appropriate current iOS unit/build checks and Android unit/build checks; use targeted instrumentation/visual checks where relevant. Record commands/results and genuine environment blockers without weakening checks to obtain a pass.

Physical acceptance requires an identity-enabled NordicFTMS build and both an iOS and Android central device: select once, restart NordicFTMS, restart console Bluetooth, reboot the console, restart PowerTread, and update the NordicFTMS APK in place. Confirm identity persistence, automatic recovery when the observed transport actually changes, absence of orphan rows, and correct treadmill/speed-source association. Repeat with two nearby consoles, an independent footpod, and the GATT-only fallback where equipment is available. A reinstall/data clear must require selecting the newly identified installation. Record versions, console model/OS, observed transport-change evidence, and results without publishing raw device IDs.

Do not claim address-rotation recovery was verified if the transport did not change during the test. Simulator/JVM tests do not prove Bluetooth radio behavior. The NordicFTMS source changes and PowerTread code being present are not proof that either app was released.

## Completion

Deliver the coordinator package, native implementations/tests, relevant maintained-documentation updates, and a requirement-by-requirement parity report. Keep statuses honest: code-complete is separate from internally-validated and released. Include the coordinator's required Operator follow-up section with required-now, required-later, and optional actions, naming the exact hardware/build evidence still needed. Resolve commit provenance yourself. If hardware is unavailable, finish all independent code and automated work and identify that validation dependency precisely.
