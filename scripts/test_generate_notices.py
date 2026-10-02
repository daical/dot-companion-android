"""Regress missing, truncated, incorrectly sliced and falsely attributed notices."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from generate_notices import archive_notices, generate, pom_licenses


def zipped(entries):
    output = io.BytesIO()
    with ZipFile(output, "w") as archive:
        for name, value in entries.items():
            archive.writestr(name, value)
    return output.getvalue()


class NoticeGenerationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "docs").mkdir()
        for name in ("LICENSE", "NOTICE", "docs/THIRD_PARTY_NOTICES.md"):
            (self.root / name).write_text(f"Project fixture {name}\n", encoding="utf-8")

    def component(self, coordinate, contents, declared=True):
        name = coordinate.split(":")[1]
        archive = self.root / f"{name}.aar"
        archive.write_bytes(contents)
        pom = self.root / f"{name}.pom"
        licenses = "<licenses><license><name>Fixture license</name><url>https://example.invalid/license</url></license></licenses>" if declared else ""
        pom.write_text(f"<project xmlns='http://maven.apache.org/POM/4.0.0'>{licenses}</project>", encoding="utf-8")
        return {"coordinate": coordinate, "archive": str(archive), "pom": str(pom)}

    def test_google_ranges_use_bytes_and_preserve_complete_unicode_crlf_text(self):
        prefix = "🟣 preamble\n".encode("utf-8")
        text = "Copyright Ω\r\nPermission line\n".encode("utf-8")
        data = zipped({"third_party_licenses.json": json.dumps({"Fixture": {"start": len(prefix), "length": len(text)}}),
                       "third_party_licenses.txt": prefix + text + b"separator"})
        self.assertEqual([("Fixture", text.decode("utf-8"))], archive_notices(data))

    def test_missing_counterpart_invalid_bounds_and_split_utf8_fail(self):
        for entries in ({"third_party_licenses.json": "{}"},
                        {"third_party_licenses.txt": "notice"},
                        {"third_party_licenses.json": '{"Fixture":{"start":-1,"length":4}}', "third_party_licenses.txt": "abcd"},
                        {"third_party_licenses.json": '{"Fixture":{"start":0,"length":5}}', "third_party_licenses.txt": "abcd"},
                        {"third_party_licenses.json": '{"Fixture":{"start":true,"length":1}}', "third_party_licenses.txt": "abcd"},
                        {"third_party_licenses.json": '{"Fixture":{"start":1,"length":1}}', "third_party_licenses.txt": "Ω"}):
            with self.subTest(entries=entries), self.assertRaises((ValueError, UnicodeError)):
                archive_notices(zipped(entries))

    def test_nested_aar_jars_retain_notice_copyright_and_license_entries(self):
        data = zipped({"classes.jar": zipped({"META-INF/NOTICE": b"Full class notice\r\n"}),
                       "libs/example.jar": zipped({"COPYRIGHT.txt": b"Full nested copyright\n"}),
                       "META-INF/LICENSES.txt": b"Full archive licenses\n"})
        self.assertCountEqual([("META-INF/NOTICE", "Full class notice\r\n"),
                               ("COPYRIGHT.txt", "Full nested copyright\n"),
                               ("META-INF/LICENSES.txt", "Full archive licenses\n")], archive_notices(data))

    def test_dedup_keeps_different_notice_texts_and_all_runtime_attribution(self):
        shared = b"Copyright fixture shared\n"
        catalog = [self.component("test:a:1.0", zipped({"NOTICE": shared})),
                   self.component("test:b:1.0", zipped({"NOTICE": shared})),
                   self.component("test:c:1.0", zipped({"NOTICE": b"Different copyright fixture\n"}))]
        encoded, report = generate(catalog, self.root)
        entries = json.loads(encoded)["entries"]
        self.assertEqual(1, sum(e["text"] == shared.decode() for e in entries))
        self.assertTrue(any(e["text"] == "Different copyright fixture\n" for e in entries))
        self.assertEqual(3, len(report["artifacts"]))
        self.assertEqual(report["artifacts"][0]["archiveNoticeIds"], report["artifacts"][1]["archiveNoticeIds"])
        self.assertNotEqual(report["artifacts"][0]["archiveNoticeIds"], report["artifacts"][2]["archiveNoticeIds"])
        for coordinate in ("test:a:1.0", "test:b:1.0", "test:c:1.0"):
            self.assertTrue(any(e["title"] == coordinate and "Declared license: Fixture license" in e["text"] for e in entries))
        self.assertNotIn(str(self.root), encoded.decode())

    def test_unknown_coverage_fails_instead_of_assigning_an_assumed_license(self):
        catalog = [self.component("test:unknown:1.0", zipped({"classes.txt": b"No notices"}), declared=False)]
        with self.assertRaisesRegex(ValueError, "Unresolved license coverage: test:unknown:1.0"):
            generate(catalog, self.root)

    def test_multiple_runtime_archives_for_one_component_keep_all_notices(self):
        first = self.component("test:classifier:1.0", zipped({"NOTICE": b"First archive copyright\n"}))
        second_archive = self.root / "classifier-secondary.jar"
        second_archive.write_bytes(zipped({"NOTICE": b"Second archive copyright\n"}))
        second = {**first, "archive": str(second_archive)}
        encoded, report = generate([first, second], self.root)
        entries = json.loads(encoded)["entries"]
        self.assertEqual(1, sum(e["title"] == "test:classifier:1.0" for e in entries))
        self.assertTrue(any(e["text"] == "First archive copyright\n" for e in entries))
        self.assertTrue(any(e["text"] == "Second archive copyright\n" for e in entries))
        metadata = next(e["text"] for e in entries if e["title"] == "test:classifier:1.0")
        self.assertIn("classifier.aar", metadata)
        self.assertIn("classifier-secondary.jar", metadata)
        self.assertEqual(2, len(report["artifacts"]))
        self.assertNotEqual(report["artifacts"][0]["archiveNoticeIds"], report["artifacts"][1]["archiveNoticeIds"])

    def test_license_inheritance_retains_nearest_declaring_parent_provenance(self):
        child = self.component("test:child:1.0", zipped({"classes.txt": b"Runtime code fixture"}), declared=False)
        empty_parent = self.component("test:empty-parent:1.0", zipped({}), declared=False)
        parent = self.component("test:declaring-parent:1.0", zipped({}))
        child["parentPoms"] = [{"coordinate": item["coordinate"], "pom": item["pom"]} for item in (empty_parent, parent)]
        encoded, report = generate([child], self.root)
        self.assertEqual("test:declaring-parent:1.0", report["artifacts"][0]["licenseSourceCoordinate"])
        self.assertEqual("Fixture license", report["artifacts"][0]["declaredLicenses"][0]["name"])
        metadata = next(e["text"] for e in json.loads(encoded)["entries"] if e["title"] == "test:child:1.0")
        self.assertIn("License declaration inherited from POM: test:declaring-parent:1.0", metadata)

    def test_child_license_declaration_takes_precedence_over_parent(self):
        child = self.component("test:child:1.0", zipped({}))
        parent = self.component("test:parent:1.0", zipped({}), declared=False)
        child["parentPoms"] = [{"coordinate": parent["coordinate"], "pom": parent["pom"]}]
        _, report = generate([child], self.root)
        self.assertEqual("test:child:1.0", report["artifacts"][0]["licenseSourceCoordinate"])

    def test_pom_metadata_does_not_replace_google_full_text(self):
        text = b"Full original copyright and permission notice\n"
        catalog = [self.component("test:google-fixture:1.0", zipped({
            "third_party_licenses.json": json.dumps({"Upstream fixture": {"start": 0, "length": len(text)}}),
            "third_party_licenses.txt": text}))]
        encoded, _ = generate(catalog, self.root)
        self.assertTrue(any(e["text"].encode() == text for e in json.loads(encoded)["entries"]))

    def test_invalid_license_metadata_is_reported(self):
        pom = self.root / "bad.pom"
        pom.write_text("<project><licenses><license><name>Unknown</name><url>file:///private</url></license></licenses></project>")
        with self.assertRaisesRegex(ValueError, "Invalid POM license declaration"):
            pom_licenses(pom)

    def test_exact_output_is_deterministic_and_project_license_is_retained(self):
        (self.root / "LICENSE").write_bytes(b"Project complete license\r\n")
        catalog = [self.component("test:one:1.0", zipped({"LICENSE": b"Dependency license\n"}))]
        first, report = generate(catalog, self.root)
        self.assertEqual(first, generate(catalog, self.root)[0])
        self.assertTrue(any(e["text"] == "Project complete license\r\n" for e in json.loads(first)["entries"]))
        self.assertEqual(len(json.loads(first)["entries"]), report["entryCount"])


if __name__ == "__main__":
    unittest.main()
