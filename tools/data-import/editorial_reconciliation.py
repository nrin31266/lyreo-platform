"""Compare only sparse editorial overrides; never mutate immutable source snapshots."""
from __future__ import annotations

import hashlib
import json
import uuid
from clean_import_common import pg_json


def fingerprint(value: dict | None) -> str | None:
    if value is None:
        return None
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), default=str).encode()).hexdigest()


def _rows(conn, table, where, params, *, omit=()):
    # Identifiers and predicates come exclusively from this module, never packages or HTTP.
    cursor = conn.execute(f"SELECT to_jsonb(t) FROM {table} t WHERE {where}", params)
    values = [{k: v for k, v in row[0].items() if k not in {"id", "release_id", "created_at", "updated_at", *omit}} for row in cursor.fetchall()]
    return sorted(values, key=lambda value: json.dumps(value, sort_keys=True, default=str))


def _item(conn, item_id):
    item = _rows(conn, "assessment_item", "t.id=%s", (item_id,))
    media = conn.execute("""SELECT u.asset_id,u.role FROM media_asset_use u WHERE u.item_id=%s
                            ORDER BY u.asset_id,u.role""", (item_id,)).fetchall()
    return {"item": item, "media": media}


def source_snapshot(conn, namespace, identity, release_id):
    if namespace == "lexicon":
        row = conn.execute("SELECT id FROM lexicon_entry WHERE release_id=%s AND headword_id=%s AND entry_type=%s",
                           (release_id, identity["headword"], identity["type"])).fetchone()
        if row is None:
            return None, None
        entry = row[0]
        body = {"entry": _rows(conn, "lexicon_entry", "t.id=%s", (entry,))}
        for table in ("lexicon_item", "lexicon_form", "lexicon_sense", "lexicon_pronunciation", "lexicon_translation"):
            values = conn.execute(f"SELECT to_jsonb(t) FROM {table} t WHERE t.entry_id=%s", (entry,)).fetchall()
            rows = []
            for (value,) in values:
                parent = value.get("item_id")
                sense = value.get("sense_id")
                normalized = {k: v for k, v in value.items() if k not in {"id", "release_id", "entry_id", "item_id", "sense_id"}}
                if parent:
                    normalized["item_identity"] = conn.execute("SELECT package_item_id FROM lexicon_item WHERE id=%s", (parent,)).fetchone()[0]
                if sense:
                    normalized["sense_identity"] = conn.execute("SELECT package_sense_id FROM lexicon_sense WHERE id=%s", (sense,)).fetchone()[0]
                rows.append(normalized)
            body[table] = sorted(rows, key=lambda value: json.dumps(value, sort_keys=True, default=str))
        return entry, body
    if namespace == "assessment":
        row = conn.execute("SELECT id FROM assessment_item WHERE release_id=%s AND package_item_id=%s",
                           (release_id, identity["item"])).fetchone()
        return (row[0], _item(conn, row[0])) if row else (None, None)
    kind = namespace.removeprefix("grammar-")
    table = {"topic": "grammar_topic", "subtopic": "grammar_subtopic", "bank": "grammar_bank", "toeic": "toeic_test"}[kind]
    row = conn.execute(f"SELECT id FROM {table}_version WHERE release_id=%s AND catalog_id=%s",
                       (release_id, identity["catalog"])).fetchone()
    if not row:
        return None, None
    version = row[0]
    body = {"catalog": _rows(conn, f"{table}_version", "t.id=%s", (version,), omit=("topic_version_id",))}
    if kind != "toeic":
        field = {"topic": "topic_version_id", "subtopic": "subtopic_version_id", "bank": "bank_version_id"}[kind]
        body["memberships"] = _rows(conn, "grammar_membership", f"t.{field}=%s", (version,),
                                    omit=("item_id", "topic_version_id", "subtopic_version_id", "bank_version_id"))
        items = conn.execute(f"SELECT DISTINCT item_id FROM grammar_membership WHERE {field}=%s ORDER BY item_id", (version,)).fetchall()
    else:
        body["placements"] = _rows(conn, "toeic_placement", "t.test_version_id=%s", (version,), omit=("test_version_id", "item_id", "group_id"))
        groups = conn.execute("SELECT id FROM toeic_stimulus_group WHERE test_version_id=%s ORDER BY package_group_id", (version,)).fetchall()
        body["groups"] = []
        for (group,) in groups:
            body["groups"].append({
                "group": _rows(conn, "toeic_stimulus_group", "t.id=%s", (group,), omit=("test_version_id",)),
                "documents": _rows(conn, "toeic_document", "t.group_id=%s", (group,), omit=("group_id",)),
                "media": conn.execute("SELECT asset_id,role FROM media_asset_use WHERE group_id=%s ORDER BY asset_id,role", (group,)).fetchall(),
            })
        items = conn.execute("SELECT DISTINCT item_id FROM toeic_placement WHERE test_version_id=%s ORDER BY item_id", (version,)).fetchall()
    # Keep question-to-position links; comparing only the set of questions misses swaps.
    if kind == "toeic":
        body["placement_links"] = conn.execute("""SELECT p.package_placement_id,i.package_item_id,g.package_group_id
            FROM toeic_placement p JOIN assessment_item i ON i.id=p.item_id
            LEFT JOIN toeic_stimulus_group g ON g.id=p.group_id WHERE p.test_version_id=%s
            ORDER BY p.question_number""", (version,)).fetchall()
    else:
        body["membership_links"] = conn.execute(f"""SELECT m.package_membership_id,i.package_item_id
            FROM grammar_membership m JOIN assessment_item i ON i.id=m.item_id
            WHERE m.{field}=%s ORDER BY m.package_membership_id""", (version,)).fetchall()
    body["items"] = sorted((_item(conn, item) for (item,) in items), key=lambda value: json.dumps(value, sort_keys=True, default=str))
    return uuid.UUID(identity["catalog"]), body


def _bind(conn, domain):
    documents = conn.execute("""SELECT d.namespace,d.id FROM content_document d
        WHERE NOT EXISTS(SELECT 1 FROM content_source_binding b WHERE b.namespace=d.namespace AND b.document_id=d.id)
          AND ((%s='lexicon' AND d.namespace='lexicon') OR
               (%s='grammar-toeic' AND d.namespace IN ('assessment','toeic','grammar-topic','grammar-subtopic','grammar-bank')))""", (domain, domain)).fetchall()
    for namespace, document_id in documents:
        if namespace == "lexicon":
            row = conn.execute("SELECT release_id,headword_id,entry_type FROM lexicon_entry WHERE id=%s", (document_id,)).fetchone()
            if not row:
                continue
            release_id, headword, entry_type = row
            identity = {"headword": str(headword), "type": entry_type}
        elif namespace == "assessment":
            row = conn.execute("SELECT release_id,package_item_id FROM assessment_item WHERE id=%s", (document_id,)).fetchone()
            if not row:
                continue
            release_id, item = row
            identity = {"item": str(item)}
        else:
            row = conn.execute("SELECT release_id FROM dataset_active_release WHERE domain=%s", (domain,)).fetchone()
            if not row:
                continue
            release_id = row[0]
            identity = {"catalog": str(document_id)}
            _, snapshot = source_snapshot(conn, namespace, identity, release_id)
            if snapshot is None:
                continue  # Content created by Admin has no source binding.
        conn.execute("""INSERT INTO content_source_binding(namespace,document_id,domain,base_release_id,identity)
            VALUES(%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING""", (namespace, document_id, domain, release_id, pg_json(identity)))


def reconcile_editorial(conn, domain, target_release):
    with conn.transaction():
        _bind(conn, domain)
        bindings = conn.execute("""SELECT b.namespace,b.document_id,b.base_release_id,b.identity,d.revision
            FROM content_source_binding b JOIN content_document d ON d.namespace=b.namespace AND d.id=b.document_id
            WHERE b.domain=%s FOR SHARE OF b,d""", (domain,)).fetchall()
        for namespace, document_id, base, identity, revision in bindings:
            _, old = source_snapshot(conn, namespace, identity, base)
            target_id, new = source_snapshot(conn, namespace, identity, target_release)
            # JDBC-style link queries contain UUID values; normalize before JSONB persistence.
            old = json.loads(json.dumps(old, default=str)) if old is not None else None
            new = json.loads(json.dumps(new, default=str)) if new is not None else None
            old_hash, new_hash = fingerprint(old), fingerprint(new)
            if old_hash is None:
                raise ValueError("Editorial source baseline is missing")
            if target_id:
                conn.execute("""INSERT INTO content_source_alias(namespace,source_id,document_id) VALUES(%s,%s,%s)
                    ON CONFLICT(namespace,source_id) DO UPDATE SET document_id=excluded.document_id""", (namespace, target_id, document_id))
            if old_hash == new_hash:
                conn.execute("DELETE FROM dataset_content_conflict WHERE release_id=%s AND namespace=%s AND document_id=%s",
                             (target_release, namespace, document_id))
                continue
            conn.execute("""INSERT INTO dataset_content_conflict(release_id,namespace,document_id,target_id,revision,base_fingerprint,target_fingerprint,base_content,target_content)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(release_id,namespace,document_id) DO UPDATE SET
                  target_id=excluded.target_id,revision=excluded.revision,base_fingerprint=excluded.base_fingerprint,
                  target_fingerprint=excluded.target_fingerprint,base_content=excluded.base_content,target_content=excluded.target_content,
                  resolution=CASE WHEN dataset_content_conflict.revision=excluded.revision AND dataset_content_conflict.target_fingerprint IS NOT DISTINCT FROM excluded.target_fingerprint THEN dataset_content_conflict.resolution END,
                  resolved_by=CASE WHEN dataset_content_conflict.revision=excluded.revision AND dataset_content_conflict.target_fingerprint IS NOT DISTINCT FROM excluded.target_fingerprint THEN dataset_content_conflict.resolved_by END,
                  resolved_at=CASE WHEN dataset_content_conflict.revision=excluded.revision AND dataset_content_conflict.target_fingerprint IS NOT DISTINCT FROM excluded.target_fingerprint THEN dataset_content_conflict.resolved_at END,
                  reason=CASE WHEN dataset_content_conflict.revision=excluded.revision AND dataset_content_conflict.target_fingerprint IS NOT DISTINCT FROM excluded.target_fingerprint THEN dataset_content_conflict.reason END""",
                (target_release, namespace, document_id, target_id, revision, old_hash, new_hash, pg_json(old), pg_json(new) if new is not None else None))
        return conn.execute("SELECT count(*) FROM dataset_content_conflict WHERE release_id=%s AND resolution IS NULL", (target_release,)).fetchone()[0]


def assert_reconciled(conn, domain, release_id):
    # Keep these locks until activation commits so an edit cannot invalidate a just-reviewed decision.
    conn.execute("""SELECT d.id FROM content_document d JOIN content_source_binding b
        ON b.namespace=d.namespace AND b.document_id=d.id WHERE b.domain=%s FOR SHARE OF d,b""", (domain,)).fetchall()
    unresolved = conn.execute("""SELECT count(*) FROM dataset_content_conflict c JOIN content_document d
        ON d.namespace=c.namespace AND d.id=c.document_id WHERE c.release_id=%s
        AND (c.resolution IS NULL OR c.revision<>d.revision)""", (release_id,)).fetchone()[0]
    if unresolved:
        raise ValueError(f"Resolve {unresolved} editorial conflicts before activating the release")
    conn.execute("""UPDATE content_source_binding b SET enabled=NOT EXISTS(
        SELECT 1 FROM dataset_content_conflict c WHERE c.release_id=%s AND c.namespace=b.namespace
        AND c.document_id=b.document_id AND c.resolution='TAKE_SOURCE') WHERE b.domain=%s""", (release_id, domain))
