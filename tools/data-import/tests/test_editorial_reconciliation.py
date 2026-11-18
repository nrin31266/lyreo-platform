"""Opt-in real PostgreSQL regression; owns and removes only its generated schema."""
import os
from pathlib import Path
from types import SimpleNamespace
import uuid
import psycopg
from psycopg import sql
import pytest
from clean_import_common import activate_release
from editorial_reconciliation import reconcile_editorial


@pytest.fixture
def database():
    url = os.getenv("IMPORT_TEST_DATABASE_URL")
    if not url:
        pytest.skip("IMPORT_TEST_DATABASE_URL is required for isolated PostgreSQL importer integration")
    schema = "import_test_" + uuid.uuid4().hex
    with psycopg.connect(url, autocommit=True) as conn:
        conn.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        try:
            conn.execute(sql.SQL("SET search_path TO {}").format(sql.Identifier(schema)))
            migrations = Path(__file__).resolve().parents[3] / "apps/core-service/src/main/resources/db/migration"
            for migration in sorted(migrations.glob("V*.sql")):
                conn.execute(migration.read_text())
            yield conn
        finally:
            conn.execute("SET search_path TO public")
            conn.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


def release(conn, version):
    identifier = uuid.uuid4()
    conn.execute("""INSERT INTO dataset_release(id,domain,package_version,schema_version,checksum_sha256,manifest_json)
        VALUES(%s,'grammar-toeic',%s,'1.0.0',%s,'{}')""", (identifier, version, uuid.uuid4().hex * 2))
    return identifier


def item(conn, release_id, package_id, answer):
    identifier = uuid.uuid4()
    conn.execute("""INSERT INTO assessment_item(id,release_id,package_item_id,kind,stem_en,options,correct_option)
        VALUES(%s,%s,%s,'MCQ_SINGLE','Source question','[{"key":"A","text":"One"},{"key":"B","text":"Two"}]',%s)""",
                 (identifier, release_id, package_id, answer))
    return identifier


def test_unchanged_source_keeps_alias_changed_source_requires_review_and_atomic_activation(database):
    conn = database
    base, same, changed = (release(conn, version) for version in ("1", "2", "3"))
    identity = uuid.uuid4()
    original = item(conn, base, identity, "A")
    target_same = item(conn, same, identity, "A")
    item(conn, changed, identity, "B")
    for release_id in (base, same, changed):
        conn.execute("""INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at)
            VALUES(%s,%s,'APPLY','SUCCEEDED',now())""", (uuid.uuid4(), release_id))
    conn.execute("INSERT INTO dataset_active_release(domain,release_id,activated_by) VALUES('grammar-toeic',%s,'test')", (base,))
    conn.execute("""INSERT INTO content_document(namespace,id,revision,draft,updated_by)
        VALUES('assessment',%s,1,'{"title":"Admin correction"}','admin')""", (original,))
    assert reconcile_editorial(conn, "grammar-toeic", same) == 0
    assert conn.execute("SELECT document_id FROM content_source_alias WHERE source_id=%s", (target_same,)).fetchone()[0] == original
    assert reconcile_editorial(conn, "grammar-toeic", changed) == 1
    package = SimpleNamespace(domain="grammar-toeic", release_id=changed)
    with pytest.raises(ValueError, match="editorial conflicts"):
        activate_release(conn, package, activated_by="admin", action="ACTIVATE")
    assert conn.execute("SELECT release_id FROM dataset_active_release").fetchone()[0] == base
    conn.execute("UPDATE dataset_content_conflict SET resolution='KEEP_EDIT',resolved_at=now(),resolved_by='admin' WHERE release_id=%s", (changed,))
    conn.execute("UPDATE content_document SET revision=revision+1 WHERE id=%s", (original,))
    assert reconcile_editorial(conn, "grammar-toeic", changed) == 1  # Review no longer applies to this edit.
    conn.execute("UPDATE dataset_content_conflict SET resolution='TAKE_SOURCE',resolved_at=now(),resolved_by='admin' WHERE release_id=%s", (changed,))
    assert conn.execute("SELECT enabled FROM content_source_binding").fetchone()[0] is True
    activate_release(conn, package, activated_by="admin", action="ACTIVATE")
    assert conn.execute("SELECT enabled FROM content_source_binding").fetchone()[0] is False
    assert conn.execute("SELECT release_id FROM dataset_active_release").fetchone()[0] == changed
    activate_release(conn, SimpleNamespace(domain="grammar-toeic", release_id=base), activated_by="admin", action="ROLLBACK")
    assert conn.execute("SELECT enabled FROM content_source_binding").fetchone()[0] is True
    assert conn.execute("SELECT count(*) FROM dataset_activation_history").fetchone()[0] == 2


def test_fingerprint_detects_question_reassignment_even_when_item_set_is_unchanged(database):
    from editorial_reconciliation import fingerprint, source_snapshot
    conn = database
    base, target = release(conn, 'base-links'), release(conn, 'target-links')
    catalog, package_topic = uuid.uuid4(), uuid.uuid4()
    item_a, item_b = uuid.uuid4(), uuid.uuid4()
    conn.execute("""INSERT INTO grammar_topic_catalog(id,publication_status,access_mode,reconciliation_status)
        VALUES(%s,'PUBLISHED','PUBLIC','CONFIRMED')""", (catalog,))
    for release_id, swapped in [(base, False), (target, True)]:
        first, second = item(conn, release_id, item_a, 'A'), item(conn, release_id, item_b, 'A')
        version = uuid.uuid4()
        conn.execute("""INSERT INTO grammar_topic_version(id,release_id,catalog_id,package_topic_id,title_en,title_vi,
            order_index,source_access_level,source_is_hidden) VALUES(%s,%s,%s,%s,'Topic','Chủ đề',1,'PUBLIC',false)""",
            (version, release_id, catalog, package_topic))
        for index, selected in enumerate(([second, first] if swapped else [first, second]), 1):
            conn.execute("""INSERT INTO grammar_membership(id,release_id,package_membership_id,item_id,mode,topic_version_id,order_index,source_url)
                VALUES(%s,%s,%s,%s,'topic',%s,%s,'https://example.invalid/source')""",
                (uuid.uuid4(), release_id, f'member-{index}', selected, version, index))
    _, original = source_snapshot(conn, 'grammar-topic', {'catalog': str(catalog)}, base)
    _, reassigned = source_snapshot(conn, 'grammar-topic', {'catalog': str(catalog)}, target)
    assert fingerprint(original) != fingerprint(reassigned)

    conn.execute("INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at) VALUES(%s,%s,'APPLY','SUCCEEDED',now())", (uuid.uuid4(), base))
    conn.execute("INSERT INTO dataset_active_release(domain,release_id,activated_by) VALUES('grammar-toeic',%s,'test')", (base,))
    conn.execute("INSERT INTO content_document(namespace,id,revision,draft,updated_by) VALUES('grammar-topic',%s,1,'{}','admin')", (catalog,))
    assert reconcile_editorial(conn, "grammar-toeic", target) == 1
    assert conn.execute("SELECT target_content->'membership_links' FROM dataset_content_conflict WHERE document_id=%s", (catalog,)).fetchone()[0]
