# NordicRower

NordicRower reads live power, stroke rate and stroke count from compatible
NordicTrack/ProForm FitPro USB rowers, and offers those measurements over
Bluetooth FTMS under the name **NordicRower**. Keep it in the foreground.

This is a separate app from NordicFTMS. Install it on the rower's Android
console, and run your receiving app on a separate phone, tablet or computer.
It does not support bikes (including the S15i) or GlassOS consoles. Current
customer evidence confirms local readings on one Wolf/iFIT rower and discovery/
connection in RowerTrain. Live measurements and recording inside RowerTrain,
and compatibility with other receiving apps, are still being validated.
Apps requiring an FTMS Control Point
are not supported. Remote resistance, ERG, speed, distance and pace are not
provided by this release.

## Install or update

1. Download **NordicRower.apk** or the **NordicRower-version.zip** asset from a
   release titled **NordicRower** in the
   [GitHub releases list](https://github.com/mikepugh/NordicFTMS/releases).
   Extract the ZIP first. Do not download NordicFTMS or GitHub's source archives
   for this installation.
2. Use your existing authorized ADB connection to the console. If you need ADB,
   get [Android platform-tools](https://developer.android.com/tools/releases/platform-tools).
3. In a terminal opened in the APK folder, run `adb install -r NordicRower.apk`.
   If ADB is in the same folder, use `./adb` on macOS/Linux or `.\adb.exe` on
   Windows instead of `adb`. With multiple devices use `adb -s DEVICE_SERIAL`.
   Updating preserves app data. If Android reports a signature mismatch, keep
   the installed app and collect diagnostics before considering an uninstall.
4. Open NordicRower from the console's app list.

## Row and connect an app

1. While idle, close/Force stop **QZ Fitness and its Companion**, if installed.
   Leave Wolf/iFIT enabled initially; its installed/enabled state does not
   establish whether it owns USB.
2. Tap **Connecter le rameur**, confirm and grant USB/Bluetooth access if asked.
   This initializes a workout on the controller. Start rowing and check that
   power and strokes appear.
3. In the receiving app on your other device, select **NordicRower** as a
   Bluetooth rower. The console separately reports waiting for an app,
   connected without a measurement subscription, and subscribed to measurements.
   Verify values in the receiving app: a connection alone does not prove it
   interprets the measurements.
4. If the USB interface cannot be claimed, use **Réglages iFIT**, choose
   **Forcer l'arrêt / Force stop** in Android's app settings, return to
   NordicRower and tap **Réessayer**. NordicRower does not stop Wolf itself.
5. Keep NordicRower visible during your workout. Tap **Déconnecter** when done.
   Old values are hidden if complete measurements stop arriving for five seconds.

## Collect diagnostics

After the test, use **Partager les diagnostics** to select an installed app to
send a generated report, or **Enregistrer les diagnostics** to save it through
Android's file picker. The filename starts with `NordicRower-`, includes the
version, date and run identifier, and ends in `.txt`. Check its first line says
NordicRower, then send that report privately to support. Sharing only opens a
chooser; nothing is sent automatically. Some consoles lack a file picker or
sharing target; **Enregistrer les diagnostics** also writes an ADB-readable copy
even if the file picker is unavailable. On any desktop platform you can retrieve it:

```sh
adb pull /sdcard/Android/data/com.nordicrower.app/files/diagnostics/latest-report.txt NordicRower-report.txt
```

For a separate capability report, disconnect first, open **Afficher / masquer
les détails techniques**, choose **Rechercher les capacités**, leave the rower
idle and wait for completion. This query-only diagnostic does not test remote
resistance or ERG. Export again to include its report.

The optional Windows PowerShell collector in the ZIP collects additional
system diagnostics from an existing ADB connection. Run it beside `adb.exe`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\collect-nordicrower-diagnostics.ps1
```

Tap **Enregistrer les diagnostics** immediately before running the collector;
it includes that export as `exported-report.txt`. Check the report's `Exported`
timestamp: the copy is a snapshot, not an automatically refreshed log. The public
APK is not debuggable, so collector errors for private `run-as` files are expected.
It never changes the
equipment or uploads anything. System dumps can contain identifiers; send
reports privately, and collect before uninstalling or clearing app data.
