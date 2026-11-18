import sqlite3
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from build_lexicon_release import init_staging_db, identity_normalization, lookup_normalization, canonical_json
from lexicon_vi_baseline import stage_record


class BaselineTest(unittest.TestCase):
    def test_vietnamese_senses_do_not_require_an_english_entry_or_alignment(self):
        with TemporaryDirectory() as directory:
            db=init_staging_db(Path(directory)/'stage.db')
            item,senses=stage_record(db,{'word':'book','pos':'noun','senses':[{'glosses':['Sách'],'examples':[{'text':'a book','translation':'một quyển sách'}]}],
                'sounds':[{'ipa':'bʊk','raw_tags':['Anh Quốc'],'audio':'book.ogg'}]},1,'a'*64,'entry',identity_normalization,lookup_normalization,canonical_json)
            payload=json.loads(db.execute('SELECT payload FROM senses').fetchone()[0])
            self.assertIsNone(payload['definition_en'])
            self.assertEqual({'vi':'Sách'},payload['definitions'])
            self.assertEqual(senses[1],payload['id'])
            self.assertEqual(item,payload['item_id'])
            self.assertEqual('UK',db.execute('SELECT accent FROM pronunciations').fetchone()[0])
            db.close()
