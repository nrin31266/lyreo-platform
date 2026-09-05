#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Iterator

from common import begin_import, checksum, fail_import, finish_import, normalized, stable_uuid


def rows(path: Path) -> Iterator[dict]:
    with path.open(encoding="utf-8") as file:
        for line in file:
            if line.strip():
                yield json.loads(line)


def entry_type(item: dict) -> str:
    word = str(item.get("word") or "").strip()
    if " " not in word and "-" not in word:
        return "WORD"
    marker = " ".join(
        [str(item.get("pos") or ""), *(item.get("tags") or []), *(item.get("categories") or [])]
    ).lower()
    if "phrasal verb" in marker:
        return "PHRASAL_VERB"
    if "idiom" in marker or "idiomatic" in marker:
        return "IDIOM"
    if "collocation" in marker:
        return "COLLOCATION"
    return "PHRASE"


def vi_words(value) -> list[str]:
    if not isinstance(value, list):
        return []
    result: list[str] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        code = item.get("code") or item.get("lang_code")
        if code != "vi":
            continue
        word = str(item.get("word") or "").strip()
        if word and word not in result:
            result.append(word)
    return result


def ensure_source(conn, *, code: str, name: str, url: str):
    # Wiktionary extracts carry upstream attribution/license obligations. Keep provenance
    # normalized so individual senses/pronunciations point at a source rather than duplicating
    # license text on every row.
    license_id = stable_uuid("data-license", "wiktionary-content")
    source_id = stable_uuid("data-source", code.lower())
    conn.execute(
        """INSERT INTO data_license(id,spdx_code,name,url)
           VALUES(%s,NULL,'Wiktionary content licenses (CC BY-SA / GFDL)',
                  'https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use')
           ON CONFLICT(id) DO UPDATE SET name=excluded.name,url=excluded.url""",
        (license_id,),
    )
    conn.execute(
        """INSERT INTO data_source(id,code,name,url,license_id,attribution_text)
           VALUES(%s,%s,%s,%s,%s,%s)
           ON CONFLICT(code) DO UPDATE
             SET name=excluded.name,url=excluded.url,license_id=excluded.license_id,
                 attribution_text=excluded.attribution_text""",
        (
            source_id,
            code,
            name,
            url,
            license_id,
            "Data derived from Wiktionary via Kaikki/Wiktextract. Preserve source attribution and upstream license metadata.",
        ),
    )
    return source_id


def import_english_item(conn, item: dict, source_id) -> bool:
    if item.get("lang_code") != "en" or not item.get("word"):
        return False
    word = str(item["word"]).strip()
    etype = entry_type(item)
    norm = normalized(word)
    eid = stable_uuid("lexicon-entry", f"en:{etype}:{norm}")
    conn.execute(
        """INSERT INTO lexicon_entry(id,canonical_form,normalized_form,entry_type,language,status)
           VALUES(%s,%s,%s,%s,'en','ACTIVE')
           ON CONFLICT(language,normalized_form,entry_type) DO UPDATE
             SET canonical_form=excluded.canonical_form,updated_at=now()""",
        (eid, word, norm, etype),
    )

    # Inflections/alternative forms allow global dictionary lookup without generating a new
    # LexiconEntry for "went", "children", "postponed", ...
    for form in item.get("forms") or []:
        if not isinstance(form, dict):
            continue
        surface = str(form.get("form") or "").strip()
        if not surface or surface == "-":
            continue
        form_norm = normalized(surface)
        fid = stable_uuid("lexicon-form", f"{eid}:{form_norm}")
        tags = form.get("tags") or []
        conn.execute(
            """INSERT INTO lexicon_form(id,entry_id,form,normalized_form,form_type)
               VALUES(%s,%s,%s,%s,%s)
               ON CONFLICT(entry_id,normalized_form) DO UPDATE
                 SET form=excluded.form,form_type=excluded.form_type""",
            (fid, eid, surface, form_norm, ",".join(str(x) for x in tags[:8]) or None),
        )

    top_level_vi = vi_words(item.get("translations"))
    senses = item.get("senses") or []
    for position, sense in enumerate(senses[:24], 1):
        if not isinstance(sense, dict):
            continue
        definition = "; ".join(str(x) for x in (sense.get("glosses") or [])[:4]) or None
        translations = vi_words(sense.get("translations"))
        if not translations and position == 1:
            translations = top_level_vi
        translation = "; ".join(translations) or None
        status = "AVAILABLE" if translation else "MISSING"
        sid = stable_uuid("lexicon-sense", f"{eid}:{position}")
        conn.execute(
            """INSERT INTO lexicon_sense(
                   id,entry_id,position,part_of_speech,definition_en,translation_vi,
                   translation_status,source_id,metadata_json)
               VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)
               ON CONFLICT(entry_id,position) DO UPDATE SET
                   part_of_speech=COALESCE(excluded.part_of_speech,lexicon_sense.part_of_speech),
                   definition_en=COALESCE(excluded.definition_en,lexicon_sense.definition_en),
                   translation_vi=COALESCE(lexicon_sense.translation_vi,excluded.translation_vi),
                   translation_status=CASE
                     WHEN lexicon_sense.translation_vi IS NULL THEN excluded.translation_status
                     ELSE lexicon_sense.translation_status END,
                   source_id=COALESCE(lexicon_sense.source_id,excluded.source_id)""",
            (
                sid,
                eid,
                position,
                item.get("pos"),
                definition,
                translation,
                status,
                source_id,
                json.dumps(
                    {"tags": sense.get("tags", []), "examples": (sense.get("examples") or [])[:5]},
                    ensure_ascii=False,
                ),
            ),
        )

    for index, sound in enumerate(item.get("sounds") or []):
        if not isinstance(sound, dict):
            continue
        ipa = sound.get("ipa")
        url = sound.get("mp3_url") or sound.get("ogg_url")
        if not ipa and not url:
            continue
        tags = " ".join(str(x) for x in (sound.get("tags") or [])).lower()
        accent = None
        if any(token in tags for token in ("us", "general american", "american")):
            accent = "US"
        elif any(token in tags for token in ("uk", "received pronunciation", "british")):
            accent = "UK"
        pid = stable_uuid("lexicon-pronunciation", f"{eid}:{index}:{ipa}:{url}")
        conn.execute(
            """INSERT INTO lexicon_pronunciation(
                   id,entry_id,accent,ipa,external_audio_url,source_id,metadata_json)
               VALUES(%s,%s,%s,%s,%s,%s,%s::jsonb)
               ON CONFLICT(id) DO UPDATE SET
                 accent=excluded.accent,ipa=excluded.ipa,
                 external_audio_url=excluded.external_audio_url,source_id=excluded.source_id""",
            (pid, eid, accent, ipa, url, source_id, json.dumps(sound, ensure_ascii=False)),
        )
    return True


def import_vietnamese_item(conn, item: dict, source_id) -> tuple[bool, int]:
    """Best-effort second pass over Vietnamese Wiktionary extraction.

    In a viwiktionary extract an English headword normally still has lang_code="en" while
    glosses are written for Vietnamese readers. We use those glosses only as Vietnamese sense
    text; English definitions remain owned by the English source. If the upstream schema for a
    future dump differs, dry-run/validation must be reviewed before `--apply`.
    """
    if item.get("lang_code") != "en" or not item.get("word"):
        return False, 0
    word = str(item["word"]).strip()
    norm = normalized(word)
    etype = entry_type(item)
    # Prefer exact type, fall back to any English entry with the same normalized form.
    row = conn.execute(
        """SELECT id FROM lexicon_entry
           WHERE language='en' AND normalized_form=%s
           ORDER BY CASE WHEN entry_type=%s THEN 0 ELSE 1 END, created_at
           LIMIT 1""",
        (norm, etype),
    ).fetchone()
    if row:
        eid = row[0]
    else:
        eid = stable_uuid("lexicon-entry", f"en:{etype}:{norm}")
        conn.execute(
            """INSERT INTO lexicon_entry(id,canonical_form,normalized_form,entry_type,language,status)
               VALUES(%s,%s,%s,%s,'en','ACTIVE')
               ON CONFLICT(language,normalized_form,entry_type) DO NOTHING""",
            (eid, word, norm, etype),
        )

    enriched = 0
    for position, sense in enumerate((item.get("senses") or [])[:24], 1):
        if not isinstance(sense, dict):
            continue
        gloss = "; ".join(str(x) for x in (sense.get("glosses") or [])[:4]).strip()
        if not gloss:
            continue
        pos = item.get("pos")
        target = conn.execute(
            """SELECT id,position FROM lexicon_sense
               WHERE entry_id=%s AND translation_vi IS NULL
               ORDER BY CASE WHEN part_of_speech=%s THEN 0 ELSE 1 END, position
               LIMIT 1""",
            (eid, pos),
        ).fetchone()
        if target:
            conn.execute(
                """UPDATE lexicon_sense
                   SET translation_vi=%s,translation_status='AVAILABLE',source_id=COALESCE(source_id,%s)
                   WHERE id=%s""",
                (gloss, source_id, target[0]),
            )
        else:
            next_position = conn.execute(
                "SELECT COALESCE(max(position),0)+1 FROM lexicon_sense WHERE entry_id=%s",
                (eid,),
            ).fetchone()[0]
            sid = stable_uuid("lexicon-sense", f"{eid}:vi:{position}:{gloss[:64]}")
            conn.execute(
                """INSERT INTO lexicon_sense(
                       id,entry_id,position,part_of_speech,definition_en,translation_vi,
                       translation_status,source_id,metadata_json)
                   VALUES(%s,%s,%s,%s,NULL,%s,'AVAILABLE',%s,%s::jsonb)
                   ON CONFLICT(entry_id,position) DO NOTHING""",
                (
                    sid,
                    eid,
                    next_position,
                    pos,
                    gloss,
                    source_id,
                    json.dumps({"source": "viwiktionary"}, ensure_ascii=False),
                ),
            )
        enriched += 1
    return True, enriched


def dry_run(path: Path, label: str, limit: int) -> dict:
    scanned = kept = senses = 0
    for item in rows(path):
        scanned += 1
        if item.get("lang_code") == "en" and item.get("word"):
            kept += 1
            senses += len(item.get("senses") or [])
        if limit and scanned >= limit:
            break
    return {"source": label, "scanned": scanned, "englishHeadwords": kept, "senses": senses}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build Lyreo global Lexicon from Kaikki/Wiktextract English + optional Vietnamese Wiktionary JSONL"
    )
    parser.add_argument("--english", default=os.getenv("KAIKKI_EN_JSONL"))
    parser.add_argument("--vietnamese", default=os.getenv("KAIKKI_VI_JSONL"))
    parser.add_argument("--limit", type=int, default=0, help="Development limit per source; 0 = all")
    parser.add_argument("--batch-size", type=int, default=5000)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    english = Path(args.english or "").expanduser()
    if not english.exists():
        raise SystemExit("KAIKKI_EN_JSONL/--english must point to a Kaikki/Wiktextract JSONL file")
    vietnamese = Path(args.vietnamese).expanduser() if args.vietnamese else None
    if vietnamese and not vietnamese.exists():
        raise SystemExit(f"Vietnamese source does not exist: {vietnamese}")

    if not args.apply:
        report = {
            "strategy": "broad seed + viwiktionary merge + explicit MISSING + lazy AI fallback",
            "english": dry_run(english, "English Wiktionary", args.limit),
            "vietnamese": dry_run(vietnamese, "Vietnamese Wiktionary", args.limit) if vietnamese else None,
            "note": "Dry-run validates source shape only; use --apply after reviewing counts.",
        }
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return

    database_url = os.getenv("DATABASE_URL")
    if not database_url:
        raise SystemExit("DATABASE_URL required for --apply")

    import psycopg

    source_files = [english] + ([vietnamese] if vietnamese else [])
    sha = checksum([p for p in source_files if p is not None])
    imported = vi_headwords = vi_senses = 0
    import_id = None

    with psycopg.connect(database_url) as conn:
        try:
            import_id = begin_import(conn, "KAIKKI_LEXICON", "wiktextract", english.parent, sha)
            english_source = ensure_source(
                conn, code="KAIKKI_EN", name="Kaikki / English Wiktionary", url="https://kaikki.org/"
            )
            vi_source = None
            if vietnamese:
                vi_source = ensure_source(
                    conn, code="KAIKKI_VI", name="Kaikki / Vietnamese Wiktionary", url="https://kaikki.org/viwiktionary/"
                )
            conn.commit()

            for item in rows(english):
                if import_english_item(conn, item, english_source):
                    imported += 1
                    if imported % args.batch_size == 0:
                        conn.commit()
                        print(f"english: {imported} entries", flush=True)
                    if args.limit and imported >= args.limit:
                        break
            conn.commit()

            if vietnamese and vi_source:
                scanned_vi = 0
                for item in rows(vietnamese):
                    considered, enriched = import_vietnamese_item(conn, item, vi_source)
                    if considered:
                        vi_headwords += 1
                        vi_senses += enriched
                        if vi_headwords % args.batch_size == 0:
                            conn.commit()
                            print(f"vietnamese: {vi_headwords} headwords / {vi_senses} senses", flush=True)
                    scanned_vi += 1
                    if args.limit and vi_headwords >= args.limit:
                        break
                conn.commit()

            finish_import(conn, import_id, imported + vi_senses)
            conn.commit()
        except Exception as exc:
            conn.rollback()
            if import_id:
                fail_import(conn, import_id, f"{type(exc).__name__}: {exc}")
                conn.commit()
            raise

    print(
        json.dumps(
            {
                "englishEntriesImported": imported,
                "vietnameseHeadwordsConsidered": vi_headwords,
                "vietnameseSensesEnriched": vi_senses,
                "missingVietnamesePolicy": "MISSING; enrich later rather than spending LLM tokens during seed",
                "checksum": sha,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
