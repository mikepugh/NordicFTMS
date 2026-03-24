# NordicFTMS

**BLE FTMS and DIRCON server for NordicTrack and ProForm fitness equipment.**

NordicFTMS is an Android app that runs on your iFit-enabled treadmill, bike, elliptical, or rower and exposes it as a standard Bluetooth FTMS (Fitness Machine Service) device. It can also expose compatible equipment over Wahoo DIRCON on your local network. Any FTMS-compatible app — [PowerTread](https://powertread.fit/), Zwift, Kinomap, and others — can connect over Bluetooth, and supported treadmill apps can also connect over Wi-Fi through DIRCON.

## Support

If you found NordicFTMS useful and would like to support continued development

<a href="https://www.buymeacoffee.com/mikepugh">
  <img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me A Coffee">
</a>

## How It Works

NordicFTMS communicates with the machine hardware through the GlassOS gRPC interface running on the machine's own Android system. It reads live metrics (speed, incline, distance, cadence, watts) via gRPC streaming subscriptions and broadcasts them over BLE FTMS or DIRCON. It also receives control commands (set speed, set incline, set resistance) and forwards them to the hardware via gRPC.

## Features

- **Zero configuration** — auto-starts on boot, auto-detects machine type (treadmill, bike, elliptical, rower)
- **Standard BLE FTMS protocol** — works with any FTMS-compatible app
- **DIRCON treadmill support** — supported treadmills can be discovered over Wi-Fi by apps that support Wahoo Direct Connect
- **Bidirectional control** — read metrics AND control incline/resistance from your phone
- **Runs silently in background** — no user interaction needed after initial install
- **Direct hardware communication** — uses gRPC to talk directly to GlassOS

## Zwift and DIRCON

For standard Bluetooth FTMS, NordicFTMS advertises as `NordicFTMS`.

For supported treadmills, NordicFTMS also advertises a DIRCON service on your local network using a `KICKR RUN <id>`-style name. This allows Zwift to discover it as a controllable treadmill in addition to the normal run-speed sensor path.

In practice, that means:

- When running on Zwift, first connect the RUN SPEED device which should show as `KICKR RUN <id>`
- Then connect the same `KICKR RUN <id>` within the CONTROLLABLE device. 
- Zwift will prompt you to allow incline control, be sure to allow it.
- Start running on Zwift!
- At any time you can manually set the incline on your treadmill and Zwift should relinquish control. Use the Zwift UI to re-enable control if and when desired.

> [CAUTION]
>
> Always remain in control of your treadmill and follow all safety precautions.
> Zwift can command steep inclines, especially on NordicTrack X-series treadmills with very high incline capability.
> If needed, manually change the incline and Zwift should relinquish control.


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
5. Open your FTMS app ([PowerTread](https://powertread.fit/), Zwift, etc.), scan for Bluetooth devices, and connect to `NordicFTMS`.
6. For supported treadmills, if you are using Zwift on the same local network, also look for `KICKR RUN <id>` in the DIRCON list and connect it as a **Controllable** treadmill.

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
