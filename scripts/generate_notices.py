#!/usr/bin/env python3
"""Bundle offline notices from the resolved runtime graph; never fetch license URLs."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
from zipfile import ZipFile

MAX_TEXT = 4 * 1024 * 1024
MAX_ASSET = 16 * 1024 * 1024
MAX_ENTRIES = 1024
NOTICE_NAME = re.compile(r"^(?:LICENSE|NOTICE|COPYING|COPYRIGHT).*$", re.I)


def strict_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate JSON field")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs)


def archive_notices(data):
    """Keep Google's byte ranges and ordinary archive notices without rewriting."""
    notices = []
    with ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive entry")
        google_index = "third_party_licenses.json"
        google_text = "third_party_licenses.txt"
        if (google_index in names) != (google_text in names):
            raise ValueError("Incomplete Google notice pair")
        if google_index in names:
            index = strict_json(archive.read(google_index).decode("utf-8", errors="strict"))
            text = archive.read(google_text)
            if not isinstance(index, dict) or not index:
                raise ValueError("Invalid Google notice index")
            for title, bounds in index.items():
                if (not isinstance(bounds, dict) or set(bounds) != {"start", "length"}
                        or type(bounds["start"]) is not int or type(bounds["length"]) is not int):
                    raise ValueError("Invalid Google notice bounds")
                start, length = bounds["start"], bounds["length"]
                if start < 0 or length <= 0 or start + length > len(text):
                    raise ValueError("Google notice range outside text")
                notices.append((title, text[start:start + length].decode("utf-8", errors="strict")))
        for name in sorted(names):
            if NOTICE_NAME.fullmatch(name.rsplit("/", 1)[-1]):
                notices.append((name, archive.read(name).decode("utf-8", errors="strict")))
            elif name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                notices.extend(archive_notices(archive.read(name)))
    return notices


def pom_licenses(path):
    root = ET.fromstring(Path(path).read_bytes())
    declarations = []
    licenses = root.find("{*}licenses")
    if licenses is not None:
        for entry in licenses.findall("{*}license"):
            name = (entry.findtext("{*}name") or "").strip()
            url = (entry.findtext("{*}url") or "").strip()
            parsed = urlsplit(url)
            if not name or parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError("Invalid POM license declaration")
            declarations.append({"name": name, "url": url})
    return declarations


def generate(catalog, source_root):
    entries = {}
    coverage = []

    def add(title, text):
        if (not isinstance(title, str) or not title.strip() or len(title.encode("utf-8")) > 1024
                or any(ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in title)):
            raise ValueError("Invalid notice title")
        if not isinstance(text, str) or not text.strip() or "\0" in text or len(text.encode("utf-8")) > MAX_TEXT:
            raise ValueError("Invalid or oversized notice text")
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        entries.setdefault(digest, {"id": digest, "title": title, "text": text})
        return digest

    for filename, title in (("LICENSE", "Dot Companion — Apache License 2.0"),
                            ("NOTICE", "Dot Companion — attribution and affiliation"),
                            ("docs/THIRD_PARTY_NOTICES.md", "Project dependency notices")):
        add(title, (Path(source_root) / filename).read_bytes().decode("utf-8", errors="strict"))
    if not isinstance(catalog, list) or not catalog:
        raise ValueError("Runtime artifact catalog is empty")
    descriptions = {}
    for component in sorted(catalog, key=lambda item: (item["coordinate"], Path(item["archive"]).name)):
        coordinate = component["coordinate"]
        if not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+", coordinate):
            raise ValueError(f"Invalid runtime coordinate: {coordinate}")
        data = Path(component["archive"]).read_bytes()
        archive_hash = hashlib.sha256(data).hexdigest()
        notices = archive_notices(data)
        licenses = pom_licenses(component["pom"])
        if not licenses and not notices:
            raise ValueError(f"Unresolved license coverage: {coordinate}")
        notice_ids = [add(title, text) for title, text in notices]
        description = f"Runtime artifact: {coordinate}\nArchive: {Path(component['archive']).name}\nArchive SHA-256: {archive_hash}\n\n"
        for license_info in licenses:
            description += f"Declared license: {license_info['name']}\nLicense URL: {license_info['url']}\n\n"
        if not licenses:
            description += "No license declared in POM metadata; complete archive notices are bundled below.\n\n"
        for (title, _), notice_id in zip(notices, notice_ids):
            description += f"Bundled upstream notice: {title}\nNotice ID: {notice_id}\n\n"
        if not notices:
            description += "This archive contains no separate notice text. Its declared license metadata is retained; the bundled project Apache text does not relicense dependencies.\n"
        descriptions.setdefault(coordinate, []).append(description)
        coverage.append({"coordinate": coordinate, "archive": Path(component["archive"]).name, "archiveSha256": archive_hash,
                         "declaredLicenses": licenses, "archiveNoticeIds": notice_ids})
    # A Maven component can publish several runtime artifacts/classifiers. Keep
    # every archive and notice in its one dependency attribution entry.
    for coordinate, descriptions_for_coordinate in descriptions.items():
        add(coordinate, "\n".join(descriptions_for_coordinate))
    result = {"schemaVersion": 1, "entries": sorted(entries.values(), key=lambda entry: (entry["title"].casefold(), entry["id"]))}
    encoded = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(entries) > MAX_ENTRIES or len(encoded) > MAX_ASSET:
        raise ValueError("Notice asset exceeds native viewer limits")
    return encoded, {"schemaVersion": 1, "assetSha256": hashlib.sha256(encoded).hexdigest(),
                     "entryCount": len(entries), "artifacts": coverage}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    data, report = generate(strict_json(args.catalog.read_text(encoding="utf-8")), args.source_root)
    output = args.output / "licenses/notices.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Bundled {report['entryCount']} notice entries from {len(report['artifacts'])} resolved runtime artifacts.")


if __name__ == "__main__":
    main()
