import json
from prepare_maintenance import prepare


def test_pilot_keeps_ordered_reading_context_and_does_not_retranslate_available_source(tmp_path):
    grammar=tmp_path/'grammar'; lexicon=tmp_path/'lexicon'; output=tmp_path/'out'
    def write(root,name,values):
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(''.join(json.dumps(v)+'\n' for v in values))
    questions=[{'id':q,'stem_en':'Question?', 'source_domains':['toeic'], 'annotations':{'question_options_translation_vi':'Đã có bản dịch nguồn'}} for q in ('q1','q2')]
    write(grammar,'shared/items.jsonl',questions)
    write(grammar,'toeic/groups.jsonl',[{'id':'g','part':7,'render_html':'combined','source_html':'raw'}])
    write(grammar,'toeic/documents.jsonl',[{'group_id':'g','ordinal':2,'document_type':'email','html':'second'},{'group_id':'g','ordinal':1,'document_type':'notice','html':'first'}])
    write(grammar,'toeic/placements.jsonl',[{'id':'p2','group_id':'g','item_id':'q2','part':7,'question_number':182},{'id':'p1','group_id':'g','item_id':'q1','part':7,'question_number':181}])
    write(lexicon,'entries.jsonl',[]);write(lexicon,'senses.jsonl',[])
    prepare(grammar,lexicon,output)
    work=json.loads((output/'pilot.jsonl').read_text())
    assert [d['html'] for d in work['context']['documents']]==['first','second']
    assert [q['id'] for q in work['context']['questions']]==['q1','q2']
    assert all(t['allowedFields']==['explanation_vi'] for t in work['targets'])
    assert 'source_html' not in work['context']['group']


def test_text_only_work_does_not_invent_photo_explanations(tmp_path):
    grammar=tmp_path/'grammar';lexicon=tmp_path/'lexicon';output=tmp_path/'out'
    def write(root,name,values):
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(''.join(json.dumps(v)+'\n' for v in values))
    write(grammar,'shared/items.jsonl',[{'id':'q','stem_en':None,'source_domains':['toeic'],'annotations':{}}])
    write(grammar,'toeic/groups.jsonl',[]);write(grammar,'toeic/documents.jsonl',[])
    write(grammar,'toeic/placements.jsonl',[{'id':'p','item_id':'q','part':1,'question_number':1}])
    write(lexicon,'entries.jsonl',[]);write(lexicon,'senses.jsonl',[])
    report=prepare(grammar,lexicon,output)
    assert report['photo_explanations_require_visual_review']==1
    assert report['candidates']=={}
    assert (output/'candidates.jsonl').read_text()==''
