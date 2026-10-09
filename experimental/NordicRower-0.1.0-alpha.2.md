# NordicRower 0.1.0-alpha.2

Experimental diagnostic build, not a confirmed working EXR/RowerTrain release.
Separate app `com.nordicrower.app`, Bluetooth name **NordicRower**. Original
NordicFTMS is unchanged. Wolf must be disabled reversibly before direct USB access.

## Changes

- French console UI and explicit six-step capability discovery.
- Query-only discovery, independent of workout initialization/teardown:
  declared feature/field IDs including unknown IDs, firmware/model information,
  resistance maximum, resistance/target power/mode readbacks and protocol errors.
- V1 reads only declared fields and declared commands, logs security blocks
  without unlocking. V2 requires a complete valid feature list and confirmed
  rower identity before subscribing to known target/limit readbacks.
- Incomplete, malformed, absent or rejected replies are UNKNOWN, not unsupported.
- Candidate control features are DECLARED_NOT_WRITE_TESTED. Neither a feature
  declaration nor a readable target proves write acceptance or working ERG.
- Automatic timestamped persistent logs (~3 MiB across three journals), partial
  probe reports, exceptions, USB timing, real telemetry/frame rejection reasons,
  and BLE registration/advertising/connection/subscription/notification evidence.
- Read-only Windows collector, `collect-nordicrower-diagnostics.ps1`, included
  in the package and separately available beside it in `experimental/`.

BLE remains sensor-only: no Control Point or advertised target features. Apps
requiring workout commands can still reject it. A later explicit, supervised
hardware write test is required to establish working resistance/ERG control.
Actual USB rower data and EXR/RowerTrain compatibility remain unverified.

## Customer Workflow

See [French installation, test, collection and rollback instructions](NordicRower-0.1.0-alpha.2-fr.md).

Install using `adb install -r`, preserving existing NordicRower logs. Run the
capability discovery while idle. Then connect the real rower session, test one
Bluetooth app at a time and disconnect. Retrieve the logs before uninstalling
using the PowerShell script beside `adb.exe`; send the resulting ZIP privately.
The collector changes no Android settings/apps/logs/controller targets and
uploads nothing. It selects only an already-authorized ADB device. App files
are retrieved with `run-as`, requiring this experimental debuggable APK.

No vendor/reference sources or instrumentation test APK are included in the ZIP.
Hyperborea MIT license and APK/script SHA-256 checksums are included.

## Verification

- 38 unit tests passed, covering FTMS frames, controller guards, query-only
  discovery, malformed/incomplete responses and persistent log rotation.
- Two Android instrumentation tests passed on the Samsung SM-T733 (Android 14):
  fresh partial-report persistence on discovery failure and French UI layout.
  Landscape and portrait UI images were visually inspected.
- Android lint: zero errors, 13 warnings. APK signature and version verified.
- The collector ran successfully through PowerShell 7.6.6 on macOS against the
  tablet, retrieved the partial report and correctly rejected ambiguous device
  selection. Native Windows PowerShell 5.1 execution remains untested.
- No physical USB rower, resistance/ERG write, or EXR/RowerTrain end-to-end test
  was performed. The tablet has no FitPro controller; its failure was expected.

APK SHA-256: `5caff93ca11a17e74097836239eeb074cdb0c2c4de42355393b382c26b054f30`.
