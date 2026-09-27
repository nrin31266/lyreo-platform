#!/usr/bin/env python3
"""Independent validator for Lyreo Lexicon clean dataset release packages.

Enforces:
- Manifest envelope and file inventory checksums
- Referential integrity (item_id -> items, entry_id -> entries, sense_id -> senses)
- ID uniqueness across all JSONL collections
- Translation linking verification (zero ordinal guessing, exact matched qualifiers)
- Absence of binary audio blobs
- Deterministic archive structure and root conventions
"""
from __future__ import annotations

import argparse
import hashlib
import json
import tarfile
from pathlib import Path, PurePosixPath


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def count_jsonl_lines(path: Path) -> int:
    with path.open("r", encoding="utf-8") as stream:
        return sum(1 for line in stream if line.strip())


def validate(root: Path, archive: Path | None = None, raw_en: Path | None = None) -> dict:
    manifest_path = root / "manifest.json"
    require(manifest_path.is_file(), f"Manifest missing: {manifest_path}")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(manifest.get("format") == "lyreo.dataset-release", "Unsupported format envelope")
    require(manifest.get("manifest_schema_version") == 1, "Unsupported manifest schema version")

    pkg = manifest.get("package", {})
    require(pkg.get("domain") == "lexicon", f"Wrong package domain: {pkg.get('domain')}")
    version = pkg.get("version")
    require(bool(version), "Package version missing in manifest")

    source = manifest.get("source", {})
    source_files = source.get("files")
    require(isinstance(source_files, list) and bool(source_files), "Source files inventory missing")
    source_by_path: dict[str, dict] = {}
    for entry in source_files:
        require(isinstance(entry, dict), "Invalid source files inventory entry")
        path = entry.get("path")
        require(isinstance(path, str) and path not in ("", ".", "..") and Path(path).name == path,
                f"Unsafe source path: {path}")
        require(path not in source_by_path, f"Duplicate source path: {path}")
        require(isinstance(entry.get("size_bytes"), int) and entry["size_bytes"] > 0, f"Invalid source size: {path}")
        checksum = entry.get("sha256")
        require(
            isinstance(checksum, str) and len(checksum) == 64 and all(c in "0123456789abcdef" for c in checksum),
            f"Invalid source SHA-256: {path}",
        )
        source_by_path[path] = entry
    expected_sources = [(source.get("file"), source.get("sha256"))]
    if source.get("auxiliary_vi_file"):
        expected_sources.append((source["auxiliary_vi_file"], source.get("auxiliary_vi_sha256")))
    require(len(source_by_path) == len(expected_sources), "Source files inventory count mismatch")
    for path, checksum in expected_sources:
        require(path in source_by_path and source_by_path[path]["sha256"] == checksum,
                f"Source files inventory mismatch: {path}")

    # 1. Direct check: Absence of binary audio / media files
    AUDIO_EXTENSIONS = {".mp3", ".ogg", ".wav", ".flac", ".aac", ".opus", ".m4a"}
    for p in root.rglob("*"):
        if p.is_file():
            require(
                p.suffix.lower() not in AUDIO_EXTENSIONS,
                f"Binary audio file detected in release package: {p.relative_to(root)}",
            )

    # 2. File inventory checksum & record counts
    inventory = manifest.get("files", [])
    expected_paths = {entry["path"] for entry in inventory}
    require(len(expected_paths) == len(inventory), "Duplicate paths in manifest files inventory")

    actual_paths = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()} - {"manifest.json"}
    require(actual_paths == expected_paths, f"Manifest inventory mismatch: missing={expected_paths - actual_paths}, extra={actual_paths - expected_paths}")

    for entry in inventory:
        rel = entry["path"]
        target = root / rel
        require(not Path(rel).is_absolute() and ".." not in Path(rel).parts, f"Unsafe file path in manifest: {rel}")
        require(target.is_file(), f"Inventory file missing: {target}")
        require(target.stat().st_size == entry["size_bytes"], f"File size mismatch: {rel}")
        actual_hash = sha256_file(target)
        require(actual_hash == entry["sha256"], f"File checksum mismatch: {rel} expected {entry['sha256']} got {actual_hash}")

        if entry.get("records") is not None:
            actual_records = count_jsonl_lines(target)
            require(actual_records == entry["records"], f"Record count mismatch in {rel}: expected {entry['records']}, got {actual_records}")

    # 3. Read & validate collections
    entries_file = root / "entries.jsonl"
    items_file = root / "items.jsonl"
    senses_file = root / "senses.jsonl"
    forms_file = root / "forms.jsonl"
    prons_file = root / "pronunciations.jsonl"
    translations_file = root / "translations.jsonl"

    for req_f in (entries_file, items_file, senses_file, forms_file, prons_file, translations_file):
        require(req_f.is_file(), f"Required collection file missing: {req_f.name}")

    entry_ids: set[str] = set()
    with entries_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = json.loads(line)
            eid = row.get("id")
            require(bool(eid), f"Missing entry id on line {idx}")
            require(eid not in entry_ids, f"Duplicate entry id: {eid}")
            entry_ids.add(eid)
            require(row.get("entry_type") in ("WORD", "PHRASE", "PHRASAL_VERB", "IDIOM", "COLLOCATION"), f"Invalid entry_type in entry {eid}")
            require(bool(row.get("identity_form")), f"Missing identity_form in entry {eid}")
            require(bool(row.get("lookup_form")), f"Missing lookup_form in entry {eid}")

    item_ids: set[str] = set()
    with items_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = json.loads(line)
            iid = row.get("id")
            require(bool(iid), f"Missing item id on line {idx}")
            require(iid not in item_ids, f"Duplicate item id: {iid}")
            item_ids.add(iid)
            eid = row.get("entry_id")
            require(eid in entry_ids, f"Item {iid} references nonexistent entry_id {eid}")
            require(bool(row.get("pos")), f"Item {iid} missing pos")

    sense_ids: set[str] = set()
    available_senses = 0
    missing_senses = 0
    with senses_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = json.loads(line)
            sid = row.get("id")
            require(bool(sid), f"Missing sense id on line {idx}")
            require(sid not in sense_ids, f"Duplicate sense id: {sid}")
            sense_ids.add(sid)

            eid = row.get("entry_id")
            iid = row.get("item_id")
            require(eid in entry_ids, f"Sense {sid} references nonexistent entry_id {eid}")
            require(iid in item_ids, f"Sense {sid} references nonexistent item_id {iid}")

            status = row.get("translation_status")
            require(status in ("AVAILABLE", "MISSING"), f"Invalid translation_status in sense {sid}: {status}")

            if status == "AVAILABLE":
                available_senses += 1
                require(bool(row.get("translation_vi")), f"Sense {sid} marked AVAILABLE but missing translation_vi")
                require(bool(row.get("matched_qualifier")), f"Sense {sid} marked AVAILABLE without matched_qualifier")
            else:
                missing_senses += 1
                require(row.get("translation_vi") is None, f"Sense {sid} marked MISSING but has translation_vi")

    form_ids: set[str] = set()
    with forms_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = json.loads(line)
            fid = row.get("id")
            require(bool(fid), f"Missing form id on line {idx}")
            require(fid not in form_ids, f"Duplicate form id: {fid}")
            form_ids.add(fid)

            eid = row.get("entry_id")
            iid = row.get("item_id")
            require(eid in entry_ids, f"Form {fid} references nonexistent entry_id {eid}")
            require(iid in item_ids, f"Form {fid} references nonexistent item_id {iid}")
            require(bool(row.get("form")), f"Form {fid} missing surface text")

    pron_ids: set[str] = set()
    with prons_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = json.loads(line)
            pid = row.get("id")
            require(bool(pid), f"Missing pronunciation id on line {idx}")
            require(pid not in pron_ids, f"Duplicate pronunciation id: {pid}")
            pron_ids.add(pid)

            eid = row.get("entry_id")
            iid = row.get("item_id")
            require(eid in entry_ids, f"Pronunciation {pid} references nonexistent entry_id {eid}")
            require(iid in item_ids, f"Pronunciation {pid} references nonexistent item_id {iid}")
            require(
                bool(row.get("ipa")) or bool(row.get("audio_url")) or bool(row.get("audio_file")),
                f"Pronunciation {pid} has no phonetic or audio information",
            )
            audio_url = row.get("audio_url")
            if audio_url:
                require(
                    audio_url.startswith("http://") or audio_url.startswith("https://"),
                    f"Pronunciation {pid} audio_url must be HTTP/HTTPS: {audio_url}",
                )

    trans_ids: set[str] = set()
    linked_sense_ids_from_trans: set[str] = set()
    with translations_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = json.loads(line)
            tid = row.get("id")
            require(bool(tid), f"Missing translation id on line {idx}")
            require(tid not in trans_ids, f"Duplicate translation id: {tid}")
            trans_ids.add(tid)

            eid = row.get("entry_id")
            require(eid in entry_ids, f"Translation {tid} references nonexistent entry_id {eid}")

            iid = row.get("item_id")
            if iid:
                require(iid in item_ids, f"Translation {tid} references nonexistent item_id {iid}")

            sid = row.get("sense_id")
            if sid:
                require(sid in sense_ids, f"Translation {tid} references nonexistent sense_id {sid}")
                linked_sense_ids_from_trans.add(sid)

            link_status = row.get("link_status")
            require(
                link_status in ("DIRECT_SENSE", "QUALIFIER_MATCH", "ITEM_CANDIDATE", "ENTRY_CANDIDATE", "UNLINKED"),
                f"Invalid link_status in translation {tid}: {link_status}",
            )
            if link_status in ("DIRECT_SENSE", "QUALIFIER_MATCH"):
                require(bool(sid), f"Translation {tid} has status {link_status} but sense_id is null")
            require(bool(row.get("word_vi")), f"Translation {tid} missing word_vi")

    # Verify that every AVAILABLE sense is backed by at least one linked translation
    with senses_file.open("r", encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            if row.get("translation_status") == "AVAILABLE":
                require(
                    row["id"] in linked_sense_ids_from_trans,
                    f"Sense {row['id']} marked AVAILABLE but has no corresponding record in translations.jsonl",
                )

    # Verify reconciliation reports
    report_rel_path = manifest.get("validation", {}).get("report_path", "validation_report.json")
    report_file = root / report_rel_path
    require(report_file.is_file(), f"Validation report file missing: {report_file}")
    report = json.loads(report_file.read_text(encoding="utf-8"))
    require(report.get("status") == "PASS", f"Validation report status is not PASS: {report.get('status')}")

    src_rec = report.get("source_reconciliation", {})
    require(
        src_rec.get("difference_unreconciled") == 0,
        f"Source rows difference unreconciled: {src_rec.get('difference_unreconciled')}",
    )
    require(
        src_rec.get("source_rows_english") == len(item_ids),
        f"Item count mismatch with English source rows: {src_rec.get('source_rows_english')} vs {len(item_ids)}",
    )

    trans_rec = report.get("translation_source_reconciliation", {})
    if trans_rec:
        require(
            trans_rec.get("enwiktionary_difference_unreconciled") == 0,
            f"Enwiktionary translation difference unreconciled: {trans_rec.get('enwiktionary_difference_unreconciled')}",
        )
        require(
            trans_rec.get("viwiktionary_difference_unreconciled") == 0,
            f"Viwiktionary translation difference unreconciled: {trans_rec.get('viwiktionary_difference_unreconciled')}",
        )
        require(
            trans_rec.get("total_translations_emitted") == len(trans_ids),
            f"Translations count mismatch: {trans_rec.get('total_translations_emitted')} vs {len(trans_ids)}",
        )

    # 4. Validate Archive (if supplied)
    archive_hash = None
    if archive:
        require(archive.is_file(), f"Archive file absent: {archive}")
        archive_hash = sha256_file(archive)
        with tarfile.open(archive, "r:gz") as tar:
            members = tar.getmembers()
            require(len(members) > 0, "Archive is empty")
            roots = {PurePosixPath(m.name).parts[0] for m in members if PurePosixPath(m.name).parts}
            require(len(roots) == 1, f"Archive must have exactly one root directory; found {roots}")
            archive_root = roots.pop()
            expected_root = f"lexicon-{version}"
            require(archive_root == expected_root, f"Unexpected archive root: expected {expected_root}, got {archive_root}")

            names = {m.name for m in members}
            expected_names = {f"{archive_root}/{p}" for p in actual_paths | {"manifest.json"}}
            require(names == expected_names, "Archive member paths do not match release inventory")
            require(
                all(m.isfile() and m.uid == 0 and m.gid == 0 and m.mtime == 0 and m.mode == 0o644 for m in members),
                "Archive tar headers are not canonical (uid/gid/mtime/mode mismatch)",
            )

    # 5. Raw Source Check (if supplied)
    if raw_en:
        require(raw_en.is_file(), f"Raw source absent: {raw_en}")
        require(source_by_path[source["file"]]["size_bytes"] == raw_en.stat().st_size,
                f"Raw source size mismatch: {raw_en}")
        expected_raw_sha = manifest.get("source", {}).get("sha256")
        if expected_raw_sha and expected_raw_sha != "unverified":
            actual_raw_sha = sha256_file(raw_en)
            require(actual_raw_sha == expected_raw_sha, f"Raw source SHA mismatch: expected {expected_raw_sha}, got {actual_raw_sha}")

    return {
        "status": "PASS",
        "package": pkg,
        "counts": {
            "entries": len(entry_ids),
            "items": len(item_ids),
            "senses": len(sense_ids),
            "forms": len(form_ids),
            "pronunciations": len(pron_ids),
            "translations": len(trans_ids),
        },
        "translations": {
            "available": available_senses,
            "missing": missing_senses,
            "total": available_senses + missing_senses,
        },
        "archive_sha256": archive_hash,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True, help="Release directory to validate")
    parser.add_argument("--archive", type=Path, help="Optional release archive to validate")
    parser.add_argument("--raw-en", type=Path, help="Optional raw source file to verify")
    parser.add_argument("--report", type=Path, help="Optional destination path for validation JSON report")
    args = parser.parse_args()

    result = validate(
        root=args.release.resolve(),
        archive=args.archive.resolve() if args.archive else None,
        raw_en=args.raw_en.resolve() if args.raw_en else None,
    )
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
