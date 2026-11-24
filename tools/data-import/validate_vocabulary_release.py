#!/usr/bin/env python3
"""Validate a Vocabulary release before reading it into PostgreSQL."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from hashlib import sha256
import json
from pathlib import Path
import re

from build_vocabulary_release import CATALOG_FILES, LEVELS, LICENSE_URL, MEDIA, REPO, REQUIRED_MEDIA, REVISION, SOURCE_MANIFEST_SHA, TOPICS, UNIT_SIZE, VERSION, clean, media_signature
from clean_import_common import jsonl_rows, sha256_file
from common import stable_uuid
from enrich_vocabulary_release import ALLOWED_MODELS, BASE_MANIFEST, LEGACY_ENRICHED_VERSION, MIN_UNIT_SIZE, PRIOR_ENRICHED_VERSIONS, VERSION as ENRICHED_VERSION, supports_cloze

COLLECTIONS = ("packs", "units", "entries", "assets")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def check_positions(groups: dict, name: str) -> None:
    for owner, positions in groups.items():
        require(sorted(positions) == list(range(1, len(positions) + 1)), f"Invalid {name} ordering for {owner}")


def rows(root: Path, name: str, count: int) -> dict:
    values = list(jsonl_rows(root / f"{name}.jsonl"))
    ids = [value["id"] for value in values]
    require(len(ids) == count and ids == sorted(set(ids)), f"Incorrect {name} count or order")
    return dict(zip(ids, values))


def validate(root: Path, *, catalog_only: bool = False) -> dict:
    manifest = json.loads((root / "manifest.json").read_bytes())
    require(manifest["format"] == "lyreo.dataset-release" and manifest["manifest_schema_version"] == 1,
            "Invalid release envelope")
    package = manifest["package"]
    enriched = package["version"] in (*PRIOR_ENRICHED_VERSIONS, ENRICHED_VERSION)
    require(package["domain"] == "vocabulary" and (package["version"] == VERSION or enriched)
            and package["schema_version"] == package["version"], "Unsupported Vocabulary version")
    source = manifest["source"]
    require((source["url"], source["revision"], source["license"], source["attribution"]) ==
            (REPO, REVISION, LICENSE_URL, "Word Atlas contributors — CC BY 4.0"), "Incorrect Vocabulary source or license")
    if enriched:
        lineage = source["enrichment"]
        require((lineage["baseManifestSha256"], lineage["toeicRelease"]) == (BASE_MANIFEST, "2.0.12")
                and lineage["translationModel"] in ALLOWED_MODELS, "Incorrect bilingual or TOEIC provenance")
        for key in ("translationSha256", "toeicGroupsSha256"):
            require(re.fullmatch(r"[0-9a-f]{64}", lineage[key]) is not None, f"Invalid {key}")
    source_files = {item["path"]: item for item in source["files"]}
    require(len(source["files"]) == 3 and set(source_files) ==
            {"CONTENT_LICENSE.md", "PUBLIC_MANIFEST.json", "data/word-atlas.json"}, "Incomplete source provenance")
    require(source_files["PUBLIC_MANIFEST.json"]["sha256"] == SOURCE_MANIFEST_SHA, "Incorrect source manifest")
    counts = manifest["counts"]
    require(set(counts) == set(COLLECTIONS), "Unexpected collections")
    inventory = {item["path"]: item for item in manifest["files"]}
    require(len(inventory) == len(manifest["files"]), "Duplicate inventory path")
    paths = list(root.rglob("*"))
    require(not any(path.is_symlink() for path in paths), "Release cannot contain symbolic links")
    actual = {path.relative_to(root).as_posix() for path in paths if path.is_file()} - {"manifest.json"}
    active_paths = set(CATALOG_FILES) - {"manifest.json"} if catalog_only else set(inventory)
    require(actual == active_paths, "Inventory does not match release files")
    for path in active_paths:
        meta = inventory[path]
        file = root / path
        require(file.stat().st_size == meta["bytes"] and sha256_file(file) == meta["sha256"], f"File integrity failure: {path}")
    require(inventory["LICENSE"]["sha256"] == source_files["CONTENT_LICENSE.md"]["sha256"], "License checksum mismatch")
    for name in COLLECTIONS:
        require(inventory[f"{name}.jsonl"]["records"] == counts[name], f"Wrong inventory row count for {name}")

    packs, units, entries, assets = (rows(root, collection, counts[collection]) for collection in COLLECTIONS)
    titles = TOPICS | ({"toeic": ("TOEIC Vocabulary", "Từ vựng TOEIC")} if enriched else {})
    require(len(packs) == len(titles), "Incomplete Vocabulary packs")
    topics = list(titles)
    for ident, pack in packs.items():
        topic = pack["source_topic"]
        require(ident == str(stable_uuid("vocabulary-pack", topic)) and pack["position"] == topics.index(topic) + 1
                and (pack["title_en"], pack["title_vi"]) == titles[topic], f"Invalid pack {ident}")

    unit_positions, entry_positions = defaultdict(list), defaultdict(list)
    for unit in sorted(units.values(), key=lambda row: row["position"]):
        owner, level = unit["pack_id"], unit["level"]
        require(owner in packs and level in LEVELS, f"Invalid unit {unit['id']}")
        unit_positions[owner].append(unit["position"])

    check_positions(unit_positions, "unit")
    terms, media_counts, uses = set(), Counter(), defaultdict(set)
    words_by_source = {entry["source_id"]: entry for entry in entries.values()}
    senses = examples = meaning_vi = examples_vi = collocations_vi = context_ready = toeic_count = base_second_recordings = 0
    for ident, entry in entries.items():
        source_id, term, content = entry["source_id"], entry["term"], entry["content"]
        owner = entry["unit_id"]
        require(owner in units, f"Unknown unit for {source_id}")
        owner_pack = units[owner]["pack_id"]
        is_toeic = packs[owner_pack]["source_topic"] == "toeic"
        atlas_id = source_id.removeprefix("toeic:") if is_toeic else source_id
        term_identity = owner_pack, term.casefold()
        require(re.fullmatch(r"[a-z0-9][a-z0-9_-]*", atlas_id) is not None
                and source_id == (f"toeic:{atlas_id}" if is_toeic else atlas_id)
                and ident == str(stable_uuid("vocabulary-entry", source_id)) and clean(term) == term
                and term_identity not in terms and len(term) <= 160, f"Invalid entry {ident}")
        terms.add(term_identity)
        if is_toeic:
            origin = words_by_source[atlas_id]
            require(origin["term"] == term and origin["media"] == entry["media"]
                    and origin["content"]["examples"] == content["examples"], f"TOEIC word lost source evidence: {source_id}")
            toeic_count += 1
        else:
            base_second_recordings += "secondExampleAudio" in entry["media"]
        entry_positions[owner].append(entry["position"])
        require(1 <= len(content["senses"]) <= 3 and len(content["examples"]) == 2, f"Incomplete text for {source_id}")
        for sense in content["senses"]:
            require(clean(sense["pos"]) == sense["pos"] and clean(sense["definitionEn"]) == sense["definitionEn"],
                    f"Invalid sense for {source_id}")
            if package["version"] == ENRICHED_VERSION:
                require(not sense["pos"].endswith("."), f"Trailing period in POS for {source_id}")
            vi = sense["meaningVi"]
            require(vi is None or enriched and clean(vi) == vi and len(vi) <= 500, f"Invalid Vietnamese meaning for {source_id}")
            meaning_vi += vi is not None
        if enriched:
            translated = content["examplesVi"]
            notes = content["contextNotes"]
            status_ok = (
                content.get("translationDraft") is True
                if package["version"] == LEGACY_ENRICHED_VERSION
                else isinstance(content.get("needsReview"), bool) and "translationDraft" not in content
            )
            require(len(translated) == len(content["examples"]) and all(
                vi is None or clean(vi) == vi and len(vi) <= 500 for vi in translated
            ) and status_ok, f"Invalid Vietnamese examples for {source_id}")
            examples_vi += sum(vi is not None for vi in translated)
            collocations = content["collocationsVi"]
            require(len(collocations) == len(content["collocations"]) and all(
                vi is None or clean(vi) == vi and len(vi) <= 500 for vi in collocations
            ), f"Invalid Vietnamese collocations for {source_id}")
            collocations_vi += sum(vi is not None for vi in collocations)
            context_ready += supports_cloze(term, content["examples"])
            require(1 <= len(notes) <= 3 if is_toeic else notes == [], f"Invalid contextual notes for {source_id}")
            for note in notes:
                ids = note["sourceGroupIds"]
                require(clean(note["meaningVi"]) == note["meaningVi"] and note["pos"] in {
                    sense["pos"].casefold().rstrip(".") for sense in content["senses"]
                } and ids and ids == sorted(set(ids)), f"Invalid TOEIC gloss source for {source_id}")
        for key in ("ipaUk", "ipaUs"):
            ipa = content[key]
            require(clean(ipa) == ipa and re.fullmatch(r"/[^/]+/(?: \([a-z.]+\))?(?:(?:,|;) /[^/]+/(?: \([a-z.]+\))?)*", ipa) is not None, f"Invalid {key} for {source_id}")
        for key in ("examples", "collocations", "family", "synonyms", "antonyms"):
            require(isinstance(content[key], list) and all(clean(value) == value for value in content[key]), f"Invalid {key} for {source_id}")
        senses += len(content["senses"])
        examples += len(content["examples"])
        require(REQUIRED_MEDIA <= entry["media"].keys(), f"Missing media for {source_id}")
        for folder, (role, extension, mime) in MEDIA.items():
            if role not in entry["media"]:
                continue
            digest = entry["media"][role]
            require(digest in assets and assets[digest]["mime_type"] == mime, f"Invalid {role} for {source_id}")
            uses[digest].add(f"public/atlas/{folder}/{atlas_id}.{extension}")
            media_counts[role] += 1
        require(set(entry["media"]) <= {role for role, _, _ in MEDIA.values()}, f"Unknown media role for {source_id}")

    check_positions(entry_positions, "entry")
    require(set(entry_positions) == set(units) and max(map(len, entry_positions.values())) <= UNIT_SIZE, "Invalid unit sizes")
    if package["version"] == ENRICHED_VERSION:
        require(min(map(len, entry_positions.values())) >= MIN_UNIT_SIZE - 1, "Unit too small for learner study modes")
    require(set(unit_positions) == set(packs) and set(uses) == set(assets), "Unreferenced packs or assets")
    require(len(entries) - toeic_count == 7298 and base_second_recordings == 1011, "Incomplete base catalog")
    if enriched:
        require(toeic_count >= 500 and meaning_vi >= senses * 0.98 and examples_vi >= examples * 0.98,
                "Bilingual or TOEIC coverage below release threshold")
    expected_files = {"LICENSE", "validation_report.json"} | {name + ".jsonl" for name in COLLECTIONS}
    source_paths = []
    for digest, asset in assets.items():
        extension = "webp" if asset["mime_type"] == "image/webp" else "mp3"
        path = f"media/{digest[:2]}/{digest}.{extension}"
        require(re.fullmatch(r"[0-9a-f]{64}", digest) is not None and asset["path"] == path
                and inventory[path]["sha256"] == digest and inventory[path]["bytes"] == asset["size_bytes"]
                and sorted(uses[digest]) == asset["source_paths"], f"Invalid asset {digest}")
        if not catalog_only:
            with (root / path).open("rb") as stream:
                require(media_signature(stream.read(12), extension), f"Invalid media header for {digest}")
        expected_files.add(path)
        source_paths.extend(f"{source_path}\t{digest}\n" for source_path in asset["source_paths"])
    require(expected_files == inventory.keys(), "Unexpected files in Vocabulary manifest")
    require(sha256("".join(sorted(source_paths)).encode()).hexdigest() == source["media_sha256_manifest"],
            "Source media digest mismatch")
    report = json.loads((root / "validation_report.json").read_bytes())
    levels = Counter(units[entry["unit_id"]]["level"] for entry in entries.values())
    coverage = {"senses": senses, "examples": examples, "meaningVi": meaning_vi, "media": media_counts, "levels": levels}
    if enriched:
        coverage.update(examplesVi=examples_vi, collocationsVi=collocations_vi, contextReady=context_ready, toeicEntries=toeic_count)
    require(report["status"] == "PASS" and report["counts"] == counts
            and report["coverage"] == coverage, "Incorrect coverage report")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--catalog-only", action="store_true")
    args = parser.parse_args()
    print(json.dumps(validate(args.release, catalog_only=args.catalog_only), ensure_ascii=False, indent=2))
