import unittest
from lexicon_pronunciation import accent, pronunciation_metadata


class PronunciationTest(unittest.TestCase):
    def test_regions_match_whole_labels_and_preserve_multiple_regions(self):
        self.assertEqual("AU/NZ", accent({"tags": ["Australia", "New-Zealand"]}))
        self.assertEqual("UK", accent({"raw_tags": ["Anh Quốc"]}))
        self.assertEqual("unspecified", accent({"tags": ["unusual"]}))

    def test_source_region_aliases_preserve_source_labels(self):
        raw = ["General-American", "Received_Pronunciation", "miền Nam nước Anh", "Úc", "unusual"]
        result = pronunciation_metadata({"raw_tags": raw}, form="record", source="viwiktionary")
        self.assertEqual(["US", "UK", "Southern-England", "AU"], result["regions"])
        self.assertEqual(raw, result["labels"])
        self.assertEqual(["unusual"], result["unknown_labels"])

    def test_source_respelling_is_not_ipa_and_no_audio_link_is_inferred(self):
        result = pronunciation_metadata({"ipa": "rĕkʹərd"}, form="record", source="viwiktionary")
        self.assertEqual("SOURCE", result["notation_system"])
        self.assertNotIn("audio", result)
