"""Apply reviewed source repairs while proving every other field unchanged."""
from __future__ import annotations
import copy
import hashlib
import json
import re

FORMAT = 'lyreo.reviewed-source-repairs.v1'
PATH = 'editorial/source_repairs.json'
FIELDS = {
    'shared/items.jsonl': {'annotations.rationale_vi', 'annotations.content_translation_vi', 'options'},
    'toeic/groups.jsonl': {'content_translation_vi'},
    'senses.jsonl': {'examples'},
}


def restored_option_key(before, after):
    for options in (before, after):
        if not isinstance(options, list) or len(options) != 4 or any(
            not isinstance(option, dict) or option.keys() != {'key', 'text'}
            or not isinstance(option['text'], str) or not option['text'].strip() for option in options
        ) or [option['key'] for option in options] != list('ABCD'):
            raise ValueError('Invalid choice transcription repair')
    changes = [(original, fixed) for original, fixed in zip(before, after) if original != fixed]
    if len(changes) != 1:
        raise ValueError('A choice transcription repair must restore one choice')
    original, fixed = changes[0]
    if not fixed['text'].startswith(original['text']) or not re.fullmatch(r'[A-Za-z]{1,3}[.!?]?', fixed['text'][len(original['text']):]):
        raise ValueError('A choice transcription repair must restore a truncated ending')
    return original['key']


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def validate(bundle, domain):
    if domain not in {'lexicon', 'grammar-toeic'} or bundle.get('format') != FORMAT or bundle.get('domain') != domain or not isinstance(bundle.get('repairs'), list) or not bundle['repairs']:
        raise ValueError('Invalid reviewed source repair envelope')
    seen = set()
    for repair in bundle['repairs']:
        collection, field = repair.get('collection'), repair.get('field')
        if collection not in FIELDS or field not in FIELDS[collection] or (domain == 'lexicon') != (collection == 'senses.jsonl'):
            raise ValueError('Unsupported source repair field')
        key = (collection, repair.get('id'), field)
        if key in seen:
            raise ValueError('Duplicate source repair')
        seen.add(key)
        if not re.fullmatch(r'[0-9a-f]{64}', repair.get('source_record_sha256', '')):
            raise ValueError('Invalid source record hash')
        for name in ('id', 'reviewer', 'reviewed_at', 'evidence', 'reason'):
            if not isinstance(repair.get(name), str) or not repair[name].strip():
                raise ValueError(f'Missing source repair {name}')
        if field == 'options':
            restored_option_key(repair.get('before'), repair.get('after'))
            continue
        for name in ('before', 'after'):
            value = repair.get(name)
            if field == 'examples':
                if not isinstance(value, list) or not all(isinstance(example, dict) for example in value):
                    raise ValueError('Invalid repaired examples')
            elif not isinstance(value, str) or not value.strip() or len(value) > 30000:
                raise ValueError('Invalid repaired language text')
        if repair['before'] == repair['after']:
            raise ValueError('Source repair must change content')
    return bundle


def record(collection, value, bundle, *, reverse=False):
    repairs = [r for r in bundle['repairs'] if r['collection'] == collection and r['id'] == value['id']]
    if not repairs:
        return value
    original = copy.deepcopy(value)
    if not reverse and any(digest(original) != repair['source_record_sha256'] for repair in repairs):
        raise ValueError('Source repair record hash no longer matches')
    changed = copy.deepcopy(value)
    for repair in repairs:
        if repair['field'] == 'options' and (
            'toeic' not in value.get('source_domains', ())
            or restored_option_key(repair['before'], repair['after']) == value['correct_option']
        ):
            raise ValueError('Choice transcription repair must preserve the keyed TOEIC answer')
        target = changed
        parts = repair['field'].split('.')
        for name in parts[:-1]:
            target = target[name]
        before, after = ('after', 'before') if reverse else ('before', 'after')
        if target.get(parts[-1]) != repair[before]:
            raise ValueError('Source repair field no longer matches')
        target[parts[-1]] = copy.deepcopy(repair[after])
    if reverse and any(digest(changed) != repair['source_record_sha256'] for repair in repairs):
        raise ValueError('Source repair changed protected content')
    return changed


def require_targets(bundle, collections):
    for repair in bundle['repairs']:
        if repair['collection'] not in collections or repair['id'] not in collections[repair['collection']]:
            raise ValueError('Source repair target is missing')
