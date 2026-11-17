#!/usr/bin/env python3
"""Package only NordicRower's signed app, public guides, license and collector."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
# Existing alpha.2–alpha.4 identity: retain upgrades and the user's stored diagnostics.
SIGNER = "c179debd870edfe6ed0602bea2bb929b87160a97f51013f537c77f0cb4874d76"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", default="0.1.0")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/release")
    parser.add_argument("--build-tools", type=Path,
                        default=Path(os.environ.get("ANDROID_HOME", "")) / "build-tools/35.0.0")
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        parser.error("Version must be major.minor.patch")
    verified = subprocess.check_output(
        [str(args.build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)], text=True)
    signers = re.findall(r"Signer #\d+ certificate SHA-256 digest: (\w+)", verified)
    if signers != [SIGNER]:
        parser.error("APK must use the existing NordicRower signing identity; refusing an incompatible upgrade")
    badging = subprocess.check_output([str(args.build_tools / "aapt"), "dump", "badging", str(args.apk)], text=True)
    if not re.search(r"package: name='com\.nordicrower\.app'.*versionName='" + re.escape(args.version) + "'", badging):
        parser.error("APK package/version mismatch")
    if "application-debuggable" in badging:
        parser.error("Public package requires the non-debuggable release APK")

    files = {
        "NordicRower.apk": args.apk.read_bytes(),
        "README.md": (ROOT / "docs/install.md").read_bytes(),
        "LIRE-MOI.md": (ROOT / "docs/install-fr.md").read_bytes(),
        "RELEASE-NOTES.md": (ROOT / "docs/release-notes.md").read_bytes(),
        "collect-nordicrower-diagnostics.ps1": (ROOT.parent / "experimental/collect-nordicrower-diagnostics.ps1").read_bytes(),
        "Hyperborea-LICENSE.txt": (ROOT / "vendor/hyperborea/LICENSE").read_bytes(),
    }
    checksums = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items()))
    files["SHA256SUMS.txt"] = checksums.encode()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "NordicRower.apk").write_bytes(files["NordicRower.apk"])
    archive = args.output_dir / f"NordicRower-{args.version}.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(f"NordicRower/{name}", date_time=(2026, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(info, data)
    external = [args.output_dir / "NordicRower.apk", archive]
    (args.output_dir / "SHA256SUMS.txt").write_text("".join(
        f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}\n" for file in external))
    print(f"Verified signer and packaged NordicRower {args.version}: {archive}")


if __name__ == "__main__":
    main()
