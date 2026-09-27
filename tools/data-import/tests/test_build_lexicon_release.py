from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from build_lexicon_release import (  # noqa: E402
    build,
    determine_entry_type,
    identity_normalization,
    lookup_normalization,
)
from validate_lexicon_release import validate  # noqa: E402


class LexiconBuilderTest(unittest.TestCase):
    def setUp(self):
        self.fixtures_dir = Path(__file__).resolve().parent / "fixtures"
        self.sample_jsonl = self.fixtures_dir / "lexicon_sample.jsonl"
        self.assertTrue(self.sample_jsonl.is_file(), f"Missing fixture: {self.sample_jsonl}")

    def test_wrong_unicode_database_is_rejected_before_build(self):
        with tempfile.TemporaryDirectory() as temp_dir, mock.patch(
            "build_lexicon_release.unicodedata.unidata_version", "16.0.0"
        ):
            temp = Path(temp_dir)
            with self.assertRaisesRegex(ValueError, "Unicode database 15.0.0 is required"):
                build(
                    raw_en_path=self.sample_jsonl,
                    output_dir=temp / "release",
                    archive_path=temp / "release.tar.gz",
                    generated_at="2026-09-26T00:00:00Z",
                    staging_dir=temp / "staging",
                    enforce_checksum=False,
                )
            self.assertFalse((temp / "release").exists())

    def test_normalization_and_entry_type_classification(self):
        # Identity vs Lookup normalization
        self.assertEqual(identity_normalization(" Polish "), "Polish")
        self.assertEqual(identity_normalization(" polish "), "polish")
        self.assertEqual(lookup_normalization(" Polish "), "polish")
        self.assertEqual(lookup_normalization(" polish "), "polish")

        # Entry type rules
        self.assertEqual(determine_entry_type({"word": "cat", "pos": "noun"}), "WORD")
        self.assertEqual(determine_entry_type({"word": "in spite of", "pos": "prep"}), "PHRASE")
        self.assertEqual(
            determine_entry_type({"word": "take off", "pos": "verb", "tags": ["phrasal verbs"]}),
            "PHRASAL_VERB",
        )
        self.assertEqual(
            determine_entry_type({"word": "kick the bucket", "pos": "verb", "tags": ["idiomatic"]}),
            "IDIOM",
        )
        self.assertEqual(
            determine_entry_type({"word": "heavy rain", "pos": "noun", "categories": ["English collocations"]}),
            "COLLOCATION",
        )
        # Fallback multi-word vs single word without tags
        self.assertEqual(determine_entry_type({"word": "hello world"}), "PHRASE")
        self.assertEqual(determine_entry_type({"word": "dog"}), "WORD")

    def test_build_sample_release_end_to_end(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            out_dir = temp / "lexicon-1.0.0"
            archive_path = temp / "lexicon-1.0.0.tar.gz"
            staging_dir = temp / "staging"
            timestamp = "2026-09-26T00:00:00Z"

            result = build(
                raw_en_path=self.sample_jsonl,
                output_dir=out_dir,
                archive_path=archive_path,
                generated_at=timestamp,
                staging_dir=staging_dir,
                enforce_checksum=False,
            )

            # Check output files
            self.assertTrue(archive_path.is_file())
            self.assertTrue((out_dir / "manifest.json").is_file())
            self.assertTrue((out_dir / "validation_report.json").is_file())
            self.assertTrue((out_dir / "LICENSE").is_file())

            # Read entries
            entries = [json.loads(line) for line in (out_dir / "entries.jsonl").open()]
            entries_by_word = {e["identity_form"]: e for e in entries}

            # Polish vs polish: separate entries
            self.assertIn("Polish", entries_by_word)
            self.assertIn("polish", entries_by_word)
            self.assertNotEqual(entries_by_word["Polish"]["id"], entries_by_word["polish"]["id"])
            self.assertEqual(entries_by_word["Polish"]["lookup_form"], "polish")
            self.assertEqual(entries_by_word["polish"]["lookup_form"], "polish")

            # bank entry
            self.assertIn("bank", entries_by_word)
            bank_entry = entries_by_word["bank"]

            # Read items
            items = [json.loads(line) for line in (out_dir / "items.jsonl").open()]
            bank_items = [i for i in items if i["entry_id"] == bank_entry["id"]]
            # bank has 3 items: noun etym 1, verb etym 1, noun etym 2
            self.assertEqual(len(bank_items), 3)

            # Read senses
            senses = [json.loads(line) for line in (out_dir / "senses.jsonl").open()]
            # All senses must have valid item_id and entry_id
            for s in senses:
                self.assertIsNotNone(s.get("item_id"))
                self.assertIsNotNone(s.get("entry_id"))

            # Duplicate gloss test in bank noun etym 1
            noun_etym1_item = next(i for i in bank_items if i["pos"] == "noun" and i["etymology_number"] == 1)
            noun_etym1_senses = [s for s in senses if s["item_id"] == noun_etym1_item["id"]]
            self.assertEqual(len(noun_etym1_senses), 3)
            senses_by_ordinal = {s["ordinal"]: s for s in noun_etym1_senses}

            # Senses 2 and 3 have duplicate glosses, but their IDs must be unique
            self.assertEqual(senses_by_ordinal[2]["definition_en"], senses_by_ordinal[3]["definition_en"])
            self.assertNotEqual(senses_by_ordinal[2]["id"], senses_by_ordinal[3]["id"])

            # Unique translation linking test:
            # Sense 1: "An institution..." -> matched "institution" -> AVAILABLE
            self.assertEqual(senses_by_ordinal[1]["translation_status"], "AVAILABLE")
            self.assertIn("ngân hàng", senses_by_ordinal[1]["translation_vi"])
            self.assertIn("nhà băng", senses_by_ordinal[1]["translation_vi"])
            self.assertEqual(senses_by_ordinal[1]["matched_qualifier"], "institution")

            # Sense 2 & 3: Ambiguous translation ("chi nhánh" matched 2 senses) -> senses are MISSING
            self.assertEqual(senses_by_ordinal[2]["translation_status"], "MISSING")
            self.assertEqual(senses_by_ordinal[3]["translation_status"], "MISSING")

            # Read translations.jsonl
            translations = [json.loads(line) for line in (out_dir / "translations.jsonl").open()]
            trans_by_word = {t["word_vi"]: t for t in translations}

            self.assertIn("ngân hàng", trans_by_word)
            self.assertEqual(trans_by_word["ngân hàng"]["link_status"], "QUALIFIER_MATCH")
            self.assertEqual(trans_by_word["ngân hàng"]["sense_id"], senses_by_ordinal[1]["id"])

            self.assertIn("chi nhánh", trans_by_word)
            self.assertEqual(trans_by_word["chi nhánh"]["link_status"], "ITEM_CANDIDATE")
            self.assertIn("AMBIGUOUS_MULTI_SENSE_MATCH", trans_by_word["chi nhánh"]["reason"])

            self.assertIn("vật lạ", trans_by_word)
            self.assertEqual(trans_by_word["vật lạ"]["link_status"], "ITEM_CANDIDATE")
            self.assertEqual("NO_EXACT_SENSE_MATCH", trans_by_word["vật lạ"]["reason"])

            for t in translations:
                self.assertIsNotNone(t.get("entry_id"))

            # Read forms: forms with same surface across items/tags have distinct IDs
            forms = [json.loads(line) for line in (out_dir / "forms.jsonl").open()]
            bank_forms = [f for f in forms if f["form"] == "banks"]
            self.assertEqual(len(bank_forms), 3)
            # All 3 have unique IDs
            form_ids = {f["id"] for f in bank_forms}
            self.assertEqual(len(form_ids), 3)

            # Read pronunciations: multiple sounds (US and UK)
            prons = [json.loads(line) for line in (out_dir / "pronunciations.jsonl").open()]
            bank_prons = [p for p in prons if p["entry_id"] == bank_entry["id"]]
            self.assertEqual(len(bank_prons), 2)
            accents = {p["accent"] for p in bank_prons}
            self.assertEqual(accents, {"US", "UK"})

            # Validate the built release using validate_lexicon_release
            val_res = validate(out_dir, archive=archive_path)
            self.assertEqual(val_res["status"], "PASS")

    def test_deterministic_reproducibility(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            out1 = temp / "run1"
            arc1 = temp / "run1.tar.gz"
            out2 = temp / "run2"
            arc2 = temp / "run2.tar.gz"
            timestamp = "2026-09-26T12:00:00Z"

            res1 = build(
                raw_en_path=self.sample_jsonl,
                output_dir=out1,
                archive_path=arc1,
                generated_at=timestamp,
                staging_dir=temp / "stg1",
                enforce_checksum=False,
            )
            res2 = build(
                raw_en_path=self.sample_jsonl,
                output_dir=out2,
                archive_path=arc2,
                generated_at=timestamp,
                staging_dir=temp / "stg2",
                enforce_checksum=False,
            )

            # Both archives must have the exact same SHA-256
            self.assertEqual(res1["archive_sha256"], res2["archive_sha256"])
            self.assertEqual(res1["manifest_sha256"], res2["manifest_sha256"])
            self.assertEqual(arc1.read_bytes(), arc2.read_bytes())


if __name__ == "__main__":
    unittest.main()
