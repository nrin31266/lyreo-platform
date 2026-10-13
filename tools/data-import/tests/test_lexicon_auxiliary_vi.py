from __future__ import annotations

import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from validate_lexicon_release import validate
from build_lexicon_release import build, sha256_file  # noqa: E402
from lexicon_vi_context import (  # noqa: E402
    EXAMPLE_CONTEXT_FIELDS,
    RECORD_CONTEXT_FIELDS,
    SENSE_CONTEXT_FIELDS,
    occurrence_id,
    stream_physical_jsonl,
)

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def en(word="arbitrary", pos="noun", etymology=1):
    return {"word": word, "lang_code": "en", "pos": pos, "etymology_number": etymology,
            "senses": [{"glosses": [f"meaning {etymology}"]}]}


def vi(word="arbitrary", pos="noun", glosses=None, **fields):
    return {"word": word, "lang_code": "en", "pos": pos,
            "senses": [{"glosses": ["nghĩa"] if glosses is None else glosses}], **fields}


class AuxiliaryViBuilderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.build_number = 0

    def build_records(self, en_records, vi_records=None, *, raw_vi_text=None, raw_vi_path=None):
        self.build_number += 1
        folder = self.root / str(self.build_number)
        folder.mkdir()
        raw_en = folder / "en.jsonl"
        raw_en.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in en_records))
        if raw_vi_path is None:
            raw_vi_path = folder / "vi.jsonl"
            raw_vi_path.write_text(raw_vi_text if raw_vi_text is not None else
                                   "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in vi_records or []))
        result = build(raw_en, folder / "release", folder / "release.tar.gz", "2026-10-13T00:00:00Z",
                       folder / "staging", raw_vi_path=raw_vi_path, enforce_checksum=False)
        release = folder / "release"
        rows = [json.loads(line) for line in (release / "translations.jsonl").read_text().splitlines()]
        report = json.loads((release / "validation_report.json").read_text())
        return rows, report, result

    def test_cardinality_is_general_and_known_pos_is_required(self):
        # Deliberately unrelated names: the rule must not encode golden headwords.
        for name in ("invented-a", "invented-b", "invented-c"):
            missing_pos = vi(name)
            del missing_pos["pos"]
            cases = [
                ([en(name)], vi(name), "ITEM_CANDIDATE", "UNIQUE_POS_ITEM", "noun"),
                ([en(name), en(name, etymology=2)], vi(name), "ENTRY_CANDIDATE", "AMBIGUOUS_POS_ITEMS", "noun"),
                ([en(name, "verb")], vi(name), "ENTRY_CANDIDATE", "NO_COMPATIBLE_POS_ITEM", "noun"),
                ([en(name, "unknown")], vi(name, "unknown"), "ENTRY_CANDIDATE", "UNKNOWN_POS", None),
                ([en(name)], vi(name, None), "ENTRY_CANDIDATE", "UNKNOWN_POS", None),
                ([en(name)], missing_pos, "ENTRY_CANDIDATE", "UNKNOWN_POS", None),
                ([en(name)], vi(name, " NOUN "), "ITEM_CANDIDATE", "UNIQUE_POS_ITEM", "noun"),
            ]
            for primary, auxiliary, status, reason, pos in cases:
                with self.subTest(name=name, reason=reason):
                    rows, _, _ = self.build_records(primary, [auxiliary])
                    row = rows[0]
                    self.assertEqual(row["link_status"], status)
                    self.assertEqual(row["reason"], "AUXILIARY_VI_" + reason)
                    self.assertEqual(row["source_pos"], pos)
                    self.assertEqual(row["item_id"] is not None, status == "ITEM_CANDIDATE")
                    self.assertIsNone(row["sense_id"])

    def test_multiple_blocks_and_duplicate_glosses_preserve_occurrences_and_context(self):
        records = [vi(pos="noun", glosses=["same", "same"], pos_title="block one"),
                   vi(pos="noun", glosses=["same"], pos_title="block two"),
                   vi(pos="verb", glosses=["same"], pos_title="verb block")]
        records[1]["senses"].append({"glosses": ["same"], "tags": ["rare"]})
        rows, report, _ = self.build_records([en(), en(etymology=2), en(pos="verb")], records)
        self.assertEqual(len(rows), 5)
        self.assertEqual(len({r["id"] for r in rows}), 5)
        nouns = [r for r in rows if r["source_pos"] == "noun"]
        self.assertEqual(len(nouns), 4)
        self.assertTrue(all(r["item_id"] is None for r in nouns))
        self.assertEqual(len({r["source_context"]["record_line"] for r in nouns}), 2)
        self.assertEqual({r["source_context"]["sense_ordinal"] for r in nouns}, {1, 2})
        self.assertEqual({r["source_context"]["gloss_ordinal"] for r in nouns}, {1, 2})
        self.assertIsNotNone(next(r for r in rows if r["source_pos"] == "verb")["item_id"])
        self.assertEqual(report["translation_source_reconciliation"]["viwiktionary_duplicate_glosses_deduped"], 0)

    def test_physical_ordinals_and_occurrence_unit_reconciliation(self):
        record = vi(glosses=["", "  ", " Ａ ", "B"])
        record["senses"].insert(0, {"glosses": []})
        text = "\n" + json.dumps({"word": "other", "lang_code": "fr"}) + "\n\n" + json.dumps(record) + "\n"
        # Two valid unmatched glosses count as two skipped occurrences, not one sense.
        text += json.dumps(vi("absent", glosses=["a", "b"])) + "\n"
        rows, report, result = self.build_records([en()], raw_vi_text=text)
        self.assertEqual({r["word_vi"] for r in rows}, {"A", "B"})
        contexts = [r["source_context"] for r in rows]
        self.assertTrue(all(c["record_line"] == 4 and c["sense_ordinal"] == 2 for c in contexts))
        self.assertEqual({c["gloss_ordinal"] for c in contexts}, {3, 4})
        raw_vi = Path(result["release"]).parent / "vi.jsonl"
        self.assertEqual({r["id"] for r in rows}, {occurrence_id(sha256_file(raw_vi), 4, 2, g) for g in (3, 4)})
        counts = report["translation_source_reconciliation"]
        self.assertEqual(counts["viwiktionary_gloss_occurrences_total"], 6)
        self.assertEqual(counts["viwiktionary_empty_gloss_occurrences"], 2)
        self.assertEqual(counts["viwiktionary_unmatched_entry_gloss_occurrences"], 2)
        self.assertEqual(counts["viwiktionary_senses_without_glosses"], 1)
        self.assertEqual(counts["viwiktionary_difference_unreconciled"], 0)

    def test_context_allowlist_preserves_nested_null_and_absent_values(self):
        record = vi(pos_title="Danh từ", categories=["omit"], forms=[{"form": "omit"}], notes=None)
        record["etymology_links"] = [["source", "link"]]
        sense = record["senses"][0]
        sense.update({"tags": None, "categories": ["en:domain"],
                      "form_of": [{"word": "other", "roman": None}],
                      "examples": [{"text": "Example", "translation": None,
                                    "ref": {"source": ["nested", None]}, "bold_text_offsets": [[0, 1]]},
                                   {"text": None, "roman": None}]})
        rows, report, result = self.build_records([en()], [record])
        self.assertEqual(validate(Path(result["release"]))["status"], "PASS")
        context = rows[0]["source_context"]
        self.assertEqual(set(context), {"record_line", "record_context", "sense_ordinal", "sense_context", "gloss_ordinal"})
        self.assertLessEqual(set(context["record_context"]), RECORD_CONTEXT_FIELDS)
        self.assertLessEqual(set(context["sense_context"]), SENSE_CONTEXT_FIELDS)
        self.assertIsNone(context["record_context"]["notes"])
        self.assertNotIn("raw_tags", context["record_context"])
        examples = context["sense_context"]["examples"]
        self.assertIsNone(examples[0]["translation"])
        self.assertEqual(examples[0]["ref"], {"source": ["nested", None]})
        self.assertNotIn("translation", examples[1])
        self.assertTrue(all(set(e) <= EXAMPLE_CONTEXT_FIELDS for e in examples))
        inventory = report["vi_source_field_inventory"]
        self.assertEqual(inventory["record"]["omitted"]["forms"], 1)
        self.assertEqual(inventory["example"]["omitted"]["bold_text_offsets"], 1)
        self.assertTrue(all(not v["unknown"] for v in inventory.values()))

    def test_unknown_raw_fields_fail_with_location_instead_of_copying_or_dropping(self):
        for level in ("record", "sense", "example"):
            record = vi()
            target = record if level == "record" else record["senses"][0]
            if level == "example":
                target["examples"] = [{"text": "example"}]
                target = target["examples"][0]
            target["new_semantic_field"] = ["unknown"]
            with self.subTest(level=level), self.assertRaisesRegex(ValueError, f"Unclassified VI {level} fields at line 1"):
                self.build_records([en()], [record])

    def test_malformed_preserved_context_fails_before_pass_report_or_archive(self):
        cases = [
            ("record", "pos", 0), ("record", "pos_title", {}),
            ("record", "notes", "wrong shape"), ("record", "tags", [None]),
            ("record", "raw_tags", [False]), ("record", "etymology_texts", [{}]),
            ("record", "etymology_links", [["only one"]]),
            ("record", "etymology_links", [["text", None]]),
            ("sense", "tags", "formal"), ("sense", "raw_tags", [1]),
            ("sense", "topics", [None]), ("sense", "categories", [{}]),
            ("sense", "form_of", ["word"]), ("sense", "form_of", [{}]),
            ("sense", "alt_of", [{"word": "other", "roman": 0}]),
            ("sense", "alt_of", [{"word": "other", "extra": "unclassified"}]),
            ("example", "text", {}), ("example", "translation", False),
            ("example", "roman", 0), ("example", "ref", 1),
        ]
        # Falsey invalid containers must not become [] through `value or []`.
        for level, fields in (("record", ("senses", "tags", "notes", "etymology_links")),
                              ("sense", ("glosses", "examples", "topics", "form_of"))):
            for field in fields:
                cases.extend((level, field, value) for value in ({}, "", False, 0))
        for level, field, value in cases:
            with self.subTest(level=level, field=field, value=value):
                record = vi()
                target = record if level == "record" else record["senses"][0]
                if level == "example":
                    target["examples"] = [{}]
                    target = target["examples"][0]
                target[field] = value
                with self.assertRaisesRegex(ValueError, rf"{field}.*line 1"):
                    self.build_records([en()], [record])
                output = self.root / str(self.build_number)
                self.assertFalse((output / "release.tar.gz").exists())
                self.assertFalse((output / "release" / "validation_report.json").exists())

    def test_nullable_empty_context_fields_keep_their_source_values(self):
        record = vi(pos_title=None, tags=[], raw_tags=None, notes=[],
                    etymology_texts=None, etymology_links=[])
        sense = record["senses"][0]
        sense.update(tags=[], raw_tags=None, topics=[], categories=None,
                     form_of=[], alt_of=None,
                     examples=[{"text": None, "translation": {}, "roman": [], "ref": None}])
        rows, _, result = self.build_records([en()], [record])
        self.assertEqual(rows[0]["source_context"]["record_context"],
                         {key: value for key, value in record.items() if key in RECORD_CONTEXT_FIELDS})
        self.assertEqual(rows[0]["source_context"]["sense_context"], sense)
        self.assertEqual(validate(Path(result["release"]),
                                  raw_vi=Path(result["release"]).parent / "vi.jsonl")["status"], "PASS")

    def test_target_reassignment_does_not_change_occurrence_ids(self):
        source = self.root / "stable-vi.jsonl"
        source.write_text(json.dumps(vi(), ensure_ascii=False) + "\n")
        first, _, _ = self.build_records([en()], raw_vi_path=source)
        second, _, _ = self.build_records([en(), en(etymology=2)], raw_vi_path=source)
        third, _, _ = self.build_records([en("arbitrary", "noun", 12)], raw_vi_path=source)
        self.assertEqual(first[0]["id"], second[0]["id"])
        self.assertEqual(first[0]["id"], third[0]["id"])
        self.assertIsNone(second[0]["item_id"])
        self.assertNotEqual(first[0]["item_id"], third[0]["item_id"])

    def test_collision_fails_without_replacing_a_source_occurrence(self):
        with mock.patch("build_lexicon_release.occurrence_id", return_value="collision"):
            with self.assertRaisesRegex(ValueError, "occurrence ID collision"):
                self.build_records([en()], [vi(glosses=["one", "two"])])

    def test_gzip_physical_lines_and_auxiliary_archive_reproducibility(self):
        source = self.root / "stable-vi.jsonl.gz"
        text = "\n" + json.dumps(vi(), ensure_ascii=False) + "\n"
        with source.open("wb") as output, gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=0) as stream:
            stream.write(text.encode())
        self.assertEqual(next(stream_physical_jsonl(source))[0], 2)
        first, _, result1 = self.build_records([en()], raw_vi_path=source)
        second, _, result2 = self.build_records([en()], raw_vi_path=source)
        self.assertEqual(first, second)
        self.assertEqual(result1["archive_sha256"], result2["archive_sha256"])
        self.assertEqual(result1["manifest_sha256"], result2["manifest_sha256"])

    def test_selected_raw_fixture_identity_matches_metadata(self):
        metadata = json.loads((FIXTURES / "lexicon_auxiliary_selected_raw_metadata.json").read_text())
        for source in metadata["fixtures"].values():
            path = FIXTURES / source["fixture"]
            self.assertEqual(sha256_file(path), source["fixture_sha256"])
            self.assertEqual(len(path.read_text().splitlines()), source["records"])

    def test_real_selected_raw_cases_preserve_uncertainty_and_singletons(self):
        primary = [json.loads(line) for line in (FIXTURES / "lexicon_auxiliary_en_selected_raw.jsonl").read_text().splitlines()]
        source = FIXTURES / "lexicon_auxiliary_vi_selected_raw.jsonl"
        rows, _, result = self.build_records(primary, raw_vi_path=source)
        release = Path(result["release"])
        entries = {e["id"]: e["identity_form"] for e in map(json.loads, (release / "entries.jsonl").read_text().splitlines())}
        items = list(map(json.loads, (release / "items.jsonl").read_text().splitlines()))
        compatible = {}
        for item in items:
            compatible.setdefault((item["entry_id"], item["pos"]), []).append(item["id"])
        self.assertEqual(set(entries.values()), {"bank", "seal", "bark", "lead", "fan", "tear", "match", "bass", "saw", "watch", "spring", "palm", "rock"})
        rows = [r for r in rows if r["source"] == "viwiktionary"]
        for row in rows:
            targets = compatible.get((row["entry_id"], row["source_pos"]), [])
            with self.subTest(word=entries[row["entry_id"]], pos=row["source_pos"], gloss=row["word_vi"]):
                self.assertEqual(row["item_id"], targets[0] if len(targets) == 1 else None)
                self.assertIsNone(row["sense_id"])
        for word in ("bank", "seal", "bark", "lead", "fan", "tear", "match", "bass", "palm", "rock"):
            noun_rows = [r for r in rows if entries[r["entry_id"]] == word and r["source_pos"] == "noun"]
            self.assertTrue(noun_rows)
            self.assertTrue(all(r["link_status"] == "ENTRY_CANDIDATE" and r["item_id"] is None for r in noun_rows))
        for word in ("watch", "spring"):
            noun_rows = [r for r in rows if entries[r["entry_id"]] == word and r["source_pos"] == "noun"]
            self.assertTrue(noun_rows)
            self.assertTrue(all(r["link_status"] == "ITEM_CANDIDATE" for r in noun_rows))
        saw = [r for r in rows if entries[r["entry_id"]] == "saw" and r["word_vi"].rstrip(".") == "Quá khứ của see"]
        self.assertEqual(len(saw), 2)
        self.assertNotEqual(saw[0]["id"], saw[1]["id"])


if __name__ == "__main__":
    unittest.main()
