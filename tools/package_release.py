"""Verify an existing signed APK and prepare versioned GitHub Release assets.

Run after the Gradle release checks. Requires Python 3.9+, JDK 17 and Android SDK.
Signing credentials are never read by this script.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
CERTIFICATE_SHA256 = "5f3d35c836f1b7c2c5bc14a6e7ecac6b9ce74fb6c83d2427998012d822b57840"


def run(*command):
    return subprocess.run(command, cwd=ROOT, check=True, capture_output=True,
                          text=True, encoding="utf-8", errors="replace").stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME")
                        or os.environ.get("ANDROID_SDK_ROOT"), help="Android SDK directory")
    args = parser.parse_args()
    if not args.sdk:
        parser.error("Set ANDROID_HOME or pass --sdk")
    if run("git", "status", "--porcelain").strip():
        raise ValueError("Commit all source changes before packaging a release")
    commit = run("git", "rev-parse", "HEAD").strip()
    properties = dict(re.findall(r"^(rillVersion\w+)=(.+)$",
                                 (ROOT / "gradle.properties").read_text(), re.MULTILINE))
    version = properties["rillVersionName"].strip()
    version_code = int(properties["rillVersionCode"].strip())
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError("Invalid release version")
    build_tools = Path(args.sdk).resolve() / "build-tools" / "34.0.0"
    windows = os.name == "nt"
    signer = build_tools / ("apksigner.bat" if windows else "apksigner")
    aapt = build_tools / ("aapt.exe" if windows else "aapt")
    zipalign = build_tools / ("zipalign.exe" if windows else "zipalign")
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    certificate = run(str(signer), "verify", "--verbose", "--print-certs", str(apk))
    digest_match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", certificate)
    if not digest_match or digest_match[1].lower() != CERTIFICATE_SHA256:
        raise ValueError("Release certificate differs from the established Rill signing key")
    run(str(zipalign), "-c", "4", str(apk))
    badging = run(str(aapt), "dump", "badging", str(apk))
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
                        badging, re.MULTILINE)
    if not package or package.groups() != ("app.rill.android", str(version_code), version):
        raise ValueError("APK package/version differs from gradle.properties")
    if "application-debuggable" in badging:
        raise ValueError("Refusing to distribute a debuggable APK")
    apk_sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    output = ROOT / "app/build/outputs/releases" / version
    output.mkdir(parents=True, exist_ok=True)
    destination = output / f"Rill-{version}.apk"
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() != apk_sha256:
        raise FileExistsError(f"A different APK already exists for {version}")
    shutil.copyfile(apk, destination)
    (output / "SHA256SUMS.txt").write_text(f"{apk_sha256}  {destination.name}\n", encoding="utf-8")
    verification = {
        "version": version, "version_code": version_code, "package": package[1],
        "source_commit": commit, "apk_sha256": apk_sha256,
        "certificate_sha256": CERTIFICATE_SHA256, "signature_verified": True,
        "zip_alignment_verified": True, "debuggable": False, "build_tools": "34.0.0",
        "verified_at_utc": datetime.now(timezone.utc).isoformat(),
    }
    (output / "verification.json").write_text(
        json.dumps(verification, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Verified {destination.name}\nSHA-256 {apk_sha256}\nAssets: {output}")


if __name__ == "__main__":
    main()
