from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from clean_import_common import ReleasePackage, scoped_id  # noqa: E402
from import_clean_grammar_toeic import (  # noqa: E402
    access_fields,
    asset_row,
    bank_catalog_id,
    initial_catalog_publication_status,
    item_row,
    media_object_key,
    verify_or_upload_media,
)
from import_clean_lexicon import dry_run_report as lexicon_dry_run_report  # noqa: E402
from import_clean_lexicon import entry_row  # noqa: E402


class CleanImporterTest(unittest.TestCase):
    def package(self, root: Path, *, domain: str, counts: dict[str, int]) -> ReleasePackage:
        manifest = {
            "package": {"domain": domain, "version": "1.0.0", "schema_version": "1.0.0"},
            "counts": counts,
        }
        return ReleasePackage(root, manifest, {"status": "PASS"}, "a" * 64, uuid.UUID(int=1))

    def test_lexicon_dry_run_includes_all_canonical_collections(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            counts = {
                "entries.jsonl": 3,
                "items.jsonl": 4,
                "senses.jsonl": 5,
                "forms.jsonl": 2,
                "pronunciations.jsonl": 1,
                "translations.jsonl": 0,
            }
            package = self.package(Path(temp_dir), domain="lexicon", counts=counts)
            report = lexicon_dry_run_report(package, 250)
            self.assertEqual(15, report["records_inserted_expected"])
            self.assertEqual(["lexicon_entry", "lexicon_item", "lexicon_sense", "lexicon_form",
                              "lexicon_pronunciation", "lexicon_translation"], report["hierarchy"])
            self.assertIn("translation coverage does not filter entries", " ".join(report["import_notes"]))

    def test_lexicon_entry_uses_case_preserving_lifetime_identity(self):
        package = self.package(Path("."), domain="lexicon", counts={})
        rows = {
            "Polish": {("en", "Polish"): uuid.UUID(int=11)},
            "polish": {("en", "polish"): uuid.UUID(int=12)},
        }
        source = {"id": "00000000-0000-0000-0000-000000000001", "language": "en",
                  "identity_form": "Polish", "display_form": "Polish", "lookup_form": "polish",
                  "entry_type": "WORD"}
        capital = entry_row(package, source, rows["Polish"])
        source_lower = dict(source, id="00000000-0000-0000-0000-000000000002",
                            identity_form="polish", display_form="polish")
        lower = entry_row(package, source_lower, rows["polish"])
        self.assertNotEqual(capital[2], lower[2])
        self.assertEqual("Polish", capital[4])
        self.assertEqual("polish", lower[4])
        self.assertEqual(scoped_id(package.release_id, "lexicon-entry", source["id"]), capital[0])

    def test_release_scoped_ids_are_stable_and_separate_versions(self):
        key = "00000000-0000-0000-0000-000000000001"
        first = scoped_id(uuid.UUID(int=1), "assessment-item", key)
        self.assertEqual(first, scoped_id(uuid.UUID(int=1), "assessment-item", key))
        self.assertNotEqual(first, scoped_id(uuid.UUID(int=2), "assessment-item", key))

    def test_unknown_catalogs_stage_draft_and_source_access_maps_to_capability(self):
        self.assertEqual("DRAFT", initial_catalog_publication_status())
        self.assertEqual(("PUBLIC", "", None), access_fields(None, "free", "grammar.advanced"))
        self.assertEqual(("FEATURE", "grammar.advanced", "grammar.advanced"),
                         access_fields(None, "premium", "grammar.advanced"))

    def test_grammar_bank_identity_uses_source_id_even_when_year_matches(self):
        self.assertNotEqual(
            bank_catalog_id("6d5f1a56-13fc-4030-8750-ace75540f669"),
            bank_catalog_id("c9b365d2-4035-40a0-be44-2380359266eb"),
        )

    def test_assessment_item_preserves_null_stem_and_source_only_fields(self):
        package = self.package(Path("."), domain="grammar-toeic", counts={})
        raw = {
            "id": "00000000-0000-0000-0000-000000000001", "kind": "MULTIPLE_CHOICE",
            "stem_en": None, "transcript_en": "complete source text", "options": [],
            "correct_option": "A", "source_only_fields": {"raw_field": [1, 2]},
            "annotations": {}, "source_domains": ["toeic"], "provenance": [],
        }
        mapped = item_row(package, raw)
        self.assertIsNone(mapped[4])
        self.assertEqual({"raw_field": [1, 2]}, mapped[-1].obj)

    def test_asset_mapping_keeps_package_hash_as_blob_and_provenance(self):
        package = self.package(Path("."), domain="grammar-toeic", counts={})
        raw = {"id": "b" * 64, "path": "media/images/b.png", "source_paths": ["raw/a.png"]}
        mapped = asset_row(package, raw)
        self.assertEqual("b" * 64, mapped[2])
        self.assertEqual("b" * 64, mapped[3])
        self.assertEqual(["raw/a.png"], mapped[-1].obj)

    def test_media_upload_is_content_addressed_and_deduplicated(self):
        class MissingObject(Exception):
            response = {"Error": {"Code": "404"}, "ResponseMetadata": {"HTTPStatusCode": 404}}

        class FakeS3:
            def __init__(self):
                self.objects = {}
                self.uploads = 0

            def head_object(self, *, Bucket, Key):
                try:
                    body, metadata, content_type = self.objects[(Bucket, Key)]
                except KeyError as exc:
                    raise MissingObject() from exc
                return {"ContentLength": len(body), "Metadata": metadata, "ContentType": content_type}

            def upload_file(self, filename, bucket, key, ExtraArgs):
                self.uploads += 1
                self.objects[(bucket, key)] = (
                    Path(filename).read_bytes(), ExtraArgs["Metadata"], ExtraArgs["ContentType"]
                )

        payload = b"canonical media bytes"
        checksum = hashlib.sha256(payload).hexdigest()
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "asset.bin"
            path.write_bytes(payload)
            client = FakeS3()
            key = media_object_key(checksum)
            self.assertEqual(f"media/sha256/{checksum[:2]}/{checksum}", key)
            self.assertTrue(verify_or_upload_media(client, "test", path, sha256=checksum,
                                                   size_bytes=len(payload), mime_type="application/octet-stream",
                                                   object_key=key))
            self.assertFalse(verify_or_upload_media(client, "test", path, sha256=checksum,
                                                    size_bytes=len(payload), mime_type="application/octet-stream",
                                                    object_key=key))
            self.assertEqual(1, client.uploads)

            path.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "changed after validation"):
                verify_or_upload_media(client, "test", path, sha256=checksum,
                                       size_bytes=len(payload), mime_type="application/octet-stream",
                                       object_key=key)


if __name__ == "__main__":
    unittest.main()
