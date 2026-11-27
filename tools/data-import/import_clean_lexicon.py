#!/usr/bin/env python3
"""Import a validated, full Lexicon clean release into PostgreSQL."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

from validate_lexicon_release import SUPPORTED_SCHEMAS
from release_media import backup_media, media_configuration, restore_media

from clean_import_common import (
    ReleasePackage,
    audit_orphans,
    begin_run,
    chunks,
    connect,
    db_url_from_args,
    ensure_single_install,
    finalize_install,
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


COLLECTIONS = (
    ("entries.jsonl", "lexicon_entry"),
    ("items.jsonl", "lexicon_item"),
    ("senses.jsonl", "lexicon_sense"),
    ("forms.jsonl", "lexicon_form"),
    ("pronunciations.jsonl", "lexicon_pronunciation"),
    ("translations.jsonl", "lexicon_translation"),
)


def _json_array(row: dict[str, Any], name: str) -> Any:
    value = row.get(name)
    return [] if value is None else value


def entry_row(package: ReleasePackage, row: dict[str, Any], headword_ids: dict[tuple[str, str], Any]) -> tuple[Any, ...]:
    language = row["language"]
    identity_form = row["identity_form"]
    headword_id = headword_ids[(language, identity_form)]
    package_id = row["id"]
    return (
        scoped_id(package.release_id, "lexicon-entry", package_id),
        package.release_id,
        headword_id,
        package_uuid(package_id, field="entry.id"),
        row["display_form"],
        row["lookup_form"],
        row["entry_type"],
    )


def item_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        scoped_id(package.release_id, "lexicon-item", row["id"]),
        package.release_id,
        scoped_id(package.release_id, "lexicon-entry", row["entry_id"]),
        package_uuid(row["id"], field="item.id"),
        row["pos"],
        row["pos_title"],
        row.get("etymology_number"),
        row.get("etymology_text"),
        row["order_index"],
    )


def sense_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        scoped_id(package.release_id, "lexicon-sense", row["id"]),
        package.release_id,
        scoped_id(package.release_id, "lexicon-entry", row["entry_id"]),
        scoped_id(package.release_id, "lexicon-item", row["item_id"]),
        package_uuid(row["id"], field="sense.id"),
        row["ordinal"],
        row.get("definition_en"),
        pg_json(_json_array(row, "raw_glosses")),
        pg_json(_json_array(row, "tags")),
        pg_json(_json_array(row, "examples")),
        row.get("translation_vi"),
        row.get("matched_qualifier"),
        row["translation_status"],
        pg_json(row.get("definitions") or ({"en":row["definition_en"]} if row.get("definition_en") else {})),
        pg_json(row.get("source_context") or {}),
    )


def form_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        scoped_id(package.release_id, "lexicon-form", row["id"]),
        package.release_id,
        scoped_id(package.release_id, "lexicon-entry", row["entry_id"]),
        scoped_id(package.release_id, "lexicon-item", row["item_id"]),
        package_uuid(row["id"], field="form.id"),
        row["form"],
        row["normalized_form"],
        pg_json(_json_array(row, "tags")),
    )


def pronunciation_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    return (
        scoped_id(package.release_id, "lexicon-pronunciation", row["id"]),
        package.release_id,
        scoped_id(package.release_id, "lexicon-entry", row["entry_id"]),
        scoped_id(package.release_id, "lexicon-item", row["item_id"]),
        package_uuid(row["id"], field="pronunciation.id"),
        row.get("accent"),
        row.get("ipa"),
        row.get("audio_file"),
        row.get("audio_url"),
        row.get("source_url"),
        None,
        pg_json(_json_array(row, "tags")),
        pg_json(row.get("pronunciation_context") or {}),
    )


def translation_row(package: ReleasePackage, row: dict[str, Any]) -> tuple[Any, ...]:
    if package.schema_version not in SUPPORTED_SCHEMAS:
        raise ValueError(f"Unsupported Lexicon schema: {package.schema_version}")
    item_id = row.get("item_id")
    sense_id = row.get("sense_id")
    source_pos = row.get("source_pos") if package.schema_version in ("1.1.0", "2.0.0") else None
    context = row.get("source_context") if package.schema_version in ("1.1.0", "2.0.0") else None
    return (
        scoped_id(package.release_id, "lexicon-translation", row["id"]),
        package.release_id,
        scoped_id(package.release_id, "lexicon-entry", row["entry_id"]),
        scoped_id(package.release_id, "lexicon-item", item_id) if item_id else None,
        scoped_id(package.release_id, "lexicon-sense", sense_id) if sense_id else None,
        package_uuid(row["id"], field="translation.id"),
        row["word_vi"],
        row["source"],
        row["source_scope"],
        row.get("source_sense_qualifier"),
        row["link_status"],
        row.get("reason"),
        pg_json(_json_array(row, "tags")),
        source_pos,
        pg_json(context) if context is not None else None,
    )


def _upsert_headwords(conn, batch: list[dict[str, Any]]) -> dict[tuple[str, str], Any]:
    unique: dict[tuple[str, str], tuple[Any, str, str]] = {}
    for row in batch:
        language = row["language"]
        identity_form = row["identity_form"]
        key = (language, identity_form)
        unique.setdefault(
            key,
            (stable_uuid("lexicon-headword", f"{language}:{identity_form}"), language, identity_form),
        )
    if not unique:
        return {}
    values = list(unique.values())
    placeholders = "(" + ",".join(["%s"] * 3) + ")"
    insert_sql = (
        "INSERT INTO lexicon_headword(id,language,identity_form) VALUES "
        + ",".join([placeholders] * len(values))
        + " ON CONFLICT(language,identity_form) DO NOTHING"
    )
    params = [value for row in values for value in row]
    conn.execute(insert_sql, params)
    key_placeholders = "(" + ",".join(["%s"] * 2) + ")"
    keys = list(unique)
    select_sql = (
        "SELECT h.language,h.identity_form,h.id FROM lexicon_headword h JOIN (VALUES "
        + ",".join([key_placeholders] * len(keys))
        + ") AS requested(language,identity_form)"
        " ON h.language=requested.language AND h.identity_form=requested.identity_form"
    )
    select_params = [value for key in keys for value in key]
    found = {
        (language, form): headword_id
        for language, form, headword_id in conn.execute(select_sql, select_params).fetchall()
    }
    if len(found) != len(unique):
        raise ValueError("Could not resolve all exact Lexicon headword identities")
    return found


def dry_run_report(package: ReleasePackage, batch_size: int) -> dict[str, Any]:
    if batch_size < 1:
        raise ValueError("--batch-size must be positive")
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
        "records_inserted_expected": sum(package.counts.values()),
        "hierarchy": [table for _, table in COLLECTIONS],
        "batch_size": batch_size,
        "import_notes": [
            "All six canonical JSONL collections are imported; translation coverage does not filter entries.",
            "Pronunciation external URLs are retained as provenance; Lexicon audio is not uploaded.",
        ],
    }


def lexicon_orphans(conn, release_id) -> dict[str, int]:
    return audit_orphans(conn, release_id, {
        "entry_headword": """SELECT count(*) FROM lexicon_entry e WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_headword h WHERE h.id=e.headword_id)""",
        "item_entry": """SELECT count(*) FROM lexicon_item i WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_entry e WHERE e.id=i.entry_id AND e.release_id=i.release_id)""",
        "sense_entry": """SELECT count(*) FROM lexicon_sense s WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_entry e WHERE e.id=s.entry_id AND e.release_id=s.release_id)""",
        "sense_item": """SELECT count(*) FROM lexicon_sense s WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_item i WHERE i.id=s.item_id AND i.entry_id=s.entry_id AND i.release_id=s.release_id)""",
        "form_entry": """SELECT count(*) FROM lexicon_form f WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_entry e WHERE e.id=f.entry_id AND e.release_id=f.release_id)""",
        "form_item": """SELECT count(*) FROM lexicon_form f WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_item i WHERE i.id=f.item_id AND i.entry_id=f.entry_id AND i.release_id=f.release_id)""",
        "pronunciation_entry": """SELECT count(*) FROM lexicon_pronunciation p WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_entry e WHERE e.id=p.entry_id AND e.release_id=p.release_id)""",
        "pronunciation_item": """SELECT count(*) FROM lexicon_pronunciation p WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_item i WHERE i.id=p.item_id AND i.entry_id=p.entry_id AND i.release_id=p.release_id)""",
        "translation_entry": """SELECT count(*) FROM lexicon_translation t WHERE release_id=%s AND NOT EXISTS (
            SELECT 1 FROM lexicon_entry e WHERE e.id=t.entry_id AND e.release_id=t.release_id)""",
        "translation_item": """SELECT count(*) FROM lexicon_translation t WHERE release_id=%s AND t.item_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM lexicon_item i WHERE i.id=t.item_id AND i.entry_id=t.entry_id AND i.release_id=t.release_id)""",
        "translation_sense": """SELECT count(*) FROM lexicon_translation t WHERE release_id=%s AND t.sense_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM lexicon_sense s WHERE s.id=t.sense_id AND s.item_id=t.item_id AND s.entry_id=t.entry_id AND s.release_id=t.release_id)""",
    })


def reconcile_translation_provenance(conn, package: ReleasePackage, batch_size: int) -> dict[str, int]:
    """Compare internal provenance with canonical values, including immutable reruns."""
    checked = 0
    for batch in chunks(jsonl_rows(package.root / "translations.jsonl"), batch_size):
        expected = {
            package_uuid(row["id"], field="translation.id"): (
                row.get("source_pos") if package.schema_version in ("1.1.0", "2.0.0") else None,
                row.get("source_context") if package.schema_version in ("1.1.0", "2.0.0") else None,
            ) for row in batch
        }
        actual = {
            row[0]: (row[1], row[2]) for row in conn.execute(
                """SELECT package_translation_id,source_pos,source_context
                     FROM lexicon_translation
                    WHERE release_id=%s AND package_translation_id=ANY(%s)""",
                (package.release_id, list(expected)),
            )
        }
        if actual != expected:
            mismatches = [key for key, value in expected.items() if key not in actual or actual[key] != value]
            raise ValueError(f"Translation provenance reconciliation mismatch: {len(mismatches)} rows; "
                             f"first package ID {mismatches[0] if mismatches else 'unexpected row'}")
        checked += len(batch)
    if checked != package.counts["translations.jsonl"]:
        raise ValueError("Translation provenance row count differs from manifest")
    return {"rows_checked": checked, "mismatch_count": 0}


def reconcile_v2_content(conn, package: ReleasePackage, batch_size: int) -> dict[str, int]:
    """Verify language content and pronunciation evidence, not only collection counts."""
    checked = {}
    if package.schema_version != "2.0.0":
        return checked
    for filename, table, identity, fields in (
        ("senses.jsonl", "lexicon_sense", "package_sense_id", ("definitions", "source_context", "examples", "raw_glosses")),
        ("pronunciations.jsonl", "lexicon_pronunciation", "package_pronunciation_id", ("pronunciation_context", "accent", "ipa")),
    ):
        total = 0
        for batch in chunks(jsonl_rows(package.root / filename), batch_size):
            expected = {package_uuid(row["id"], field=filename): tuple(row.get(field) for field in fields) for row in batch}
            actual = {row[0]: tuple(row[1:]) for row in conn.execute(
                f"SELECT {identity},{','.join(fields)} FROM {table} WHERE release_id=%s AND {identity}=ANY(%s)",
                (package.release_id, list(expected)),
            )}
            if actual != expected:
                raise ValueError(f"Source content reconciliation mismatch in {filename}")
            total += len(batch)
        if total != package.counts[filename]:
            raise ValueError(f"Source content row count differs in {filename}")
        checked[filename] = total
    return checked


def apply_import(
    package: ReleasePackage,
    *,
    database_url: str,
    batch_size: int,
    allow_upgrade: bool = False,
) -> dict[str, Any]:
    if batch_size < 1:
        raise ValueError("--batch-size must be positive")

    conn = connect(database_url)
    run_id = None
    run_succeeded = False
    records_read = 0
    records_inserted = 0
    try:
        lock_import_domain(conn, package.domain)
        ensure_single_install(conn, package, allow_upgrade=allow_upgrade)
        register_release(conn, package)
        baseline = not prior_release_exists(conn, package)
        already_imported = conn.execute(
            """SELECT EXISTS(SELECT 1 FROM dataset_import_run
                 WHERE release_id=%s AND run_type='APPLY' AND status='SUCCEEDED'
                   AND completed_at IS NOT NULL)""",
            (package.release_id,),
        ).fetchone()[0]
        run_id = begin_run(conn, package, "APPLY")

        # A completed snapshot is immutable. Revalidate the clean package and database
        # counts/hierarchy, then audit a zero-write rerun instead of replaying millions
        # of guaranteed conflicts. Failed partial runs still resume through the batches.
        if already_imported:
            expected = {table: package.counts[filename] for filename, table in COLLECTIONS}
            reconciled = reconcile_counts(conn, package.release_id, expected)
            provenance = reconcile_translation_provenance(conn, package, batch_size)
            content_evidence = reconcile_v2_content(conn, package, batch_size)
            orphan_counts = lexicon_orphans(conn, package.release_id)
            orphan_total = sum(orphan_counts.values())
            if orphan_total:
                raise ValueError(f"Release hierarchy has {orphan_total} orphan references: {orphan_counts}")
            headwords = conn.execute(
                "SELECT count(DISTINCT headword_id) FROM lexicon_entry WHERE release_id=%s",
                (package.release_id,),
            ).fetchone()[0]
            if headwords != package.counts["entries.jsonl"]:
                raise ValueError("Lifetime headword count differs from the validated release")
            records_read = package_record_count(package)
            finish_run(conn, run_id, records_read, 0, {
                "manifest_checksum_sha256": package.checksum,
                "validation_status": package.validation_report["status"],
                "manifest_counts": package.counts,
                "database_counts": reconciled,
                "lifetime_headwords": {"expected": package.counts["entries.jsonl"], "actual": headwords},
                "intentionally_skipped_collections": {},
                "baseline_release": baseline,
                "reused_complete_snapshot": True,
                "reconciliation": {
                    "translation_provenance": provenance,
                    "source_content": content_evidence,
                    "rows_read": records_read,
                    "rows_inserted_this_run": 0,
                    "mismatch_count": 0,
                    "orphan_count": orphan_total,
                    "orphans_by_relation": orphan_counts,
                },
            })
            run_succeeded = True
            finalize_install(conn, package, allow_upgrade=allow_upgrade)
            return {
                "mode": "APPLY",
                "run_id": str(run_id),
                "release_id": str(package.release_id),
                "records_read": records_read,
                "records_inserted_this_run": 0,
                "database_counts": reconciled,
                "reused_complete_snapshot": True,
                "installed": True,
            }

        for filename, table in COLLECTIONS:
            path = package.root / filename
            records_expected = package.counts[filename]
            if filename == "entries.jsonl":
                source_rows = jsonl_rows(path)
                for batch in chunks(source_rows, batch_size):
                    with conn.transaction():
                        headword_ids = _upsert_headwords(conn, batch)
                        mapped = [entry_row(package, row, headword_ids) for row in batch]
                        inserted = insert_values(
                            conn,
                            table,
                            ("id", "release_id", "headword_id", "package_entry_id", "display_form", "lookup_form", "entry_type"),
                            mapped,
                        )
                        records_read += len(batch)
                        records_inserted += inserted
                        conn.execute(
                            "UPDATE dataset_import_run SET records_read=%s,records_inserted=%s WHERE id=%s",
                            (records_read, records_inserted, run_id),
                        )
            else:
                converters = {
                    "items.jsonl": item_row,
                    "senses.jsonl": sense_row,
                    "forms.jsonl": form_row,
                    "pronunciations.jsonl": pronunciation_row,
                    "translations.jsonl": translation_row,
                }
                columns = {
                    "items.jsonl": ("id", "release_id", "entry_id", "package_item_id", "part_of_speech", "pos_title", "etymology_number", "etymology_text", "order_index"),
                    "senses.jsonl": ("id", "release_id", "entry_id", "item_id", "package_sense_id", "position", "definition_en", "raw_glosses", "tags", "examples", "translation_vi", "matched_qualifier", "translation_status", "definitions", "source_context"),
                    "forms.jsonl": ("id", "release_id", "entry_id", "item_id", "package_form_id", "form", "normalized_form", "tags"),
                    "pronunciations.jsonl": ("id", "release_id", "entry_id", "item_id", "package_pronunciation_id", "accent", "ipa", "audio_file", "audio_url", "source_url", "cached_audio_object_key", "tags", "pronunciation_context"),
                    "translations.jsonl": ("id", "release_id", "entry_id", "item_id", "sense_id", "package_translation_id", "word_vi", "source", "source_scope", "source_sense_qualifier", "link_status", "unlinked_reason", "tags", "source_pos", "source_context"),
                }[filename]
                converter = converters[filename]
                for batch in chunks(jsonl_rows(path), batch_size):
                    with conn.transaction():
                        mapped = [converter(package, row) for row in batch]
                        inserted = insert_values(conn, table, columns, mapped)
                        records_read += len(batch)
                        records_inserted += inserted
                        conn.execute(
                            "UPDATE dataset_import_run SET records_read=%s,records_inserted=%s WHERE id=%s",
                            (records_read, records_inserted, run_id),
                        )
            if records_expected < 0:
                raise ValueError(f"Invalid manifest count for {filename}")

        expected = {table: package.counts[filename] for filename, table in COLLECTIONS}
        with conn.transaction():
            reconciled = reconcile_counts(conn, package.release_id, expected)
            provenance = reconcile_translation_provenance(conn, package, batch_size)
            content_evidence = reconcile_v2_content(conn, package, batch_size)
            orphan_counts = lexicon_orphans(conn, package.release_id)
            orphan_total = sum(orphan_counts.values())
            if orphan_total:
                raise ValueError(f"Release hierarchy has {orphan_total} orphan references: {orphan_counts}")
            headwords = conn.execute(
                """SELECT count(DISTINCT headword_id) FROM lexicon_entry WHERE release_id=%s""",
                (package.release_id,),
            ).fetchone()[0]
            if headwords != package.counts["entries.jsonl"]:
                raise ValueError(
                    f"Headword reconciliation mismatch: expected {package.counts['entries.jsonl']}, found {headwords}"
                )
            if records_read != sum(expected.values()):
                raise ValueError(f"Rows read differ from manifest: {records_read} vs {sum(expected.values())}")
            details = {
                "manifest_checksum_sha256": package.checksum,
                "validation_status": package.validation_report["status"],
                "manifest_counts": package.counts,
                "database_counts": reconciled,
                "lifetime_headwords": {"expected": package.counts["entries.jsonl"], "actual": headwords},
                "intentionally_skipped_collections": {},
                "baseline_release": baseline,
                "reconciliation": {
                    "translation_provenance": provenance,
                    "source_content": content_evidence,
                    "rows_read": records_read,
                    "rows_inserted_this_run": records_inserted,
                    "mismatch_count": sum(v["expected"] != v["actual"] for v in reconciled.values()),
                    "orphan_count": orphan_total,
                    "orphans_by_relation": orphan_counts,
                },
            }
            finish_run(conn, run_id, records_read, records_inserted, details)
        run_succeeded = True
        finalize_install(conn, package, allow_upgrade=allow_upgrade)
        return {
            "mode": "APPLY",
            "run_id": str(run_id),
            "release_id": str(package.release_id),
            "records_read": records_read,
            "records_inserted_this_run": records_inserted,
            "database_counts": reconciled,
            "installed": True,
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, default=os.getenv("LEXICON_RELEASE_DIR"))
    parser.add_argument("--database-url", default=os.getenv("DATABASE_URL"))
    parser.add_argument("--batch-size", type=int, default=1000)
    parser.add_argument("--apply", action="store_true", help="Write to PostgreSQL; default is a full dry run")
    parser.add_argument("--allow-upgrade", action="store_true", help="Cut over dataset_active_release after a SUCCEEDED APPLY")
    parser.add_argument("--backup-media", type=Path, help="Export manifest-referenced media objects to a backup directory")
    parser.add_argument("--restore-media", type=Path, help="Restore backed-up media objects to target storage")
    parser.add_argument("--prefix", default="", help="Optional target storage prefix for --backup-media/--restore-media")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.backup_media and args.restore_media:
        raise SystemExit("Specify at most one of --backup-media or --restore-media")
    if args.restore_media:
        client, bucket = media_configuration()
        report = restore_media(args.restore_media, client, bucket, release_root=args.release, prefix=args.prefix)
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
        return
    if not args.release:
        raise SystemExit("--release or LEXICON_RELEASE_DIR is required")
    package = validate_clean_release(
        args.release,
        domain="lexicon",
        validator_name="validate_lexicon_release.py",
    )
    if args.backup_media:
        try:
            client, bucket = media_configuration()
        except ValueError:
            client, bucket = None, None
        report = backup_media(package.root, args.backup_media, client, bucket, prefix=args.prefix)
    elif args.apply:
        report = apply_import(
            package,
            database_url=db_url_from_args(args.database_url),
            batch_size=args.batch_size,
            allow_upgrade=args.allow_upgrade,
        )
    else:
        report = dry_run_report(package, args.batch_size)
    print(json.dumps(report, ensure_ascii=False, indent=2, default=str))


if __name__ == "__main__":
    main()
