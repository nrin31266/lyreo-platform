from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
IMPORT_ROOT = HERE.parent
sys.path.insert(0, str(IMPORT_ROOT))

from import_toeic import MediaCatalog, validate_integrity  # noqa: E402


class ToeicIntegrityTest(unittest.TestCase):
    def test_valid_dataset_has_no_fatal_integrity_errors(self):
        report = validate_integrity(
            [{"id": "t1"}],
            [{"id": "p1", "test_id": "t1"}],
            [{"id": "q1", "test_id": "t1", "passage_id": "p1"}],
        )
        self.assertEqual(0, report.fatal_count)

    def test_unknown_references_are_reported_before_database_write(self):
        report = validate_integrity(
            [{"id": "t1"}],
            [{"id": "p1", "test_id": "missing"}],
            [{"id": "q1", "test_id": "t1", "passage_id": "missing"}],
        )
        self.assertEqual(1, report.passages_without_test)
        self.assertEqual(1, report.questions_with_unknown_passage)
        self.assertGreater(report.fatal_count, 0)


class ToeicMediaCatalogTest(unittest.TestCase):
    def test_unique_filename_fallback_resolves_scraper_media(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            media = root / "mock_test_data" / "downloads" / "2026" / "Test 1" / "audio"
            media.mkdir(parents=True)
            source = media / "part2_101.mp3"
            source.write_bytes(b"audio")

            catalog = MediaCatalog.build(root, root / "mock_test_data" / "downloads")
            self.assertEqual(source.resolve(), catalog.resolve("part2_101.mp3"))

    def test_ambiguous_filename_is_not_guessed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for year in ("2025", "2026"):
                media = root / "mock_test_data" / "downloads" / year / "Test 1" / "audio"
                media.mkdir(parents=True)
                (media / "audio.mp3").write_bytes(year.encode())

            catalog = MediaCatalog.build(root, root / "mock_test_data" / "downloads")
            self.assertIsNone(catalog.resolve("audio.mp3"))
            self.assertIn("audio.mp3", catalog.unresolved)


if __name__ == "__main__":
    unittest.main()
