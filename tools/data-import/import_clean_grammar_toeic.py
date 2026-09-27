#!/usr/bin/env python3
"""Import a validated clean Grammar/TOEIC release into PostgreSQL and object storage."""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import sys
import uuid
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence

from clean_import_common import (
    ReleasePackage,
    audit_orphans,
    activate_existing_release,
    activate_release,
    begin_run,
    chunks,
    connect,
    db_url_from_args,
    fail_run,
    finish_run,
    insert_values,
    jsonl_rows,
    lock_import_domain,
    package_record_count,
    package_uuid,
    pg_json,
    prior_release_exists,
    reconcile_counts,
    register_release,
    scoped_id,
    stable_uuid,
    unlock_import_domain,
    validate_clean_release,
)
from common import s3_client


COLLECTION_TABLES = {
    "shared/items": "assessment_item",
    "toeic/tests": "toeic_test_version",
    "toeic/groups": "toeic_stimulus_group",
    "toeic/documents": "toeic_document",
    "toeic/placements": "toeic_placement",
    "shared/assets": "release_media_asset",
    "shared/asset_uses": "media_asset_use",
    "grammar/topics": "grammar_topic_version",
    "grammar/subtopics": "grammar_subtopic_version",
    "grammar/bank_sets": "grammar_bank_version",
    "grammar/difficulty_levels": "grammar_difficulty_level_version",
    "grammar/memberships": "grammar_membership",
}

FEATURES = {
    "toeic.full-access": ("TOEIC full access", "Access to restricted TOEIC test catalogs."),
    "grammar.advanced": ("Advanced grammar", "Access to restricted Grammar catalogs."),
}


def _sid(package: ReleasePackage, kind: str, value: Any) -> uuid.UUID:
    return scoped_id(package.release_id, kind, value)


def _uuid(value: Any, field: str) -> uuid.UUID:
    return package_uuid(value, field=field)


def item_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        _sid(package, "assessment-item", row["id"]),
        package.release_id,
        _uuid(row["id"], "item.id"),
        row["kind"],
        row.get("stem_en"),
        row.get("transcript_en"),
        pg_json(row["options"]),
        row["correct_option"],
        row.get("difficulty_level"),
        row.get("explanation_preference"),
        pg_json(row.get("annotations") or {}),
        pg_json(row.get("source_domains") or []),
        pg_json(row.get("provenance") or []),
        pg_json(row["source_only_fields"]) if row.get("source_only_fields") is not None else None,
    )


def test_version_row(package: ReleasePackage, row: dict[str, Any], catalog_id: uuid.UUID) -> tuple[Any, ...]:
    return (
        _sid(package, "toeic-test-version", row["id"]),
        package.release_id,
        catalog_id,
        _uuid(row["id"], "test.id"),
        row["name"],
        row["year"],
        row["test_number"],
        _uuid(row["set_id"], "test.set_id"),
        row.get("order_index"),
        row.get("source_label"),
        row.get("listening_duration_seconds"),
        row.get("reading_duration_seconds"),
        row["total_questions"],
        row.get("difficulty_level"),
        row["is_free"],
        row["is_hidden"],
        row.get("media_version"),
    )


def group_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        _sid(package, "toeic-stimulus-group", row["id"]),
        package.release_id,
        _sid(package, "toeic-test-version", row["test_id"]),
        _uuid(row["id"], "group.id"),
        row["part"],
        row["kind"],
        row.get("title"),
        row.get("order_index"),
        row.get("difficulty_level"),
        row["question_numbers"],
        row.get("transcript_en"),
        row.get("content_translation_vi"),
        row.get("vocabulary_note_vi"),
        row.get("render_html"),
        row.get("source_html"),
        row.get("source_html_sha256"),
        row.get("document_parse_status"),
        row["document_count"],
    )


def document_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        _sid(package, "toeic-document", row["id"]),
        package.release_id,
        _sid(package, "toeic-stimulus-group", row["group_id"]),
        str(row["id"]),
        row["ordinal"],
        row.get("document_type"),
        row["html"],
        row.get("parse_status"),
    )


def placement_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    group_id = row.get("group_id")
    return (
        _sid(package, "toeic-placement", row["id"]),
        package.release_id,
        _uuid(row["id"], "placement.id"),
        _sid(package, "toeic-test-version", row["test_id"]),
        _sid(package, "assessment-item", row["item_id"]),
        _sid(package, "toeic-stimulus-group", group_id) if group_id else None,
        row["section"],
        row["part"],
        row["question_number"],
        row.get("gap_number"),
        row["order_index"],
    )


def topic_version_row(package: ReleasePackage, row: dict[str, Any], catalog_id: uuid.UUID) -> tuple[Any, ...]:
    return (
        _sid(package, "grammar-topic-version", row["id"]),
        package.release_id,
        catalog_id,
        _uuid(row["id"], "topic.id"),
        row["title_en"],
        row["title_vi"],
        row.get("description_en"),
        row.get("description_vi"),
        row.get("icon"),
        row["order_index"],
        row["access_level"],
        row["is_hidden"],
    )


def subtopic_version_row(package: ReleasePackage, row: dict[str, Any], catalog_id: uuid.UUID,
                         topic_catalog_id: uuid.UUID) -> tuple[Any, ...]:
    return (
        _sid(package, "grammar-subtopic-version", row["id"]),
        package.release_id,
        catalog_id,
        topic_catalog_id,
        _uuid(row["id"], "subtopic.id"),
        _uuid(row["topic_id"], "subtopic.topic_id"),
        _sid(package, "grammar-topic-version", row["topic_id"]),
        row["title_en"],
        row["title_vi"],
        row.get("description_en"),
        row.get("description_vi"),
        row.get("level"),
        row["order_index"],
        row["access_level"],
        row["is_hidden"],
    )


def bank_version_row(package: ReleasePackage, row: dict[str, Any], catalog_id: uuid.UUID) -> tuple[Any, ...]:
    return (
        _sid(package, "grammar-bank-version", row["id"]),
        package.release_id,
        catalog_id,
        _uuid(row["id"], "bank_set.id"),
        row["name"],
        row["year"],
        row["order_index"],
        row["access_level"],
        pg_json(row.get("source_counts") or {}),
    )


def difficulty_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        _sid(package, "grammar-difficulty-level", row["id"]),
        package.release_id,
        row["level"],
        str(row["id"]),
        row["name_vi"],
        row.get("description_vi"),
    )


def membership_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    topic_id = row.get("topic_id")
    subtopic_id = row.get("subtopic_id")
    bank_id = row.get("bank_set_id")
    return (
        _sid(package, "grammar-membership", row["id"]),
        package.release_id,
        str(row["id"]),
        _sid(package, "assessment-item", row["item_id"]),
        row["mode"],
        _sid(package, "grammar-topic-version", topic_id) if topic_id else None,
        _sid(package, "grammar-subtopic-version", subtopic_id) if subtopic_id else None,
        _sid(package, "grammar-bank-version", bank_id) if bank_id else None,
        row.get("difficulty_level"),
        row["order_index"],
        row["source_url"],
        str(row["source_test_id"]) if row.get("source_test_id") is not None else None,
        row.get("source_question_number"),
    )


def asset_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        _sid(package, "release-media-asset", row["id"]),
        package.release_id,
        row["id"],
        row["id"],
        row["path"],
        pg_json(row["source_paths"]),
    )


def asset_use_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    owner_type = row["owner_type"]
    if owner_type not in ("group", "item"):
        raise ValueError(f"Unsupported media owner type: {owner_type}")
    owner_id = row["owner_id"]
    group_id = _sid(package, "toeic-stimulus-group", owner_id) if owner_type == "group" else None
    item_id = _sid(package, "assessment-item", owner_id) if owner_type == "item" else None
    return (
        _sid(package, "media-asset-use", row["id"]),
        package.release_id,
        str(row["id"]),
        row["asset_id"],
        row["role"],
        group_id,
        item_id,
        row.get("source_reference"),
        row.get("media_version"),
    )


def topic_catalog_id(package_topic_id: str) -> uuid.UUID:
    return stable_uuid("grammar-topic-catalog", package_topic_id)


def subtopic_catalog_id(package_subtopic_id: str) -> uuid.UUID:
    return stable_uuid("grammar-subtopic-catalog", package_subtopic_id)


def bank_catalog_id(package_bank_id: str) -> uuid.UUID:
    return stable_uuid("grammar-bank-catalog", package_bank_id)


def toeic_catalog_id(year: int, test_number: int) -> uuid.UUID:
    return stable_uuid("toeic-test-catalog", f"{year}:{test_number}")


def access_fields(is_free: bool | None, access_level: str | None, feature_key: str) -> tuple[str, str, str | None]:
    is_public = bool(is_free) if is_free is not None else (str(access_level or "").lower() == "free")
    if is_public:
        return "PUBLIC", "", None
    return "FEATURE", feature_key, feature_key


def initial_catalog_publication_status() -> str:
    # Keep unaccepted and later unknown identities unavailable until reconciliation succeeds.
    return "DRAFT"


def _ensure_feature(conn, key: str) -> None:
    display_name, description = FEATURES[key]
    conn.execute(
        """INSERT INTO entitlement_feature(feature_key,display_name,description)
           VALUES(%s,%s,%s) ON CONFLICT(feature_key) DO NOTHING""",
        (key, display_name, description),
    )


def _catalog_row(conn, *, table: str, unique_where: str,
                 unique_values: tuple[Any, ...], columns: Sequence[str],
                 values: Sequence[Any]) -> tuple[uuid.UUID, bool]:
    known = conn.execute(f"SELECT id FROM {table} WHERE {unique_where}", unique_values).fetchone()
    if known:
        return known[0], True
    conn.execute(
        f"INSERT INTO {table} ({','.join(columns)}) VALUES ({','.join(['%s'] * len(columns))}) ON CONFLICT DO NOTHING",
        values,
    )
    row = conn.execute(f"SELECT id FROM {table} WHERE {unique_where}", unique_values).fetchone()
    if row is None:
        raise ValueError(f"Could not reconcile catalog identity in {table}")
    return row[0], False


def seed_catalogs(conn, package: ReleasePackage, *, baseline: bool) -> dict[str, dict[str, int]]:
    counts = {name: {"created": 0, "reused": 0, "provisional": 0} for name in ("toeic_test_catalog", "grammar_topic_catalog", "grammar_subtopic_catalog", "grammar_bank_catalog")}
    topics = {row["id"]: row for row in jsonl_rows(package.root / "grammar/topics.jsonl")}

    for row in jsonl_rows(package.root / "toeic/tests.jsonl"):
        access, feature, required_feature = access_fields(row["is_free"], None, "toeic.full-access")
        if required_feature:
            _ensure_feature(conn, required_feature)
        ident = toeic_catalog_id(row["year"], row["test_number"])
        catalog, known = _catalog_row(
            conn,
            table="toeic_test_catalog",
            unique_where="year=%s AND test_number=%s",
            unique_values=(row["year"], row["test_number"]),
            columns=("id", "year", "test_number", "publication_status", "access_mode", "required_feature_key", "reconciliation_status"),
            values=(ident, row["year"], row["test_number"], initial_catalog_publication_status(), access, required_feature, "PROVISIONAL"),
        )
        key = "toeic_test_catalog"
        counts[key]["reused" if known else "created"] += 1
        if not baseline and not known:
            counts[key]["provisional"] += 1

    for row in topics.values():
        access, _, required_feature = access_fields(None, row["access_level"], "grammar.advanced")
        if required_feature:
            _ensure_feature(conn, required_feature)
        ident = topic_catalog_id(row["id"])
        catalog, known = _catalog_row(
            conn,
            table="grammar_topic_catalog",
            unique_where="id=%s",
            unique_values=(ident,),
            columns=("id", "publication_status", "access_mode", "required_feature_key", "reconciliation_status"),
            values=(ident, initial_catalog_publication_status(), access, required_feature, "PROVISIONAL"),
        )
        key = "grammar_topic_catalog"
        counts[key]["reused" if known else "created"] += 1
        if not baseline and not known:
            counts[key]["provisional"] += 1

    for row in jsonl_rows(package.root / "grammar/subtopics.jsonl"):
        parent_topic = row["topic_id"]
        parent_catalog = topic_catalog_id(parent_topic)
        if parent_topic not in topics:
            raise ValueError(f"Subtopic references missing topic {parent_topic}")
        access, _, required_feature = access_fields(None, row["access_level"], "grammar.advanced")
        if required_feature:
            _ensure_feature(conn, required_feature)
        ident = subtopic_catalog_id(row["id"])
        known = conn.execute("SELECT id FROM grammar_subtopic_catalog WHERE id=%s", (ident,)).fetchone()
        if known:
            catalog = known[0]
        else:
            conn.execute(
                """INSERT INTO grammar_subtopic_catalog(
                       id,topic_catalog_id,publication_status,access_mode,required_feature_key,reconciliation_status
                   ) VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(id) DO NOTHING""",
                (ident, parent_catalog, initial_catalog_publication_status(), access, required_feature,
                 "PROVISIONAL"),
            )
            actual = conn.execute("SELECT id FROM grammar_subtopic_catalog WHERE id=%s", (ident,)).fetchone()
            if actual is None:
                raise ValueError(f"Could not reconcile subtopic catalog {ident}")
            catalog = actual[0]
        key = "grammar_subtopic_catalog"
        counts[key]["reused" if known else "created"] += 1
        if not baseline and not known:
            counts[key]["provisional"] += 1

    for row in jsonl_rows(package.root / "grammar/bank_sets.jsonl"):
        access, _, required_feature = access_fields(None, row["access_level"], "grammar.advanced")
        if required_feature:
            _ensure_feature(conn, required_feature)
        ident = bank_catalog_id(row["id"])
        known = conn.execute("SELECT id FROM grammar_bank_catalog WHERE id=%s", (ident,)).fetchone()
        if known:
            catalog = known[0]
        else:
            conn.execute(
                """INSERT INTO grammar_bank_catalog(
                       id,year,publication_status,access_mode,required_feature_key,reconciliation_status
                   ) VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(id) DO NOTHING""",
                (ident, row["year"], initial_catalog_publication_status(), access, required_feature,
                 "PROVISIONAL"),
            )
            actual = conn.execute("SELECT id FROM grammar_bank_catalog WHERE id=%s", (ident,)).fetchone()
            if actual is None:
                raise ValueError(f"Could not reconcile bank catalog year {row['year']}")
            catalog = actual[0]
        key = "grammar_bank_catalog"
        counts[key]["reused" if known else "created"] += 1
        if not baseline and not known:
            counts[key]["provisional"] += 1
    return counts


def confirm_first_baseline_catalogs(conn, release_id: uuid.UUID) -> dict[str, int]:
    """Publish source-seeded catalog policy only after the first APPLY is fully reconciled."""
    statements = {
        "toeic_test_catalog": """UPDATE toeic_test_catalog c
            SET publication_status=CASE WHEN v.source_is_hidden THEN 'HIDDEN' ELSE 'PUBLISHED' END,
                access_mode=CASE WHEN v.source_is_free THEN 'PUBLIC' ELSE 'FEATURE' END,
                required_feature_key=CASE WHEN v.source_is_free THEN NULL ELSE 'toeic.full-access' END,
                reconciliation_status='CONFIRMED', updated_at=now()
            FROM toeic_test_version v
            WHERE v.release_id=%s AND v.catalog_id=c.id AND c.reconciliation_status='PROVISIONAL'""",
        "grammar_topic_catalog": """UPDATE grammar_topic_catalog c
            SET publication_status=CASE WHEN v.source_is_hidden THEN 'HIDDEN' ELSE 'PUBLISHED' END,
                access_mode=CASE WHEN lower(v.source_access_level)='free' THEN 'PUBLIC' ELSE 'FEATURE' END,
                required_feature_key=CASE WHEN lower(v.source_access_level)='free' THEN NULL ELSE 'grammar.advanced' END,
                reconciliation_status='CONFIRMED', updated_at=now()
            FROM grammar_topic_version v
            WHERE v.release_id=%s AND v.catalog_id=c.id AND c.reconciliation_status='PROVISIONAL'""",
        "grammar_subtopic_catalog": """UPDATE grammar_subtopic_catalog c
            SET publication_status=CASE WHEN v.source_is_hidden THEN 'HIDDEN' ELSE 'PUBLISHED' END,
                access_mode=CASE WHEN lower(v.source_access_level)='free' THEN 'PUBLIC' ELSE 'FEATURE' END,
                required_feature_key=CASE WHEN lower(v.source_access_level)='free' THEN NULL ELSE 'grammar.advanced' END,
                reconciliation_status='CONFIRMED', updated_at=now()
            FROM grammar_subtopic_version v
            WHERE v.release_id=%s AND v.catalog_id=c.id AND c.reconciliation_status='PROVISIONAL'""",
        "grammar_bank_catalog": """UPDATE grammar_bank_catalog c
            SET publication_status='PUBLISHED',
                access_mode=CASE WHEN lower(v.source_access_level)='free' THEN 'PUBLIC' ELSE 'FEATURE' END,
                required_feature_key=CASE WHEN lower(v.source_access_level)='free' THEN NULL ELSE 'grammar.advanced' END,
                reconciliation_status='CONFIRMED', updated_at=now()
            FROM grammar_bank_version v
            WHERE v.release_id=%s AND v.catalog_id=c.id AND c.reconciliation_status='PROVISIONAL'""",
    }
    promoted: dict[str, int] = {}
    for table, sql in statements.items():
        cursor = conn.execute(sql, (release_id,))
        promoted[table] = cursor.rowcount
    return promoted


def grammar_toeic_orphans(conn, release_id: uuid.UUID) -> dict[str, int]:
    return audit_orphans(conn, release_id, {
        "test_catalog": """SELECT count(*) FROM toeic_test_version v WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM toeic_test_catalog c WHERE c.id=v.catalog_id AND c.year=v.year AND c.test_number=v.test_number)""",
        "group_test_version": """SELECT count(*) FROM toeic_stimulus_group g WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM toeic_test_version v WHERE v.id=g.test_version_id AND v.release_id=g.release_id)""",
        "document_group": """SELECT count(*) FROM toeic_document d WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM toeic_stimulus_group g WHERE g.id=d.group_id AND g.release_id=d.release_id)""",
        "placement_test_version": """SELECT count(*) FROM toeic_placement p WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM toeic_test_version v WHERE v.id=p.test_version_id AND v.release_id=p.release_id)""",
        "placement_item": """SELECT count(*) FROM toeic_placement p WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM assessment_item i WHERE i.id=p.item_id AND i.release_id=p.release_id)""",
        "placement_group": """SELECT count(*) FROM toeic_placement p WHERE release_id=%s AND p.group_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM toeic_stimulus_group g WHERE g.id=p.group_id AND g.test_version_id=p.test_version_id AND g.release_id=p.release_id)""",
        "asset_blob": """SELECT count(*) FROM release_media_asset a WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM media_blob b WHERE b.sha256=a.blob_sha256)""",
        "asset_use_asset": """SELECT count(*) FROM media_asset_use u WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM release_media_asset a WHERE a.release_id=u.release_id AND a.package_asset_id=u.asset_id)""",
        "asset_use_group": """SELECT count(*) FROM media_asset_use u WHERE release_id=%s AND u.group_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM toeic_stimulus_group g WHERE g.id=u.group_id AND g.release_id=u.release_id)""",
        "asset_use_item": """SELECT count(*) FROM media_asset_use u WHERE release_id=%s AND u.item_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM assessment_item i WHERE i.id=u.item_id AND i.release_id=u.release_id)""",
        "topic_catalog": """SELECT count(*) FROM grammar_topic_version v WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM grammar_topic_catalog c WHERE c.id=v.catalog_id)""",
        "subtopic_catalog_parent": """SELECT count(*) FROM grammar_subtopic_version s WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM grammar_subtopic_catalog c WHERE c.id=s.catalog_id AND c.topic_catalog_id=s.topic_catalog_id)""",
        "subtopic_topic_version": """SELECT count(*) FROM grammar_subtopic_version s WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM grammar_topic_version t WHERE t.id=s.topic_version_id AND t.catalog_id=s.topic_catalog_id AND t.release_id=s.release_id AND t.package_topic_id=s.package_topic_id)""",
        "bank_catalog": """SELECT count(*) FROM grammar_bank_version v WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM grammar_bank_catalog c WHERE c.id=v.catalog_id AND c.year=v.year)""",
        "membership_item": """SELECT count(*) FROM grammar_membership m WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM assessment_item i WHERE i.id=m.item_id AND i.release_id=m.release_id)""",
        "membership_topic": """SELECT count(*) FROM grammar_membership m WHERE release_id=%s AND m.topic_version_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM grammar_topic_version t WHERE t.id=m.topic_version_id AND t.release_id=m.release_id)""",
        "membership_subtopic": """SELECT count(*) FROM grammar_membership m WHERE release_id=%s AND m.subtopic_version_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM grammar_subtopic_version s WHERE s.id=m.subtopic_version_id AND s.topic_version_id=m.topic_version_id AND s.release_id=m.release_id)""",
        "membership_bank": """SELECT count(*) FROM grammar_membership m WHERE release_id=%s AND m.bank_version_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM grammar_bank_version b WHERE b.id=m.bank_version_id AND b.release_id=m.release_id)""",
        "membership_difficulty": """SELECT count(*) FROM grammar_membership m WHERE release_id=%s AND m.difficulty_level IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM grammar_difficulty_level_version d WHERE d.release_id=m.release_id AND d.level=m.difficulty_level)""",
    })


def media_object_key(sha256: str) -> str:
    return f"media/sha256/{sha256[:2]}/{sha256}"


def _hash_stream(stream) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
        size += len(chunk)
    return digest.hexdigest(), size


def _remote_object_valid(client, bucket: str, key: str, sha256: str, size: int) -> bool:
    try:
        head = client.head_object(Bucket=bucket, Key=key)
    except Exception as exc:
        response = getattr(exc, "response", {})
        code = str(response.get("Error", {}).get("Code", ""))
        status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        if code in {"404", "NoSuchKey", "NotFound"} or status == 404:
            return False
        raise
    if head.get("ContentLength") != size:
        raise ValueError(f"Object storage key {key} exists with wrong size")
    metadata = {str(k).lower(): v for k, v in (head.get("Metadata") or {}).items()}
    if metadata.get("sha256") == sha256:
        return True
    response = client.get_object(Bucket=bucket, Key=key)
    try:
        actual_hash, actual_size = _hash_stream(response["Body"])
    finally:
        response["Body"].close()
    if (actual_hash, actual_size) != (sha256, size):
        raise ValueError(f"Existing media object failed SHA-256 verification: {key}")
    return True


def verify_or_upload_media(client, bucket: str, path: Path, *, sha256: str,
                           size_bytes: int, mime_type: str, object_key: str) -> bool:
    """Verify immutable content-addressed storage; return True when an upload was needed."""
    actual_hash = hashlib.sha256()
    actual_size = 0
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            actual_hash.update(chunk)
            actual_size += len(chunk)
    if actual_hash.hexdigest() != sha256 or actual_size != size_bytes:
        raise ValueError(f"Package media changed after validation: {path}")
    if _remote_object_valid(client, bucket, object_key, sha256, size_bytes):
        return False
    client.upload_file(
        str(path),
        bucket,
        object_key,
        ExtraArgs={"ContentType": mime_type, "Metadata": {"sha256": sha256}},
    )
    return True


def _media_configuration() -> tuple[Any, str]:
    bucket = os.getenv("R2_BUCKET")
    client = s3_client()
    if client is None or not bucket:
        raise ValueError("R2_ENDPOINT/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_BUCKET are required for media import")
    return client, bucket


def _upload_and_insert_assets(conn, package: ReleasePackage, run_id: uuid.UUID, batch_size: int,
                              client, bucket: str, progress: dict[str, int]) -> tuple[int, int]:
    uploads = 0
    release_rows_inserted = 0
    for batch in chunks(jsonl_rows(package.root / "shared/assets.jsonl"), batch_size):
        for row in batch:
            path = (package.root / row["path"]).resolve(strict=True)
            if not path.is_relative_to(package.root.resolve()):
                raise ValueError(f"Media path escapes release root: {row['path']}")
            key = media_object_key(row["id"])
            if verify_or_upload_media(
                client,
                bucket,
                path,
                sha256=row["id"],
                size_bytes=row["size_bytes"],
                mime_type=row["mime_type"],
                object_key=key,
            ):
                uploads += 1
        blob_rows = [
            (row["id"], row["mime_type"], row["size_bytes"], media_object_key(row["id"]))
            for row in batch
        ]
        asset_rows = [asset_row(package, row) for row in batch]
        with conn.transaction():
            insert_values(conn, "media_blob", ("sha256", "mime_type", "size_bytes", "storage_object_key"), blob_rows)
            for digest, mime_type, size_bytes, object_key in blob_rows:
                existing = conn.execute(
                    "SELECT mime_type,size_bytes,storage_object_key FROM media_blob WHERE sha256=%s",
                    (digest,),
                ).fetchone()
                if existing != (mime_type, size_bytes, object_key):
                    raise ValueError(f"Conflicting media blob metadata for SHA-256 {digest}")
            release_rows_inserted += insert_values(
                conn,
                "release_media_asset",
                ("id", "release_id", "package_asset_id", "blob_sha256", "package_path", "source_paths"),
                asset_rows,
            )
            progress["active_rows_read"] += len(batch)
            progress["records_inserted"] += release_rows_inserted - progress.get("asset_inserted_before", 0)
            progress["asset_inserted_before"] = release_rows_inserted
            conn.execute(
                "UPDATE dataset_import_run SET records_read=%s,records_inserted=%s WHERE id=%s",
                (progress["records_read"], progress["records_inserted"], run_id),
            )
    return uploads, release_rows_inserted


def _process(conn, package: ReleasePackage, run_id: uuid.UUID, *, batch_size: int,
             filename: str, table: str, columns: Sequence[str], mapper: Callable,
             progress: dict[str, int], map_context=None) -> int:
    inserted_total = 0
    read_total = 0
    for batch in chunks(jsonl_rows(package.root / f"{filename}.jsonl"), batch_size):
        with conn.transaction():
            if map_context is None:
                mapped = [mapper(package, row) for row in batch]
            else:
                mapped = [mapper(package, row, *map_context(row)) for row in batch]
            inserted = insert_values(conn, table, columns, mapped)
            inserted_total += inserted
            read_total += len(batch)
            progress["active_rows_read"] += len(batch)
            progress["records_inserted"] += inserted
            conn.execute(
                "UPDATE dataset_import_run SET records_read=%s,records_inserted=%s WHERE id=%s",
                (progress["records_read"], progress["records_inserted"], run_id),
            )
    expected = package.counts[filename]
    if read_total != expected:
        raise ValueError(f"Read count differs from manifest for {filename}: {read_total} vs {expected}")
    return inserted_total


def dry_run_report(package: ReleasePackage, batch_size: int) -> dict[str, Any]:
    if batch_size < 1:
        raise ValueError("--batch-size must be positive")
    media_count = media_bytes = 0
    for row in jsonl_rows(package.root / "shared/assets.jsonl"):
        media_count += 1
        media_bytes += row["size_bytes"]
    return {
        "mode": "DRY_RUN",
        "domain": package.domain,
        "version": package.version,
        "schema_version": package.schema_version,
        "release_checksum_sha256": package.checksum,
        "release_id": str(package.release_id),
        "validation_status": package.validation_report["status"],
        "counts": package.counts,
        "records_read": package_record_count(package),
        "records_inserted_expected": sum(
            count for name, count in package.counts.items()
            if not (name.startswith("quarantine/") or name.startswith("source_only/"))
        ),
        "intentionally_skipped_collections": {
            name: count for name, count in package.counts.items()
            if name.startswith("quarantine/") or name.startswith("source_only/")
        },
        "media": {"assets": media_count, "bytes": media_bytes, "object_key_scheme": "media/sha256/{prefix}/{sha256}"},
        "batch_size": batch_size,
        "import_notes": [
            "Media blobs are content-addressed and deduplicated by SHA-256.",
            "Catalog policy is seeded only for new identities; later releases preserve runtime policy.",
            "Unmatched quarantine/source-only collections are audited and skipped from active content tables.",
        ],
    }


def apply_import(package: ReleasePackage, *, database_url: str, batch_size: int,
                 activate: bool = False, rollback: bool = False,
                 activated_by: str | None = None, storage_client=None,
                 bucket: str | None = None) -> dict[str, Any]:
    if batch_size < 1:
        raise ValueError("--batch-size must be positive")
    if activate and rollback:
        raise ValueError("--activate and --rollback are mutually exclusive")
    if (activate or rollback) and not activated_by:
        raise ValueError("--activated-by is required with --activate or --rollback")
    if storage_client is None or bucket is None:
        storage_client, bucket = _media_configuration()

    conn = connect(database_url)
    run_id = None
    run_succeeded = False
    progress = {"records_read": package_record_count(package), "active_rows_read": 0, "records_inserted": 0}
    try:
        lock_import_domain(conn, package.domain)
        register_release(conn, package)
        baseline = not prior_release_exists(conn, package)
        run_id = begin_run(conn, package, "APPLY")
        conn.execute(
            "UPDATE dataset_import_run SET records_read=%s WHERE id=%s",
            (progress["records_read"], run_id),
        )

        with conn.transaction():
            catalog_counts = seed_catalogs(conn, package, baseline=baseline)

        uploads, _ = _upload_and_insert_assets(conn, package, run_id, batch_size,
                                               storage_client, bucket, progress)

        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="shared/items", table="assessment_item",
            columns=("id", "release_id", "package_item_id", "kind", "stem_en", "transcript_en", "options", "correct_option", "difficulty_level", "explanation_preference", "annotations", "source_domains", "provenance", "source_only_fields"),
            mapper=item_row, progress=progress,
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="toeic/tests", table="toeic_test_version",
            columns=("id", "release_id", "catalog_id", "package_test_id", "name", "year", "test_number", "set_id", "order_index", "source_label", "listening_duration_seconds", "reading_duration_seconds", "total_questions", "difficulty_level", "source_is_free", "source_is_hidden", "media_version"),
            mapper=test_version_row, progress=progress,
            map_context=lambda row: (toeic_catalog_id(row["year"], row["test_number"]),),
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="toeic/groups", table="toeic_stimulus_group",
            columns=("id", "release_id", "test_version_id", "package_group_id", "part", "kind", "title", "order_index", "difficulty_level", "question_numbers", "transcript_en", "content_translation_vi", "vocabulary_note_vi", "render_html", "source_html", "source_html_sha256", "document_parse_status", "document_count"),
            mapper=group_row, progress=progress,
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="toeic/documents", table="toeic_document",
            columns=("id", "release_id", "group_id", "package_doc_id", "ordinal", "document_type", "html", "parse_status"),
            mapper=document_row, progress=progress,
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="toeic/placements", table="toeic_placement",
            columns=("id", "release_id", "package_placement_id", "test_version_id", "item_id", "group_id", "section", "part", "question_number", "gap_number", "order_index"),
            mapper=placement_row, progress=progress,
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="shared/asset_uses", table="media_asset_use",
            columns=("id", "release_id", "package_use_id", "asset_id", "role", "group_id", "item_id", "source_reference", "media_version"),
            mapper=asset_use_row, progress=progress,
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="grammar/topics", table="grammar_topic_version",
            columns=("id", "release_id", "catalog_id", "package_topic_id", "title_en", "title_vi", "description_en", "description_vi", "icon", "order_index", "source_access_level", "source_is_hidden"),
            mapper=topic_version_row, progress=progress,
            map_context=lambda row: (topic_catalog_id(row["id"]),),
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="grammar/subtopics", table="grammar_subtopic_version",
            columns=("id", "release_id", "catalog_id", "topic_catalog_id", "package_subtopic_id", "package_topic_id", "topic_version_id", "title_en", "title_vi", "description_en", "description_vi", "difficulty_level", "order_index", "source_access_level", "source_is_hidden"),
            mapper=subtopic_version_row, progress=progress,
            map_context=lambda row: (subtopic_catalog_id(row["id"]), topic_catalog_id(row["topic_id"])),
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="grammar/bank_sets", table="grammar_bank_version",
            columns=("id", "release_id", "catalog_id", "package_bank_id", "name", "year", "order_index", "source_access_level", "source_counts"),
            mapper=bank_version_row, progress=progress,
            map_context=lambda row: (bank_catalog_id(row["id"]),),
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="grammar/difficulty_levels", table="grammar_difficulty_level_version",
            columns=("id", "release_id", "level", "package_level_id", "name_vi", "description_vi"),
            mapper=difficulty_row, progress=progress,
        )
        _process(
            conn, package, run_id, batch_size=batch_size,
            filename="grammar/memberships", table="grammar_membership",
            columns=("id", "release_id", "package_membership_id", "item_id", "mode", "topic_version_id", "subtopic_version_id", "bank_version_id", "difficulty_level", "order_index", "source_url", "source_test_id", "source_question_number"),
            mapper=membership_row, progress=progress,
        )

        expected = {table: package.counts[name] for name, table in COLLECTION_TABLES.items()}
        with conn.transaction():
            reconciled = reconcile_counts(conn, package.release_id, expected)
            orphan_counts = grammar_toeic_orphans(conn, package.release_id)
            orphan_total = sum(orphan_counts.values())
            if orphan_total:
                raise ValueError(f"Release hierarchy has {orphan_total} orphan references: {orphan_counts}")
            if progress["active_rows_read"] != sum(expected.values()):
                raise ValueError(
                    f"Rows processed differ from active manifest collections: "
                    f"{progress['active_rows_read']} vs {sum(expected.values())}"
                )
            details = {
                "manifest_checksum_sha256": package.checksum,
                "validation_status": package.validation_report["status"],
                "manifest_counts": package.counts,
                "database_counts": reconciled,
                "intentionally_skipped_collections": {
                    name: count for name, count in package.counts.items()
                    if name.startswith("quarantine/") or name.startswith("source_only/")
                },
                "catalog_reconciliation": catalog_counts,
                "baseline_catalogs_confirmed": confirm_first_baseline_catalogs(conn, package.release_id) if baseline else {},
                "media": {
                    "assets": package.counts["shared/assets"],
                    "uploads_this_run": uploads,
                    "object_key_scheme": "media/sha256/{prefix}/{sha256}",
                },
                "reconciliation": {
                    "active_rows_read": progress["active_rows_read"],
                    "rows_inserted_this_run": progress["records_inserted"],
                    "mismatch_count": sum(v["expected"] != v["actual"] for v in reconciled.values()),
                    "orphan_count": orphan_total,
                    "orphans_by_relation": orphan_counts,
                },
            }
            finish_run(conn, run_id, package_record_count(package), progress["records_inserted"], details)
        run_succeeded = True

        if activate or rollback:
            activate_release(conn, package, activated_by=activated_by or "",
                             action="ROLLBACK" if rollback else "ACTIVATE")
        return {
            "mode": "APPLY",
            "run_id": str(run_id),
            "release_id": str(package.release_id),
            "records_read": package_record_count(package),
            "records_inserted_this_run": progress["records_inserted"],
            "database_counts": reconciled,
            "intentionally_skipped_collections": {
                name: count for name, count in package.counts.items()
                if name.startswith("quarantine/") or name.startswith("source_only/")
            },
            "media_uploads_this_run": uploads,
            "activated": bool(activate or rollback),
            "activation_action": "ROLLBACK" if rollback else "ACTIVATE" if activate else None,
        }
    except Exception as exc:
        if run_id is not None and not run_succeeded:
            try:
                fail_run(conn, run_id, str(exc))
            except Exception:
                pass
        raise
    finally:
        try:
            unlock_import_domain(conn, package.domain)
        except Exception:
            pass
        conn.close()


def activate_only(package: ReleasePackage, *, database_url: str, activated_by: str,
                  rollback: bool = False) -> dict[str, Any]:
    conn = connect(database_url)
    try:
        lock_import_domain(conn, package.domain)
        counts = activate_existing_release(
            conn,
            package,
            activated_by=activated_by,
            action="ROLLBACK" if rollback else "ACTIVATE",
            expected_by_table={table: package.counts[name] for name, table in COLLECTION_TABLES.items()},
        )
        return {"mode": "ACTIVATE_ONLY", "release_id": str(package.release_id),
                "activation_action": "ROLLBACK" if rollback else "ACTIVATE", "database_counts": counts}
    finally:
        try:
            unlock_import_domain(conn, package.domain)
        except Exception:
            pass
        conn.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, default=os.getenv("GRAMMAR_TOEIC_RELEASE_DIR"))
    parser.add_argument("--database-url", default=os.getenv("DATABASE_URL"))
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--apply", action="store_true", help="Write to PostgreSQL/storage; default is a full dry run")
    activation = parser.add_mutually_exclusive_group()
    activation.add_argument("--activate", action="store_true", help="Set this imported release active")
    activation.add_argument("--rollback", action="store_true", help="Activate this older release and record a rollback")
    activation.add_argument("--activate-only", action="store_true", help="Activate an already imported release")
    activation.add_argument("--rollback-only", action="store_true", help="Roll back to an already imported release")
    parser.add_argument("--activated-by", help="Operator label recorded in activation history")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.release:
        raise SystemExit("--release or GRAMMAR_TOEIC_RELEASE_DIR is required")
    if (args.activate or args.rollback) and not args.apply:
        raise SystemExit("--activate/--rollback requires --apply")
    if (args.activate_only or args.rollback_only) and args.apply:
        raise SystemExit("--activate-only/--rollback-only cannot be combined with --apply")
    if (args.activate or args.rollback or args.activate_only or args.rollback_only) and not args.activated_by:
        raise SystemExit("--activated-by is required for activation")
    package = validate_clean_release(
        args.release,
        domain="grammar-toeic",
        validator_name="validate_grammar_toeic_release.py",
    )
    if args.activate_only or args.rollback_only:
        report = activate_only(
            package,
            database_url=db_url_from_args(args.database_url),
            activated_by=args.activated_by,
            rollback=args.rollback_only,
        )
    elif args.apply:
        client, bucket = _media_configuration()
        report = apply_import(
            package,
            database_url=db_url_from_args(args.database_url),
            batch_size=args.batch_size,
            activate=args.activate,
            rollback=args.rollback,
            activated_by=args.activated_by,
            storage_client=client,
            bucket=bucket,
        )
    else:
        report = dry_run_report(package, args.batch_size)
    print(json.dumps(report, ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    main()
