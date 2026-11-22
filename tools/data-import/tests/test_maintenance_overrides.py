import copy
import hashlib
import json
import pytest
from maintenance_overrides import apply, digest, reviewed, verify_effective


def fixture(tmp_path):
    item = {'id':'q', 'correct_option':'B', 'options':[{'key':'B','text':'source'}], 'annotations':{}, 'provenance':[]}
    (tmp_path/'shared').mkdir()
    (tmp_path/'manifest.json').write_text(json.dumps({'package':{'domain':'grammar-toeic'}}))
    (tmp_path/'shared/items.jsonl').write_text(json.dumps(item)+'\n')
    payload={'work':{'context':{'questions':[item]},'targets':[{'id':'q','fields':{},'allowedFields':['explanation_vi']}]},'model':'deepseek-flash','promptVersion':'review-test'}
    row={'fingerprint':'a'*64,'status':'SUCCEEDED','domain':'toeic','input':payload,'output':{'changes':[{'targetId':'q','field':'explanation_vi','value':'Giải thích có căn cứ','evidence':'Source phrase'}]}}
    decisions={'format':'lyreo.maintenance-review.v1','reviewer':'curator','reviewed_at':'2026-11-18T00:00:00Z','accepted':[{'fingerprint':'a'*64,'targetId':'q','field':'explanation_vi','reason':'Checked against complete passage and answer'}]}
    return item,[row],decisions


def test_accept_apply_and_reverse_verification_preserve_protected_source(tmp_path):
    item,results,decisions=fixture(tmp_path)
    bundle=reviewed(results,decisions,tmp_path)
    assert bundle['base_manifest_sha256']==hashlib.sha256((tmp_path/'manifest.json').read_bytes()).hexdigest()
    items={'q':copy.deepcopy(item)}
    apply(items,bundle)
    assert items['q']['annotations']['rationale_vi']=='Giải thích có căn cứ'
    assert verify_effective(items,bundle)=={'q':item}
    items['q']['correct_option']='A'
    with pytest.raises(ValueError,match='protected source'): verify_effective(items,bundle)


def test_rejects_stale_source_unsupported_fields_and_duplicate_decisions(tmp_path):
    item,results,decisions=fixture(tmp_path)
    results[0]['input']['work']['context']['questions'][0]=dict(item,correct_option='A')
    with pytest.raises(ValueError,match='Source/context'): reviewed(results,decisions,tmp_path)
    results[0]['input']['work']['context']['questions'][0]=item
    bundle=reviewed(results,decisions,tmp_path)
    bundle['changes'][0]['field']='correct_option'
    with pytest.raises(ValueError,match='unsupported'): apply({'q':item},bundle)
    decisions['accepted']*=2
    with pytest.raises(ValueError,match='Duplicate'): reviewed(results,decisions,tmp_path)


def test_source_hash_prevents_applying_review_to_changed_item(tmp_path):
    item,results,decisions=fixture(tmp_path)
    bundle=reviewed(results,decisions,tmp_path)
    changed=copy.deepcopy(item);changed['options'][0]['text']='Changed option'
    with pytest.raises(ValueError,match='source hash'): apply({'q':changed},bundle)


def test_explicit_null_is_preserved_distinct_from_absent_annotation(tmp_path):
    item,results,decisions=fixture(tmp_path)
    item['annotations']['rationale_vi']=None
    (tmp_path/'shared/items.jsonl').write_text(json.dumps(item)+'\n')
    bundle=reviewed(results,decisions,tmp_path)
    assert bundle['changes'][0]['before_present'] is True
    items={'q':copy.deepcopy(item)}
    apply(items,bundle)
    assert verify_effective(items,bundle)=={'q':item}
