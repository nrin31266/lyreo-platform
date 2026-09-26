from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from build_lexicon_release import build  # noqa: E402
from validate_lexicon_release import validate  # noqa: E402


def update_manifest_file_checksum(release_dir: Path, rel_path: str):
    manifest_file = release_dir / "manifest.json"
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    target = release_dir / rel_path
    data = target.read_bytes()
    for entry in manifest["files"]:
        if entry["path"] == rel_path:
            entry["size_bytes"] = len(data)
            entry["sha256"] = hashlib.sha256(data).hexdigest()
            break
    manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class LexiconValidatorFailureTest(unittest.TestCase):
    def setUp(self):
        self.fixtures_dir = Path(__file__).resolve().parent / "fixtures"
        self.sample_jsonl = self.fixtures_dir / "lexicon_sample.jsonl"

    def test_missing_source_inventory_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            out_dir = temp / "lexicon-1.0.0"
            build(
                raw_en_path=self.sample_jsonl,
                output_dir=out_dir,
                archive_path=temp / "lexicon-1.0.0.tar.gz",
                generated_at="2026-09-26T00:00:00Z",
                staging_dir=temp / "stg",
                enforce_checksum=False,
            )
            manifest_path = out_dir / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["source"].pop("files")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "Source files inventory missing"):
                validate(out_dir)

    def test_binary_audio_leak_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            out_dir = temp / "lexicon-1.0.0"
            archive_path = temp / "lexicon-1.0.0.tar.gz"

            build(
                raw_en_path=self.sample_jsonl,
                output_dir=out_dir,
                archive_path=archive_path,
                generated_at="2026-09-26T00:00:00Z",
                staging_dir=temp / "stg",
                enforce_checksum=False,
            )

            # Inject a binary audio file into release directory
            bad_audio = out_dir / "audio.mp3"
            bad_audio.write_bytes(b"FAKE_MP3_DATA")

            with self.assertRaises(ValueError) as ctx:
                validate(out_dir)
            self.assertIn("Binary audio file detected", str(ctx.exception))

    def test_unreferenced_item_id_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            out_dir = temp / "lexicon-1.0.0"
            archive_path = temp / "lexicon-1.0.0.tar.gz"

            build(
                raw_en_path=self.sample_jsonl,
                output_dir=out_dir,
                archive_path=archive_path,
                generated_at="2026-09-26T00:00:00Z",
                staging_dir=temp / "stg",
                enforce_checksum=False,
            )

            # Corrupt senses.jsonl by changing item_id to nonexistent
            senses_file = out_dir / "senses.jsonl"
            lines = [json.loads(line) for line in senses_file.open()]
            lines[0]["item_id"] = "nonexistent-item-uuid"
            with senses_file.open("w", encoding="utf-8") as f:
                for line in lines:
                    f.write(json.dumps(line) + "\n")

            update_manifest_file_checksum(out_dir, "senses.jsonl")

            with self.assertRaises(ValueError) as ctx:
                validate(out_dir)
            self.assertIn("references nonexistent item_id", str(ctx.exception))

    def test_available_translation_without_qualifier_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            out_dir = temp / "lexicon-1.0.0"
            archive_path = temp / "lexicon-1.0.0.tar.gz"

            build(
                raw_en_path=self.sample_jsonl,
                output_dir=out_dir,
                archive_path=archive_path,
                generated_at="2026-09-26T00:00:00Z",
                staging_dir=temp / "stg",
                enforce_checksum=False,
            )

            # Corrupt senses.jsonl: AVAILABLE translation with matched_qualifier removed
            senses_file = out_dir / "senses.jsonl"
            lines = [json.loads(line) for line in senses_file.open()]
            available_idx = next(i for i, s in enumerate(lines) if s["translation_status"] == "AVAILABLE")
            lines[available_idx]["matched_qualifier"] = None
            with senses_file.open("w", encoding="utf-8") as f:
                for line in lines:
                    f.write(json.dumps(line) + "\n")

            update_manifest_file_checksum(out_dir, "senses.jsonl")

            with self.assertRaises(ValueError) as ctx:
                validate(out_dir)
            self.assertIn("marked AVAILABLE without matched_qualifier", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
