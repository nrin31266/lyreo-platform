#!/usr/bin/env python3
"""Validate and install one Vocabulary release."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import json
import os
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any
from urllib.parse import urlparse
import uuid

from common import stable_uuid
from enrich_vocabulary_release import BASE_MANIFEST, LEGACY_ENRICHED_VERSION, VERSION
from clean_import_common import (
    ReleasePackage,
    begin_run, chunks, connect, db_url_from_args, ensure_single_install, fail_run,
    finalize_install, finish_run, insert_values, jsonl_rows, lock_import_domain,
    package_record_count, package_uuid, pg_json, reconcile_counts, register_release, scoped_id, sha256_file,
    validate_clean_release,
)
from release_media import (
    backup_media, media_configuration, media_object_key, remote_object_valid, restore_media, verify_or_upload_media,
)
from scripts.fetch_utils import download_archive, safe_extract_archive

ARCHIVE_NAME = f"vocabulary-catalog-{VERSION}.tar.gz"
ARCHIVE_SHA256 = "2fd037e2824c2663edcff72ff1ba48b958acb1d9953aa8e6935511a8551f2c51"
MANIFEST_SHA256 = "dff95c8c129587636a8d445b03dbd0e64a2ff4c7215ed634ffecb6e9094264df"
DEFAULT_RELEASE_URL = "https://drive.google.com/file/d/1PzxSDGzxihgs1gNW6FwvOuKI59bl2r0P/view"
DEFAULT_RELEASE = Path(__file__).resolve().parents[2] / ".data" / "releases" / "vocabulary" / VERSION
LOCAL_ARCHIVE = Path(__file__).resolve().parents[2] / ".data" / "releases" / ARCHIVE_NAME
MEDIA_WORKERS = 32
BASE_RELEASE_ID = stable_uuid("dataset-release", f"vocabulary:1.0.0:{BASE_MANIFEST}")
LEGACY_110_MANIFEST = "6e3ba8c835c3d41d4af4d2cd744461192be083257d3285c9133cf3bbaa7bfb88"
LEGACY_110_RELEASE_ID = stable_uuid("dataset-release", f"vocabulary:{LEGACY_ENRICHED_VERSION}:{LEGACY_110_MANIFEST}")
PRIOR_120_MANIFEST = "6450c03c8623fe9753e1892a5acf866ad268ac39c92a5c414ad80e4a99e8ec29"
PRIOR_120_RELEASE_ID = stable_uuid("dataset-release", f"vocabulary:1.2.0:{PRIOR_120_MANIFEST}")
PRIOR_121_MANIFEST = "a195388b84d4f70f0ff185ff9cca67af1838a03ecce31dc6c0a91652efc8570d"
PRIOR_121_RELEASE_ID = stable_uuid("dataset-release", f"vocabulary:1.2.1:{PRIOR_121_MANIFEST}")
UPGRADEABLE_RELEASES = {BASE_RELEASE_ID, LEGACY_110_RELEASE_ID, PRIOR_120_RELEASE_ID, PRIOR_121_RELEASE_ID}


def validated(path: Path, *, complete: bool = False) -> ReleasePackage:
    if sha256_file(path / "manifest.json") != (BASE_MANIFEST if complete else MANIFEST_SHA256):
        raise ValueError(f"Vocabulary manifest does not match the pinned {VERSION} release: {path}")
    return validate_clean_release(
        path, domain="vocabulary", validator_name="validate_vocabulary_release.py",
        validator_args=() if complete else ("--catalog-only",),
    )


def shared_storage(max_pool_connections: int = 10):
    if os.environ.get("STORAGE_MODE") != "r2":
        raise ValueError("Vocabulary media requires STORAGE_MODE=r2 in Core and the importer")
    return media_configuration(max_pool_connections=max_pool_connections)


def prepare(target: Path = DEFAULT_RELEASE) -> ReleasePackage:
    if target.exists():
        return validated(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix=".vocabulary-fetch-", dir=target.parent) as directory:
        work = Path(directory)
        url = os.environ.get("VOCABULARY_RELEASE_URL", "").strip()
        explicit_local = bool(url) and urlparse(url).scheme in ("", "file")
        if not explicit_local and LOCAL_ARCHIVE.is_file():
            archive = LOCAL_ARCHIVE
        else:
            archive = work / ARCHIVE_NAME
            download_archive(url or DEFAULT_RELEASE_URL, archive)
        if sha256_file(archive) != ARCHIVE_SHA256:
            raise ValueError("Vocabulary catalog archive failed SHA-256 verification")
        staged = safe_extract_archive(archive, work / "extracted", {f"vocabulary-{VERSION}"})
        package = validated(staged)
        try:
            staged.rename(target)
        except OSError:
            if not target.is_dir():
                raise
            return validated(target)
        return replace(package, root=target.resolve())


def sync_media(package: ReleasePackage, client, bucket: str, *, publish: bool = False) -> int:
    assets = list(jsonl_rows(package.root / "assets.jsonl"))

    def check(asset):
        key = media_object_key(asset["id"])
        if publish:
            return verify_or_upload_media(client, bucket, package.root / asset["path"], sha256=asset["id"],
                                          size_bytes=asset["size_bytes"], mime_type=asset["mime_type"], object_key=key)
        if not remote_object_valid(client, bucket, key, asset["id"], asset["size_bytes"]):
            raise ValueError(f"Vocabulary media is missing in R2: {key}. The release maintainer must publish it.")
        return False

    uploads = checked = 0
    with ThreadPoolExecutor(max_workers=MEDIA_WORKERS) as workers:
        for batch in chunks(assets, 2000):
            uploads += sum(workers.map(check, batch))
            checked += len(batch)
            print(f"Vocabulary media: {checked}/{len(assets)} checked, {uploads} uploaded",
                  file=sys.stderr, flush=True)
    return uploads


def install(
    package: ReleasePackage,
    database_url: str,
    *,
    allow_upgrade: bool = False,
    storage_client=None,
    bucket: str | None = None,
) -> dict:
    if storage_client is None or bucket is None:
        storage_client, bucket = shared_storage(MEDIA_WORKERS)
    with connect(database_url) as conn:
        lock_import_domain(conn, package.domain)
        previous = conn.execute("SELECT release_id FROM dataset_active_release WHERE domain='vocabulary'").fetchone()
        previous_id = previous[0] if previous is not None else None
        upgrade = (
            previous_id is not None
            and previous_id != package.release_id
            and (allow_upgrade or previous_id in UPGRADEABLE_RELEASES)
        )
        if not upgrade:
            ensure_single_install(conn, package, allow_upgrade=allow_upgrade)
        register_release(conn, package)
        run = begin_run(conn, package, "APPLY")
        try:
            sync_media(package, storage_client, bucket)
            with conn.transaction():
                if upgrade:
                    inserted, lineage_stats = reconcile_vocabulary_upgrade(conn, package, previous_id)
                else:
                    inserted = insert_catalog(conn, package)
                    lineage_stats = {"mode": "INSTALL", "conflicts": 0, "adminModified": 0, "removedHidden": 0}
                details = {
                    "mediaVerified": package.counts["assets"],
                    "mediaUploaded": 0,
                    "upgraded": upgrade,
                    "counts": package.counts,
                    "lineage": lineage_stats,
                }
                finish_run(conn, run, package_record_count(package), inserted, details)
                finalize_install(conn, package, allow_upgrade=upgrade or allow_upgrade)
            return {"releaseId": str(package.release_id), "inserted": inserted, **details}
        except Exception as exc:
            fail_run(conn, run, str(exc))
            raise


def merge_translations(content: dict, fresh: dict, *, replace_draft: bool = False) -> dict:
    preserved_review = content.get("translationDraft") is False
    overwrite = replace_draft and not preserved_review
    meaning_by_sense = {(sense["pos"], sense["definitionEn"]): sense["meaningVi"] for sense in fresh["senses"]}
    senses = [
        sense | {"meaningVi": meaning_by_sense.get((sense["pos"], sense["definitionEn"]), sense.get("meaningVi"))}
        if overwrite and (sense["pos"], sense["definitionEn"]) in meaning_by_sense
        else (
            sense if sense.get("meaningVi") else sense | {
                "meaningVi": meaning_by_sense.get((sense["pos"], sense["definitionEn"]))
            }
        )
        for sense in content["senses"]
    ]
    cleaned_base = {k: v for k, v in content.items() if k != "translationDraft"}
    merged = cleaned_base | {"senses": senses, "needsReview": bool(content.get("needsReview", False))}
    for english, vietnamese in (("examples", "examplesVi"), ("collocations", "collocationsVi")):
        original = content[english]
        old_translations = content.get(vietnamese) or [None] * len(original)
        translated = dict(zip(fresh[english], fresh[vietnamese], strict=True))
        merged[vietnamese] = [
            translated[text] if overwrite and text in translated else (old or translated.get(text))
            for text, old in zip(original, old_translations, strict=True)
        ]
    for key in ("synonyms", "antonyms", "family"):
        if key in fresh:
            current_items = list(content.get(key) or [])
            seen = {item.split(" (", 1)[0].strip().casefold() for item in current_items}
            for item in fresh[key]:
                head = item.split(" (", 1)[0].strip().casefold()
                if head and head not in seen and len(current_items) < 12:
                    current_items.append(item)
                    seen.add(head)
            merged[key] = current_items
    return merged


MEDIA_ROLES = ("image", "ukAudio", "usAudio", "exampleAudio", "secondExampleAudio")
_MISSING = object()


def _asset_id(digest: str | None) -> uuid.UUID | None:
    return scoped_id(BASE_RELEASE_ID, "release-media-asset", digest) if digest else None


def _pack_snapshot(source_topic: str, title_en: str, title_vi: str, hidden: bool = False) -> dict[str, Any]:
    return {
        "source_topic": source_topic,
        "title_en": title_en,
        "title_vi": title_vi,
        "hidden": bool(hidden),
    }


def _unit_snapshot(pack_source_topic: str, level: str, title: str, hidden: bool = False) -> dict[str, Any]:
    return {
        "pack_source_topic": pack_source_topic,
        "level": level,
        "title": title,
        "hidden": bool(hidden),
    }


def _entry_snapshot(
    source_id: str,
    term: str,
    content: dict[str, Any],
    media_ids: dict[str, str | None],
    hidden: bool = False,
) -> dict[str, Any]:
    return {
        "source_id": source_id,
        "term": term,
        "content": content,
        "media": media_ids,
        "hidden": bool(hidden),
    }


def _three_way_merge(
    base_map: dict[str, Any],
    admin_map: dict[str, Any],
    incoming_map: dict[str, Any],
    *,
    keys: list[str] | tuple[str, ...],
    admin_revision: int = 0,
    has_base: bool = True,
    field_prefix: str = "",
) -> tuple[dict[str, Any], bool, list[dict[str, Any]]]:
    """Perform 3-way field merge keeping Admin edits on conflict."""
    merged: dict[str, Any] = {}
    conflicts: list[dict[str, Any]] = []
    admin_kept_change = False

    for key in keys:
        b = base_map.get(key, _MISSING)
        a = admin_map.get(key, _MISSING)
        i = incoming_map.get(key, _MISSING)

        if not has_base:
            if admin_revision > 0 and a != i:
                if a is not _MISSING:
                    merged[key] = a
                admin_kept_change = True
                conflicts.append({
                    "field": f"{field_prefix}{key}",
                    "base": None,
                    "admin": None if a is _MISSING else a,
                    "incoming": None if i is _MISSING else i,
                })
            elif i is not _MISSING:
                merged[key] = i
            continue

        admin_changed = a != b
        source_changed = i != b
        if admin_changed and source_changed and a != i:
            if a is not _MISSING:
                merged[key] = a
            admin_kept_change = True
            conflicts.append({
                "field": f"{field_prefix}{key}",
                "base": None if b is _MISSING else b,
                "admin": None if a is _MISSING else a,
                "incoming": None if i is _MISSING else i,
            })
        elif admin_changed:
            if a is not _MISSING:
                merged[key] = a
            admin_kept_change = True
        elif i is not _MISSING:
            merged[key] = i

    return merged, admin_kept_change, conflicts


def _reconcile_pack_row(
    base: dict[str, Any] | None,
    current: dict[str, Any],
    incoming: dict[str, Any],
    revision: int,
    release_id: uuid.UUID,
) -> tuple[dict[str, Any], bool, dict[str, Any] | None]:
    has_base = bool(base)
    merged, kept_admin, conflicts = _three_way_merge(
        base or {}, current, incoming, keys=("title_en", "title_vi", "hidden"), admin_revision=revision, has_base=has_base
    )
    admin_modified = bool(conflicts or kept_admin or revision > 0)
    conflict_json = {"releaseId": str(release_id), "conflicts": conflicts} if conflicts else None
    return merged, admin_modified, conflict_json


def _reconcile_unit_row(
    base: dict[str, Any] | None,
    current: dict[str, Any],
    incoming: dict[str, Any],
    revision: int,
    release_id: uuid.UUID,
) -> tuple[dict[str, Any], bool, dict[str, Any] | None]:
    has_base = bool(base)
    merged, kept_admin, conflicts = _three_way_merge(
        base or {}, current, incoming, keys=("title", "level", "hidden"), admin_revision=revision, has_base=has_base
    )
    admin_modified = bool(conflicts or kept_admin or revision > 0)
    conflict_json = {"releaseId": str(release_id), "conflicts": conflicts} if conflicts else None
    return merged, admin_modified, conflict_json


def _reconcile_entry_row(
    base: dict[str, Any] | None,
    current: dict[str, Any],
    incoming: dict[str, Any],
    revision: int,
    release_id: uuid.UUID,
) -> tuple[dict[str, Any], bool, dict[str, Any] | None]:
    has_base = bool(base)
    base_map = base or {}
    top_keys = ["term", "hidden"] + (["media"] if ("media" in base_map or not has_base) else [])
    merged_top, kept_top, conflicts_top = _three_way_merge(
        base_map, current, incoming, keys=top_keys, admin_revision=revision, has_base=has_base
    )
    if "media" not in top_keys:
        merged_top["media"] = incoming["media"]

    base_content = base_map.get("content") or {}
    current_content = current.get("content") or {}
    incoming_content = incoming.get("content") or {}
    content_keys = sorted(set(base_content) | set(current_content) | set(incoming_content))
    merged_content, kept_content, conflicts_content = _three_way_merge(
        base_content,
        current_content,
        incoming_content,
        keys=content_keys,
        admin_revision=revision,
        has_base=bool(base_content) if has_base else False,
        field_prefix="content.",
    )
    merged_top["content"] = merged_content
    conflicts = conflicts_top + conflicts_content
    admin_modified = bool(conflicts or kept_top or kept_content or revision > 0)
    conflict_json = {"releaseId": str(release_id), "conflicts": conflicts} if conflicts else None
    return merged_top, admin_modified, conflict_json


def _upsert_lineage(conn, rows: list[tuple[Any, ...]]) -> None:
    if not rows:
        return
    with conn.cursor() as cursor:
        for batch in chunks(rows, 500):
            placeholders = ",".join(["(%s,%s,%s,%s,%s,%s,%s,%s,now())"] * len(batch))
            cursor.execute(
                f"""INSERT INTO vocabulary_release_lineage (
                        id, entity_type, entity_id, release_id, stable_key,
                        base_snapshot, admin_modified, conflict_json, updated_at
                    ) VALUES {placeholders}
                    ON CONFLICT (entity_type, entity_id) DO UPDATE SET
                        release_id = EXCLUDED.release_id,
                        stable_key = EXCLUDED.stable_key,
                        base_snapshot = EXCLUDED.base_snapshot,
                        admin_modified = EXCLUDED.admin_modified,
                        conflict_json = EXCLUDED.conflict_json,
                        updated_at = now()""",
                [val for row in batch for val in row],
            )


def _insert_release_assets(conn, package: ReleasePackage) -> int:
    release = package.release_id
    inserted = 0
    for batch in chunks(jsonl_rows(package.root / "assets.jsonl"), 1000):
        blobs = [(row["id"], row["mime_type"], row["size_bytes"], media_object_key(row["id"])) for row in batch]
        insert_values(conn, "media_blob", ("sha256", "mime_type", "size_bytes", "storage_object_key"), blobs)
        actual = conn.execute(
            "SELECT sha256,mime_type,size_bytes,storage_object_key FROM media_blob WHERE sha256=ANY(%s)",
            ([blob[0] for blob in blobs],),
        ).fetchall()
        if set(actual) != set(blobs):
            raise ValueError("Conflicting media blob metadata")
        inserted += insert_values(
            conn,
            "release_media_asset",
            ("id", "release_id", "package_asset_id", "blob_sha256", "package_path", "source_paths"),
            [
                (
                    scoped_id(release, "release-media-asset", row["id"]),
                    release,
                    row["id"],
                    row["id"],
                    row["path"],
                    pg_json(row["source_paths"]),
                )
                for row in batch
            ],
        )
        insert_values(
            conn,
            "content_asset",
            ("id", "sha256", "storage_key", "mime_type", "size_bytes", "filename", "created_by", "synthetic"),
            [
                (
                    _asset_id(row["id"]),
                    row["id"],
                    media_object_key(row["id"]),
                    row["mime_type"],
                    row["size_bytes"],
                    row["source_paths"][0].rsplit("/", 1)[-1],
                    "dataset-import",
                    True,
                )
                for row in batch
            ],
        )
    return inserted


def reconcile_vocabulary_upgrade(conn, package: ReleasePackage, previous_release_id) -> tuple[int, dict[str, int]]:
    """Perform 3-way lineage reconciliation from previous_release_id to package without deleting referenced rows."""
    release = package.release_id
    inserted = _insert_release_assets(conn, package)

    packs_in = list(jsonl_rows(package.root / "packs.jsonl"))
    units_in = list(jsonl_rows(package.root / "units.jsonl"))
    entries_in = list(jsonl_rows(package.root / "entries.jsonl"))
    pack_topic_by_in_id = {row["id"]: row["source_topic"] for row in packs_in}
    unit_meta_by_in_id = {
        row["id"]: (pack_topic_by_in_id[row["pack_id"]], row["level"], row["position"])
        for row in units_in
    }

    lineage_by_entity: dict[tuple[str, uuid.UUID], tuple[str, dict[str, Any], bool]] = {}
    lineage_by_key: dict[tuple[str, str], uuid.UUID] = {}
    for etype, eid, skey, base_snap, admin_mod in conn.execute(
        "SELECT entity_type, entity_id, stable_key, base_snapshot, admin_modified FROM vocabulary_release_lineage"
    ).fetchall():
        lineage_by_entity[(etype, eid)] = (skey, base_snap or {}, bool(admin_mod))
        lineage_by_key[(etype, skey)] = eid

    existing_packs = {
        source_topic: {
            "id": pid,
            "source_topic": source_topic,
            "position": pos,
            "title_en": title_en,
            "title_vi": title_vi,
            "hidden": bool(hidden),
            "revision": int(rev),
        }
        for pid, source_topic, pos, title_en, title_vi, hidden, rev in conn.execute(
            """SELECT id, source_topic, position, title_en, title_vi, hidden, revision
               FROM vocabulary_pack WHERE release_id = %s""",
            (previous_release_id,),
        ).fetchall()
    }
    existing_units_by_id: dict[uuid.UUID, dict[str, Any]] = {}
    existing_units_by_key: dict[str, dict[str, Any]] = {}
    for uid, pid, source_topic, pos, level, title, hidden, rev in conn.execute(
        """SELECT u.id, u.pack_id, p.source_topic, u.position, u.level, u.title, u.hidden, u.revision
           FROM vocabulary_unit u
           JOIN vocabulary_pack p ON p.id = u.pack_id
           WHERE p.release_id = %s""",
        (previous_release_id,),
    ).fetchall():
        unit_rec = {
            "id": uid,
            "pack_id": pid,
            "pack_source_topic": source_topic,
            "position": pos,
            "level": level,
            "title": title,
            "hidden": bool(hidden),
            "revision": int(rev),
        }
        existing_units_by_id[uid] = unit_rec
        existing_units_by_key[f"{source_topic}:{level}:{pos}"] = unit_rec

    existing_entries_by_source: dict[str, dict[str, Any]] = {}
    for eid, uid, pos, source_id, term, content, img, uk, us, ex1, ex2, hidden, rev in conn.execute(
        """SELECT e.id, e.unit_id, e.position, e.source_id, e.term, e.content,
                  e.image_id, e.uk_audio_id, e.us_audio_id, e.example_audio_id, e.second_example_audio_id,
                  e.hidden, e.revision
           FROM vocabulary_unit_entry e
           JOIN vocabulary_unit u ON u.id = e.unit_id
           JOIN vocabulary_pack p ON p.id = u.pack_id
           WHERE p.release_id = %s""",
        (previous_release_id,),
    ).fetchall():
        existing_entries_by_source[source_id] = {
            "id": eid,
            "unit_id": uid,
            "position": pos,
            "source_id": source_id,
            "term": term,
            "content": content or {},
            "media": {
                "image": str(img) if img else None,
                "ukAudio": str(uk) if uk else None,
                "usAudio": str(us) if us else None,
                "exampleAudio": str(ex1) if ex1 else None,
                "secondExampleAudio": str(ex2) if ex2 else None,
            },
            "hidden": bool(hidden),
            "revision": int(rev),
        }

    # Shift existing positions cleanly above 1,000,000 so rebalanced positions never collide.
    conn.execute("UPDATE vocabulary_pack SET position = position + 1000000 WHERE release_id = %s", (previous_release_id,))
    conn.execute(
        """UPDATE vocabulary_unit SET position = position + 1000000
           WHERE pack_id IN (SELECT id FROM vocabulary_pack WHERE release_id = %s)""",
        (previous_release_id,),
    )
    conn.execute(
        """UPDATE vocabulary_unit_entry SET position = position + 1000000
           WHERE unit_id IN (
               SELECT u.id FROM vocabulary_unit u JOIN vocabulary_pack p ON p.id = u.pack_id WHERE p.release_id = %s
           )""",
        (previous_release_id,),
    )

    stats = {"mode": "THREE_WAY_LINEAGE", "conflicts": 0, "adminModified": 0, "removedHidden": 0}
    lineage_rows: list[tuple[Any, ...]] = []
    resolved_pack_ids: dict[str, uuid.UUID] = {}
    matched_pack_topics: set[str] = set()
    max_pack_pos = 0

    for row in packs_in:
        topic = row["source_topic"]
        max_pack_pos = max(max_pack_pos, int(row["position"]))
        inc_snap = _pack_snapshot(topic, row["title_en"], row["title_vi"], hidden=False)
        existing = existing_packs.get(topic)
        if existing is not None:
            matched_pack_topics.add(topic)
            pid = existing["id"]
            base_entry = lineage_by_entity.get(("PACK", pid))
            base_snap = base_entry[1] if base_entry else None
            cur_snap = _pack_snapshot(topic, existing["title_en"], existing["title_vi"], existing["hidden"])
            merged, admin_mod, conflict_json = _reconcile_pack_row(
                base_snap, cur_snap, inc_snap, existing["revision"], release
            )
            conn.execute(
                """UPDATE vocabulary_pack
                   SET release_id = %s, position = %s, title_en = %s, title_vi = %s, hidden = %s
                   WHERE id = %s""",
                (release, row["position"], merged["title_en"], merged["title_vi"], merged["hidden"], pid),
            )
            if conflict_json:
                stats["conflicts"] += 1
            if admin_mod:
                stats["adminModified"] += 1
        else:
            pid = package_uuid(row["id"], field="pack.id")
            admin_mod, conflict_json = False, None
            inserted += insert_values(
                conn,
                "vocabulary_pack",
                ("id", "release_id", "source_topic", "position", "title_en", "title_vi"),
                [(pid, release, topic, row["position"], row["title_en"], row["title_vi"])],
            )
        resolved_pack_ids[row["id"]] = pid
        lineage_rows.append((
            stable_uuid("vocabulary-release-lineage", f"PACK:{pid}"),
            "PACK",
            pid,
            release,
            topic,
            pg_json(inc_snap),
            admin_mod,
            pg_json(conflict_json) if conflict_json is not None else None,
        ))

    removed_packs = 0
    for topic, existing in existing_packs.items():
        if topic in matched_pack_topics:
            continue
        removed_packs += 1
        max_pack_pos += 1
        conn.execute(
            "UPDATE vocabulary_pack SET release_id = %s, position = %s, hidden = true WHERE id = %s",
            (release, max_pack_pos, existing["id"]),
        )

    resolved_unit_ids: dict[str, uuid.UUID] = {}
    claimed_unit_ids: set[uuid.UUID] = set()
    max_unit_pos_by_pack: dict[uuid.UUID, int] = {}

    for row in units_in:
        pack_id = resolved_pack_ids[row["pack_id"]]
        topic = pack_topic_by_in_id[row["pack_id"]]
        stable_key = f"{topic}:{row['level']}:{row['position']}"
        max_unit_pos_by_pack[pack_id] = max(max_unit_pos_by_pack.get(pack_id, 0), int(row["position"]))
        inc_snap = _unit_snapshot(topic, row["level"], row["title"], hidden=False)

        in_uuid = package_uuid(row["id"], field="unit.id")
        candidate = existing_units_by_id.get(in_uuid)
        if candidate is None or candidate["id"] in claimed_unit_ids:
            lineage_uid = lineage_by_key.get(("UNIT", stable_key))
            if lineage_uid is not None and lineage_uid in existing_units_by_id and lineage_uid not in claimed_unit_ids:
                candidate = existing_units_by_id[lineage_uid]
            else:
                key_match = existing_units_by_key.get(stable_key)
                candidate = key_match if (key_match and key_match["id"] not in claimed_unit_ids) else None

        if candidate is not None:
            uid = candidate["id"]
            claimed_unit_ids.add(uid)
            base_entry = lineage_by_entity.get(("UNIT", uid))
            base_snap = base_entry[1] if base_entry else None
            cur_snap = _unit_snapshot(
                candidate["pack_source_topic"], candidate["level"], candidate["title"], candidate["hidden"]
            )
            merged, admin_mod, conflict_json = _reconcile_unit_row(
                base_snap, cur_snap, inc_snap, candidate["revision"], release
            )
            conn.execute(
                """UPDATE vocabulary_unit
                   SET pack_id = %s, position = %s, level = %s, title = %s, hidden = %s
                   WHERE id = %s""",
                (pack_id, row["position"], merged["level"], merged["title"], merged["hidden"], uid),
            )
            if conflict_json:
                stats["conflicts"] += 1
            if admin_mod:
                stats["adminModified"] += 1
        else:
            uid = in_uuid
            admin_mod, conflict_json = False, None
            inserted += insert_values(
                conn,
                "vocabulary_unit",
                ("id", "pack_id", "position", "level", "title"),
                [(uid, pack_id, row["position"], row["level"], row["title"])],
            )
        resolved_unit_ids[row["id"]] = uid
        lineage_rows.append((
            stable_uuid("vocabulary-release-lineage", f"UNIT:{uid}"),
            "UNIT",
            uid,
            release,
            stable_key,
            pg_json(inc_snap),
            admin_mod,
            pg_json(conflict_json) if conflict_json is not None else None,
        ))

    removed_units = 0
    for uid, existing in existing_units_by_id.items():
        if uid in claimed_unit_ids:
            continue
        removed_units += 1
        next_pos = max_unit_pos_by_pack.get(existing["pack_id"], 0) + 1
        max_unit_pos_by_pack[existing["pack_id"]] = next_pos
        conn.execute(
            "UPDATE vocabulary_unit SET position = %s, hidden = true WHERE id = %s",
            (next_pos, uid),
        )

    matched_source_ids: set[str] = set()
    max_entry_pos_by_unit: dict[uuid.UUID, int] = {}

    for row in entries_in:
        uid = resolved_unit_ids[row["unit_id"]]
        source_id = row["source_id"]
        matched_source_ids.add(source_id)
        max_entry_pos_by_unit[uid] = max(max_entry_pos_by_unit.get(uid, 0), int(row["position"]))
        inc_media_ids = {
            role: (str(_asset_id(row["media"].get(role))) if row["media"].get(role) else None)
            for role in MEDIA_ROLES
        }
        inc_snap = _entry_snapshot(source_id, row["term"], row["content"], inc_media_ids, hidden=False)
        existing = existing_entries_by_source.get(source_id)

        if existing is not None:
            eid = existing["id"]
            base_entry = lineage_by_entity.get(("ENTRY", eid))
            base_snap = base_entry[1] if base_entry else None
            cur_snap = _entry_snapshot(
                source_id, existing["term"], existing["content"], existing["media"], existing["hidden"]
            )
            merged, admin_mod, conflict_json = _reconcile_entry_row(
                base_snap, cur_snap, inc_snap, existing["revision"], release
            )
            merged_media = merged["media"]
            conn.execute(
                """UPDATE vocabulary_unit_entry
                   SET unit_id = %s, position = %s, term = %s, content = %s,
                       image_id = %s, uk_audio_id = %s, us_audio_id = %s,
                       example_audio_id = %s, second_example_audio_id = %s, hidden = %s
                   WHERE id = %s""",
                (
                    uid,
                    row["position"],
                    merged["term"],
                    pg_json(merged["content"]),
                    *(uuid.UUID(merged_media[role]) if merged_media.get(role) else None for role in MEDIA_ROLES),
                    merged["hidden"],
                    eid,
                ),
            )
            if conflict_json:
                stats["conflicts"] += 1
            if admin_mod:
                stats["adminModified"] += 1
        else:
            eid = package_uuid(row["id"], field="entry.id")
            admin_mod, conflict_json = False, None
            inserted += insert_values(
                conn,
                "vocabulary_unit_entry",
                (
                    "id", "unit_id", "position", "source_id", "term", "content",
                    "image_id", "uk_audio_id", "us_audio_id", "example_audio_id", "second_example_audio_id",
                ),
                [
                    (
                        eid,
                        uid,
                        row["position"],
                        source_id,
                        row["term"],
                        pg_json(row["content"]),
                        *(_asset_id(row["media"].get(role)) for role in MEDIA_ROLES),
                    )
                ],
            )
        lineage_rows.append((
            stable_uuid("vocabulary-release-lineage", f"ENTRY:{eid}"),
            "ENTRY",
            eid,
            release,
            source_id,
            pg_json(inc_snap),
            admin_mod,
            pg_json(conflict_json) if conflict_json is not None else None,
        ))

    removed_entries = 0
    for source_id, existing in existing_entries_by_source.items():
        if source_id in matched_source_ids:
            continue
        removed_entries += 1
        stats["removedHidden"] += 1
        uid = existing["unit_id"]
        next_pos = max_entry_pos_by_unit.get(uid, 0) + 1
        max_entry_pos_by_unit[uid] = next_pos
        conn.execute(
            "UPDATE vocabulary_unit_entry SET position = %s, hidden = true WHERE id = %s",
            (next_pos, existing["id"]),
        )
        base_entry = lineage_by_entity.get(("ENTRY", existing["id"]))
        base_snap = base_entry[1] if base_entry else _entry_snapshot(
            source_id, existing["term"], existing["content"], existing["media"], existing["hidden"]
        )
        admin_mod = bool(existing["revision"] > 0 or (base_entry and base_entry[2]))
        conflict_json = {"releaseId": str(release), "removedInRelease": True} if admin_mod else None
        lineage_rows.append((
            stable_uuid("vocabulary-release-lineage", f"ENTRY:{existing['id']}"),
            "ENTRY",
            existing["id"],
            release,
            source_id,
            pg_json(base_snap),
            admin_mod,
            pg_json(conflict_json) if conflict_json is not None else None,
        ))

    _upsert_lineage(conn, lineage_rows)

    reconcile_counts(
        conn,
        release,
        {
            "vocabulary_pack": package.counts["packs"] + removed_packs,
            "release_media_asset": package.counts["assets"],
        },
    )
    for name, expected_count, query in (
        (
            "units",
            package.counts["units"] + removed_units,
            "SELECT count(*) FROM vocabulary_unit u JOIN vocabulary_pack p ON p.id=u.pack_id WHERE p.release_id=%s",
        ),
        (
            "entries",
            package.counts["entries"] + removed_entries,
            """SELECT count(*) FROM vocabulary_unit_entry e JOIN vocabulary_unit u ON u.id=e.unit_id
               JOIN vocabulary_pack p ON p.id=u.pack_id WHERE p.release_id=%s""",
        ),
    ):
        if conn.execute(query, (release,)).fetchone()[0] != expected_count:
            raise ValueError(f"Imported {name} count differs from the release")
    return inserted, stats


def insert_catalog(conn, package: ReleasePackage) -> int:
    release = package.release_id
    inserted = _insert_release_assets(conn, package)

    packs_rows = list(jsonl_rows(package.root / "packs.jsonl"))
    inserted += insert_values(
        conn,
        "vocabulary_pack",
        ("id", "release_id", "source_topic", "position", "title_en", "title_vi"),
        [
            (row["id"], release, row["source_topic"], row["position"], row["title_en"], row["title_vi"])
            for row in packs_rows
        ],
    )
    pack_topic_by_id = {row["id"]: row["source_topic"] for row in packs_rows}

    units_rows = list(jsonl_rows(package.root / "units.jsonl"))
    inserted += insert_values(
        conn,
        "vocabulary_unit",
        ("id", "pack_id", "position", "level", "title"),
        [(row["id"], row["pack_id"], row["position"], row["level"], row["title"]) for row in units_rows],
    )

    lineage_rows: list[tuple[Any, ...]] = [
        (
            stable_uuid("vocabulary-release-lineage", f"PACK:{row['id']}"),
            "PACK",
            package_uuid(row["id"], field="pack.id"),
            release,
            row["source_topic"],
            pg_json(_pack_snapshot(row["source_topic"], row["title_en"], row["title_vi"], hidden=False)),
            False,
            None,
        )
        for row in packs_rows
    ] + [
        (
            stable_uuid("vocabulary-release-lineage", f"UNIT:{row['id']}"),
            "UNIT",
            package_uuid(row["id"], field="unit.id"),
            release,
            f"{pack_topic_by_id[row['pack_id']]}:{row['level']}:{row['position']}",
            pg_json(_unit_snapshot(pack_topic_by_id[row["pack_id"]], row["level"], row["title"], hidden=False)),
            False,
            None,
        )
        for row in units_rows
    ]

    for batch in chunks(jsonl_rows(package.root / "entries.jsonl"), 1000):
        inserted += insert_values(
            conn,
            "vocabulary_unit_entry",
            (
                "id", "unit_id", "position", "source_id", "term", "content",
                "image_id", "uk_audio_id", "us_audio_id", "example_audio_id", "second_example_audio_id",
            ),
            [
                (
                    row["id"],
                    row["unit_id"],
                    row["position"],
                    row["source_id"],
                    row["term"],
                    pg_json(row["content"]),
                    *(_asset_id(row["media"].get(role)) for role in MEDIA_ROLES),
                )
                for row in batch
            ],
        )
        for row in batch:
            inc_media = {
                role: (str(_asset_id(row["media"].get(role))) if row["media"].get(role) else None)
                for role in MEDIA_ROLES
            }
            lineage_rows.append((
                stable_uuid("vocabulary-release-lineage", f"ENTRY:{row['id']}"),
                "ENTRY",
                package_uuid(row["id"], field="entry.id"),
                release,
                row["source_id"],
                pg_json(_entry_snapshot(row["source_id"], row["term"], row["content"], inc_media, hidden=False)),
                False,
                None,
            ))

    if inserted > 0:
        _upsert_lineage(conn, lineage_rows)

    reconcile_counts(conn, release, {"vocabulary_pack": package.counts["packs"], "release_media_asset": package.counts["assets"]})
    for name, query in {
        "units": "SELECT count(*) FROM vocabulary_unit u JOIN vocabulary_pack p ON p.id=u.pack_id WHERE p.release_id=%s",
        "entries": """SELECT count(*) FROM vocabulary_unit_entry e JOIN vocabulary_unit u ON u.id=e.unit_id
                      JOIN vocabulary_pack p ON p.id=u.pack_id WHERE p.release_id=%s""",
    }.items():
        if conn.execute(query, (release,)).fetchone()[0] != package.counts[name]:
            raise ValueError(f"Imported {name} count differs from the release")
    return inserted


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, help="Use an already extracted release directory")
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--apply", action="store_true")
    action.add_argument("--publish-media", action="store_true", help="Maintainer only: publish missing files from a full release")
    action.add_argument("--backup-media", type=Path, help="Export manifest-referenced media objects to a backup directory")
    action.add_argument("--restore-media", type=Path, help="Restore backed-up media objects to target storage")
    parser.add_argument("--allow-upgrade", action="store_true", help="Allow controlled upgrade cutover from an existing release")
    parser.add_argument("--prefix", default="", help="Optional target storage prefix for --backup-media/--restore-media")
    parser.add_argument("--database-url")
    args = parser.parse_args()
    if args.publish_media:
        if not args.release:
            parser.error("--publish-media requires --release with a full release directory")
        package = validated(args.release, complete=True)
        client, bucket = shared_storage(MEDIA_WORKERS)
        result = {"mediaUploaded": sync_media(package, client, bucket, publish=True)}
    elif args.backup_media:
        package = validated(args.release) if args.release else prepare()
        client, bucket = media_configuration(max_pool_connections=MEDIA_WORKERS)
        result = backup_media(package.root, args.backup_media, client, bucket, prefix=args.prefix, workers=MEDIA_WORKERS)
    elif args.restore_media:
        client, bucket = media_configuration(max_pool_connections=MEDIA_WORKERS)
        release_root = validated(args.release).root if args.release else None
        result = restore_media(args.restore_media, client, bucket, release_root=release_root, prefix=args.prefix, workers=MEDIA_WORKERS)
    else:
        package = validated(args.release) if args.release else prepare()
        result = (
            install(package, db_url_from_args(args.database_url), allow_upgrade=args.allow_upgrade)
            if args.apply
            else package.validation_report
        )
    print(json.dumps(result, ensure_ascii=False, indent=2))
