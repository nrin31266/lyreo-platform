#!/usr/bin/env python3
"""Convert the pinned Word Atlas source archive to a self-contained Lyreo release."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from hashlib import sha256
import json
from pathlib import Path
import re
import tarfile
from tempfile import TemporaryDirectory
import unicodedata

from common import package_release, stable_uuid
from clean_import_common import sha256_file

REVISION = "0f1bae41fe5428f0f44781e60282525407b5ae46"
REPO = "https://github.com/designfuchen/word-atlas"
SOURCE_MANIFEST_SHA = "d69abb9adc7f8c1fcf73dc60edbd5ac932d3eba6547c1a9c37a336e159b3f6eb"
LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"
VERSION = "1.0.0"
LEVELS = ("A1", "A2", "B1", "B2", "C1", "C2")
TOPICS = {
    "daily": ("Everyday life", "Đời sống hằng ngày"),
    "education": ("Education", "Giáo dục"),
    "work": ("Work", "Công việc"),
    "travel": ("Travel", "Du lịch"),
    "health": ("Health", "Sức khỏe"),
    "society": ("Society", "Xã hội"),
    "emotion": ("Emotions", "Cảm xúc"),
    "thinking": ("Thinking", "Tư duy"),
    "city": ("Cities", "Đô thị"),
    "nature": ("Nature", "Thiên nhiên"),
    "environment": ("Environment", "Môi trường"),
    "technology": ("Technology", "Công nghệ"),
    "science": ("Science", "Khoa học"),
    "economy": ("Economy", "Kinh tế"),
    "government": ("Government", "Chính quyền"),
    "media": ("Media", "Truyền thông"),
    "arts": ("Arts", "Nghệ thuật"),
    "sport": ("Sports", "Thể thao"),
    "history": ("History", "Lịch sử"),
}
MEDIA = {
    "img": ("image", "webp", "image/webp"),
    "audio/uk": ("ukAudio", "mp3", "audio/mpeg"),
    "audio/us": ("usAudio", "mp3", "audio/mpeg"),
    "audio/ex": ("exampleAudio", "mp3", "audio/mpeg"),
    "audio/ex2": ("secondExampleAudio", "mp3", "audio/mpeg"),
}
REQUIRED_MEDIA = {"image", "ukAudio", "usAudio", "exampleAudio"}
MEDIA_PATH = re.compile(r"public/atlas/(img|audio/(?:uk|us|ex2?))/([a-z0-9_-]+)\.(webp|mp3)")
UNIT_SIZE = 30
CATALOG_FILES = ("LICENSE", "manifest.json", "validation_report.json", "packs.jsonl", "units.jsonl", "entries.jsonl", "assets.jsonl")


def package_catalog(release: Path, archive: Path) -> str:
    return package_release(release, archive, f"vocabulary-{VERSION}", paths=(release / path for path in CATALOG_FILES))


def clean(value: str) -> str:
    value = " ".join(unicodedata.normalize("NFC", value).split())
    if not value or any(unicodedata.category(char).startswith("C") for char in value):
        raise ValueError(f"Invalid text {value!r}")
    return value


def word_rows(source: list[dict]) -> tuple[list[dict], list[dict], list[dict]]:
    grouped = defaultdict(list)
    ids, terms = set(), set()
    for row in source:
        source_id, term = row["id"], clean(row["w"])
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", source_id) or source_id in ids or term.casefold() in terms:
            raise ValueError(f"Invalid or repeated word identity: {source_id}")
        ids.add(source_id)
        terms.add(term.casefold())
        if row["topic"] not in TOPICS or row["cefr"] not in LEVELS:
            raise ValueError(f"Unrecognized topic or CEFR level: {source_id}")
        grouped[row["topic"], row["cefr"]].append({
            "id": str(stable_uuid("vocabulary-entry", source_id)),
            "source_id": source_id,
            "term": term,
            "content": {
                "ipaUk": clean(row["uk"]),
                "ipaUs": clean(row["us"]),
                "senses": [{"pos": clean(s["pos"]), "definitionEn": clean(s["en"]), "meaningVi": None}
                           for s in row["senses"]],
                "examples": [clean(example["en"]) for example in row["ex"]],
                "collocations": [clean(item.split("｜", 1)[0]) for item in row["col"]],
                "family": [clean(item) for item in row["fam"]],
                "synonyms": [clean(item) for item in row["syn"]],
                "antonyms": [clean(item) for item in row["ant"]],
            },
            "media": {},
        })

    packs, units, entries = [], [], []
    for position, (topic, (en, vi)) in enumerate(TOPICS.items(), 1):
        pack_id = str(stable_uuid("vocabulary-pack", topic))
        if not any((topic, level) in grouped for level in LEVELS):
            continue
        packs.append({"id": pack_id, "source_topic": topic, "position": position, "title_en": en, "title_vi": vi})
        unit_position = 0
        for level in LEVELS:
            words = sorted(grouped[topic, level], key=lambda word: (word["term"].casefold(), word["source_id"]))
            for offset in range(0, len(words), UNIT_SIZE):
                unit_position += 1
                number = offset // UNIT_SIZE + 1
                unit_id = str(stable_uuid("vocabulary-unit", f"{topic}:{level}:{number}"))
                units.append({"id": unit_id, "pack_id": pack_id, "position": unit_position,
                              "level": level, "title": f"{level} · {number:02d}"})
                for index, entry in enumerate(words[offset:offset + UNIT_SIZE], 1):
                    entries.append(dict(entry, unit_id=unit_id, position=index))
    return packs, units, entries


def media_signature(data: bytes, extension: str) -> bool:
    if extension == "webp":
        return data[:4] == b"RIFF" and data[8:12] == b"WEBP"
    return len(data) >= 12 and (data.startswith(b"ID3") or data[0] == 255 and data[1] & 224 == 224)


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def build(source: Path, output: Path) -> dict:
    if output.exists():
        raise FileExistsError(output)
    if source.resolve().is_relative_to(output.resolve()):
        raise ValueError("Release output cannot contain the source archive")
    output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=output.parent, prefix=".vocabulary-") as tmp:
        root = Path(tmp) / "release"
        root.mkdir()
        report = build_payload(source, root)
        root.rename(output)
    return report


def build_payload(source: Path, root: Path) -> dict:
    assets, words, source_files = {}, {}, []
    packs, units, entries = [], [], []
    upstream = {}
    with tarfile.open(source, "r|gz") as archive:
        for member in archive:
            if not member.isfile():
                continue
            prefix, _, name = member.name.partition("/")
            if prefix != f"word-atlas-{REVISION}":
                raise ValueError("Archive root does not match the pinned Word Atlas revision")
            match = MEDIA_PATH.fullmatch(name)
            if not match and name not in {"CONTENT_LICENSE.md", "PUBLIC_MANIFEST.json", "data/word-atlas.json"}:
                continue
            if member.size > 16 * 1024 * 1024:
                raise ValueError(f"Unexpected source member size: {name}")
            with archive.extractfile(member) as stream:
                data = stream.read()
            digest = sha256(data).hexdigest()
            evidence = {"path": name, "bytes": len(data), "sha256": digest}
            if name == "PUBLIC_MANIFEST.json":
                if digest != SOURCE_MANIFEST_SHA:
                    raise ValueError("Pinned source manifest checksum mismatch")
                upstream = {item["path"]: (item["bytes"], item["sha256"]) for item in json.loads(data)["items"]}
                for pending in source_files:
                    if upstream.get(pending["path"]) != (pending["bytes"], pending["sha256"]):
                        raise ValueError(f"Source manifest mismatch: {pending['path']}")
                source_files.append(evidence)
                continue
            if upstream and upstream.get(name) != (len(data), digest):
                raise ValueError(f"Source manifest mismatch: {name}")
            if name == "CONTENT_LICENSE.md":
                (root / "LICENSE").write_bytes(data)
                source_files.append(evidence)
            elif name == "data/word-atlas.json":
                packs, units, entries = word_rows(json.loads(data))
                words = {entry["source_id"]: entry for entry in entries}
                source_files.append(evidence)
            else:
                folder, source_id, extension = match.groups()
                role, expected, mime = MEDIA[folder]
                if source_id not in words or role in words[source_id]["media"]:
                    raise ValueError(f"Unmatched or duplicate source media: {name}")
                if extension != expected or not media_signature(data, extension):
                    raise ValueError(f"Unexpected media format: {name}")
                words[source_id]["media"][role] = digest
                if digest not in assets:
                    path = f"media/{digest[:2]}/{digest}.{extension}"
                    asset = {"id": digest, "path": path, "mime_type": mime, "size_bytes": len(data), "source_paths": []}
                    assets[digest] = asset
                    target = root / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
                assets[digest]["source_paths"].append(name)

    if len(source_files) != 3 or len(entries) != 7298 or len(packs) != len(TOPICS):
        raise ValueError("Pinned archive is incomplete")
    if any(not REQUIRED_MEDIA <= entry["media"].keys() for entry in entries):
        raise ValueError("Pinned archive is missing required word or example media")
    if sum("secondExampleAudio" in entry["media"] for entry in entries) != 1011:
        raise ValueError("Pinned archive is missing second-example recordings")
    for asset in assets.values():
        asset["source_paths"].sort()
    tables = {"packs": packs, "units": units, "entries": entries, "assets": list(assets.values())}
    counts = {key: len(rows) for key, rows in tables.items()}
    for key, rows in tables.items():
        with (root / f"{key}.jsonl").open("w", encoding="utf-8") as stream:
            for row in sorted(rows, key=lambda value: value["id"]):
                stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
    unit_levels = {unit["id"]: unit["level"] for unit in units}
    report = {
        "status": "PASS", "counts": counts, "issues": [],
        "coverage": {
            "senses": sum(len(entry["content"]["senses"]) for entry in entries),
            "examples": sum(len(entry["content"]["examples"]) for entry in entries),
            "meaningVi": 0,
            "media": dict(Counter(role for entry in entries for role in entry["media"])),
            "levels": dict(Counter(unit_levels[entry["unit_id"]] for entry in entries)),
        },
    }
    write_json(root / "validation_report.json", report)
    files = [
        {"path": path.relative_to(root).as_posix(), "bytes": path.stat().st_size, "sha256": sha256_file(path),
         **({"records": counts[path.stem]} if path.suffix == ".jsonl" else {})}
        for path in sorted(root.rglob("*")) if path.is_file()
    ]
    media_hashes = sorted(f"{path}\t{asset['id']}\n" for asset in assets.values() for path in asset["source_paths"])
    write_json(root / "manifest.json", {
        "format": "lyreo.dataset-release", "manifest_schema_version": 1,
        "package": {"domain": "vocabulary", "version": VERSION, "schema_version": VERSION,
                    "generated_at": "2026-11-24T00:00:00Z"},
        "builder": {"name": "build_vocabulary_release.py", "version": VERSION},
        "source": {"url": REPO, "revision": REVISION, "license": LICENSE_URL,
                   "attribution": "Word Atlas contributors — CC BY 4.0",
                   "changes": "English study fields normalized and grouped into CEFR units",
                   "files": sorted(source_files, key=lambda file: file["path"]),
                   "media_sha256_manifest": sha256("".join(media_hashes).encode()).hexdigest()},
        "files": files, "counts": counts, "validation": "validation_report.json",
    })
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--catalog-archive", type=Path)
    args = parser.parse_args()
    for archive in (args.archive, args.catalog_archive):
        if archive and (archive.exists() or archive.resolve().is_relative_to(args.output.resolve())):
            parser.error("Archives must be new files outside the release directory")
    if args.archive and args.catalog_archive and args.archive.resolve() == args.catalog_archive.resolve():
        parser.error("The full and catalog archives must use different files")
    report = build(args.source, args.output)
    if args.archive:
        report["archiveSha256"] = package_release(args.output, args.archive, f"vocabulary-{VERSION}")
    if args.catalog_archive:
        report["catalogSha256"] = package_catalog(args.output, args.catalog_archive)
    print(json.dumps(report, ensure_ascii=False, indent=2))
