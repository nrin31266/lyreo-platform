#!/usr/bin/env python3
"""Turn explicitly reviewed language proposals into source-hash-pinned release overrides."""
from __future__ import annotations
import argparse
import copy
import hashlib
import json
import re
from pathlib import Path

FIELDS = {'translation_vi': 'content_translation_vi', 'answer_translation_vi': 'option_translation_note_vi',
          'explanation_vi': 'rationale_vi', 'question_options_translation_vi': 'question_options_translation_vi'}
FORMAT = 'lyreo.reviewed-language-overrides.v1'


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def validate(bundle):
    if bundle.get('format') != FORMAT or not re.fullmatch(r'[0-9a-f]{64}', bundle.get('base_manifest_sha256', '')) or not isinstance(bundle.get('changes'), list):
        raise ValueError('Invalid reviewed language override envelope')
    used = set()
    for change in bundle['changes']:
        key = (change.get('item_id'), change.get('field'))
        if key in used or change.get('field') not in FIELDS.values():
            raise ValueError('Duplicate or unsupported language field')
        used.add(key)
        for name in ('item_id', 'after', 'evidence', 'reviewer', 'reviewed_at', 'reason', 'model', 'prompt_version'):
            if not isinstance(change.get(name), str) or not change[name].strip():
                raise ValueError(f'Missing override {name}')
        if len(change['after']) > 30000:
            raise ValueError('Language override exceeds field limit')
        if not isinstance(change.get('before_present'), bool):
            raise ValueError('Missing source field presence')
        for name in ('source_item_sha256', 'work_fingerprint', 'input_sha256'):
            if not re.fullmatch(r'[0-9a-f]{64}', change.get(name, '')):
                raise ValueError(f'Invalid override {name}')
    return bundle


def reviewed(results: list[dict], decisions: dict, release: Path):
    if decisions.get('format') != 'lyreo.maintenance-review.v1' or not isinstance(decisions.get('accepted'), list):
        raise ValueError('Explicit review decisions are required')
    manifest = release / 'manifest.json'
    if json.loads(manifest.read_text())['package']['domain'] != 'grammar-toeic':
        raise ValueError('This release repair owns only Grammar/TOEIC language fields')
    items = {row['id']: row for row in map(json.loads, (release / 'shared/items.jsonl').read_text().splitlines())}
    by_hash = {row['fingerprint']: row for row in results}
    changes = []
    for decision in decisions['accepted']:
        row = by_hash[decision['fingerprint']]
        if row['status'] != 'SUCCEEDED' or row['domain'] not in ('grammar', 'toeic'):
            raise ValueError('Only successful Grammar/TOEIC proposals may be reviewed')
        payload = row['input']; work = payload['work']; target = decision['targetId']; field = decision['field']
        found = [c for c in row['output']['changes'] if c['targetId'] == target and c['field'] == field]
        scope = [t for t in work['targets'] if t['id'] == target and field in t['allowedFields']]
        if len(found) != 1 or len(scope) != 1 or field not in FIELDS:
            raise ValueError('Review does not select one allowed proposal')
        item = items[target]; context = work['context']
        source = next((q for q in context.get('questions', [context.get('question')]) if q and q['id'] == target), None)
        if source != item or scope[0]['fields'].get(field) != item['annotations'].get(FIELDS[field]):
            raise ValueError('Source/context changed since the AI invocation')
        proposal = found[0]
        changes.append({'item_id': target, 'field': FIELDS[field], 'before': item['annotations'].get(FIELDS[field]),
                        'before_present': FIELDS[field] in item['annotations'],
                        'after': proposal['value'], 'source_item_sha256': digest(item),
                        'work_fingerprint': row['fingerprint'], 'input_sha256': digest(payload),
                        'model': payload.get('model', 'unknown'), 'prompt_version': payload.get('promptVersion', 'unknown'),
                        'evidence': proposal['evidence'], 'reviewer': decisions.get('reviewer'),
                        'reviewed_at': decisions.get('reviewed_at'), 'reason': decision.get('reason')})
    return validate({'format': FORMAT, 'base_manifest_sha256': hashlib.sha256(manifest.read_bytes()).hexdigest(), 'changes': changes})


def apply(items: dict[str, dict], bundle: dict):
    validate(bundle)
    originals = {key: copy.deepcopy(value) for key, value in items.items()}
    for change in bundle['changes']:
        item = items[change['item_id']]; source = originals[change['item_id']]
        if digest(source) != change['source_item_sha256'] or source['annotations'].get(change['field']) != change['before']:
            raise ValueError('Reviewed override source hash/field no longer matches')
        item['annotations'][change['field']] = change['after']
        item['provenance'].append({'source_file': 'editorial/language_overrides.json',
                                   'source_id': change['work_fingerprint'], 'field': change['field']})


def verify_effective(items: dict[str, dict], bundle: dict):
    validate(bundle)
    originals = copy.deepcopy(items)
    for change in bundle['changes']:
        item = originals[change['item_id']]
        if item['annotations'].get(change['field']) != change['after']:
            raise ValueError('Effective language field differs from accepted review')
        if change['before_present']: item['annotations'][change['field']] = change['before']
        else: item['annotations'].pop(change['field'], None)
        proof = {'source_file': 'editorial/language_overrides.json', 'source_id': change['work_fingerprint'], 'field': change['field']}
        if item['provenance'].count(proof) != 1:
            raise ValueError('Language override provenance is missing or duplicated')
        item['provenance'].remove(proof)
    for change in bundle['changes']:
        if digest(originals[change['item_id']]) != change['source_item_sha256']:
            raise ValueError('An override changed protected source content')
    return originals


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('results', 'decisions', 'release', 'output'): parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    result = reviewed(json.loads(args.results.read_text()), json.loads(args.decisions.read_text()), args.release)
    with args.output.open('x') as stream: stream.write(canonical(result) + '\n')
    print(f"Accepted {len(result['changes'])} explicitly reviewed fields; source answers/passages unchanged.")
