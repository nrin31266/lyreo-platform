from __future__ import annotations

import contextlib
import io
import json
import tarfile
import tempfile
import unittest
import uuid
from collections import Counter
from pathlib import Path

from build_lexicon_release import build
from validate_lexicon_release import validate
from test_validate_lexicon_release import update_manifest_file_checksum


class AuxiliaryVietnameseValidatorTest(unittest.TestCase):
    def release(self, root: Path, *, auxiliary: bool = True) -> tuple[Path, Path]:
        raw_vi = root / "vi.jsonl"
        record = {
            "word": "bank", "lang_code": "en", "pos": "noun", "pos_title": "Danh từ",
            "senses": [{"glosses": ["  Bờ sông.  ", "Bờ sông."], "tags": ["figuratively"],
                        "examples": [{"text": "a bank", "translation": None,
                                      "ref": {"source": {"edition": None}},
                                      "bold_text_offsets": [[2, 6]]}]}],
        }
        raw_vi.write_text("\n" + json.dumps({"word": "bank", "lang_code": "vi"}) + "\n"
                          + json.dumps(record, ensure_ascii=False) + "\n", encoding="utf-8")
        release = root / "release"
        with contextlib.redirect_stdout(io.StringIO()):
            build(raw_en_path=Path(__file__).parent / "fixtures/lexicon_sample.jsonl",
                  raw_vi_path=raw_vi if auxiliary else None, output_dir=release,
                  archive_path=root / "release.tar.gz", staging_dir=root / "stage",
                  generated_at="2026-09-26T00:00:00Z", enforce_checksum=False)
        return release, raw_vi

    def translations(self, release: Path) -> list[dict]:
        return [json.loads(line) for line in (release / "translations.jsonl").read_text().splitlines()]

    def write_translations(self, release: Path, rows: list[dict]) -> None:
        (release / "translations.jsonl").write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
        update_manifest_file_checksum(release, "translations.jsonl")

    def repair_occurrence_counts(self, release: Path, rows: list[dict]) -> None:
        self.write_translations(release, rows)
        manifest_path = release / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["counts"]["translations.jsonl"] = len(rows)
        for file in manifest["files"]:
            if file["path"] == "translations.jsonl":
                file["records"] = len(rows)
        manifest_path.write_text(json.dumps(manifest))
        path = release / "validation_report.json"
        report = json.loads(path.read_text())
        vi = [row for row in rows if row["source"] == "viwiktionary"]
        report["counts"]["translations.jsonl"] = len(rows)
        accounting = report["translation_source_reconciliation"]
        accounting.update(total_translations_emitted=len(rows), viwiktionary_emitted_records=len(vi),
                          viwiktionary_gloss_occurrences_total=len(vi),
                          viwiktionary_emitted_by_reason=dict(Counter(row["reason"] for row in vi)))
        path.write_text(json.dumps(report))
        update_manifest_file_checksum(release, "validation_report.json")

    def mutate_vi(self, release: Path, change) -> None:
        rows = self.translations(release)
        row = next(row for row in rows if row["source"] == "viwiktionary")
        change(row)
        self.write_translations(release, rows)

    def test_clean_and_raw_verification_preserve_distinct_occurrences(self):
        with tempfile.TemporaryDirectory() as temp:
            release, raw_vi = self.release(Path(temp))
            self.assertEqual("PASS", validate(release)["status"])
            verified = validate(release, raw_vi=raw_vi)
            self.assertEqual(2, verified["vi_source_verification"]["eligible_occurrences"])
            self.assertEqual("PASS", verified["vi_source_verification"]["status"])
            rows = [row for row in self.translations(release) if row["source"] == "viwiktionary"]
            self.assertEqual(2, len({row["id"] for row in rows}))
            self.assertTrue(all(row["source_context"]["record_line"] == 3 for row in rows))

    def test_unknown_context_keys_are_rejected_at_each_level(self):
        changes = [lambda row: row["source_context"].update(extra={}),
                   lambda row: row["source_context"]["record_context"].update(forms=[]),
                   lambda row: row["source_context"]["sense_context"].update(raw_blob={}),
                   lambda row: row["source_context"]["sense_context"]["examples"][0].update(
                       bold_text_offsets=[[0, 1]])]
        for change in changes:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temp:
                release, _ = self.release(Path(temp))
                self.mutate_vi(release, change)
                with self.assertRaisesRegex(ValueError, "context|allowlist|Unknown"):
                    validate(release)

    def test_locator_requires_positive_integers_not_booleans(self):
        for key in ("record_line", "sense_ordinal", "gloss_ordinal"):
            for value in (None, 0, -1, True, "3", 1.5):
                with self.subTest(key=key, value=value), tempfile.TemporaryDirectory() as temp:
                    release, _ = self.release(Path(temp))
                    self.mutate_vi(release, lambda row: row["source_context"].update({key: value}))
                    with self.assertRaisesRegex(ValueError, "locator|ordinal|record_line"):
                        validate(release)

    def test_context_container_and_label_types_are_rejected(self):
        changes = [lambda row: row.update(source_context=[]),
                   lambda row: row["source_context"].update(record_context=[]),
                   lambda row: row["source_context"].update(sense_context="bad"),
                   lambda row: row["source_context"]["sense_context"].update(tags="formal"),
                   lambda row: row["source_context"]["sense_context"].update(examples="bad"),
                   lambda row: row["source_context"]["sense_context"].update(form_of=["bad"]),
                   lambda row: row["source_context"]["record_context"].update(etymology_links=[7])]
        for change in changes:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temp:
                release, _ = self.release(Path(temp))
                self.mutate_vi(release, change)
                with self.assertRaises(ValueError):
                    validate(release)

    def test_multi_item_candidate_cannot_be_relinked_to_first_item(self):
        with tempfile.TemporaryDirectory() as temp:
            release, _ = self.release(Path(temp))
            items = [json.loads(line) for line in (release / "items.jsonl").read_text().splitlines()]
            entry_id = next(row["entry_id"] for row in self.translations(release)
                            if row["source"] == "viwiktionary")
            first = min((item for item in items if item["pos"] == "noun" and item["entry_id"] == entry_id),
                        key=lambda item: item["order_index"])
            self.mutate_vi(release, lambda row: row.update(item_id=first["id"],
                           link_status="ITEM_CANDIDATE", reason="AUXILIARY_VI_UNIQUE_POS_ITEM"))
            with self.assertRaisesRegex(ValueError, "compatible|unique|cardinality"):
                validate(release)

    def test_entry_candidate_cannot_carry_item_reference(self):
        with tempfile.TemporaryDirectory() as temp:
            release, _ = self.release(Path(temp))
            entry_id = next(row["entry_id"] for row in self.translations(release)
                            if row["source"] == "viwiktionary")
            item = next(item for item in map(json.loads, (release / "items.jsonl").read_text().splitlines())
                        if item["entry_id"] == entry_id)
            self.mutate_vi(release, lambda row: row.update(item_id=item["id"]))
            with self.assertRaisesRegex(ValueError, "ENTRY_CANDIDATE"):
                validate(release)

    def test_pos_and_occurrence_identity_must_match_context(self):
        for change in (lambda row: row.update(source_pos="verb"),
                       lambda row: row.update(id="00000000-0000-0000-0000-000000000001"),
                       lambda row: row.update(word_vi="Nghĩa khác.")):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temp:
                release, _ = self.release(Path(temp))
                self.mutate_vi(release, change)
                with self.assertRaisesRegex(ValueError, "source_pos|occurrence|gloss"):
                    validate(release)

    def test_raw_verifier_detects_semantic_context_tampering(self):
        with tempfile.TemporaryDirectory() as temp:
            release, raw_vi = self.release(Path(temp))
            rows = self.translations(release)
            for row in rows:
                if row["source"] == "viwiktionary":
                    row["source_context"]["sense_context"]["examples"][0]["text"] = "invented example"
            self.write_translations(release, rows)
            self.assertEqual("PASS", validate(release)["status"])
            with self.assertRaisesRegex(ValueError, "context|projection"):
                validate(release, raw_vi=raw_vi)

    def test_raw_verifier_rejects_missing_occurrence_even_if_report_counts_agree(self):
        with tempfile.TemporaryDirectory() as temp:
            release, raw_vi = self.release(Path(temp))
            rows = self.translations(release)
            removed = next(row for row in rows if row["source"] == "viwiktionary")
            rows.remove(removed)
            self.repair_occurrence_counts(release, rows)
            self.assertEqual("PASS", validate(release)["status"])
            with self.assertRaisesRegex(ValueError, "Missing raw VI occurrence"):
                validate(release, raw_vi=raw_vi)

    def test_raw_verifier_rejects_extra_occurrence_even_if_report_counts_agree(self):
        with tempfile.TemporaryDirectory() as temp:
            release, raw_vi = self.release(Path(temp))
            rows = self.translations(release)
            extra = json.loads(json.dumps(next(row for row in rows if row["source"] == "viwiktionary")))
            extra["source_context"]["record_line"] = 4
            source = json.loads((release / "manifest.json").read_text())["source"]
            extra["id"] = str(uuid.uuid5(uuid.NAMESPACE_URL,
                f"lyreo:lexicon:trans:viwiktionary:occurrence:{source['auxiliary_vi_sha256']}:4:1:"
                f"{extra['source_context']['gloss_ordinal']}"))
            rows.append(extra)
            self.repair_occurrence_counts(release, rows)
            self.assertEqual("PASS", validate(release)["status"])
            with self.assertRaisesRegex(ValueError, "Extra canonical VI occurrences"):
                validate(release, raw_vi=raw_vi)

    def test_duplicate_locator_or_inconsistent_context_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            release, _ = self.release(Path(temp))
            rows = self.translations(release)
            vi = [row for row in rows if row["source"] == "viwiktionary"]
            vi[0]["source_context"]["record_context"]["pos_title"] = "Different heading"
            self.write_translations(release, rows)
            with self.assertRaisesRegex(ValueError, "Inconsistent VI record context"):
                validate(release)
            vi[0]["source_context"] = json.loads(json.dumps(vi[1]["source_context"]))
            vi[0]["id"] = vi[1]["id"]
            self.write_translations(release, rows)
            with self.assertRaisesRegex(ValueError, "Duplicate translation id|Duplicate VI occurrence"):
                validate(release)

    def test_raw_checksum_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            release, raw_vi = self.release(Path(temp))
            raw_vi.write_text(raw_vi.read_text() + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Raw VI|raw VI"):
                validate(release, raw_vi=raw_vi)

    def test_unsupported_schema_is_rejected_but_legacy_stays_readable(self):
        with tempfile.TemporaryDirectory() as temp:
            release, _ = self.release(Path(temp), auxiliary=False)
            manifest_path = release / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["package"]["schema_version"] = "9.0.0"
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "Unsupported.*schema"):
                validate(release)
            manifest["package"]["schema_version"] = "1.0.0"
            manifest_path.write_text(json.dumps(manifest))
            self.assertEqual("PASS", validate(release)["status"])

    def test_context_inventory_unknown_fields_block_clean_validation(self):
        with tempfile.TemporaryDirectory() as temp:
            release, _ = self.release(Path(temp))
            path = release / "validation_report.json"
            report = json.loads(path.read_text())
            report["vi_source_field_inventory"]["record"]["unknown"]["future_signal"] = 1
            path.write_text(json.dumps(report))
            update_manifest_file_checksum(release, "validation_report.json")
            with self.assertRaisesRegex(ValueError, "Unclassified|unknown|Unknown"):
                validate(release)

    def test_archive_payload_corruption_and_duplicate_members_are_rejected(self):
        for duplicate in (False, True):
            with self.subTest(duplicate=duplicate), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                release, _ = self.release(root)
                bad = root / "bad.tar.gz"
                with tarfile.open(root / "release.tar.gz", "r:gz") as original:
                    contents = [(member, original.extractfile(member).read()) for member in original]
                with tarfile.open(bad, "w:gz") as archive:
                    for member, content in contents:
                        if not duplicate and member.name.endswith("translations.jsonl"):
                            content = b"X" + content[1:]
                        archive.addfile(member, io.BytesIO(content))
                    if duplicate:
                        member, content = contents[0]
                        archive.addfile(member, io.BytesIO(content))
                with self.assertRaisesRegex(ValueError, "checksum mismatch|Duplicate archive member"):
                    validate(release, archive=bad)

    def test_nonfinite_nested_json_is_rejected_before_import(self):
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as temp:
                release, _ = self.release(Path(temp))
                self.mutate_vi(release, lambda row: row["source_context"]["sense_context"]["examples"][0]
                               .update(ref={"invalid": value}))
                with self.assertRaisesRegex(ValueError, "Non-finite JSON number"):
                    validate(release)

    def test_raw_nonfinite_numbers_and_overflow_are_rejected(self):
        for number in ("NaN", "Infinity", "-Infinity", "1e999"):
            with self.subTest(number=number), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                raw_vi = root / "bad-vi.jsonl"
                raw_vi.write_text('{"word":"bank","lang_code":"en","pos":"noun",'
                                  '"senses":[{"glosses":["bờ"],"examples":'
                                  '[{"ref":{"number":' + number + '}}]}]}\n')
                with self.assertRaisesRegex(ValueError, "Non-finite JSON number"):
                    with contextlib.redirect_stdout(io.StringIO()):
                        build(raw_en_path=Path(__file__).parent / "fixtures/lexicon_sample.jsonl",
                              raw_vi_path=raw_vi, output_dir=root / "release",
                              archive_path=root / "release.tar.gz", staging_dir=root / "stage",
                              generated_at="2026-09-26T00:00:00Z", enforce_checksum=False)
