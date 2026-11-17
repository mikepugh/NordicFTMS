# Hyperborea Dependency

Source: https://github.com/ball2jh/Hyperborea

Pinned commit: `b7b10f33c2890f1bbbd5caace6c8a652b4a58961` (MIT).

`vendor/` and `references/` directories are ignored throughout this repository.
No third-party source or reference checkout should be staged or committed.
Hyperborea is a runtime dependency here, not merely a research reference: a
fresh checkout must run `bash setup-vendor.sh` before building NordicRower.

The script fetches the exact commit into a temporary checkout, copies only
FitPro transport/session code and minimal model dependencies, applies the small
tracked patch, and retains the upstream license. The license is included in the
APK by a build task. An existing vendor directory is never overwritten.

For offline preparation, pass the path of a clean local Hyperborea checkout at
that exact revision: `bash setup-vendor.sh /path/to/Hyperborea`.

The patch contains our rower-specific changes, not the upstream codebase:

- Reject unconfirmed/non-rower controllers before workout initialization.
- Require actual stroke-count/rate fields; do not infer strokes from RPM.
- Add FitPro2 rower feature IDs 343/344, recovered from the supplied Wolf APK.
- Do not estimate rower watts or publish truncated positional V1 data.
- Try normal USB claims first; permit a HID kernel-driver detach retry only when
  sysfs reports `usbhid`. Other/unknown ownership uses the manual recovery flow.
- Reject truncated FitPro2 packets and partial USB writes.
- Limit optional V1 reads to declared fields, interpret rower stroke count as
  unsigned, and ignore non-finite V2 events.
- Restrict V1 rower polls to declared rowing/session fields (13 payload bytes at
  most), avoid unrelated startup limits, and reject mismatched startup replies.
- Validate single and fragmented V1 reply framing, length, sequence and checksum
  before decoding. Clear live measurements after malformed polling replies.

No proprietary Wolf source is included. Hardware behavior on the customer's
Rower700 is not yet validated.

When changing the tracked patch, regenerate the ignored dependency before
building. Preserve any local vendor changes separately first. Verification for
alpha.4 applies the patch to the recovered exact pinned baseline and compares
every resulting source file with the sources used for the APK. The setup script
continues to leave an existing vendor directory untouched.
