# NordicRower — installation and use

NordicRower shares power, stroke rate and stroke count from compatible
NordicTrack/ProForm Wolf/iFIT USB rowers over Bluetooth. Install it on the rower's
console and use your fitness app on another device.

## Install or update

1. Download **NordicRower.apk**, or download and extract **NordicRower-version.zip**,
   from a [NordicRower release](https://github.com/mikepugh/NordicFTMS/releases).
2. Connect to the console using your authorized ADB connection. If needed,
   download [Android platform-tools](https://developer.android.com/tools/releases/platform-tools).
3. Run `adb install -r NordicRower.apk`, then open NordicRower on the console.
   Updating preserves app data. If ADB is in the current folder, use `./adb` on
   macOS/Linux or `.\adb.exe` on Windows. With multiple devices, add `-s DEVICE_SERIAL`.

## Use

1. Close other fitness apps on the console. Wolf/iFIT can remain enabled initially.
2. Tap **Connecter le rameur**, confirm, and grant USB/Bluetooth access if asked.
3. Start rowing. In your fitness app on the other device, connect to **NordicRower**
   as a Bluetooth rower.
4. Keep NordicRower in the foreground. Tap **Déconnecter** when finished.

If USB access fails, tap **Réglages iFIT**, choose **Force stop** in Android's
settings, return to NordicRower and tap **Réessayer**.

This app provides measurements only. Remote resistance, ERG, speed, distance
and pace are not supported. Apps requiring an FTMS Control Point are unsupported.
Use NordicFTMS for compatible GlassOS consoles.

## Diagnostics

Use **Partager les diagnostics** to share a report or **Enregistrer les diagnostics**
to save one. Nothing is sent automatically. Send reports privately to support.

If the console has no file picker, tap **Enregistrer les diagnostics**, then retrieve
the report from your computer:

```sh
adb pull /sdcard/Android/data/com.nordicrower.app/files/diagnostics/latest-report.txt NordicRower-report.txt
```

The optional Windows collector in the ZIP gathers additional system information.
Place it beside `adb.exe`, save a fresh report in the app, then run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\collect-nordicrower-diagnostics.ps1
```

The collector includes the saved report. Private `run-as` reads are unavailable
on public APKs; those errors are expected. Collect reports before uninstalling
or clearing app data.
