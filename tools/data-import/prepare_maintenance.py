#!/usr/bin/env python3
"""Inventory deterministic gaps and create a stratified, bounded Core maintenance pilot."""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path


def rows(path):
    with path.open() as stream:
        for line in stream:
            yield json.loads(line)


def prepare(grammar: Path, lexicon: Path, output: Path, final: bool = False):
    items={row['id']:row for row in rows(grammar/'shared/items.jsonl')}
    groups={row['id']:row for row in rows(grammar/'toeic/groups.jsonl')}
    documents=defaultdict(list)
    for row in rows(grammar/'toeic/documents.jsonl'):
        documents[row['group_id']].append({k:row[k] for k in ('ordinal','document_type','html')})
    for group_documents in documents.values(): group_documents.sort(key=lambda row:row['ordinal'])
    placements=defaultdict(list)
    for row in rows(grammar/'toeic/placements.jsonl'):
        placements[row.get('group_id') or row['id']].append(row)
    taxonomy=defaultdict(list)
    taxonomies={}
    for name in ('topics','subtopics','bank_sets'):
        path=grammar/'grammar'/f'{name}.jsonl'
        taxonomies[name]={row['id']:row for row in rows(path)} if path.exists() else {}
    path=grammar/'grammar/memberships.jsonl'
    if path.exists():
        for membership in rows(path):
            context={'mode':membership['mode']}
            for field,collection in [('topic_id','topics'),('subtopic_id','subtopics'),('bank_set_id','bank_sets')]:
                if membership.get(field): context[collection]=taxonomies[collection].get(membership[field])
            if context not in taxonomy[membership['item_id']]: taxonomy[membership['item_id']].append(context)
    work=[];seen=set();oversized=0;visual_review=set()
    def target(row):
        a=row.get('annotations') or {}
        fields={'translation_vi':a.get('content_translation_vi'),'answer_translation_vi':a.get('option_translation_note_vi'),
                'explanation_vi':a.get('rationale_vi'),'question_options_translation_vi':a.get('question_options_translation_vi')}
        allowed=[]
        if row.get('stem_en') and not fields['translation_vi'] and not fields['question_options_translation_vi']: allowed.append('translation_vi')
        if not fields['explanation_vi']: allowed.append('explanation_vi')
        return {'id':row['id'],'fields':fields,'allowedFields':allowed}
    for key,placed in sorted(placements.items(),key=lambda pair:(pair[1][0]['part'],pair[1][0]['question_number'],pair[0])):
        placed=sorted(placed,key=lambda p:p['question_number'])
        questions=[items[p['item_id']] for p in placed]
        targets=[target(q) for q in questions]
        if final and placed[0]['part'] == 7:
            for t in targets:
                t['allowedFields'] = ['explanation_vi']
                if t['fields']['question_options_translation_vi']:
                    t['allowedFields'].append('question_options_translation_vi')
                else:
                    t['allowedFields'].extend(['translation_vi', 'answer_translation_vi'])
        # This text-only capability has no photograph input. A source answer key alone
        # cannot justify why a Part 1 description matches the photograph.
        if placed[0]['part']==1:
            for t in targets:
                if 'explanation_vi' in t['allowedFields']:
                    t['allowedFields'].remove('explanation_vi');visual_review.add(t['id'])
        if not any(t['allowedFields'] for t in targets): continue
        group=groups.get(placed[0].get('group_id'))
        context={'part':placed[0]['part'],'group':group,'placements':placed,'questions':questions}
        # Source HTML is retained in the package, but duplicate raw HTML need not consume model tokens.
        if group:
            context['group']={k:v for k,v in group.items() if k not in ('source_html','source_html_sha256','render_html')}
            context['documents']=documents[group['id']]
            if not context['documents'] and group.get('render_html'):
                context['documents']=[{'ordinal':1,'document_type':'PASSAGE','html':group['render_html']}]
            if final and placed[0]['part'] == 7:
                context['source_html'] = group.get('source_html')
                context['source_html_sha256'] = group.get('source_html_sha256')
        unit={'domain':'toeic','id':key,'task':'repair_supported_language_fields','context':context,'targets':targets}
        limit = 56000 if final else 18000
        if len(json.dumps(unit,ensure_ascii=False).encode()) > limit:
            if final:
                raise ValueError(f'Work unit exceeds the request envelope: {key}')
            oversized+=1
            continue
        work.append(unit);seen.update(q['id'] for q in questions)
    for row in items.values():
        if 'grammar' not in row['source_domains']: continue
        t=target(row)
        review_only=not t['allowedFields'] and bool(t['fields']['explanation_vi'])
        if review_only: t['allowedFields']=['explanation_vi']
        if t['allowedFields'] and row['id'] not in seen: work.append({'domain':'grammar','id':row['id'],'task':'review_existing_explanation' if review_only else 'repair_supported_language_fields','context':{'question':row,'taxonomy':taxonomy[row['id']]},'targets':[t]})
    chosen=defaultdict(list)
    for unit in work:
        bucket=unit['domain']+('-'+str(unit['context']['part']) if unit['domain']=='toeic' else '')
        if len(chosen[bucket])<2: chosen[bucket].append(unit)
    wanted={row['id']:row['display_form'] for row in rows(lexicon/'entries.jsonl') if row['display_form'] in ('book','read','lead','record')}
    picked=set()
    for sense in rows(lexicon/'senses.jsonl'):
        word=wanted.get(sense['entry_id'])
        if word and word not in picked and sense.get('translation_vi') and sense.get('source_context',{}).get('source')=='viwiktionary':
            picked.add(word);chosen['lexicon'].append({'domain':'lexicon','id':sense['id'],'task':'review_source_vietnamese_sense',
                'context':{'headword':word,'sense':sense},'targets':[{'id':sense['id'],'fields':{'translation_vi':sense['translation_vi']},'allowedFields':['translation_vi']}]})
    pilot=[unit for bucket in sorted(chosen) for unit in chosen[bucket]][:100]
    output.mkdir(parents=True,exist_ok=True)
    (output/'candidates.jsonl').write_text(''.join(json.dumps(unit,ensure_ascii=False,sort_keys=True)+'\n' for unit in work if unit['task']!='review_existing_explanation'))
    (output/'pilot.jsonl').write_text(''.join(json.dumps(unit,ensure_ascii=False,sort_keys=True)+'\n' for unit in pilot))
    if final:
        grammar_titles = [
            {'domain': 'grammar', 'id': f'{kind}-titles', 'task': 'review_grammar_titles',
             'context': {'catalog': kind, 'rows': [{'id': row['id'], 'title_vi': row['title_vi'],
                                                   'title_en': row['title_en']} for row in catalog.values()]},
             'targets': [{'id': row['id'], 'fields': {'title_en': row['title_en']},
                          'allowedFields': ['title_en']} for row in catalog.values()]}
            for kind, catalog in taxonomies.items() if kind != 'bank_sets' and catalog
        ]
        part7 = [unit for unit in work if unit['domain'] == 'toeic' and unit['context']['part'] == 7]
        part7.sort(key=lambda unit: (-len(unit['context']['documents']), unit['id']))
        remaining = [unit for unit in work if unit['domain'] == 'toeic' and unit['context']['part'] != 7]
        # Keep the first six representative and use the same fingerprints for the full run.
        lexicon_units = chosen['lexicon']
        grammar_review = [unit for unit in work if unit['domain'] == 'grammar']
        queue = (part7[:3] + grammar_titles + lexicon_units[:1] + part7[3:] + remaining
                 + lexicon_units[1:] + grammar_review)
        (output/'final.jsonl').write_text(''.join(json.dumps(unit,ensure_ascii=False,sort_keys=True)+'\n' for unit in queue))
    report={'candidates':dict(Counter(unit['domain'] for unit in work if unit['task']!='review_existing_explanation')),
            'optional_grammar_review_units':sum(unit['task']=='review_existing_explanation' for unit in work),
            'pilot':dict(Counter(unit['domain'] for unit in pilot)),
            'oversized_toeic_contexts_not_scheduled':oversized,
            'photo_explanations_require_visual_review':len(visual_review),
            'grammar_missing_translation':sum(not any((row.get('annotations') or {}).get(k) for k in ('content_translation_vi','question_options_translation_vi')) for row in items.values() if 'grammar' in row['source_domains']),
            'grammar_missing_explanation':sum(not (row.get('annotations') or {}).get('rationale_vi') for row in items.values() if 'grammar' in row['source_domains']),
            'combined_translation_available':sum(bool((row.get('annotations') or {}).get('question_options_translation_vi')) for row in items.values()),
            'missing_translation':sum(not any((row.get('annotations') or {}).get(k) for k in ('content_translation_vi','question_options_translation_vi')) for row in items.values()),
            'missing_explanation':sum(not (row.get('annotations') or {}).get('rationale_vi') for row in items.values())}
    if final:
        report.update(part7_contexts=len(part7), part7_questions=sum(len(unit['targets']) for unit in part7),
                      final_units=len(queue))
    (output/'inventory.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--grammar',type=Path,required=True);parser.add_argument('--lexicon',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--final', action='store_true')
    args=parser.parse_args();print(json.dumps(prepare(args.grammar,args.lexicon,args.output,args.final),ensure_ascii=False,indent=2))
