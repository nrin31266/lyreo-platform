import copy
import json
import pytest
from source_repairs import FORMAT, QUESTION_TRANSCRIPTION, digest, record, require_targets, validate


def repair_bundle(collection, row, field, before, after, domain='grammar-toeic'):
    return {'format': FORMAT, 'domain': domain, 'repairs': [{
        'collection': collection, 'id': row['id'], 'field': field, 'before': before, 'after': after,
        'source_record_sha256': digest(row), 'reviewer': 'curator', 'reviewed_at': '2026-11-18T00:00:00Z',
        'evidence': 'Checked complete source context', 'reason': 'Source wording error',
    }]}


def test_item_language_repair_rejects_stale_source_and_protected_answer_changes():
    original = {'id': 'q', 'correct_option': 'D', 'options': [{'key': 'D', 'text': 'viewing'}], 'annotations': {'rationale_vi': 'wrong'}}
    bundle = validate(repair_bundle('shared/items.jsonl', original, 'annotations.rationale_vi', 'wrong', 'correct'), 'grammar-toeic')
    changed = record('shared/items.jsonl', original, bundle)
    assert original['annotations']['rationale_vi'] == 'wrong'
    assert changed['annotations']['rationale_vi'] == 'correct'
    assert record('shared/items.jsonl', changed, bundle, reverse=True) == original
    changed['correct_option'] = 'A'
    with pytest.raises(ValueError, match='protected'): record('shared/items.jsonl', changed, bundle, reverse=True)
    with pytest.raises(ValueError, match='hash'): record('shared/items.jsonl', changed, bundle)
    bundle['repairs'][0]['field'] = 'correct_option'
    with pytest.raises(ValueError, match='Unsupported'): validate(bundle, 'grammar-toeic')


def test_group_translation_preserves_source_passage_and_requires_exact_field():
    row = {'id': 'g', 'content_translation_vi': 'Distractor.', 'source_html': '<p>____[141].</p>'}
    bundle = validate(repair_bundle('toeic/groups.jsonl', row, 'content_translation_vi', 'Distractor.', '____[141].'), 'grammar-toeic')
    changed = record('toeic/groups.jsonl', row, bundle)
    assert changed['source_html'] == row['source_html']
    assert record('toeic/groups.jsonl', changed, bundle, reverse=True) == row
    bundle['repairs'][0]['before'] = 'different'
    with pytest.raises(ValueError, match='field'): record('toeic/groups.jsonl', row, bundle)
    with pytest.raises(ValueError, match='missing'): require_targets(bundle, {'toeic/groups.jsonl': {}})


def test_choice_repair_restores_only_the_suffix_of_an_incorrect_toeic_choice():
    before = [{'key': key, 'text': 'Join the GN' if key == 'D' else 'Other'} for key in 'ABCD']
    row = {'id': 'q', 'source_domains': ['toeic'], 'correct_option': 'A', 'options': before}
    after = copy.deepcopy(before)
    after[-1]['text'] += 'A.'
    bundle = validate(repair_bundle('shared/items.jsonl', row, 'options', before, after), 'grammar-toeic')
    changed = record('shared/items.jsonl', row, bundle)
    assert changed['options'] == after
    assert record('shared/items.jsonl', changed, bundle, reverse=True) == row
    correct = {**row, 'correct_option': 'D'}
    keyed = validate(repair_bundle('shared/items.jsonl', correct, 'options', before, after), 'grammar-toeic')
    with pytest.raises(ValueError, match='keyed TOEIC answer'):
        record('shared/items.jsonl', correct, keyed)
    after[-1]['text'] = 'Rewritten choice'
    with pytest.raises(ValueError, match='truncated ending'):
        validate(bundle, 'grammar-toeic')


def test_mismatched_toeic_question_transcription_roundtrips_without_changing_the_key():
    choices = lambda text: [{'key': key, 'text': f'{text} {key}'} for key in 'ABCD']
    row = {'id': 'q', 'source_domains': ['toeic'], 'correct_option': 'B',
           'stem_en': 'Unrelated question?', 'options': choices('Unrelated'),
           'annotations': {'question_options_translation_vi': 'Wrong translation', 'rationale_vi': 'Wrong rationale'}}
    before = {'stem_en': row['stem_en'], 'options': row['options'],
              'annotations.question_options_translation_vi': 'Wrong translation', 'annotations.rationale_vi': 'Wrong rationale'}
    after = {'stem_en': 'Actual question?', 'options': choices('Original'),
             'annotations.question_options_translation_vi': 'Verified translation', 'annotations.rationale_vi': 'Verified rationale'}
    bundle = repair_bundle('shared/items.jsonl', row, QUESTION_TRANSCRIPTION, before, after)
    repair = bundle['repairs'][0]
    repair['sources'] = ['https://one.example/scan']
    with pytest.raises(ValueError, match='independent HTTPS sources'):
        validate(bundle, 'grammar-toeic')
    repair['sources'].append('https://two.example/transcription')
    changed = record('shared/items.jsonl', row, validate(bundle, 'grammar-toeic'))
    assert changed['stem_en'] == after['stem_en']
    assert changed['options'] == after['options']
    assert changed['annotations']['rationale_vi'] == after['annotations.rationale_vi']
    assert changed['correct_option'] == row['correct_option']
    assert record('shared/items.jsonl', changed, bundle, reverse=True) == row
    changed['correct_option'] = 'D'
    with pytest.raises(ValueError, match='protected'):
        record('shared/items.jsonl', changed, bundle, reverse=True)


def test_lexicon_example_repair_preserves_translation_and_updates_offsets():
    row = {'id': 'sense', 'examples': [{'text': 'to writer a book', 'bold_text_offsets': [[12, 16]], 'translation': 'viết một cuốn sách'}]}
    after = [{'text': 'to write a book', 'bold_text_offsets': [[11, 15]], 'translation': 'viết một cuốn sách'}]
    bundle = validate(repair_bundle('senses.jsonl', row, 'examples', row['examples'], after, 'lexicon'), 'lexicon')
    changed = record('senses.jsonl', row, bundle)
    assert changed['examples'] == after
    assert record('senses.jsonl', changed, bundle, reverse=True) == row
    with pytest.raises(ValueError, match='envelope'): validate(bundle, 'grammar-toeic')
    bundle['repairs'] *= 2
    with pytest.raises(ValueError, match='Duplicate'): validate(bundle, 'lexicon')


def test_lexicon_builder_roundtrip_with_reviewed_example_repair(tmp_path):
    from build_lexicon_release import build
    from validate_lexicon_release import validate as check_release
    english = tmp_path/'en.jsonl'
    vietnamese = tmp_path/'vi.jsonl'
    english.write_text(json.dumps({'word': 'book', 'lang_code': 'en', 'pos': 'noun', 'senses': [{'glosses': ['A volume.']}]}))
    vietnamese.write_text(json.dumps({'word': 'book', 'lang_code': 'en', 'pos': 'noun', 'senses': [{'glosses': ['Sách.'], 'examples': [{'text': 'to writer a book', 'translation': 'viết một cuốn sách'}]}]}))
    def build_at(version, repairs=None):
        output = tmp_path/version
        archive = tmp_path/(version+'.tar.gz')
        build(english, output, archive, '2026-11-18T00:00:00Z', tmp_path/'stage', vietnamese, False, version, repairs)
        return output, archive
    baseline, _ = build_at('2.0.0')
    source = next(row for row in map(json.loads, (baseline/'senses.jsonl').read_text().splitlines()) if row.get('source_context', {}).get('source') == 'viwiktionary')
    after = copy.deepcopy(source['examples']); after[0]['text'] = 'to write a book'
    path = tmp_path/'repairs.json'
    path.write_text(json.dumps(repair_bundle('senses.jsonl', source, 'examples', source['examples'], after, 'lexicon')))
    output, archive = build_at('2.0.1', path)
    assert check_release(output, archive, english, vietnamese)['status'] == 'PASS'
    assert (output/'editorial/source_repairs.json').is_file()
    assert next(row for row in map(json.loads, (output/'senses.jsonl').read_text().splitlines()) if row['id'] == source['id'])['examples'] == after
