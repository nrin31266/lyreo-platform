import pytest

from build_vocabulary_release import UNIT_SIZE, word_rows


def word(index):
    return {
        "id": f"entry-{index:02d}", "w": f"Word {index:02d}", "topic": "daily", "cefr": "A1",
        "uk": "/wɜːd/", "us": "/wɝːd/", "senses": [{"pos": "n.", "en": "  Sample    word.  "}],
        "ex": [{"en": "A sample word."}, {"en": "A second example."}], "col": ["first word｜第一个词"],
        "fam": [], "syn": [], "ant": [],
    }


def test_groups_all_words_deterministically_with_a_separate_level():
    source = [word(index) for index in range(1, UNIT_SIZE + 3)]
    source[-1]["cefr"] = "A2"
    result = word_rows(source)
    assert result == word_rows(list(reversed(source)))
    packs, units, entries = result
    assert [item["source_topic"] for item in packs] == ["daily"]
    assert [item["level"] for item in units] == ["A1", "A1", "A2"]
    assert [item["position"] for item in entries] == list(range(1, UNIT_SIZE + 1)) + [1, 1]
    assert entries[0]["content"]["senses"] == [{"pos": "n.", "definitionEn": "Sample word.", "meaningVi": None}]
    assert entries[0]["content"]["collocations"] == ["first word"]


def test_rejects_duplicate_term_even_when_source_ids_differ():
    first, second = word(1), word(2)
    second["w"] = first["w"].lower()
    with pytest.raises(ValueError, match="repeated word identity"):
        word_rows([first, second])


def test_toeic_notes_require_matching_part_of_speech_and_independent_groups():
    import json
    from enrich_vocabulary_release import supports_cloze, toeic_notes

    entry = {"id": "pilot", "term": "order", "content": {"senses": [{"pos": "n."}]}}
    rows = [{"id": str(i), "vocabulary_note_vi": "order (n): đơn đặt hàng\norder (v): ra lệnh"}
            for i in range(3)]
    groups = "\n".join(map(json.dumps, rows)).encode()
    assert toeic_notes(groups, [entry])["pilot"] == (
        3, [{"pos": "n", "meaningVi": "đơn đặt hàng", "sourceGroupIds": ["0", "1", "2"]}],
    )
    assert supports_cloze("order", ["Your order is ready."])
    assert not supports_cloze("order", ["The order describes another order."])


def test_upgrade_keeps_editorial_meanings_and_does_not_translate_stale_examples():
    from import_clean_vocabulary import merge_translations

    current = {
        "senses": [{"pos": "n.", "definitionEn": "A sales request", "meaningVi": "đơn hàng"}],
        "examples": ["We changed the example.", "We shipped your order."], "collocations": ["online order"],
    }
    generated = {
        "senses": [{"pos": "n.", "definitionEn": "A sales request", "meaningVi": "yêu cầu bán hàng"}],
        "examples": ["Please place an order.", "We shipped your order."],
        "examplesVi": ["Vui lòng đặt hàng.", "Chúng tôi đã gửi đơn hàng."],
        "collocations": ["online order"], "collocationsVi": ["đơn đặt hàng trực tuyến"],
    }
    merged = merge_translations(current, generated)
    assert merged["senses"][0]["meaningVi"] == "đơn hàng"
    assert merged["examplesVi"] == [None, "Chúng tôi đã gửi đơn hàng."]
    assert merged["collocationsVi"] == ["đơn đặt hàng trực tuyến"]


def test_draft_refresh_replaces_unreviewed_machine_drafts_but_preserves_reviewed_entries():
    from import_clean_vocabulary import merge_translations

    machine_draft = {
        "senses": [{"pos": "n.", "definitionEn": "A public place to borrow books.", "meaningVi": "công công"}],
        "examples": ["Visit the public library.", "Books are free here."],
        "examplesVi": ["Thăm công công.", "Sách miễn phí."],
        "collocations": ["public library"], "collocationsVi": ["công công"],
        "synonyms": [], "antonyms": [], "family": [],
        "translationDraft": True,
    }
    deepseek = {
        "senses": [{"pos": "n.", "definitionEn": "A public place to borrow books.", "meaningVi": "thư viện công cộng"}],
        "examples": ["Visit the public library.", "Books are free here."],
        "examplesVi": ["Hãy ghé thăm thư viện công cộng.", "Sách ở đây miễn phí."],
        "collocations": ["public library"], "collocationsVi": ["thư viện công cộng"],
        "synonyms": ["reading room"], "antonyms": [], "family": ["librarian (n.)"],
    }
    refreshed = merge_translations(machine_draft, deepseek, replace_draft=True)
    assert refreshed["senses"][0]["meaningVi"] == "thư viện công cộng"
    assert refreshed["collocationsVi"] == ["thư viện công cộng"]
    assert refreshed["synonyms"] == ["reading room"]
    assert refreshed["family"] == ["librarian (n.)"]
    assert "translationDraft" not in refreshed
    assert refreshed["needsReview"] is False

    reviewed = machine_draft | {
        "senses": [{"pos": "n.", "definitionEn": "A public place to borrow books.", "meaningVi": "thư viện nhân dân"}],
        "collocationsVi": ["thư viện nhân dân"],
        "translationDraft": False,
        "needsReview": True,
    }
    kept = merge_translations(reviewed, deepseek, replace_draft=True)
    assert kept["senses"][0]["meaningVi"] == "thư viện nhân dân"
    assert kept["collocationsVi"] == ["thư viện nhân dân"]
    assert kept["synonyms"] == ["reading room"]
    assert "translationDraft" not in kept
    assert kept["needsReview"] is True


def test_rebalance_topic_units_merges_tiny_cefr_buckets_and_normalizes_pos():
    from enrich_vocabulary_release import normalize_pos_notes, normalize_pos_tag, rebalance_topic_units

    assert normalize_pos_tag("adj.") == "adj"
    assert normalize_pos_tag("phr. v.") == "phr v"
    assert normalize_pos_notes("winged (adj.)") == "winged (adj)"

    pack = {"id": "p1", "source_topic": "sport", "position": 1}
    units = [
        {"id": "u-a1", "pack_id": "p1", "level": "A1"},
        {"id": "u-a2", "pack_id": "p1", "level": "A2"},
        {"id": "u-c2", "pack_id": "p1", "level": "C2"},
    ]
    entries = (
        [{"id": f"a1-{i}", "source_id": f"a1-{i}", "term": f"a{i:02d}", "unit_id": "u-a1"} for i in range(4)]
        + [{"id": f"a2-{i}", "source_id": f"a2-{i}", "term": f"b{i:02d}", "unit_id": "u-a2"} for i in range(20)]
        + [{"id": f"c2-{i}", "source_id": f"c2-{i}", "term": f"c{i:02d}", "unit_id": "u-c2"} for i in range(2)]
    )
    new_units, new_entries = rebalance_topic_units([pack], units, entries)
    assert len(new_units) == 1
    assert new_units[0]["level"] == "A2"
    assert new_units[0]["title"] == "A1–C2 · 01"
    assert len(new_entries) == 26
