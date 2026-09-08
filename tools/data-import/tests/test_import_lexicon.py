from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
IMPORT_ROOT = HERE.parent
sys.path.insert(0, str(IMPORT_ROOT))

from import_lexicon import dry_run, entry_type, rows, vi_words  # noqa: E402


def test_entry_type_distinguishes_common_multiword_kinds():
    assert entry_type({"word": "postpone"}) == "WORD"
    assert entry_type({"word": "take into account"}) == "PHRASE"
    assert entry_type({"word": "give up", "tags": ["phrasal verb"]}) == "PHRASAL_VERB"
    assert entry_type({"word": "break the ice", "categories": ["English idioms"]}) == "IDIOM"
    assert entry_type({"word": "strong coffee", "tags": ["collocation"]}) == "COLLOCATION"


def test_vi_words_filters_and_deduplicates_vietnamese_translations():
    values = [
        {"code": "vi", "word": "hoãn"},
        {"lang_code": "vi", "word": "trì hoãn"},
        {"code": "fr", "word": "reporter"},
        {"code": "vi", "word": "hoãn"},
    ]
    assert vi_words(values) == ["hoãn", "trì hoãn"]


def test_jsonl_reader_and_dry_run_are_streaming_friendly():
    with tempfile.TemporaryDirectory() as temp:
        path = Path(temp) / "sample.jsonl"
        path.write_text(
            "\n".join(
                [
                    json.dumps({"lang_code": "en", "word": "account", "senses": [{}, {}]}),
                    json.dumps({"lang_code": "fr", "word": "compte", "senses": [{}]}),
                    json.dumps({"lang_code": "en", "word": "take into account", "senses": [{}]}),
                ]
            )
            + "\n",
            encoding="utf-8",
        )

        assert [item["word"] for item in rows(path)] == [
            "account",
            "compte",
            "take into account",
        ]
        report = dry_run(path, "sample", 0)
        assert report["scanned"] == 3
        assert report["englishHeadwords"] == 2
        assert report["senses"] == 3
