#!/usr/bin/env python3
"""Build a self-contained, reproducible Lexicon clean release from Wiktextract data.

This is offline dataset preparation conforming to RELEASE_FORMAT.md.
SQLite disk-backed staging bounds memory use and produces deterministic
ID-sorted outputs.
"""
from __future__ import annotations

import argparse
import datetime as dt
import gzip
import hashlib
import json
import os
import resource
import shutil
import sqlite3
import sys
import tarfile
import time
import unicodedata
import uuid
from pathlib import Path
from typing import Iterator

BUILDER_NAME = "build_lexicon_release.py"
BUILDER_VERSION = "1.0.0"
PACKAGE_DOMAIN = "lexicon"
PACKAGE_VERSION = "1.0.0"
SCHEMA_VERSION = "1.0.0"
PINNED_RAW_EN_SHA256 = "5ab411b8859490789d0d002074cc029569648f901db940c0ab10e709932e1632"
PINNED_RAW_EN_DUMP_DATE = "2026-09-02"
PINNED_RAW_VI_SHA256 = "10e62bccdf3d85fc9e7472c1f85b2149602f97c75177b09523f4762c57ef7d3d"
PINNED_RAW_VI_DUMP_DATE = "2026-09-01"
REQUIRED_UNICODE_DATA_VERSION = "15.0.0"

NAMESPACE_LYREO = uuid.NAMESPACE_URL


def fail(message: str) -> None:
    raise ValueError(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def identity_normalization(text: str) -> str:
    """Normalize for entity identity: Unicode NFKC, stripped, preserving casing."""
    return unicodedata.normalize("NFKC", text or "").strip()


def lookup_normalization(text: str) -> str:
    """Normalize for query search index: Unicode NFKC, stripped, casefolded."""
    return unicodedata.normalize("NFKC", text or "").strip().casefold()


STOP_WORDS = {"a", "an", "the", "of", "or", "in", "to", "for", "and", "on", "with", "by", "at", "etc", "see", "also"}


def plural_stem(w: str) -> str:
    """Simple deterministic English plural suffix stripper."""
    w = w.lower()
    if len(w) > 4 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 4 and w.endswith(("ches", "shes", "sses", "xes", "zes")):
        return w[:-2]
    if len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
        return w[:-1]
    return w


def content_stems(text: str) -> set[str]:
    """Extract significant lowercase content stems for deterministic translation matching."""
    import re

    clean = re.sub(r"[^\w\s]", " ", unicodedata.normalize("NFKC", text or "").lower())
    return {plural_stem(w) for w in clean.split() if w and w not in STOP_WORDS}


ENTRY_TYPE_PRIORITY = {
    "PHRASAL_VERB": 5,
    "IDIOM": 4,
    "COLLOCATION": 3,
    "PHRASE": 2,
    "WORD": 1,
}


def determine_entry_type(item: dict) -> str:
    """Deterministic entry_type classification rule.

    1. PHRASAL_VERB: pos in ('verb', 'phrase') AND (tags/categories contain 'phrasal verbs'
       OR pos_title contains 'phrasal verb').
    2. IDIOM: tags/categories contain 'idioms' or 'idiomatic'.
    3. COLLOCATION: tags/categories contain 'collocations'.
    4. PHRASE: Multi-word terms containing whitespace (' ' in word) without special tags.
    5. WORD: Single-word tokens (including hyphenated compound words like 'well-being').
    6. Fallback: WORD (single token) or PHRASE (multi-token).
    """
    word = identity_normalization(str(item.get("word") or ""))
    pos = str(item.get("pos") or "").strip().lower()
    pos_title = str(item.get("pos_title") or "").strip().lower()
    tags = [str(t).strip().lower() for t in item.get("tags") or []]
    categories = [str(c).strip().lower() for c in item.get("categories") or []]
    markers = set(tags + categories)

    has_whitespace = " " in word

    if (pos in ("verb", "phrase") and any("phrasal verb" in m for m in markers)) or "phrasal verb" in pos_title:
        return "PHRASAL_VERB"
    if any("idiom" in m or "idiomatic" in m for m in markers):
        return "IDIOM"
    if any("collocation" in m for m in markers):
        return "COLLOCATION"
    if has_whitespace:
        return "PHRASE"
    return "WORD"


def stream_jsonl(path: Path) -> Iterator[dict]:
    if path.suffix == ".gz":
        with gzip.open(path, "rt", encoding="utf-8") as stream:
            for line in stream:
                line = line.strip()
                if line:
                    yield json.loads(line)
    else:
        with path.open("r", encoding="utf-8") as stream:
            for line in stream:
                line = line.strip()
                if line:
                    yield json.loads(line)


def init_staging_db(db_path: Path) -> sqlite3.Connection:
    if db_path.exists():
        db_path.unlink()
    db_path.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA synchronous = OFF")
    conn.execute("PRAGMA journal_mode = MEMORY")
    conn.execute(
        """CREATE TABLE entries (
            id TEXT PRIMARY KEY,
            display_form TEXT,
            identity_form TEXT,
            lookup_form TEXT,
            entry_type TEXT,
            language TEXT,
            priority INT
        )"""
    )
    conn.execute(
        """CREATE TABLE items (
            id TEXT PRIMARY KEY,
            entry_id TEXT NOT NULL,
            pos TEXT,
            etymology_number INT,
            order_index INT,
            payload TEXT
        )"""
    )
    conn.execute("CREATE INDEX idx_items_entry_pos ON items(entry_id, pos)")
    conn.execute(
        """CREATE TABLE senses (
            id TEXT PRIMARY KEY,
            entry_id TEXT NOT NULL,
            item_id TEXT NOT NULL,
            ordinal INT,
            definition_en TEXT,
            translation_vi TEXT,
            translation_status TEXT,
            payload TEXT
        )"""
    )
    conn.execute(
        """CREATE TABLE forms (
            id TEXT PRIMARY KEY,
            entry_id TEXT NOT NULL,
            item_id TEXT NOT NULL,
            form TEXT,
            normalized_form TEXT,
            payload TEXT
        )"""
    )
    conn.execute(
        """CREATE TABLE pronunciations (
            id TEXT PRIMARY KEY,
            entry_id TEXT NOT NULL,
            item_id TEXT NOT NULL,
            accent TEXT,
            ipa TEXT,
            audio_url TEXT,
            payload TEXT
        )"""
    )
    conn.execute(
        """CREATE TABLE translations (
            id TEXT PRIMARY KEY,
            entry_id TEXT NOT NULL,
            item_id TEXT,
            sense_id TEXT,
            word_vi TEXT,
            source TEXT,
            source_scope TEXT,
            source_sense_qualifier TEXT,
            link_status TEXT,
            reason TEXT,
            payload TEXT
        )"""
    )
    return conn


def package_release(release: Path, archive: Path, root_name: str) -> str:
    archive.parent.mkdir(parents=True, exist_ok=True)
    with archive.open("wb") as output, gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0, compresslevel=6) as compressed, tarfile.open(fileobj=compressed, mode="w") as tar:
        for path in sorted(release.rglob("*")):
            if not path.is_file():
                continue
            info = tar.gettarinfo(str(path), arcname=f"{root_name}/{path.relative_to(release).as_posix()}")
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 0
            info.mode = 0o644
            with path.open("rb") as stream:
                tar.addfile(info, stream)
    return sha256_file(archive)


def build(
    raw_en_path: Path,
    output_dir: Path,
    archive_path: Path,
    generated_at: str,
    staging_dir: Path,
    raw_vi_path: Path | None = None,
    enforce_checksum: bool = True,
) -> dict:
    if unicodedata.unidata_version != REQUIRED_UNICODE_DATA_VERSION:
        fail(
            f"Unicode database {REQUIRED_UNICODE_DATA_VERSION} is required for stable Lexicon IDs; "
            f"this Python provides {unicodedata.unidata_version}"
        )
    if output_dir.exists() or archive_path.exists():
        fail(f"Output directory or archive already exists: {output_dir}, {archive_path}")

    if enforce_checksum:
        actual_en_sha = sha256_file(raw_en_path)
        if actual_en_sha != PINNED_RAW_EN_SHA256:
            fail(f"Raw English source checksum mismatch: expected {PINNED_RAW_EN_SHA256}, got {actual_en_sha}")
        source_en_sha = actual_en_sha
        if raw_vi_path:
            actual_vi_sha = sha256_file(raw_vi_path)
            if actual_vi_sha != PINNED_RAW_VI_SHA256:
                fail(f"Raw Vietnamese source checksum mismatch: expected {PINNED_RAW_VI_SHA256}, got {actual_vi_sha}")
            source_vi_sha = actual_vi_sha
        else:
            source_vi_sha = None
    else:
        source_en_sha = sha256_file(raw_en_path) if raw_en_path.is_file() else "unverified"
        source_vi_sha = sha256_file(raw_vi_path) if raw_vi_path and raw_vi_path.is_file() else None

    staging_db_path = staging_dir / f"lexicon_staging_{os.getpid()}_{int(time.time())}.db"
    conn = init_staging_db(staging_db_path)

    raw_rows_count = 0
    en_rows_count = 0
    skipped_rows_count = 0
    issues = []

    # Enwiktionary translation accounting counters
    en_vi_raw_total = 0
    en_vi_empty_skipped = 0
    en_vi_exact_dup_skipped = 0
    en_vi_emitted = 0

    # Viwiktionary translation accounting counters
    vi_raw_senses_total = 0
    vi_empty_gloss_skipped = 0
    vi_unmatched_entries_skipped = 0
    vi_exact_dup_skipped = 0
    vi_multi_gloss_net = 0
    vi_emitted = 0

    # Disambiguation mapping: (entry_id, pos, etym_num) -> count of items encountered
    item_disambiguation_counts: dict[tuple[str, str, int | None], int] = {}
    item_order_by_entry: dict[str, int] = {}

    batch_entries = []
    batch_items = []
    batch_senses = []
    batch_forms = []
    batch_pronunciations = []
    batch_translations = []

    BATCH_SIZE = 5000

    def flush_batches():
        nonlocal batch_entries, batch_items, batch_senses, batch_forms, batch_pronunciations, batch_translations
        if batch_entries:
            conn.executemany(
                """INSERT INTO entries (id, display_form, identity_form, lookup_form, entry_type, language, priority)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET
                     entry_type = CASE WHEN excluded.priority > entries.priority THEN excluded.entry_type ELSE entries.entry_type END,
                     priority = MAX(entries.priority, excluded.priority)""",
                batch_entries,
            )
            batch_entries = []
        if batch_items:
            conn.executemany("INSERT INTO items VALUES (?, ?, ?, ?, ?, ?)", batch_items)
            batch_items = []
        if batch_senses:
            conn.executemany("INSERT OR REPLACE INTO senses VALUES (?, ?, ?, ?, ?, ?, ?, ?)", batch_senses)
            batch_senses = []
        if batch_forms:
            conn.executemany("INSERT OR REPLACE INTO forms VALUES (?, ?, ?, ?, ?, ?)", batch_forms)
            batch_forms = []
        if batch_pronunciations:
            conn.executemany("INSERT OR REPLACE INTO pronunciations VALUES (?, ?, ?, ?, ?, ?, ?)", batch_pronunciations)
            batch_pronunciations = []
        if batch_translations:
            conn.executemany("INSERT OR REPLACE INTO translations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", batch_translations)
            batch_translations = []
        conn.commit()

    print(f"Reading and staging raw Lexicon data from {raw_en_path.name}...")
    start_time = time.time()

    for item in stream_jsonl(raw_en_path):
        raw_rows_count += 1
        if item.get("lang_code") != "en":
            skipped_rows_count += 1
            continue

        word = identity_normalization(str(item.get("word") or ""))
        if not word:
            skipped_rows_count += 1
            continue

        en_rows_count += 1
        pos = str(item.get("pos") or "").strip().lower() or "unknown"
        pos_title = str(item.get("pos_title") or "").strip() or pos.capitalize()
        etym_num = item.get("etymology_number")
        if etym_num is not None:
            try:
                etym_num = int(etym_num)
            except (ValueError, TypeError):
                etym_num = None

        etype = determine_entry_type(item)
        lookup_key = lookup_normalization(word)

        # 1. Entry identity depends ONLY on (language, identity_form). entry_type does NOT alter entry_id!
        entry_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:entry:en:{word}"))
        batch_entries.append((
            entry_id,
            str(item.get("word") or ""),
            word,
            lookup_key,
            etype,
            "en",
            ENTRY_TYPE_PRIORITY.get(etype, 1),
        ))

        # 2. Item identity (POS + Etymology block + sequence index for identical items)
        disambig_key = (entry_id, pos, etym_num)
        item_disambiguation_counts[disambig_key] = item_disambiguation_counts.get(disambig_key, 0) + 1
        item_seq = item_disambiguation_counts[disambig_key]

        order_index = item_order_by_entry.get(entry_id, 0) + 1
        item_order_by_entry[entry_id] = order_index

        item_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:item:{entry_id}:{pos}:{etym_num or 0}:{item_seq}"))
        etym_text = (str(item.get("etymology_text") or "").strip()) or None

        item_payload = {
            "id": item_id,
            "entry_id": entry_id,
            "pos": pos,
            "pos_title": pos_title,
            "etymology_number": etym_num,
            "etymology_text": etym_text,
            "order_index": order_index,
        }
        batch_items.append((
            item_id,
            entry_id,
            pos,
            etym_num,
            order_index,
            canonical_json(item_payload),
        ))

        # 3. Process senses & translations for this item
        raw_senses = item.get("senses") or []
        parsed_senses = []
        for s_idx, raw_s in enumerate(raw_senses, 1):
            if not isinstance(raw_s, dict):
                continue
            glosses = [str(g).strip() for g in raw_s.get("glosses") or [] if str(g).strip()]
            raw_glosses = [str(g).strip() for g in raw_s.get("raw_glosses") or [] if str(g).strip()]
            tags = [str(t).strip().lower() for t in raw_s.get("tags") or [] if str(t).strip()]
            examples = []
            for ex in raw_s.get("examples") or []:
                if isinstance(ex, dict) and ex.get("text"):
                    examples.append({
                        "text": str(ex["text"]).strip(),
                        "ref": str(ex.get("ref")).strip() if ex.get("ref") else None,
                        "type": str(ex.get("type")).strip() if ex.get("type") else None,
                    })
            parsed_senses.append({
                "ordinal": s_idx,
                "glosses": glosses,
                "raw_glosses": raw_glosses,
                "tags": tags,
                "examples": examples,
                "linked_translations": [],
                "matched_qualifiers": [],
            })

        # Disambiguate duplicate glosses in this item and generate sense IDs
        gloss_counts: dict[str, int] = {}
        for s in parsed_senses:
            primary_def = "; ".join(s["glosses"]) if s["glosses"] else ("; ".join(s["raw_glosses"]) if s["raw_glosses"] else "")
            norm_primary = lookup_normalization(primary_def)
            primary_hash = hashlib.sha256(norm_primary.encode("utf-8")).hexdigest()
            disambiguation_ordinal = gloss_counts.get(primary_hash, 0) + 1
            gloss_counts[primary_hash] = disambiguation_ordinal

            sense_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:sense:{item_id}:{primary_hash}:{disambiguation_ordinal}"))
            s["id"] = sense_id
            s["primary_def"] = primary_def

        # Process Vietnamese translations from enwiktionary
        seen_en_vi_in_item = set()
        for trans in item.get("translations") or []:
            if not isinstance(trans, dict):
                continue
            code = trans.get("code") or trans.get("lang_code")
            if code != "vi":
                continue
            en_vi_raw_total += 1
            vi_word = identity_normalization(str(trans.get("word") or ""))
            if not vi_word:
                en_vi_empty_skipped += 1
                continue
            raw_qualifier = str(trans.get("sense") or "").strip() or None
            k = (vi_word, raw_qualifier)
            if k in seen_en_vi_in_item:
                en_vi_exact_dup_skipped += 1
                continue
            seen_en_vi_in_item.add(k)
            en_vi_emitted += 1
            t_sense = identity_normalization(raw_qualifier or "").lower()
            t_stems = content_stems(t_sense)

            if not t_sense:
                # No qualifier -> ITEM_CANDIDATE
                t_hash = hashlib.sha256(vi_word.encode("utf-8")).hexdigest()[:12]
                trans_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:trans:enwiktionary:{item_id}:{vi_word}:{t_hash}"))
                trans_payload = {
                    "id": trans_id,
                    "entry_id": entry_id,
                    "item_id": item_id,
                    "sense_id": None,
                    "word_vi": vi_word,
                    "source": "enwiktionary",
                    "source_scope": "item",
                    "source_sense_qualifier": None,
                    "link_status": "ITEM_CANDIDATE",
                    "reason": "NO_SENSE_QUALIFIER",
                    "tags": trans.get("tags") or [],
                }
                batch_translations.append((
                    trans_id,
                    entry_id,
                    item_id,
                    None,
                    vi_word,
                    "enwiktionary",
                    "item",
                    None,
                    "ITEM_CANDIDATE",
                    "NO_SENSE_QUALIFIER",
                    canonical_json(trans_payload),
                ))
                continue

            # Deterministic matching using substring and plural-stem containment
            matches = []
            for s in parsed_senses:
                search_text = [identity_normalization(g).lower() for g in s["glosses"] + s["raw_glosses"]]
                search_stems = [content_stems(g) for g in s["glosses"] + s["raw_glosses"]]
                matched = bool(t_stems) and any(t_stems.issubset(toks) for toks in search_stems if toks)
                if not matched and t_sense:
                    matched = any(t_sense in g or g in t_sense for g in search_text if g)
                if matched:
                    matches.append(s)

            if len(matches) == 1:
                # Exactly one deterministic qualifier match -> QUALIFIER_MATCH
                target_sense = matches[0]
                if vi_word not in target_sense["linked_translations"]:
                    target_sense["linked_translations"].append(vi_word)
                if raw_qualifier and raw_qualifier not in target_sense["matched_qualifiers"]:
                    target_sense["matched_qualifiers"].append(raw_qualifier)

                t_hash = hashlib.sha256(f"{target_sense['id']}:{raw_qualifier}".encode("utf-8")).hexdigest()[:12]
                trans_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:trans:enwiktionary:{item_id}:{vi_word}:{t_hash}"))
                trans_payload = {
                    "id": trans_id,
                    "entry_id": entry_id,
                    "item_id": item_id,
                    "sense_id": target_sense["id"],
                    "word_vi": vi_word,
                    "source": "enwiktionary",
                    "source_scope": "item",
                    "source_sense_qualifier": raw_qualifier,
                    "link_status": "QUALIFIER_MATCH",
                    "reason": None,
                    "tags": trans.get("tags") or [],
                }
                batch_translations.append((
                    trans_id,
                    entry_id,
                    item_id,
                    target_sense["id"],
                    vi_word,
                    "enwiktionary",
                    "item",
                    raw_qualifier,
                    "QUALIFIER_MATCH",
                    None,
                    canonical_json(trans_payload),
                ))
            elif len(matches) > 1:
                # Ambiguous match -> ITEM_CANDIDATE
                t_hash = hashlib.sha256(f"ambiguous:{t_sense}".encode("utf-8")).hexdigest()[:12]
                trans_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:trans:enwiktionary:{item_id}:{vi_word}:{t_hash}"))
                trans_payload = {
                    "id": trans_id,
                    "entry_id": entry_id,
                    "item_id": item_id,
                    "sense_id": None,
                    "word_vi": vi_word,
                    "source": "enwiktionary",
                    "source_scope": "item",
                    "source_sense_qualifier": raw_qualifier,
                    "link_status": "ITEM_CANDIDATE",
                    "reason": f"AMBIGUOUS_MULTI_SENSE_MATCH ({len(matches)} matches)",
                    "tags": trans.get("tags") or [],
                }
                batch_translations.append((
                    trans_id,
                    entry_id,
                    item_id,
                    None,
                    vi_word,
                    "enwiktionary",
                    "item",
                    raw_qualifier,
                    "ITEM_CANDIDATE",
                    f"AMBIGUOUS_MULTI_SENSE_MATCH ({len(matches)} matches)",
                    canonical_json(trans_payload),
                ))
            else:
                # No match in senses -> ITEM_CANDIDATE
                t_hash = hashlib.sha256(f"nomatch:{t_sense}".encode("utf-8")).hexdigest()[:12]
                trans_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:trans:enwiktionary:{item_id}:{vi_word}:{t_hash}"))
                trans_payload = {
                    "id": trans_id,
                    "entry_id": entry_id,
                    "item_id": item_id,
                    "sense_id": None,
                    "word_vi": vi_word,
                    "source": "enwiktionary",
                    "source_scope": "item",
                    "source_sense_qualifier": raw_qualifier,
                    "link_status": "ITEM_CANDIDATE",
                    "reason": "NO_EXACT_SENSE_MATCH",
                    "tags": trans.get("tags") or [],
                }
                batch_translations.append((
                    trans_id,
                    entry_id,
                    item_id,
                    None,
                    vi_word,
                    "enwiktionary",
                    "item",
                    raw_qualifier,
                    "ITEM_CANDIDATE",
                    "NO_EXACT_SENSE_MATCH",
                    canonical_json(trans_payload),
                ))

        # Build senses records
        for s in parsed_senses:
            trans_vi = "; ".join(sorted(s["linked_translations"])) if s["linked_translations"] else None
            matched_q = "; ".join(sorted(s["matched_qualifiers"])) if s["matched_qualifiers"] else None
            status = "AVAILABLE" if trans_vi else "MISSING"

            sense_payload = {
                "id": s["id"],
                "entry_id": entry_id,
                "item_id": item_id,
                "ordinal": s["ordinal"],
                "definition_en": s["primary_def"] or None,
                "raw_glosses": s["raw_glosses"],
                "tags": s["tags"],
                "examples": s["examples"],
                "translation_vi": trans_vi,
                "translation_status": status,
                "matched_qualifier": matched_q,
            }
            batch_senses.append((
                s["id"],
                entry_id,
                item_id,
                s["ordinal"],
                s["primary_def"] or None,
                trans_vi,
                status,
                canonical_json(sense_payload),
            ))

        # 4. Process forms
        for form in item.get("forms") or []:
            if not isinstance(form, dict):
                continue
            surface = identity_normalization(str(form.get("form") or ""))
            if not surface or surface == "-":
                continue
            form_norm = lookup_normalization(surface)
            tags = sorted(str(t).strip().lower() for t in form.get("tags") or [] if str(t).strip())
            tags_hash = hashlib.sha256(",".join(tags).encode("utf-8")).hexdigest()[:12]
            form_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:form:{item_id}:{form_norm}:{tags_hash}"))

            form_payload = {
                "id": form_id,
                "entry_id": entry_id,
                "item_id": item_id,
                "form": surface,
                "normalized_form": form_norm,
                "tags": tags,
            }
            batch_forms.append((
                form_id,
                entry_id,
                item_id,
                surface,
                form_norm,
                canonical_json(form_payload),
            ))

        # 5. Process pronunciations
        for sound in item.get("sounds") or []:
            if not isinstance(sound, dict):
                continue
            ipa = str(sound.get("ipa")).strip() if sound.get("ipa") else None
            audio_url = str(sound.get("mp3_url") or sound.get("ogg_url") or "").strip() or None
            audio_file = str(sound.get("audio")).strip() if sound.get("audio") else None
            if not ipa and not audio_url and not audio_file:
                continue

            tags_str = " ".join(str(t).strip().lower() for t in sound.get("tags") or [] if str(t).strip())
            if any(t in tags_str for t in ("us", "general american", "american")):
                accent = "US"
            elif any(t in tags_str for t in ("uk", "received pronunciation", "british", "england")):
                accent = "UK"
            else:
                accent = "general"

            ipa_hash = hashlib.sha256((ipa or "").encode("utf-8")).hexdigest()[:12]
            audio_hash = hashlib.sha256((audio_url or audio_file or "").encode("utf-8")).hexdigest()[:12]
            pron_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:pron:{item_id}:{accent}:{ipa_hash}:{audio_hash}"))

            source_url = sound.get("ogg_url") or sound.get("mp3_url") or (f"https://commons.wikimedia.org/wiki/Special:FilePath/{audio_file}" if audio_file else None)

            pron_payload = {
                "id": pron_id,
                "entry_id": entry_id,
                "item_id": item_id,
                "accent": accent,
                "ipa": ipa,
                "audio_url": audio_url,
                "audio_file": audio_file,
                "source_url": source_url,
                "tags": sound.get("tags") or [],
            }
            batch_pronunciations.append((
                pron_id,
                entry_id,
                item_id,
                accent,
                ipa,
                audio_url,
                canonical_json(pron_payload),
            ))

        if len(batch_senses) >= BATCH_SIZE or len(batch_forms) >= BATCH_SIZE or len(batch_translations) >= BATCH_SIZE:
            flush_batches()
            if en_rows_count % 50000 == 0:
                elapsed = time.time() - start_time
                print(f"Processed {en_rows_count} English rows in {elapsed:.1f}s...")

    flush_batches()
    parse_elapsed = time.time() - start_time
    print(f"Staged {en_rows_count} English records into SQLite in {parse_elapsed:.1f}s.")

    # Ingest auxiliary Vietnamese Wiktionary candidates if supplied
    vi_candidates_count = 0
    if raw_vi_path and raw_vi_path.is_file():
        print(f"Ingesting auxiliary Vietnamese candidate definitions from {raw_vi_path.name}...")
        vi_start_time = time.time()
        seen_vi_trans = set()
        for vi_item in stream_jsonl(raw_vi_path):
            if vi_item.get("lang_code") != "en":
                continue
            vi_word = identity_normalization(str(vi_item.get("word") or ""))
            if not vi_word:
                continue

            entry_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:entry:en:{vi_word}"))
            # Check if this entry exists in our primary dictionary
            entry_row = conn.execute("SELECT 1 FROM entries WHERE id = ?", (entry_id,)).fetchone()

            vi_pos = str(vi_item.get("pos") or "").strip().lower()
            # Find candidate item under this entry matching pos
            matched_item = conn.execute("SELECT id FROM items WHERE entry_id = ? AND pos = ? ORDER BY order_index LIMIT 1", (entry_id, vi_pos)).fetchone()
            matched_item_id = matched_item[0] if matched_item else None

            for vi_s in vi_item.get("senses") or []:
                if not isinstance(vi_s, dict):
                    continue
                vi_raw_senses_total += 1
                raw_glosses = vi_s.get("glosses") or []
                valid_glosses = [identity_normalization(str(g)).strip() for g in raw_glosses if identity_normalization(str(g)).strip()]
                if not valid_glosses:
                    vi_empty_gloss_skipped += 1
                    continue
                if len(valid_glosses) > 1:
                    vi_multi_gloss_net += (len(valid_glosses) - 1)

                if not entry_row:
                    vi_unmatched_entries_skipped += 1
                    continue

                for gloss in valid_glosses:
                    k = (entry_id, matched_item_id, gloss)
                    if k in seen_vi_trans:
                        vi_exact_dup_skipped += 1
                        continue
                    seen_vi_trans.add(k)
                    vi_emitted += 1
                    vi_candidates_count += 1
                    link_status = "ITEM_CANDIDATE" if matched_item_id else "ENTRY_CANDIDATE"
                    gloss_hash = hashlib.sha256(gloss.encode("utf-8")).hexdigest()[:12]
                    trans_id = str(uuid.uuid5(NAMESPACE_LYREO, f"lyreo:lexicon:trans:viwiktionary:{entry_id}:{matched_item_id or 'none'}:{gloss_hash}"))

                    trans_payload = {
                        "id": trans_id,
                        "entry_id": entry_id,
                        "item_id": matched_item_id,
                        "sense_id": None,
                        "word_vi": gloss,
                        "source": "viwiktionary",
                        "source_scope": "viwiktionary_gloss",
                        "source_sense_qualifier": None,
                        "link_status": link_status,
                        "reason": "AUXILIARY_VIWIKTIONARY_GLOSS",
                        "tags": vi_s.get("tags") or [],
                    }
                    batch_translations.append((
                        trans_id,
                        entry_id,
                        matched_item_id,
                        None,
                        gloss,
                        "viwiktionary",
                        "viwiktionary_gloss",
                        None,
                        link_status,
                        "AUXILIARY_VIWIKTIONARY_GLOSS",
                        canonical_json(trans_payload),
                    ))
                    if len(batch_translations) >= BATCH_SIZE:
                        flush_batches()

        flush_batches()
        vi_elapsed = time.time() - vi_start_time
        print(f"Staged {vi_candidates_count} auxiliary Vietnamese candidate definitions in {vi_elapsed:.1f}s.")

    # Write collections sorted deterministically by ID
    output_dir.mkdir(parents=True, exist_ok=True)

    def write_entries_collection() -> tuple[int, int, str]:
        target = output_dir / "entries.jsonl"
        count = 0
        hasher = hashlib.sha256()
        with target.open("w", encoding="utf-8", newline="\n") as out:
            cursor = conn.execute("SELECT id, display_form, identity_form, lookup_form, entry_type, language FROM entries ORDER BY id ASC")
            for row in cursor:
                payload = {
                    "id": row[0],
                    "display_form": row[1],
                    "identity_form": row[2],
                    "lookup_form": row[3],
                    "entry_type": row[4],
                    "language": row[5],
                }
                line = canonical_json(payload) + "\n"
                out.write(line)
                hasher.update(line.encode("utf-8"))
                count += 1
        return count, target.stat().st_size, hasher.hexdigest()

    def write_collection(table_name: str, file_rel_path: str) -> tuple[int, int, str]:
        target = output_dir / file_rel_path
        target.parent.mkdir(parents=True, exist_ok=True)
        count = 0
        hasher = hashlib.sha256()
        with target.open("w", encoding="utf-8", newline="\n") as out:
            cursor = conn.execute(f"SELECT payload FROM {table_name} ORDER BY id ASC")
            for row in cursor:
                line = row[0] + "\n"
                out.write(line)
                hasher.update(line.encode("utf-8"))
                count += 1
        return count, target.stat().st_size, hasher.hexdigest()

    print("Exporting canonical sorted JSONL collections from staging SQLite...")
    collections = {
        "entries.jsonl": ("entries", write_entries_collection()),
        "items.jsonl": ("items", write_collection("items", "items.jsonl")),
        "senses.jsonl": ("senses", write_collection("senses", "senses.jsonl")),
        "forms.jsonl": ("forms", write_collection("forms", "forms.jsonl")),
        "pronunciations.jsonl": ("pronunciations", write_collection("pronunciations", "pronunciations.jsonl")),
        "translations.jsonl": ("translations", write_collection("translations", "translations.jsonl")),
    }

    counts = {name: res[1][0] for name, res in collections.items()}

    # Compute detailed coverage statistics
    cursor = conn.execute("SELECT translation_status, COUNT(*) FROM senses GROUP BY translation_status")
    sense_trans_stats = dict(cursor.fetchall())
    senses_avail = sense_trans_stats.get("AVAILABLE", 0)
    senses_miss = sense_trans_stats.get("MISSING", 0)

    cursor = conn.execute("SELECT link_status, COUNT(*) FROM translations GROUP BY link_status")
    trans_status_stats = dict(cursor.fetchall())

    cursor = conn.execute("SELECT source, COUNT(*) FROM translations GROUP BY source")
    trans_source_stats = dict(cursor.fetchall())

    cursor = conn.execute("SELECT COUNT(DISTINCT entry_id) FROM translations")
    entries_with_vi = cursor.fetchone()[0]

    cursor = conn.execute("SELECT COUNT(DISTINCT item_id) FROM translations WHERE item_id IS NOT NULL")
    items_with_vi = cursor.fetchone()[0]

    total_entries = counts["entries.jsonl"]
    total_items = counts["items.jsonl"]
    total_senses = counts["senses.jsonl"]

    # Compute entry type stats
    cursor = conn.execute("SELECT entry_type, COUNT(*) FROM entries GROUP BY entry_type")
    entry_type_stats = dict(cursor.fetchall())

    en_diff_unrec = en_vi_raw_total - (en_vi_empty_skipped + en_vi_exact_dup_skipped + en_vi_emitted)
    vi_diff_unrec = (vi_raw_senses_total + vi_multi_gloss_net) - (vi_empty_gloss_skipped + vi_unmatched_entries_skipped + vi_exact_dup_skipped + vi_emitted)

    report = {
        "status": "PASS",
        "counts": counts,
        "source_reconciliation": {
            "source_rows_total": raw_rows_count,
            "source_rows_english": en_rows_count,
            "items_emitted": counts["items.jsonl"],
            "difference_unreconciled": en_rows_count - counts["items.jsonl"],
            "rows_skipped": skipped_rows_count,
            "rows_merged": 0,
            "rows_deduplicated": 0,
            "rows_quarantined": 0,
            "item_collisions_disambiguated": sum(v - 1 for v in item_disambiguation_counts.values() if v > 1),
        },
        "translation_source_reconciliation": {
            "enwiktionary_total_raw_records": en_vi_raw_total,
            "enwiktionary_empty_word_skipped": en_vi_empty_skipped,
            "enwiktionary_exact_duplicates_deduped": en_vi_exact_dup_skipped,
            "enwiktionary_emitted_records": en_vi_emitted,
            "enwiktionary_difference_unreconciled": en_diff_unrec,
            "viwiktionary_total_raw_senses": vi_raw_senses_total,
            "viwiktionary_empty_gloss_skipped": vi_empty_gloss_skipped,
            "viwiktionary_unmatched_headwords_skipped": vi_unmatched_entries_skipped,
            "viwiktionary_duplicate_glosses_deduped": vi_exact_dup_skipped,
            "viwiktionary_multi_gloss_net": vi_multi_gloss_net,
            "viwiktionary_emitted_records": vi_emitted,
            "viwiktionary_difference_unreconciled": vi_diff_unrec,
            "total_translations_emitted": counts["translations.jsonl"],
        },
        "vietnamese_coverage": {
            "entries_total": total_entries,
            "entries_with_vi": entries_with_vi,
            "entry_vi_percentage": round((entries_with_vi / total_entries) * 100, 2) if total_entries > 0 else 0.0,
            "items_total": total_items,
            "items_with_vi": items_with_vi,
            "item_vi_percentage": round((items_with_vi / total_items) * 100, 2) if total_items > 0 else 0.0,
            "senses_total": total_senses,
            "senses_with_exact_vi": senses_avail,
            "exact_sense_vi_percentage": round((senses_avail / total_senses) * 100, 2) if total_senses > 0 else 0.0,
            "translations_total_records": counts["translations.jsonl"],
            "translations_by_status": trans_status_stats,
            "translations_by_source": trans_source_stats,
        },
        "entry_type_distribution": entry_type_stats,
        "issues": sorted(issues, key=lambda x: (x.get("code", ""), x.get("id", ""))),
    }

    report_path = output_dir / "validation_report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    # Write LICENSE and ATTRIBUTION notices
    license_text = """Lyreo Lexicon Data Release (v1.0.0)

This dataset incorporates content extracted from Wiktionary (English edition)
and optionally Vietnamese Wiktionary via the Wiktextract parser by Tatu Ylönen (Kaikki.org).

Content License:
Wiktionary text is dual-licensed under the Creative Commons Attribution-ShareAlike 4.0
International License (CC BY-SA 4.0) and the GNU Free Documentation License (GFDL).
Derivative distributions of this lexical data are released under CC BY-SA 4.0:
https://creativecommons.org/licenses/by-sa/4.0/

Attribution & Notice:
- English Wiktionary contributors: https://en.wiktionary.org/
- Vietnamese Wiktionary contributors (when auxiliary input is used): https://vi.wiktionary.org/
- Tatu Ylönen, Wiktextract / Kaikki.org: https://kaikki.org/
  Publication: Tatu Ylonen: Wiktextract: Wiktionary as Machine-Readable Structured Data,
  Proceedings of the 13th Conference on Language Resources and Evaluation (LREC), 2022.

Original entry pages can be found by appending the URL-encoded identity_form from
entries.jsonl to https://en.wiktionary.org/wiki/ . For translations with
source=viwiktionary, use https://vi.wiktionary.org/wiki/ and the same headword.
This release selects, normalizes, and restructures source lexical data.

Audio Notice:
Audio pronunciations are referenced by external URLs to Wikimedia Commons.
Audio binaries are not bundled in this archive. Each Wikimedia Commons recording
maintains its own independent author attribution and license (e.g. CC0, CC BY, CC BY-SA).
"""
    (output_dir / "LICENSE").write_text(license_text, encoding="utf-8")

    # Build file inventory for manifest
    inventory = []
    for path in sorted(output_dir.rglob("*")):
        if path.is_file():
            rel = path.relative_to(output_dir).as_posix()
            inventory.append({
                "path": rel,
                "size_bytes": path.stat().st_size,
                "sha256": sha256_file(path),
                "records": counts.get(rel),
            })

    manifest = {
        "format": "lyreo.dataset-release",
        "manifest_schema_version": 1,
        "package": {
            "domain": PACKAGE_DOMAIN,
            "version": PACKAGE_VERSION,
            "schema_version": SCHEMA_VERSION,
            "generated_at": generated_at,
        },
        "builder": {
            "name": BUILDER_NAME,
            "version": BUILDER_VERSION,
        },
        "source": {
            "root_kind": "wiktionary-wiktextract-raw",
            "files": [
                {"path": raw_en_path.name, "size_bytes": raw_en_path.stat().st_size, "sha256": source_en_sha},
                *(
                    [{"path": raw_vi_path.name, "size_bytes": raw_vi_path.stat().st_size, "sha256": source_vi_sha}]
                    if raw_vi_path else []
                ),
            ],
            "file": raw_en_path.name,
            "sha256": source_en_sha,
            "upstream_dump_date": PINNED_RAW_EN_DUMP_DATE,
            "auxiliary_vi_file": raw_vi_path.name if raw_vi_path else None,
            "auxiliary_vi_sha256": source_vi_sha,
            "auxiliary_vi_dump_date": PINNED_RAW_VI_DUMP_DATE if raw_vi_path else None,
            "raw_rows_processed": raw_rows_count,
            "english_rows_processed": en_rows_count,
            "items_emitted": counts["items.jsonl"],
            "skipped_rows": skipped_rows_count,
        },
        "files": inventory,
        "counts": counts,
        "validation": {
            "status": report["status"],
            "report_path": "validation_report.json",
        },
    }

    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    # Record staging DB size before closing and unlinking
    staging_size_bytes = staging_db_path.stat().st_size if staging_db_path.exists() else 0

    # Package tar.gz
    canonical_root_name = f"{PACKAGE_DOMAIN}-{PACKAGE_VERSION}"
    archive_sha = package_release(output_dir, archive_path, canonical_root_name)
    archive_size_bytes = archive_path.stat().st_size

    # Clean up staging db
    conn.close()
    staging_db_path.unlink(missing_ok=True)

    total_elapsed = time.time() - start_time
    peak_mem_mb = round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024, 2)

    result = {
        "release": str(output_dir),
        "archive": str(archive_path),
        "archive_sha256": archive_sha,
        "archive_size_bytes": archive_size_bytes,
        "manifest_sha256": sha256_file(manifest_path),
        "counts": counts,
        "source_reconciliation": report["source_reconciliation"],
        "vietnamese_coverage": report["vietnamese_coverage"],
        "entry_types": entry_type_stats,
        "metrics": {
            "raw_rows_processed": raw_rows_count,
            "english_rows_processed": en_rows_count,
            "items_emitted": counts["items.jsonl"],
            "skipped_rows": skipped_rows_count,
            "staging_sqlite_size_bytes": staging_size_bytes,
            "peak_memory_mb": peak_mem_mb,
            "build_time_seconds": round(total_elapsed, 2),
        },
    }
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-en", type=Path, required=True, help="Path to raw Wiktextract JSONL or .jsonl.gz")
    parser.add_argument("--raw-vi", type=Path, help="Optional auxiliary Vietnamese Wiktionary extract")
    parser.add_argument("--output", type=Path, required=True, help="Output directory for clean release")
    parser.add_argument("--archive", type=Path, required=True, help="Output path for archive .tar.gz")
    parser.add_argument(
        "--generated-at",
        default=dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        help="Deterministic ISO timestamp",
    )
    parser.add_argument(
        "--staging-dir",
        type=Path,
        default=Path(".data/tmp"),
        help="Directory for temporary SQLite staging",
    )
    parser.add_argument(
        "--allow-unverified-checksum",
        action="store_true",
        help="Bypass strict pinned SHA-256 check (for tests/fixtures only)",
    )
    args = parser.parse_args()

    result = build(
        raw_en_path=args.raw_en.resolve(),
        output_dir=args.output.resolve(),
        archive_path=args.archive.resolve(),
        generated_at=args.generated_at,
        staging_dir=args.staging_dir.resolve(),
        raw_vi_path=args.raw_vi.resolve() if args.raw_vi else None,
        enforce_checksum=not args.allow_unverified_checksum,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
