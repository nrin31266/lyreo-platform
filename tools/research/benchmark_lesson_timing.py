"""Compare retained Groq/Qwen runs; human-reviewed references are required for acceptance."""
import argparse
import hashlib
import json
import math
import unicodedata
from pathlib import Path

# Fixed before scoring: visible word/silence failures also veto acceptance, regardless of averages.
CRITERIA = {"coverage": 1.0, "median_boundary_error_ms": 80, "p95_boundary_error_ms": 200,
            "max_boundary_error_ms": 400, "invalid_intervals": 0,
            "human_karaoke_review_required": True, "human_reference_required": True}


def lexical(text):
    return ''.join(c for c in unicodedata.normalize('NFKC', text).casefold() if c.isalnum())


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def measure(words, transcript, duration_ms, reference=None):
    stream = ''.join(lexical(w['word']) for w in words)
    expected = lexical(transcript)
    result = {"words": len(words), "lexical_coverage_exact": stream == expected,
              "nonpositive": sum(w['end_ms'] <= w['start_ms'] for w in words),
              "overlap": sum(w['start_ms'] < previous['end_ms'] for previous, w in zip(words, words[1:])),
              "out_of_bounds": sum(w['start_ms'] < 0 or w['end_ms'] > duration_ms for w in words),
              "human_reference": False, "accepted": False}
    if not reference or not reference.get('reviewer') or not reference.get('checked_at'):
        result['decision'] = 'INSUFFICIENT_HUMAN_REFERENCE'
        return result
    anchors = reference.get('words', [])
    errors = []
    for anchor in anchors:
        index = anchor['index']
        if not 0 <= index < len(words) or lexical(anchor['word']) != lexical(words[index]['word']):
            raise ValueError('Reference index/surface does not match the provider word stream')
        errors.extend([abs(words[index][key] - anchor[key]) for key in ('start_ms', 'end_ms')])
    result.update(human_reference=bool(anchors), reviewed_words=len(anchors),
                  median_boundary_error_ms=percentile(errors, .5), p95_boundary_error_ms=percentile(errors, .95),
                  max_boundary_error_ms=max(errors) if errors else None,
                  listening_review=reference.get('karaoke_passed') is True)
    # Partial references produce useful measurements, but do not approve an entire lesson automatically.
    result['accepted'] = bool(anchors) and len({a['index'] for a in anchors}) == len(words) and result['listening_review'] and stream == expected and not any(result[k] for k in ('nonpositive', 'overlap', 'out_of_bounds')) and all(result[k] <= CRITERIA[k] for k in ('median_boundary_error_ms', 'p95_boundary_error_ms', 'max_boundary_error_ms'))
    result['decision'] = 'ACCEPTED' if result['accepted'] else 'REVIEW_OR_ALIGNMENT_REQUIRED'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path)
    parser.add_argument('--references', type=Path, help='JSON keyed by source-seconds-provider with reviewer, checked_at, words, karaoke_passed')
    parser.add_argument('--offset-artifacts', type=Path, help='Optional nonzero-offset groq.json, qwen.json and measurement.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    references = json.loads(args.references.read_text()) if args.references else {}
    rows = []
    asr_runs = json.loads((args.artifacts / 'whisper-results.json').read_text())
    alignment_runs = json.loads((args.artifacts / 'alignment-results.json').read_text())
    for source in ('listening', 'story'):
        for seconds in (120, 300, 600):
            raw_path = args.artifacts / f'{source}-{seconds}-whisper.json'
            if not raw_path.exists():
                continue
            raw = json.loads(raw_path.read_text())
            if not raw.get('words'):
                rows.append({'source': source, 'seconds': seconds, 'decision': 'PROVIDER_RUN_UNAVAILABLE'})
                continue
            for provider in ('groq', 'qwen'):
                path = raw_path if provider == 'groq' else args.artifacts / f'{source}-{seconds}-whisper-alignment.json'
                if not path.exists():
                    continue
                data = json.loads(path.read_text())
                words = [{'word': w['word'], 'start_ms': round(w['start'] * 1000), 'end_ms': round(w['end'] * 1000)} for w in raw['words']] if provider == 'groq' else data['output']['words']
                timing = next((r for r in (asr_runs if provider == 'groq' else alignment_runs) if r.get('source') == source and r.get('seconds') == seconds and (provider == 'groq' or r.get('transcript') == 'whisper')), {})
                row = {'wall_seconds': timing.get('wall_seconds'), 'source': source, 'seconds': seconds, 'provider': provider,
                       'artifact_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                       **measure(words, raw['text'], seconds * 1000, references.get(f'{source}-{seconds}-{provider}'))}
                rows.append(row)
    if args.offset_artifacts:
        folder=args.offset_artifacts
        metadata=json.loads((folder / 'measurement.json').read_text())
        raw=json.loads((folder / 'groq.json').read_text())
        duration=metadata['source_end_ms']-metadata['source_start_ms']
        for provider in ('groq','qwen'):
            path=folder / f'{provider}.json'
            data=json.loads(path.read_text())
            words=[{'word':w['word'],'start_ms':round(w['start']*1000),'end_ms':round(w['end']*1000)} for w in data['words']] if provider=='groq' else data['output']['words']
            rows.append({'source':'listening-offset','source_start_ms':metadata['source_start_ms'],'seconds':duration/1000,
                         'provider':provider,'wall_seconds':metadata[f'{provider}_wall_seconds'],
                         'artifact_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                         **measure(words,raw['text'],duration,references.get(f'listening-offset-{provider}'))})
    args.output.write_text(json.dumps({'criteria': CRITERIA, 'source_offset': 'Nonzero-offset results are included only when --offset-artifacts is supplied. None of these runs supplies a human timing reference.', 'rows': rows, 'reuse_policy': 'REQUIRE_ALIGNMENT_UNTIL_HUMAN_BENCHMARK_ACCEPTANCE'}, indent=2) + '\n')
    print(json.dumps({'comparisons': len(rows), 'accepted': sum(r.get('accepted', False) for r in rows), 'output': str(args.output)}))


if __name__ == '__main__':
    main()
