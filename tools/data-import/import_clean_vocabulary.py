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

from common import stable_uuid
from enrich_vocabulary_release import BASE_MANIFEST, LEGACY_ENRICHED_VERSION, VERSION
from clean_import_common import (
    ReleasePackage,
    begin_run, chunks, connect, db_url_from_args, ensure_single_install, fail_run,
    finalize_install, finish_run, insert_values, jsonl_rows, lock_import_domain,
    package_record_count, pg_json, reconcile_counts, register_release, scoped_id, sha256_file, validate_clean_release,
)
from release_media import media_configuration, media_object_key, remote_object_valid, verify_or_upload_media
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
        if not url and LOCAL_ARCHIVE.is_file():
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


def install(package: ReleasePackage, database_url: str) -> dict:
    client, bucket = shared_storage(MEDIA_WORKERS)
    with connect(database_url) as conn:
        lock_import_domain(conn, package.domain)
        previous = conn.execute("SELECT release_id FROM dataset_active_release WHERE domain='vocabulary'").fetchone()
        previous_id = previous[0] if previous is not None else None
        upgrade = previous_id in UPGRADEABLE_RELEASES and previous_id != package.release_id
        if not upgrade:
            ensure_single_install(conn, package)
        register_release(conn, package)
        run = begin_run(conn, package, "APPLY")
        try:
            sync_media(package, client, bucket)
            with conn.transaction():
                saved_flags = prepare_rebalanced_upgrade(conn, package, previous_id) if upgrade else {}
                inserted = insert_catalog(conn, package)
                if saved_flags:
                    restore_saved_flags(conn, saved_flags)
                details = {"mediaVerified": package.counts["assets"], "mediaUploaded": 0,
                           "upgraded": upgrade, "counts": package.counts}
                finish_run(conn, run, package_record_count(package), inserted, details)
                if upgrade:
                    conn.execute("""
                        UPDATE dataset_active_release SET release_id=%s
                        WHERE domain='vocabulary' AND release_id=%s
                        """, (package.release_id, previous_id))
                finalize_install(conn, package)
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


def prepare_rebalanced_upgrade(conn, package: ReleasePackage, previous_release_id) -> dict[str, tuple[bool, bool]]:
    # Rebalanced releases reassign unit memberships cleanly while preserving editorial visibility/review flags.
    saved_entries = {
        str(ident): (hidden, bool(content.get("needsReview", False)))
        for ident, hidden, content in conn.execute(
            """SELECT e.id, e.hidden, e.content
               FROM vocabulary_unit_entry e
               JOIN vocabulary_unit u ON u.id = e.unit_id
               JOIN vocabulary_pack p ON p.id = u.pack_id
               WHERE p.release_id = %s AND (e.hidden OR COALESCE((e.content->>'needsReview')::boolean, false))""",
            (previous_release_id,),
        ).fetchall()
    }
    conn.execute(
        """DELETE FROM vocabulary_unit_entry WHERE unit_id IN (
               SELECT u.id FROM vocabulary_unit u JOIN vocabulary_pack p ON p.id = u.pack_id WHERE p.release_id = %s
           )""",
        (previous_release_id,),
    )
    conn.execute(
        "DELETE FROM vocabulary_unit WHERE pack_id IN (SELECT id FROM vocabulary_pack WHERE release_id = %s)",
        (previous_release_id,),
    )
    conn.execute("UPDATE vocabulary_pack SET release_id=%s WHERE release_id=%s", (package.release_id, previous_release_id))
    return saved_entries


def restore_saved_flags(conn, saved_entries: dict[str, tuple[bool, bool]]) -> None:
    with conn.cursor() as cursor:
        cursor.executemany(
            """UPDATE vocabulary_unit_entry
               SET hidden = %s,
                   content = jsonb_set(content, '{needsReview}', to_jsonb(%s::boolean), true)
               WHERE id = %s""",
            [(hidden, needs_review, ident) for ident, (hidden, needs_review) in saved_entries.items()],
        )


def insert_catalog(conn, package: ReleasePackage) -> int:
    release = package.release_id

    def asset_id(digest):
        return scoped_id(BASE_RELEASE_ID, "release-media-asset", digest) if digest else None

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
        inserted += insert_values(conn, "release_media_asset",
            ("id", "release_id", "package_asset_id", "blob_sha256", "package_path", "source_paths"),
            [(scoped_id(release, "release-media-asset", row["id"]), release, row["id"], row["id"], row["path"],
              pg_json(row["source_paths"])) for row in batch])
        insert_values(conn, "content_asset",
            ("id", "sha256", "storage_key", "mime_type", "size_bytes", "filename", "created_by", "synthetic"),
            [(asset_id(row["id"]), row["id"], media_object_key(row["id"]), row["mime_type"], row["size_bytes"],
              row["source_paths"][0].rsplit("/", 1)[-1], "dataset-import", True) for row in batch])
    inserted += insert_values(conn, "vocabulary_pack",
        ("id", "release_id", "source_topic", "position", "title_en", "title_vi"),
        [(row["id"], release, row["source_topic"], row["position"], row["title_en"], row["title_vi"])
         for row in jsonl_rows(package.root / "packs.jsonl")])
    inserted += insert_values(conn, "vocabulary_unit",
        ("id", "pack_id", "position", "level", "title"),
        [(row["id"], row["pack_id"], row["position"], row["level"], row["title"])
         for row in jsonl_rows(package.root / "units.jsonl")])
    roles = ("image", "ukAudio", "usAudio", "exampleAudio", "secondExampleAudio")
    for batch in chunks(jsonl_rows(package.root / "entries.jsonl"), 1000):
        inserted += insert_values(conn, "vocabulary_unit_entry",
            ("id", "unit_id", "position", "source_id", "term", "content", "image_id", "uk_audio_id",
             "us_audio_id", "example_audio_id", "second_example_audio_id"),
            [(row["id"], row["unit_id"], row["position"], row["source_id"], row["term"], pg_json(row["content"]),
              *(asset_id(row["media"].get(role)) for role in roles)) for row in batch])
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
    parser.add_argument("--database-url")
    args = parser.parse_args()
    if args.publish_media:
        if not args.release:
            parser.error("--publish-media requires --release with a full release directory")
        package = validated(args.release, complete=True)
        client, bucket = shared_storage(MEDIA_WORKERS)
        result = {"mediaUploaded": sync_media(package, client, bucket, publish=True)}
    else:
        package = validated(args.release) if args.release else prepare()
        result = install(package, db_url_from_args(args.database_url)) if args.apply else package.validation_report
    print(json.dumps(result, ensure_ascii=False, indent=2))
