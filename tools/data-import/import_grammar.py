#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, os
from pathlib import Path
from common import begin_import, checksum, db, finish_import, load_json, stable_uuid


def need(d: Path, name: str) -> Path:
    p = d / name
    if not p.exists():
        raise SystemExit(f"Missing {p}")
    return p



def question_external_id(row: dict) -> str:
    value = row.get("question_id") or row.get("id")
    if value is None or str(value).strip() == "":
        raise ValueError("Grammar question is missing question_id/id")
    return str(value)


def explanation_policy(row: dict) -> str:
    return "AI_PREFERRED" if bool(row.get("prefer_ai_explanation")) else "SOURCE"


def main() -> None:
    ap = argparse.ArgumentParser(description="Import Lyreo grammar bank from dautoeic/grammar_data")
    ap.add_argument("--data-dir", default=os.getenv("DAUTOEIC_DATA_DIR"))
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()
    root = Path(args.data_dir or "").expanduser()
    g = root / "grammar_data"
    qf, tf, sf, bf = [need(g, n) for n in ["grammar_questions_flat.json", "grammar_topics.json", "grammar_subtopics.json", "grammar_bank_sets.json"]]
    questions, topics, subtopics, banks = map(load_json, (qf, tf, sf, bf))
    sha = checksum([qf, tf, sf, bf])
    unresolved = sum(1 for q in questions if not q.get("topic_id") or not q.get("subtopic_id"))
    print(json.dumps({"dataset":"grammar","questions":len(questions),"topics":len(topics),"subtopics":len(subtopics),"bankSets":len(banks),"missingClassification":unresolved,"checksum":sha}, ensure_ascii=False, indent=2))
    if not args.apply:
        return

    with db(os.getenv("DATABASE_URL")) as conn:
        imp = begin_import(conn, "DAUTOEIC_GRAMMAR", "local", g, sha)
        for t in topics:
            ext = str(t.get("id") or t.get("topic_id")); tid = stable_uuid("grammar-topic", ext)
            conn.execute("""INSERT INTO grammar_topic(id,external_id,code,name_en,name_vi,description_vi,position)
                VALUES(%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT(external_id) DO UPDATE SET name_en=excluded.name_en,name_vi=excluded.name_vi,description_vi=excluded.description_vi""",
                (tid, ext, t.get("code"), t.get("name_en"), t.get("name_vi") or t.get("topic_name_vi") or ext, t.get("description_vi"), t.get("position")))
        for st in subtopics:
            ext = str(st.get("id") or st.get("subtopic_id")); sid = stable_uuid("grammar-subtopic", ext)
            topic_ext = st.get("topic_id"); tid = stable_uuid("grammar-topic", str(topic_ext)) if topic_ext else None
            conn.execute("""INSERT INTO grammar_subtopic(id,external_id,topic_id,code,name_en,name_vi,description_vi,position)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT(external_id) DO UPDATE SET topic_id=excluded.topic_id,name_vi=excluded.name_vi""",
                (sid, ext, tid, st.get("code"), st.get("name_en"), st.get("name_vi") or st.get("subtopic_name_vi") or ext, st.get("description_vi"), st.get("position")))
        for b in banks:
            ext = str(b.get("id") or b.get("bank_set_id")); bid = stable_uuid("grammar-bank", ext)
            conn.execute("""INSERT INTO grammar_bank_set(id,external_id,name,source_url,metadata_json)
                VALUES(%s,%s,%s,%s,%s::jsonb)
                ON CONFLICT(external_id) DO UPDATE SET name=excluded.name""",
                (bid, ext, b.get("name") or b.get("bank_set_name") or ext, b.get("source_url"), json.dumps(b, ensure_ascii=False)))
        count = 0
        for q in questions:
            ext = question_external_id(q); qid = stable_uuid("grammar-question", ext)
            topic = stable_uuid("grammar-topic", str(q["topic_id"])) if q.get("topic_id") else None
            sub = stable_uuid("grammar-subtopic", str(q["subtopic_id"])) if q.get("subtopic_id") else None
            policy = explanation_policy(q)
            conn.execute("""INSERT INTO grammar_question(id,external_question_id,question_text,option_a,option_b,option_c,option_d,correct_answer,explanation_en,explanation_vi,translation_vi,answer_translation_vi,vocabulary_note,difficulty_level,topic_id,subtopic_id,explanation_policy,source_test_id,source_test_name,source_question_number,source_url,metadata_json)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)
                ON CONFLICT(external_question_id) DO UPDATE SET explanation_vi=excluded.explanation_vi,translation_vi=excluded.translation_vi,topic_id=excluded.topic_id,subtopic_id=excluded.subtopic_id,metadata_json=excluded.metadata_json""",
                (qid, ext, q.get("question_text") or "", q.get("option_a"), q.get("option_b"), q.get("option_c"), q.get("option_d"), q.get("correct_answer") or "", q.get("explanation_en"), q.get("explanation_vi"), q.get("translation_vi"), q.get("answer_translation_vi"), q.get("vocabulary"), q.get("difficulty_level"), topic, sub, policy, q.get("test_id"), q.get("test_name"), q.get("question_number"), q.get("source_url"), json.dumps(q, ensure_ascii=False)))
            if q.get("bank_set_id"):
                bid = stable_uuid("grammar-bank", str(q["bank_set_id"]))
                conn.execute("""INSERT INTO grammar_question_membership(question_id,bank_set_id,position) VALUES(%s,%s,%s)
                    ON CONFLICT(question_id,bank_set_id) DO UPDATE SET position=excluded.position""", (qid, bid, q.get("order_index")))
            count += 1
        finish_import(conn, imp, count)
    print(f"Imported {count} grammar questions.")

if __name__ == "__main__":
    main()
