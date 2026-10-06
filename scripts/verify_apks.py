#!/usr/bin/env python3
"""Verify only this build's developer APKs with already installed SDK tools."""
import hashlib
import argparse
import json
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile

from generate_notices import strict_json

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("build_tools", type=Path)
parser.add_argument("--side-by-side", action="store_true", help="Verify the separate .dev phone/watch pair")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
tools = args.build_tools
expected_package = "dev.dotcompanion.app.dev" if args.side_by_side else "dev.dotcompanion.app"
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
    if len(digests) != 1 or package is None or package.group(1) != expected_package:
        raise SystemExit(f"Unexpected signing or package identity in {module} developer APK")
    with ZipFile(apk) as archive:
        notice_bytes = archive.read("assets/licenses/notices.json")
    notice_asset = strict_json(notice_bytes.decode("utf-8", errors="strict"))
    coverage = strict_json((root / module / "build/reports/licenses/debug/coverage.json").read_text(encoding="utf-8"))
    entries = notice_asset["entries"]
    if (notice_asset["schemaVersion"] != 1 or len(entries) != coverage["entryCount"]
            or hashlib.sha256(notice_bytes).hexdigest() != coverage["assetSha256"]):
        raise SystemExit(f"APK notices differ from this build's coverage in {module}")
    entry_ids = {entry["id"] for entry in entries}
    if len(entry_ids) != len(entries) or any(hashlib.sha256(entry["text"].encode("utf-8")).hexdigest() != entry["id"] for entry in entries):
        raise SystemExit(f"Invalid APK notice identity in {module}")
    for filename in ("LICENSE", "NOTICE", "docs/THIRD_PARTY_NOTICES.md"):
        expected = (root / filename).read_bytes().decode("utf-8", errors="strict")
        if not any(entry["text"] == expected for entry in entries):
            raise SystemExit(f"APK missing complete project notice {filename} in {module}")
    for artifact in coverage["artifacts"]:
        if not set(artifact["archiveNoticeIds"]).issubset(entry_ids):
            raise SystemExit(f"APK missing upstream archive notices in {module}")
        if not any(entry["title"] == artifact["coordinate"] for entry in entries):
            raise SystemExit(f"APK missing runtime dependency attribution in {module}")
    results[module] = {
        "package": package.group(1),
        "signerCertificateSha256": digests[0].lower(),
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "noticeAssetSha256": coverage["assetSha256"],
        "noticeEntryCount": len(entries),
        "runtimeArtifactCount": len(coverage["artifacts"]),
    }
if results["mobile"]["signerCertificateSha256"] != results["wear"]["signerCertificateSha256"]:
    raise SystemExit("Phone and watch developer APKs have different signing certificates")
(reports / "verified.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
print("Phone/watch APK signatures and complete bundled notices verified; application ID and signing certificate match.")
