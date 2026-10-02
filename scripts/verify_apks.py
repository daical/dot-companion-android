#!/usr/bin/env python3
"""Verify only this build's developer APKs with already installed SDK tools."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
tools = Path(sys.argv[1])
reports = root / "build/reports/apks"
reports.mkdir(parents=True, exist_ok=True)
results = {}
for module in ("mobile", "wear"):
    apk = root / module / f"build/outputs/apk/debug/{module}-debug.apk"
    signing = subprocess.run(
        [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    badging = subprocess.run(
        [str(tools / "aapt2"), "dump", "badging", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    (reports / f"{module}-signing.txt").write_text(signing, encoding="utf-8")
    (reports / f"{module}-package.txt").write_text(badging, encoding="utf-8")
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signing, re.MULTILINE)
    package = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if len(digests) != 1 or package is None or package.group(1) != "dev.dotcompanion.app":
        raise SystemExit(f"Unexpected signing or package identity in {module} developer APK")
    results[module] = {
        "package": package.group(1),
        "signerCertificateSha256": digests[0].lower(),
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }
if results["mobile"]["signerCertificateSha256"] != results["wear"]["signerCertificateSha256"]:
    raise SystemExit("Phone and watch developer APKs have different signing certificates")
(reports / "verified.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
print("Phone/watch APK signatures verified; application ID and signing certificate match.")
