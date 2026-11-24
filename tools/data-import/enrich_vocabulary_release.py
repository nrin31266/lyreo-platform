#!/usr/bin/env python3
"""Build the bilingual Vocabulary catalog and the TOEIC study pack from verified inputs."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from copy import deepcopy
import json
from pathlib import Path
import re
import shutil
import tarfile
import tempfile

from build_vocabulary_release import CATALOG_FILES, LEVELS, TOPICS, UNIT_SIZE, clean, write_json
from clean_import_common import jsonl_rows, sha256_file
from common import package_release, stable_uuid

VERSION = "1.3.0"
MIN_UNIT_SIZE = 18
PRIOR_ENRICHED_VERSIONS = ("1.1.0", "1.2.0", "1.2.1")
LEGACY_ENRICHED_VERSION = "1.1.0"
BASE_MANIFEST = "59c68f0b030c9f6ee40ba3cde5106336f453198381779d3a91f5c25f4045db54"
TOEIC_MANIFEST_SOURCE = "grammar-toeic-2.0.12"
GROUPS = f"{TOEIC_MANIFEST_SOURCE}/toeic/groups.jsonl"
LEGACY_MODEL = "dekthedev/opus-mt-en-vi-ct2-int8@c22547827b876e8ee939d6a9363965e5c9f769e1"
MODEL = "deepseek-flash"
ALLOWED_MODELS = (MODEL, LEGACY_MODEL)
NOTE = re.compile(r"^[*•\-\s]*([A-Za-z][A-Za-z’' .-]{0,70}?)\s+\(([^()]{1,22})\)\s*(?:\*\*)?\s*[:：–-]\s*(.+)$")
POS_NAMES = {"noun": "n", "verb": "v", "adjective": "adj", "adverb": "adv", "preposition": "prep"}
THEMES = {
    "Công việc và văn phòng": {"work", "education"},
    "Kinh doanh và tài chính": {"economy", "government", "society"},
    "Đi lại và dịch vụ": {"travel", "city", "health", "sport"},
    "Công nghệ và truyền thông": {"technology", "science", "media", "arts"},
    "Giao tiếp hằng ngày": {"daily", "emotion", "thinking", "nature", "environment", "history"},
}


def supports_cloze(term: str, examples: list[str]) -> bool:
    pattern = re.compile(rf"(?<![\w'-]){re.escape(normalize_term(term))}(?![\w'-])")
    return any(len(pattern.findall(normalize_term(example))) == 1 for example in examples)


def normalize_term(value: str) -> str:
    return value.replace("’", "'").strip().casefold()


def toeic_groups(archive: Path) -> tuple[bytes, str]:
    groups = manifest = None
    with tarfile.open(archive, "r|gz") as bundle:
        for member in bundle:
            if not member.isfile() or member.name not in {GROUPS, f"{TOEIC_MANIFEST_SOURCE}/manifest.json"}:
                continue
            with bundle.extractfile(member) as stream:
                data = stream.read()
            if member.name == GROUPS:
                groups = data
            else:
                manifest = json.loads(data)
            if groups is not None and manifest is not None:
                break
    if manifest is None or groups is None or manifest["package"]["version"] != "2.0.12":
        raise ValueError("Expected the installed Grammar/TOEIC 2.0.12 archive")
    from hashlib import sha256
    digest = sha256(groups).hexdigest()
    inventory = next(row for row in manifest["files"] if row["path"] == "toeic/groups.jsonl")
    if digest != inventory["sha256"] or len(groups) != inventory["size_bytes"]:
        raise ValueError("TOEIC vocabulary note source changed")
    return groups, digest


def toeic_notes(groups: bytes, entries: list[dict]) -> dict:
    by_term = {normalize_term(entry["term"]): entry for entry in entries}
    evidence = defaultdict(lambda: defaultdict(set))
    for line in groups.splitlines():
        group = json.loads(line)
        for note in (group.get("vocabulary_note_vi") or "").splitlines():
            match = NOTE.fullmatch(note)
            if not match:
                continue
            term, pos, gloss = match.groups()
            entry = by_term.get(normalize_term(term))
            if entry is None:
                continue
            pos = pos.strip().casefold().rstrip(".")
            pos = POS_NAMES.get(pos, pos)
            if pos not in {sense["pos"].casefold().rstrip(".") for sense in entry["content"]["senses"]}:
                continue
            gloss = gloss.strip("* .")
            if not gloss:
                continue
            gloss = clean(gloss)
            if len(gloss) <= 160 and any(char in "ăâđêôơưàáảãạèéẻẽẹìíỉĩịòóỏõọùúủũụỳýỷỹỵ" for char in gloss.casefold()):
                evidence[entry["id"]][pos, gloss].add(group["id"])
    result = {}
    for ident, glosses in evidence.items():
        count = len(set().union(*glosses.values()))
        if count < 3:
            continue
        options = sorted(glosses.items(), key=lambda row: (-len(row[1]), row[0]))
        result[ident] = (count, [
            {"pos": pos, "meaningVi": gloss, "sourceGroupIds": sorted(ids)}
            for (pos, gloss), ids in options[:3]
        ])
    return result


POS_PREFIX = re.compile(
    r"^(?:\(?(?:danh từ|động từ|tính từ|trạng từ|giới từ|liên từ|đại từ|thán từ|mạo từ|từ hạn định|trợ động từ|cụm từ|cụm động từ|n|v|adj|adv|prep|conj|pron|interj|det|phr(?:\.\s*v)?|modal|num|aux)\.?\)?\s*[:.\-–]?\s+)+",
    re.IGNORECASE,
)


def strip_pos(value: str) -> str:
    cleaned = clean(value)
    stripped = POS_PREFIX.sub("", cleaned).strip()
    if stripped.endswith(".") and not stripped.endswith("..."):
        stripped = stripped[:-1].strip()
    return stripped or cleaned


def normalize_pos_tag(pos: str) -> str:
    tag = clean(pos).rstrip(".")
    return re.sub(r"\.\s+", " ", tag)


def normalize_pos_notes(text: str) -> str:
    return re.sub(r"\(([a-z]+)\.\)", r"(\1)", clean(text))


def rebalance_topic_units(packs: list[dict], units: list[dict], entries: list[dict]) -> tuple[list[dict], list[dict]]:
    unit_by_id = {u["id"]: u for u in units}
    pack_by_id = {p["id"]: p for p in packs}
    by_topic_level: dict[tuple[str, str], list[dict]] = defaultdict(list)
    entry_level: dict[str, str] = {}
    for entry in entries:
        unit = unit_by_id[entry["unit_id"]]
        topic = pack_by_id[unit["pack_id"]]["source_topic"]
        by_topic_level[topic, unit["level"]].append(entry)
        entry_level[entry["id"]] = unit["level"]

    new_units: list[dict] = []
    new_entries: list[dict] = []
    for pack in sorted(packs, key=lambda row: row["position"]):
        topic = pack["source_topic"]
        buckets: list[tuple[list[str], list[dict]]] = []
        for level in LEVELS:
            words = sorted(by_topic_level[topic, level], key=lambda w: (w["term"].casefold(), w["source_id"]))
            if words:
                buckets.append(([level], words))
        while len(buckets) > 1 and any(len(words) < MIN_UNIT_SIZE for _, words in buckets):
            idx = next(i for i, (_, words) in enumerate(buckets) if len(words) < MIN_UNIT_SIZE)
            if idx == 0:
                target = 1
            elif idx == len(buckets) - 1:
                target = idx - 1
            else:
                target = idx - 1 if len(buckets[idx - 1][1]) <= len(buckets[idx + 1][1]) else idx + 1
            lo, hi = min(idx, target), max(idx, target)
            buckets[lo:hi + 1] = [(buckets[lo][0] + buckets[hi][0], buckets[lo][1] + buckets[hi][1])]

        label_counts: Counter[str] = Counter()
        unit_position = 0
        for _, words in buckets:
            batch_count = max(1, (len(words) + UNIT_SIZE - 1) // UNIT_SIZE)
            for index in range(batch_count):
                start = index * len(words) // batch_count
                end = (index + 1) * len(words) // batch_count
                chunk = words[start:end]
                chunk_levels = [lvl for lvl in LEVELS if any(entry_level[w["id"]] == lvl for w in chunk)]
                counts = Counter(entry_level[w["id"]] for w in chunk)
                modal_level = max(LEVELS, key=lambda lvl: (counts[lvl], -LEVELS.index(lvl)))
                label = chunk_levels[0] if len(chunk_levels) == 1 else f"{chunk_levels[0]}–{chunk_levels[-1]}"
                label_counts[label] += 1
                number = label_counts[label]
                unit_position += 1
                unit_id = str(stable_uuid("vocabulary-unit", f"{topic}:{label}:{number}"))
                new_units.append({
                    "id": unit_id,
                    "pack_id": pack["id"],
                    "position": unit_position,
                    "level": modal_level,
                    "title": f"{label} · {number:02d}",
                })
                for pos_idx, word in enumerate(chunk, 1):
                    new_entries.append(dict(word, unit_id=unit_id, position=pos_idx))
    return new_units, new_entries


def apply_translations(entries: list[dict], translations: Path) -> None:
    by_id = {row["id"]: row for row in jsonl_rows(translations)}
    if set(by_id) != {entry["id"] for entry in entries}:
        raise ValueError("Translations must cover the Word Atlas entry IDs exactly")
    for entry in entries:
        generated = by_id[entry["id"]]
        content = entry["content"]
        content["ipaUk"] = normalize_pos_notes(content["ipaUk"])
        content["ipaUs"] = normalize_pos_notes(content["ipaUs"])
        for sense, vi in zip(content["senses"], generated["senses"], strict=True):
            sense["pos"] = normalize_pos_tag(sense["pos"])
            sense["meaningVi"] = strip_pos(vi) if vi else None
        if len(content["examples"]) != len(generated["examples"]):
            raise ValueError(f"Missing translated examples for {entry['source_id']}")
        content["examplesVi"] = [clean(value) if value else None for value in generated["examples"]]
        if len(content["collocations"]) != len(generated["collocations"]):
            raise ValueError(f"Missing translated collocations for {entry['source_id']}")
        content["collocationsVi"] = [clean(value) if value else None for value in generated["collocations"]]
        for key in ("synonyms", "antonyms", "family"):
            additions = generated.get(key, [])
            if not isinstance(additions, list):
                raise ValueError(f"Invalid generated {key} for {entry['source_id']}")
            existing = {value.split(" (", 1)[0].strip().casefold() for value in content[key]}
            for value in additions:
                head = value.split(" (", 1)[0].strip().casefold() if isinstance(value, str) else ""
                if isinstance(value, str) and value.strip() and head and head not in existing and len(content[key]) < 12:
                    content[key].append(clean(value))
                    existing.add(head)
        content["family"] = [normalize_pos_notes(item) for item in content["family"]]
        content["needsReview"] = False
        content["contextNotes"] = []


def build(base: Path, translations: Path, toeic: Path, output: Path, archive: Path,
          *, version: str = VERSION, model: str = MODEL) -> dict:
    if output.exists() or archive.exists() or archive.resolve().is_relative_to(output.resolve()):
        raise ValueError("Choose new output and archive paths")
    if sha256_file(base / "manifest.json") != BASE_MANIFEST:
        raise ValueError("Incorrect Word Atlas base release")
    manifest = json.loads((base / "manifest.json").read_bytes())
    packs, units, entries = (list(jsonl_rows(base / f"{name}.jsonl")) for name in ("packs", "units", "entries"))
    pack_topics = {pack["id"]: pack["source_topic"] for pack in packs}
    base_unit_meta = {unit["id"]: (pack_topics[unit["pack_id"]], unit["level"]) for unit in units}
    entry_origin = {entry["id"]: base_unit_meta[entry["unit_id"]] for entry in entries}
    apply_translations(entries, translations)
    units, entries = rebalance_topic_units(packs, units, entries)
    groups, groups_digest = toeic_groups(toeic)
    notes = toeic_notes(groups, entries)
    pack_id = str(stable_uuid("vocabulary-pack", "toeic"))
    packs.append({"id": pack_id, "source_topic": "toeic", "position": len(TOPICS) + 1,
                  "title_en": "TOEIC Vocabulary", "title_vi": "Từ vựng TOEIC"})
    theme_entries = defaultdict(list)
    for entry in entries:
        if entry["id"] in notes:
            topic, level = entry_origin[entry["id"]]
            theme = next(name for name, topics in THEMES.items() if topic in topics)
            count, glosses = notes[entry["id"]]
            theme_entries[theme].append((count, level, entry, glosses))
    for theme in THEMES:
        matches = sorted(theme_entries[theme], key=lambda value: (-value[0], value[2]["term"].casefold()))
        batch_count = (len(matches) + UNIT_SIZE - 1) // UNIT_SIZE
        for index in range(batch_count):
            start = index * len(matches) // batch_count
            end = (index + 1) * len(matches) // batch_count
            batch = matches[start:end]
            unit_id = str(stable_uuid("vocabulary-unit", f"toeic:{theme}:{index + 1}"))
            level = Counter(level for _, level, _, _ in batch).most_common(1)[0][0]
            units.append({"id": unit_id, "pack_id": pack_id, "position": sum(u["pack_id"] == pack_id for u in units) + 1,
                          "level": level, "title": f"{theme} · {index + 1:02d}"})
            for position, (_, _, source, glosses) in enumerate(batch, 1):
                entry = deepcopy(source)
                entry["source_id"] = f"toeic:{source['source_id']}"
                entry.update(id=str(stable_uuid("vocabulary-entry", entry["source_id"])), unit_id=unit_id, position=position)
                entry["content"]["contextNotes"] = glosses
                entries.append(entry)

    report = json.loads((base / "validation_report.json").read_bytes())
    data = {"packs": packs, "units": units, "entries": entries}
    counts = manifest["counts"] | {name: len(rows) for name, rows in data.items()}
    levels = {unit["id"]: unit["level"] for unit in units}
    report.update(counts=counts, coverage={
        "senses": sum(len(row["content"]["senses"]) for row in entries),
        "examples": sum(len(row["content"]["examples"]) for row in entries),
        "meaningVi": sum(bool(sense["meaningVi"]) for row in entries for sense in row["content"]["senses"]),
        "examplesVi": sum(bool(vi) for row in entries for vi in row["content"]["examplesVi"]),
        "collocationsVi": sum(bool(vi) for row in entries for vi in row["content"]["collocationsVi"]),
        "contextReady": sum(supports_cloze(row["term"], row["content"]["examples"]) for row in entries),
        "media": dict(Counter(role for row in entries for role in row["media"])),
        "levels": dict(Counter(levels[row["unit_id"]] for row in entries)),
        "toeicEntries": len(notes),
    })
    manifest["package"].update(version=version, schema_version=version, generated_at="2026-11-25T00:00:00Z")
    manifest["builder"] = {"name": Path(__file__).name, "version": version}
    manifest["source"].update(
        changes="English study fields normalized, grouped by CEFR and localized with Vietnamese meanings, examples, collocations and word relations",
        enrichment={"baseManifestSha256": BASE_MANIFEST, "translationModel": model,
                    "translationSha256": sha256_file(translations), "toeicRelease": "2.0.12",
                    "toeicGroupsSha256": groups_digest},
    )
    manifest["counts"] = counts
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".vocabulary-enrich-", dir=output.parent) as directory:
        staged = Path(directory) / "catalog"
        staged.mkdir()
        for file in ("LICENSE", "assets.jsonl"):
            shutil.copyfile(base / file, staged / file)
        for name, rows in data.items():
            with (staged / f"{name}.jsonl").open("w", encoding="utf-8") as stream:
                for row in sorted(rows, key=lambda value: value["id"]):
                    stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
        write_json(staged / "validation_report.json", report)
        for file in manifest["files"]:
            path = staged / file["path"]
            if path.is_file():
                file.update(bytes=path.stat().st_size, sha256=sha256_file(path))
                if path.stem in data:
                    file["records"] = counts[path.stem]
        write_json(staged / "manifest.json", manifest)
        from validate_vocabulary_release import validate
        validate(staged, catalog_only=True)
        staged.rename(output)
    checksum = package_release(output, archive, f"vocabulary-{version}", paths=(output / member for member in CATALOG_FILES))
    return report | {"catalogSha256": checksum, "manifestSha256": sha256_file(output / "manifest.json")}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, type=Path)
    parser.add_argument("--translations", required=True, type=Path)
    parser.add_argument("--toeic", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--version", default=VERSION)
    parser.add_argument("--model", default=MODEL, choices=ALLOWED_MODELS)
    args = parser.parse_args()
    print(json.dumps(build(args.base, args.translations, args.toeic, args.output, args.archive, version=args.version, model=args.model), ensure_ascii=False, indent=2))
