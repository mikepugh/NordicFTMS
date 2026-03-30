# NordicFTMS - Development Guide

## Overview

Android app that runs on iFit-enabled NordicTrack/ProForm fitness equipment and exposes it as a standard BLE FTMS device via the GlassOS gRPC interface.

## Package

`com.nordicftms.app`

## Source Files

- `FTMSService.java` — BLE FTMS GATT server and advertiser
- `GrpcControlService.java` — gRPC client connecting to GlassOS (localhost:54321, mTLS)
- `MainActivity.java` — Auto-starts FTMSService, moves to background
- `BootUpReceiver.java` — Starts FTMSService on device boot

## Proto Files

`app/src/main/proto/com/ifit/glassos/` — gRPC service definitions for speed, incline, distance, resistance, cadence, watts, console, and workout services.

## Building

```bash
./gradlew assembleDebug
```

## Versioning

App versioning is automatic:
1. Release builds pass `-PVERSION_NAME` from the GitHub tag, for example `v0.9.5`
2. Local builds fall back to `git describe --tags --long --dirty`
3. `app/build.gradle` converts the resolved semver into Android `versionName` and `versionCode`

## Notes

- SSL certs in `app/src/main/assets/certs/` are gitignored (bundled in APK only)
- Code style: English comments, descriptive variable names, match existing patterns
