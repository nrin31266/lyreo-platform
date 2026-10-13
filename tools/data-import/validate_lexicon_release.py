#!/usr/bin/env python3
"""Independent validator for Lyreo Lexicon clean dataset release packages.

Enforces:
- Manifest envelope and file inventory checksums
- Referential integrity (item_id -> items, entry_id -> entries, sense_id -> senses)
- ID uniqueness across all JSONL collections
- Candidate scope/cardinality and selected internal VI provenance
- Optional independent RAW VI occurrence/context verification (not semantic adjudication)
- Absence of binary audio blobs
- Deterministic archive structure and root conventions
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import tarfile
import unicodedata
import uuid
from collections import Counter
from pathlib import Path

from lexicon_vi_context import (
    EXAMPLE_CONTEXT_FIELDS,
    KNOWN_OMITTED_EXAMPLE_FIELDS,
    KNOWN_OMITTED_RECORD_FIELDS,
    RECORD_CONTEXT_FIELDS,
    SENSE_CONTEXT_FIELDS,
)

SUPPORTED_SCHEMAS = frozenset({"1.0.0", "1.1.0"})
CONTEXT_KEYS = frozenset({"record_line", "record_context", "sense_ordinal", "sense_context", "gloss_ordinal"})
VI_REASONS = frozenset({"AUXILIARY_VI_UNIQUE_POS_ITEM", "AUXILIARY_VI_AMBIGUOUS_POS_ITEMS",
                        "AUXILIARY_VI_NO_COMPATIBLE_POS_ITEM", "AUXILIARY_VI_UNKNOWN_POS"})


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"Non-finite JSON number: {value}")


def _finite_json_float(value: str) -> float:
    result = float(value)
    require(math.isfinite(result), f"Non-finite JSON number: {value}")
    return result


def load_json(value: str) -> dict:
    result = json.loads(value, parse_constant=_reject_json_constant, parse_float=_finite_json_float)
    require(isinstance(result, dict), "Canonical/RAW JSON record must be an object")
    return result


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def count_jsonl_lines(path: Path) -> int:
    with path.open("r", encoding="utf-8") as stream:
        return sum(1 for line in stream if line.strip())


def _identity(value: str) -> str:
    return unicodedata.normalize("NFKC", value).strip()


def _source_pos(value: str | None) -> str | None:
    normalized = (value or "").strip().lower()
    return normalized if normalized and normalized != "unknown" else None


def _occurrence_id(checksum: str, locator: tuple[int, int, int]) -> str:
    # Reconstruct the contract independently; do not call the builder's ID helper.
    line, sense, gloss = locator
    return str(uuid.uuid5(uuid.NAMESPACE_URL,
                         f"lyreo:lexicon:trans:viwiktionary:occurrence:{checksum}:{line}:{sense}:{gloss}"))


def _string_array(value: object, field: str) -> None:
    require(value is None or (isinstance(value, list) and all(isinstance(v, str) for v in value)),
            f"Invalid VI context {field}: expected nullable string array")


def _context(row: dict, checksum: str) -> tuple[int, int, int]:
    context = row.get("source_context")
    require(isinstance(context, dict), "Invalid VI source_context: expected object")
    require(set(context) == CONTEXT_KEYS, "Unknown or missing VI source_context keys")
    for key in ("record_line", "sense_ordinal", "gloss_ordinal"):
        require(type(context[key]) is int and context[key] > 0, f"Invalid VI locator {key}")
    record = context["record_context"]
    sense = context["sense_context"]
    require(isinstance(record, dict) and set(record) <= RECORD_CONTEXT_FIELDS,
            "Invalid record_context object/allowlist")
    require(isinstance(sense, dict) and set(sense) <= SENSE_CONTEXT_FIELDS,
            "Invalid sense_context object/allowlist")
    for key in ("pos", "pos_title"):
        require(record.get(key) is None or isinstance(record[key], str), f"Invalid record_context.{key}")
    for key in ("tags", "raw_tags", "notes", "etymology_texts"):
        if key in record:
            _string_array(record[key], f"record_context.{key}")
    links = record.get("etymology_links")
    require(links is None or (isinstance(links, list) and all(
        isinstance(link, list) and len(link) == 2 and all(isinstance(v, str) for v in link)
        for link in links)), "Invalid record_context.etymology_links")
    for key in ("glosses", "tags", "raw_tags", "topics", "categories"):
        if key in sense:
            _string_array(sense[key], f"sense_context.{key}")
    for key in ("form_of", "alt_of"):
        links = sense.get(key)
        require(links is None or (isinstance(links, list) and all(
            isinstance(link, dict) and set(link) <= {"word", "roman"}
            and isinstance(link.get("word"), str)
            and (link.get("roman") is None or isinstance(link["roman"], str))
            for link in links)), f"Invalid sense_context.{key}")
    examples = sense.get("examples")
    require(examples is None or isinstance(examples, list), "Invalid sense_context.examples array")
    for example in examples or []:
        require(isinstance(example, dict) and set(example) <= EXAMPLE_CONTEXT_FIELDS,
                "Invalid sense_context.examples object/allowlist")
        require(example.get("text") is None or isinstance(example["text"], str),
                "Invalid sense_context.examples.text")
        for key in ("ref", "translation", "roman"):
            require(example.get(key) is None or isinstance(example[key], (str, dict, list)),
                    f"Invalid sense_context.examples.{key}")
        # ref/translation/roman retain JSON nested values and explicit nulls from
        # the source. Their names are allowlisted; never copy arbitrary keys.
    source_pos = row.get("source_pos")
    require(source_pos is None or isinstance(source_pos, str), "Invalid source_pos type")
    require(source_pos == _source_pos(record.get("pos")), "source_pos disagrees with record_context.pos")
    glosses = sense.get("glosses")
    require(isinstance(glosses, list) and context["gloss_ordinal"] <= len(glosses),
            "VI gloss ordinal does not locate a source gloss")
    gloss = glosses[context["gloss_ordinal"] - 1]
    require(row["word_vi"] == _identity(gloss) and bool(row["word_vi"]),
            "VI word_vi disagrees with source gloss")
    locator = (context["record_line"], context["sense_ordinal"], context["gloss_ordinal"])
    require(row["id"] == _occurrence_id(checksum, locator), "VI occurrence identity disagrees with locator")
    require(row.get("tags") == (sense.get("tags") or []), "VI tags disagree with selected context")
    return locator


def _verify_raw_vi(raw_vi: Path, source: dict, entries: set[str], actual: dict,
                   report: dict) -> dict:
    require(raw_vi.is_file(), f"Raw VI source absent: {raw_vi}")
    require(sha256_file(raw_vi) == source.get("auxiliary_vi_sha256"), "Raw VI source SHA mismatch")
    inventory = {level: {kind: Counter() for kind in ("preserved", "omitted", "unknown")}
                 for level in ("record", "sense", "example")}
    counters = Counter()
    remaining = set(actual)

    def inventory_fields(value: dict, level: str, kept: frozenset, omitted: frozenset) -> None:
        for key in value:
            bucket = "preserved" if key in kept else "omitted" if key in omitted else "unknown"
            inventory[level][bucket][key] += 1
        require(not inventory[level]["unknown"], f"Unclassified raw VI {level} fields")

    # This traversal and projection deliberately do not call builder iterators,
    # occurrence generators, accounting, or projection functions.
    opener = gzip.open if raw_vi.suffix == ".gz" else open
    with opener(raw_vi, "rt", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            record = load_json(line)
            require(isinstance(record, dict), f"Invalid raw VI object on line {line_number}")
            if record.get("lang_code") != "en":
                continue
            inventory_fields(record, "record", RECORD_CONTEXT_FIELDS | {"senses"}, KNOWN_OMITTED_RECORD_FIELDS)
            word = _identity(str(record.get("word") or ""))
            entry_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"lyreo:lexicon:entry:en:{word}"))
            senses = record.get("senses") or []
            require(isinstance(senses, list), "Invalid raw VI senses array")
            for sense_ordinal, sense in enumerate(senses, 1):
                require(isinstance(sense, dict), "Invalid raw VI sense object")
                inventory_fields(sense, "sense", SENSE_CONTEXT_FIELDS, frozenset())
                counters["viwiktionary_total_raw_senses"] += 1
                glosses = sense.get("glosses") or []
                require(isinstance(glosses, list), "Invalid raw VI glosses array")
                if not glosses:
                    counters["viwiktionary_senses_without_glosses"] += 1
                examples = sense.get("examples") or []
                require(isinstance(examples, list), "Invalid raw VI examples array")
                for example in examples:
                    require(isinstance(example, dict), "Invalid raw VI example object")
                    inventory_fields(example, "example", EXAMPLE_CONTEXT_FIELDS, KNOWN_OMITTED_EXAMPLE_FIELDS)
                for gloss_ordinal, gloss in enumerate(glosses, 1):
                    require(isinstance(gloss, str), "Invalid raw VI gloss text")
                    counters["viwiktionary_gloss_occurrences_total"] += 1
                    normalized = _identity(gloss)
                    if not normalized:
                        counters["viwiktionary_empty_gloss_occurrences"] += 1
                        continue
                    if not word or entry_id not in entries:
                        counters["viwiktionary_unmatched_entry_gloss_occurrences"] += 1
                        continue
                    locator = (line_number, sense_ordinal, gloss_ordinal)
                    require(locator in actual, f"Missing raw VI occurrence: {locator}")
                    translated = actual[locator]
                    expected_record = {k: v for k, v in record.items() if k in RECORD_CONTEXT_FIELDS}
                    expected_sense = {k: v for k, v in sense.items() if k in SENSE_CONTEXT_FIELDS}
                    if isinstance(sense.get("examples"), list):
                        expected_sense["examples"] = [{k: v for k, v in e.items() if k in EXAMPLE_CONTEXT_FIELDS}
                                                      for e in sense["examples"]]
                    expected_context = {"record_line": line_number, "sense_ordinal": sense_ordinal,
                                        "gloss_ordinal": gloss_ordinal, "record_context": expected_record,
                                        "sense_context": expected_sense}
                    require(translated["source_context"] == expected_context,
                            f"Raw VI context projection mismatch: {locator}")
                    require(translated["entry_id"] == entry_id and translated["word_vi"] == normalized,
                            f"Raw VI entry/gloss mismatch: {locator}")
                    remaining.remove(locator)
                    counters["viwiktionary_emitted_records"] += 1
    require(not remaining, f"Extra canonical VI occurrences: {len(remaining)}")
    reconciliation = report["translation_source_reconciliation"]
    for key in ("viwiktionary_total_raw_senses", "viwiktionary_senses_without_glosses",
                "viwiktionary_gloss_occurrences_total", "viwiktionary_empty_gloss_occurrences",
                "viwiktionary_unmatched_entry_gloss_occurrences", "viwiktionary_emitted_records"):
        require(reconciliation.get(key) == counters[key], f"Raw VI accounting mismatch: {key}")
    require(report["vi_source_field_inventory"] == inventory, "Raw VI field inventory mismatch")
    return {"status": "PASS", "eligible_occurrences": counters["viwiktionary_emitted_records"],
            "raw_gloss_occurrences": counters["viwiktionary_gloss_occurrences_total"],
            "source_sha256": source["auxiliary_vi_sha256"]}


def validate(root: Path, archive: Path | None = None, raw_en: Path | None = None,
             raw_vi: Path | None = None) -> dict:
    manifest_path = root / "manifest.json"
    require(manifest_path.is_file(), f"Manifest missing: {manifest_path}")

    manifest = load_json(manifest_path.read_text(encoding="utf-8"))
    require(manifest.get("format") == "lyreo.dataset-release", "Unsupported format envelope")
    require(manifest.get("manifest_schema_version") == 1, "Unsupported manifest schema version")

    pkg = manifest.get("package", {})
    require(pkg.get("domain") == "lexicon", f"Wrong package domain: {pkg.get('domain')}")
    version = pkg.get("version")
    require(bool(version), "Package version missing in manifest")
    schema = pkg.get("schema_version")
    require(schema in SUPPORTED_SCHEMAS, f"Unsupported Lexicon schema: {schema}")
    strict = schema == "1.1.0"

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
            row = load_json(line)
            eid = row.get("id")
            require(bool(eid), f"Missing entry id on line {idx}")
            require(eid not in entry_ids, f"Duplicate entry id: {eid}")
            entry_ids.add(eid)
            require(row.get("entry_type") in ("WORD", "PHRASE", "PHRASAL_VERB", "IDIOM", "COLLOCATION"), f"Invalid entry_type in entry {eid}")
            require(bool(row.get("identity_form")), f"Missing identity_form in entry {eid}")
            require(bool(row.get("lookup_form")), f"Missing lookup_form in entry {eid}")

    item_ids: set[str] = set()
    item_parents: dict[str, tuple[str, str]] = {}
    compatible_counts: Counter = Counter()
    with items_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = load_json(line)
            iid = row.get("id")
            require(bool(iid), f"Missing item id on line {idx}")
            require(iid not in item_ids, f"Duplicate item id: {iid}")
            item_ids.add(iid)
            eid = row.get("entry_id")
            require(eid in entry_ids, f"Item {iid} references nonexistent entry_id {eid}")
            require(bool(row.get("pos")), f"Item {iid} missing pos")
            if strict:
                item_parents[iid] = (eid, row["pos"])
                compatible_counts[(eid, row["pos"])] += 1

    sense_ids: set[str] = set()
    sense_parents: dict[str, tuple[str, str]] = {}
    available_senses = 0
    missing_senses = 0
    with senses_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = load_json(line)
            sid = row.get("id")
            require(bool(sid), f"Missing sense id on line {idx}")
            require(sid not in sense_ids, f"Duplicate sense id: {sid}")
            sense_ids.add(sid)

            eid = row.get("entry_id")
            iid = row.get("item_id")
            require(eid in entry_ids, f"Sense {sid} references nonexistent entry_id {eid}")
            require(iid in item_ids, f"Sense {sid} references nonexistent item_id {iid}")
            if strict:
                require(item_parents[iid][0] == eid, f"Sense {sid} item belongs to another entry")
                sense_parents[sid] = (eid, iid)

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
            row = load_json(line)
            fid = row.get("id")
            require(bool(fid), f"Missing form id on line {idx}")
            require(fid not in form_ids, f"Duplicate form id: {fid}")
            form_ids.add(fid)

            eid = row.get("entry_id")
            iid = row.get("item_id")
            require(eid in entry_ids, f"Form {fid} references nonexistent entry_id {eid}")
            require(iid in item_ids, f"Form {fid} references nonexistent item_id {iid}")
            if strict:
                require(item_parents[iid][0] == eid, f"Form {fid} item belongs to another entry")
            require(bool(row.get("form")), f"Form {fid} missing surface text")

    pron_ids: set[str] = set()
    with prons_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = load_json(line)
            pid = row.get("id")
            require(bool(pid), f"Missing pronunciation id on line {idx}")
            require(pid not in pron_ids, f"Duplicate pronunciation id: {pid}")
            pron_ids.add(pid)

            eid = row.get("entry_id")
            iid = row.get("item_id")
            require(eid in entry_ids, f"Pronunciation {pid} references nonexistent entry_id {eid}")
            require(iid in item_ids, f"Pronunciation {pid} references nonexistent item_id {iid}")
            if strict:
                require(item_parents[iid][0] == eid, f"Pronunciation {pid} item belongs to another entry")
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
    vi_occurrences: dict[tuple[int, int, int], dict] = {}
    record_contexts: dict[int, str] = {}
    sense_contexts: dict[tuple[int, int], str] = {}
    translation_sources: Counter = Counter()
    vi_reasons: Counter = Counter()
    with translations_file.open("r", encoding="utf-8") as stream:
        for idx, line in enumerate(stream, 1):
            row = load_json(line)
            tid = row.get("id")
            require(bool(tid), f"Missing translation id on line {idx}")
            require(tid not in trans_ids, f"Duplicate translation id: {tid}")
            trans_ids.add(tid)

            eid = row.get("entry_id")
            require(eid in entry_ids, f"Translation {tid} references nonexistent entry_id {eid}")

            iid = row.get("item_id")
            if iid:
                require(iid in item_ids, f"Translation {tid} references nonexistent item_id {iid}")
                if strict:
                    require(item_parents[iid][0] == eid, f"Translation {tid} item belongs to another entry")

            sid = row.get("sense_id")
            if sid:
                require(sid in sense_ids, f"Translation {tid} references nonexistent sense_id {sid}")
                linked_sense_ids_from_trans.add(sid)
                if strict:
                    require(sense_parents[sid] == (eid, iid), f"Translation {tid} sense belongs to another item/entry")

            link_status = row.get("link_status")
            require(
                link_status in ("DIRECT_SENSE", "QUALIFIER_MATCH", "ITEM_CANDIDATE", "ENTRY_CANDIDATE", "UNLINKED"),
                f"Invalid link_status in translation {tid}: {link_status}",
            )
            if link_status in ("DIRECT_SENSE", "QUALIFIER_MATCH"):
                require(bool(sid), f"Translation {tid} has status {link_status} but sense_id is null")
            require(bool(row.get("word_vi")), f"Translation {tid} missing word_vi")
            translation_sources[row.get("source")] += 1
            if strict:
                require(isinstance(row.get("word_vi"), str), f"Translation {tid} word_vi must be text")
                source_pos = row.get("source_pos")
                require(source_pos is None or isinstance(source_pos, str), f"Invalid source_pos in {tid}")
                if link_status in ("DIRECT_SENSE", "QUALIFIER_MATCH"):
                    require(bool(iid), f"Translation {tid} sense link requires item_id")
                if link_status == "QUALIFIER_MATCH":
                    require(isinstance(row.get("source_sense_qualifier"), str)
                            and bool(row["source_sense_qualifier"].strip()),
                            f"Translation {tid} QUALIFIER_MATCH requires source qualifier")
                if link_status == "ITEM_CANDIDATE":
                    require(bool(iid) and sid is None, f"Translation {tid} invalid ITEM_CANDIDATE scope")
                if link_status in ("ENTRY_CANDIDATE", "UNLINKED"):
                    require(iid is None and sid is None, f"Translation {tid} invalid {link_status} scope")
                if row.get("source") == "viwiktionary":
                    require(bool(source.get("auxiliary_vi_sha256")), "VI translations require auxiliary source identity")
                    locator = _context(row, source["auxiliary_vi_sha256"])
                    require(locator not in vi_occurrences, f"Duplicate VI occurrence: {locator}")
                    context = row["source_context"]
                    record_fingerprint = json.dumps(context["record_context"], sort_keys=True, ensure_ascii=False)
                    sense_fingerprint = json.dumps(context["sense_context"], sort_keys=True, ensure_ascii=False)
                    require(record_contexts.setdefault(locator[0], record_fingerprint) == record_fingerprint,
                            f"Inconsistent VI record context: {locator}")
                    require(sense_contexts.setdefault(locator[:2], sense_fingerprint) == sense_fingerprint,
                            f"Inconsistent VI sense context: {locator}")
                    vi_occurrences[locator] = row
                    require(row.get("source_scope") == "viwiktionary_gloss"
                            and row.get("source_sense_qualifier") is None,
                            f"Invalid auxiliary VI origin scope in {tid}")
                    count = compatible_counts[(eid, source_pos)] if source_pos else 0
                    expected_reason = ("AUXILIARY_VI_UNKNOWN_POS" if source_pos is None else
                                       "AUXILIARY_VI_UNIQUE_POS_ITEM" if count == 1 else
                                       "AUXILIARY_VI_AMBIGUOUS_POS_ITEMS" if count > 1 else
                                       "AUXILIARY_VI_NO_COMPATIBLE_POS_ITEM")
                    require((link_status == "ITEM_CANDIDATE") == (source_pos is not None and count == 1),
                            f"VI compatible item cardinality requires unique POS candidate or entry fallback: {tid}")
                    require(link_status in ("ITEM_CANDIDATE", "ENTRY_CANDIDATE") and sid is None,
                            f"Invalid auxiliary VI candidate status: {tid}")
                    if iid:
                        require(item_parents[iid] == (eid, source_pos), f"VI item/POS mismatch: {tid}")
                    require(row.get("reason") == expected_reason, f"VI reason disagrees with compatible cardinality: {tid}")
                    vi_reasons[expected_reason] += 1
                else:
                    require(row.get("source_context") is None,
                            f"Internal VI context is not defined for source {row.get('source')}")

    # Verify that every AVAILABLE sense is backed by at least one linked translation
    with senses_file.open("r", encoding="utf-8") as stream:
        for line in stream:
            row = load_json(line)
            if row.get("translation_status") == "AVAILABLE":
                require(
                    row["id"] in linked_sense_ids_from_trans,
                    f"Sense {row['id']} marked AVAILABLE but has no corresponding record in translations.jsonl",
                )

    # Verify reconciliation reports
    report_rel_path = manifest.get("validation", {}).get("report_path", "validation_report.json")
    report_file = root / report_rel_path
    require(report_file.is_file(), f"Validation report file missing: {report_file}")
    report = load_json(report_file.read_text(encoding="utf-8"))
    require(report.get("status") == "PASS", f"Validation report status is not PASS: {report.get('status')}")
    collection_counts = dict(zip(("entries.jsonl", "items.jsonl", "senses.jsonl", "forms.jsonl",
                                  "pronunciations.jsonl", "translations.jsonl"),
                                 map(len, (entry_ids, item_ids, sense_ids, form_ids, pron_ids, trans_ids))))
    require(manifest.get("counts") == collection_counts, "Manifest collection counts mismatch")
    require(report.get("counts") == collection_counts, "Validation report collection counts mismatch")

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
    if strict:
        require(bool(trans_rec), "New schema requires translation source reconciliation")
        require(trans_rec.get("viwiktionary_emitted_records") == translation_sources["viwiktionary"],
                "VI emitted translation count mismatch")
        require(trans_rec.get("enwiktionary_emitted_records") == translation_sources["enwiktionary"],
                "EN emitted translation count mismatch")
        require(trans_rec.get("viwiktionary_duplicate_glosses_deduped") == 0,
                "New VI occurrence schema must not deduplicate occurrences")
        for key in ("viwiktionary_gloss_occurrences_total", "viwiktionary_empty_gloss_occurrences",
                    "viwiktionary_unmatched_entry_gloss_occurrences"):
            require(type(trans_rec.get(key)) is int and trans_rec[key] >= 0, f"Invalid VI accounting: {key}")
        require(trans_rec["viwiktionary_gloss_occurrences_total"] ==
                trans_rec["viwiktionary_empty_gloss_occurrences"] +
                trans_rec["viwiktionary_unmatched_entry_gloss_occurrences"] + len(vi_occurrences),
                "VI occurrence accounting mismatch")
        require(trans_rec.get("viwiktionary_emitted_by_reason") == vi_reasons, "VI reason accounting mismatch")
        field_inventory = report.get("vi_source_field_inventory")
        require(isinstance(field_inventory, dict) and set(field_inventory) == {"record", "sense", "example"},
                "Missing VI source field inventory")
        for level, buckets in field_inventory.items():
            require(isinstance(buckets, dict) and set(buckets) == {"preserved", "omitted", "unknown"},
                    f"Invalid VI field inventory level: {level}")
            for bucket, counts in buckets.items():
                require(isinstance(counts, dict) and all(isinstance(k, str) and type(v) is int and v > 0
                                                       for k, v in counts.items()),
                        f"Invalid VI field inventory counts: {level}.{bucket}")
            require(not buckets["unknown"], f"Unclassified VI source fields: {level}")
            kept, omitted = {"record": (RECORD_CONTEXT_FIELDS | {"senses"}, KNOWN_OMITTED_RECORD_FIELDS),
                             "sense": (SENSE_CONTEXT_FIELDS, frozenset()),
                             "example": (EXAMPLE_CONTEXT_FIELDS, KNOWN_OMITTED_EXAMPLE_FIELDS)}[level]
            require(set(buckets["preserved"]) <= kept and set(buckets["omitted"]) <= omitted,
                    f"Unclassified VI source inventory keys: {level}")

    vi_source_verification = None
    if raw_vi:
        require(strict, "RAW VI occurrence verification requires schema 1.1.0")
        vi_source_verification = _verify_raw_vi(raw_vi, source, entry_ids, vi_occurrences, report)

    # 4. Validate Archive (if supplied)
    archive_hash = None
    if archive:
        require(archive.is_file(), f"Archive file absent: {archive}")
        archive_hash = sha256_file(archive)
        expected_root = f"lexicon-{version}"
        expected_names = {f"{expected_root}/{p}" for p in actual_paths | {"manifest.json"}}
        expected_hashes = {entry["path"]: entry["sha256"] for entry in inventory}
        expected_hashes["manifest.json"] = sha256_file(manifest_path)
        seen_members = set()
        # Streaming mode checks every member without re-seeking a large gzip archive.
        with tarfile.open(archive, "r|gz") as tar:
            for member in tar:
                require(member.name not in seen_members, f"Duplicate archive member: {member.name}")
                require(member.name in expected_names, f"Unexpected archive member/root: {member.name}")
                seen_members.add(member.name)
                require(member.isfile() and member.uid == 0 and member.gid == 0
                        and member.mtime == 0 and member.mode == 0o644,
                        "Archive tar headers are not canonical (uid/gid/mtime/mode mismatch)")
                relative = member.name[len(expected_root) + 1:]
                require(member.size == (root / relative).stat().st_size,
                        f"Archive member size mismatch: {relative}")
                content = tar.extractfile(member)
                require(content is not None, f"Archive member has no content: {relative}")
                digest = hashlib.sha256()
                for chunk in iter(lambda: content.read(1024 * 1024), b""):
                    digest.update(chunk)
                require(digest.hexdigest() == expected_hashes[relative],
                        f"Archive member checksum mismatch: {relative}")
        require(seen_members == expected_names, "Archive member paths do not match release inventory")

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
        "vi_source_verification": vi_source_verification,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True, help="Release directory to validate")
    parser.add_argument("--archive", type=Path, help="Optional release archive to validate")
    parser.add_argument("--raw-en", type=Path, help="Optional raw source file to verify")
    parser.add_argument("--raw-vi", type=Path, help="Pinned auxiliary source for independent publication verification")
    parser.add_argument("--report", type=Path, help="Optional destination path for validation JSON report")
    args = parser.parse_args()

    result = validate(
        root=args.release.resolve(),
        archive=args.archive.resolve() if args.archive else None,
        raw_en=args.raw_en.resolve() if args.raw_en else None,
        raw_vi=args.raw_vi.resolve() if args.raw_vi else None,
    )
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
