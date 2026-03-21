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

## Version Bumps

Update version in all three files:
1. `app/build.gradle` — `versionCode` and `versionName`
2. `AndroidManifest.xml` — `android:versionCode` and `android:versionName`
3. `.github/workflows/release.yml` — tag trigger version

## Notes

- SSL certs in `app/src/main/assets/certs/` are gitignored (bundled in APK only)
- Code style: English comments, descriptive variable names, match existing patterns
