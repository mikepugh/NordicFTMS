# NordicFTMS

**BLE FTMS server for NordicTrack and ProForm fitness equipment.**

NordicFTMS is an Android app that runs on your iFit-enabled treadmill, bike, elliptical, or rower and exposes it as a standard Bluetooth FTMS (Fitness Machine Service) device. Any FTMS-compatible app — [PowerTread](https://powertread.fit/), Zwift, Kinomap, and others — can connect directly over Bluetooth.

## How It Works

```
Your Phone/Tablet (PowerTread, Zwift, etc.)
        |
    BLE FTMS
        |
NordicFTMS app (on the treadmill's built-in Android tablet)
        |
    gRPC / mTLS
        |
GlassOS (treadmill hardware controller)
```

NordicFTMS communicates with the machine hardware through the GlassOS gRPC interface running on the treadmill's own Android system. It reads live metrics (speed, incline, distance, cadence, watts) via gRPC streaming subscriptions and broadcasts them over BLE FTMS. It also receives FTMS Control Point commands (set speed, set incline, set resistance) and forwards them to the hardware via gRPC.

## Features

- **Zero configuration** — auto-starts on boot, auto-detects machine type (treadmill, bike, elliptical, rower)
- **Standard BLE FTMS protocol** — works with any FTMS-compatible app
- **Bidirectional control** — read metrics AND control speed/incline/resistance from your phone
- **Runs silently in background** — no user interaction needed after initial install
- **Direct hardware communication** — uses gRPC to talk directly to GlassOS

## Supported Data

| Treadmills | Bikes |
|---|---|
| Speed (km/h) | Speed (km/h) |
| Incline (%) | Cadence (RPM) |
| Distance (m) | Resistance Level |
| | Power (watts) |

## Installation

1. Enable privileged mode on your treadmill/bike (tap white area 10 times, wait 7 seconds, tap 10 more times)
2. Enable USB debugging in Developer Options
3. Install the APK via ADB:
   ```bash
   adb connect <treadmill-ip>:5555
   adb install NordicFTMS.apk
   ```
4. Reboot the treadmill. NordicFTMS will auto-start in the background.
5. Open your FTMS app ([PowerTread](https://powertread.fit/), Zwift, etc.), scan for Bluetooth devices, and connect to "FTMS Tread" (or "FTMS Bike" / "FTMS Ellip" / "FTMS Rower").

## Tested Hardware

- NordicTrack X11i Incline Trainer (ETNT22019, MT8163, Android 9)

More iFit v2 machines with GlassOS should work — please report your results!

## Building from Source

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Acknowledgments

This project was inspired by and originally forked from [QZ Companion](https://github.com/cagnulein/QZCompanionNordictrackTreadmill) by Roberto Viola ([@cagnulein](https://github.com/cagnulein)). Roberto's pioneering work on the QZ ecosystem — including the companion app and the [QZ (qdomyos-zwift)](https://github.com/cagnulein/qdomyos-zwift) fitness bridge — made it possible to connect NordicTrack machines to third-party fitness apps for the first time. His generous open-source contributions to the fitness community are deeply appreciated.

NordicFTMS would not exist without Roberto's foundational work.

## License

GNU GENERAL PUBLIC LICENSE V3 — see [LICENSE](LICENSE).
