# NordicRower 0.1.0

First public NordicRower release, packaged independently from NordicFTMS.
Runs on compatible Wolf/iFIT FitPro USB rower consoles and advertises as
**NordicRower** over Bluetooth FTMS.

- Retains alpha.4's compact, validated USB rower polling and manual Wolf
  Force stop fallback. No IP address or QZ Companion is required.
- Shows power, stroke rate and stroke count, with clear waiting/connected/
  measurement-subscription states. Hides stale values after five seconds.
- Keeps the workout screen focused on rowing, with technical details available
  on demand. Adds uniquely named diagnostic reports that can be saved or shared.
- Adds English/French installation guides, signed APK verification, checksums
  and an independent release workflow. Updates preserve alpha app data.

Customer screenshots from November 17 running alpha.4 confirm changing local power,
stroke rate and stroke count on his rower (188 W / 27 strokes per minute /
9 strokes, and 97 W / 23 / 4). Both screenshots show zero Bluetooth clients
and zero notifications. Third-party reception, long workouts and recovery
across all console variants remain unverified.

This release sends measurements only. It provides no FTMS Control Point,
remote resistance, ERG, speed, distance or pace. Apps requiring a Control Point
are unsupported. Bikes including the S15i are outside NordicRower's scope.
NordicFTMS's app and release workflow are unchanged.

Download **NordicRower.apk** for installation or **NordicRower-0.1.0.zip** for
the APK, guides, license and optional Windows system diagnostic collector.
Use `adb install -r NordicRower.apk` to update. Close QZ/Companion first and
leave NordicRower in the foreground during use. See the guides in the ZIP.

Validation: 72 unit tests and four Android 15 emulator UI/diagnostic tests
passed; release lint reports zero errors (24 warnings). The signed,
non-debuggable release APK was installed over alpha.4, retained its diagnostic
history and successfully shared/saved a report. Its explicit export is readable
over ADB while private run-as access remains disabled. No physical rower or
receiving-app test was performed for 0.1.0. The PowerShell collector's added
export read was reviewed but not executed on Windows.
