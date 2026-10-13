from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from clean_import_common import ReleasePackage, insert_values, scoped_id  # noqa: E402
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
from import_clean_lexicon import translation_row  # noqa: E402
from import_clean_lexicon import reconcile_translation_provenance  # noqa: E402


class CleanImporterTest(unittest.TestCase):
    def test_wide_translation_batches_respect_postgresql_parameter_limit(self):
        class Cursor:
            received = []

            def __enter__(self):
                return self

            def __exit__(self, *args):
                pass

            def execute(self, sql, params):
                self_limit = 65_535
                if len(params) > self_limit:
                    raise ValueError("PostgreSQL parameter limit exceeded")
                self.received.extend(params[::15])
                self.rowcount = len(params) // 15

        cursor = Cursor()
        database = SimpleNamespace(cursor=lambda: cursor)
        rows = [(i,) + (None,) * 14 for i in range(5000)]
        self.assertEqual(5000, insert_values(database, "lexicon_translation", tuple(f"c{i}" for i in range(15)), rows))
        self.assertEqual(list(range(5000)), cursor.received)

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

    def test_lexicon_translation_preserves_internal_context_without_inference(self):
        package = self.package(Path("."), domain="lexicon", counts={})
        package.manifest["package"]["schema_version"] = "1.1.0"
        context = {"record_line": 3, "sense_ordinal": 1, "gloss_ordinal": 2,
                   "record_context": {"pos": "noun", "pos_title": "Danh từ"},
                   "sense_context": {"glosses": ["Bờ.", "Bờ."],
                                     "examples": [{"text": "bank", "ref": {"edition": None}}]}}
        row = {"id": str(uuid.UUID(int=11)), "entry_id": str(uuid.UUID(int=12)),
               "item_id": None, "sense_id": None, "word_vi": "Bờ.", "source": "viwiktionary",
               "source_scope": "viwiktionary_gloss", "source_sense_qualifier": None,
               "link_status": "ENTRY_CANDIDATE", "reason": "AUXILIARY_VI_AMBIGUOUS_POS_ITEMS",
               "tags": [], "source_pos": "noun", "source_context": context}
        mapped = translation_row(package, row)
        self.assertEqual(scoped_id(package.release_id, "lexicon-translation", row["id"]), mapped[0])
        self.assertIsNone(mapped[3])
        self.assertIsNone(mapped[4])
        self.assertEqual("ENTRY_CANDIDATE", mapped[10])
        self.assertEqual("noun", mapped[-2])
        self.assertEqual(context, mapped[-1].obj)

    def test_lexicon_legacy_translation_provenance_stays_sql_null(self):
        package = self.package(Path("."), domain="lexicon", counts={})
        row = {"id": str(uuid.UUID(int=11)), "entry_id": str(uuid.UUID(int=12)),
               "item_id": str(uuid.UUID(int=13)), "sense_id": None, "word_vi": "một nghĩa",
               "source": "enwiktionary", "source_scope": "item", "link_status": "ITEM_CANDIDATE",
               "reason": "NO_SENSE_QUALIFIER", "tags": []}
        mapped = translation_row(package, row)
        self.assertIsNone(mapped[-2])
        self.assertIsNone(mapped[-1])

    def test_lexicon_converter_rejects_unsupported_schema(self):
        package = self.package(Path("."), domain="lexicon", counts={})
        package.manifest["package"]["schema_version"] = "9.0.0"
        with self.assertRaisesRegex(ValueError, "Unsupported.*schema"):
            translation_row(package, {})

    def test_lexicon_reconciliation_detects_persisted_provenance_drift(self):
        class Database:
            def __init__(self, rows):
                self.rows = rows

            def execute(self, query, params):
                return [row for row in self.rows if row[0] in params[1]]

        with tempfile.TemporaryDirectory() as directory:
            package = self.package(Path(directory), domain="lexicon", counts={"translations.jsonl": 1})
            package.manifest["package"]["schema_version"] = "1.1.0"
            key = uuid.UUID(int=11)
            context = {"sense_context": {"glosses": ["Bờ."], "examples": [{"ref": None}]}}
            row = {"id": str(key), "source_pos": "noun", "source_context": context}
            (package.root / "translations.jsonl").write_text(json.dumps(row) + "\n")
            self.assertEqual({"rows_checked": 1, "mismatch_count": 0},
                             reconcile_translation_provenance(Database([(key, "noun", context)]), package, 1))
            for persisted in ([], [(key, "verb", context)], [(key, "noun", {})]):
                with self.subTest(persisted=persisted), self.assertRaisesRegex(ValueError, "provenance reconciliation mismatch"):
                    reconcile_translation_provenance(Database(persisted), package, 1)
            package.manifest["package"]["schema_version"] = "1.0.0"
            self.assertEqual(1, reconcile_translation_provenance(Database([(key, None, None)]), package, 1)["rows_checked"])
            with self.assertRaisesRegex(ValueError, "provenance reconciliation mismatch"):
                reconcile_translation_provenance(Database([(key, "noun", context)]), package, 1)

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
