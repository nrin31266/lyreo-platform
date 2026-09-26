#!/usr/bin/env python3
"""Verify the clean Grammar/TOEIC release without using production importers."""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import re
import tarfile
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlparse

from build_grammar_toeic_release import GRAPHIC_RE, clean_html, sha256, visible_tokens


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def rows(root: Path, name: str) -> list[dict]:
    with (root / name).open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream]


def index(data: list[dict], name: str) -> dict[str, dict]:
    result = {row["id"]: row for row in data}
    require(len(result) == len(data), f"Duplicate {name} ID")
    return result


def validate(root: Path, raw: Path | None, archive: Path | None) -> dict:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    require(manifest["format"] == "lyreo.dataset-release" and manifest["manifest_schema_version"] == 1, "Unsupported manifest envelope")
    require(manifest["package"]["domain"] == "grammar-toeic", "Wrong domain")
    inventory = manifest["files"]
    expected_paths = {entry["path"] for entry in inventory}
    require(len(expected_paths) == len(inventory), "Duplicate manifest path")
    actual_paths = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()} - {"manifest.json"}
    require(actual_paths == expected_paths, "Manifest inventory mismatch")
    for entry in inventory:
        relative = Path(entry["path"])
        require(not relative.is_absolute() and ".." not in relative.parts, f"Unsafe manifest path: {relative}")
        path = root / entry["path"]
        require(path.is_file() and path.stat().st_size == entry["size_bytes"] and sha256(path) == entry["sha256"], f"File checksum mismatch: {path}")
        if entry["records"] is not None:
            require(len(rows(root, entry["path"])) == entry["records"], f"Record count mismatch: {path}")
    asset_list = rows(root, "shared/assets.jsonl")
    assets = index(asset_list, "asset")
    asset_uses = index(rows(root, "shared/asset_uses.jsonl"), "asset use")
    item_list = rows(root, "shared/items.jsonl")
    items = index(item_list, "item")
    tests = index(rows(root, "toeic/tests.jsonl"), "test")
    group_list = rows(root, "toeic/groups.jsonl")
    groups = index(group_list, "group")
    documents = index(rows(root, "toeic/documents.jsonl"), "document")
    placements = index(rows(root, "toeic/placements.jsonl"), "TOEIC placement")
    topics = index(rows(root, "grammar/topics.jsonl"), "topic")
    subtopics = index(rows(root, "grammar/subtopics.jsonl"), "subtopic")
    banks = index(rows(root, "grammar/bank_sets.jsonl"), "bank set")
    levels = index(rows(root, "grammar/difficulty_levels.jsonl"), "difficulty level")
    memberships = index(rows(root, "grammar/memberships.jsonl"), "Grammar membership")
    quarantined_passages = index(rows(root, "quarantine/toeic_passages.jsonl"), "quarantined passage")
    quarantined_subtopics = index(rows(root, "quarantine/grammar_subtopics.jsonl"), "quarantined subtopic")
    stats = rows(root, "source_only/difficulty_observations.jsonl")
    issue_report = json.loads((root / "validation_report.json").read_text(encoding="utf-8"))
    require(issue_report["counts"] == manifest["counts"], "Count summary mismatch")
    require(issue_report["status"] == manifest["validation"]["status"] == "PASS_WITH_ISSUES", "Issue report status mismatch")
    issue_counts = [{"code": code, "status": status, "count": count} for (code, status), count in sorted(collections.Counter((row["code"], row["status"]) for row in issue_report["issues"]).items())]
    require(issue_counts == issue_report["issue_counts"] == manifest["validation"]["issue_counts"], "Issue summary mismatch")
    issue_ids: dict[str, set[str]] = collections.defaultdict(set)
    for issue in issue_report["issues"]:
        require(issue["source_id"] not in issue_ids[issue["code"]], f"Duplicate issue: {issue['code']} {issue['source_id']}")
        issue_ids[issue["code"]].add(issue["source_id"])
    for name, expected in manifest["counts"].items():
        require(len(rows(root, name + ".jsonl")) == expected, f"Manifest count mismatch: {name}")
    for asset in asset_list:
        path = root / asset["path"]
        require(path.is_file() and sha256(path) == asset["id"] and path.stat().st_size == asset["size_bytes"] and asset["source_paths"], f"Asset corrupted: {asset['id']}")
    source_media = sorted((path, asset["id"]) for asset in asset_list for path in asset["source_paths"])
    require(len(source_media) == manifest["source"]["media_files"], "Source media count mismatch")
    source_manifest_hash = hashlib.sha256("\n".join(f"{path} {digest}" for path, digest in source_media).encode()).hexdigest()
    require(source_manifest_hash == manifest["source"]["media_sha256_manifest"], "Source media manifest mismatch")
    for use in asset_uses.values():
        require(use["asset_id"] in assets, f"Asset use without asset: {use['id']}")
        owner = items if use["owner_type"] == "item" else groups
        require(use["owner_id"] in owner, f"Asset use without owner: {use['id']}")
    for item in item_list:
        keys = [option["key"] for option in item["options"]]
        require(keys == list("ABCD")[:len(keys)] and len(keys) in (3, 4) and item["correct_option"] in keys, f"Invalid answer: {item['id']}")
        require(item["source_domains"] in (["toeic"], ["grammar"], ["toeic", "grammar"]), f"Invalid item provenance: {item['id']}")
    grouped: dict[str, list[dict]] = collections.defaultdict(list)
    by_test: dict[str, list[dict]] = collections.defaultdict(list)
    for place in placements.values():
        require(place["item_id"] in items and place["test_id"] in tests, f"Orphan placement: {place['id']}")
        require(place["section"] == ("listening" if place["part"] <= 4 else "reading"), f"Wrong section: {place['id']}")
        if place["group_id"]:
            group = groups.get(place["group_id"])
            require(group is not None and group["test_id"] == place["test_id"] and group["part"] == place["part"], f"Wrong group: {place['id']}")
            grouped[place["group_id"]].append(place)
        else:
            require(place["part"] in (1, 2, 5), f"Missing group: {place['id']}")
        if place["part"] == 2:
            require(len(items[place["item_id"]]["options"]) == 3, f"Part 2 answer count: {place['id']}")
        if place["part"] == 1:
            require(items[place["item_id"]]["stem_en"] is None, f"Part 1 has stem: {place['id']}")
        by_test[place["test_id"]].append(place)
    profile = {int(key): value for key, value in issue_report["part_counts_per_test"].items()}
    for test in tests.values():
        places = by_test[test["id"]]
        require(sorted(row["question_number"] for row in places) == list(range(1, test["total_questions"] + 1)), f"Cannot reconstruct test: {test['id']}")
        require(collections.Counter(row["part"] for row in places) == profile, f"Part counts wrong: {test['id']}")
    docs_by_group: dict[str, list[dict]] = collections.defaultdict(list)
    for doc in documents.values():
        require(doc["group_id"] in groups and doc["html"], f"Orphan/empty document: {doc['id']}")
        require(clean_html(doc["html"]) == doc["html"] and visible_tokens(doc["html"]), f"Unsafe/empty document projection: {doc['id']}")
        docs_by_group[doc["group_id"]].append(doc)
    for group in group_list:
        members = grouped[group["id"]]
        require(sorted(row["question_number"] for row in members) == group["question_numbers"], f"Group members differ: {group['id']}")
        docs = docs_by_group[group["id"]]
        require(len(docs) == group["document_count"], f"Document count differs: {group['id']}")
        require(sorted(doc["ordinal"] for doc in docs) == list(range(1, len(docs) + 1)), f"Document order invalid: {group['id']}")
        if group["source_html"]:
            require(group["source_html_sha256"] == hashlib.sha256(group["source_html"].encode()).hexdigest(), f"Original HTML hash changed: {group['id']}")
            require(group["render_html"] and visible_tokens(group["source_html"]) == visible_tokens(group["render_html"]), f"HTML text changed: {group['id']}")
            require(clean_html(group["render_html"]) == group["render_html"], f"Unsafe render HTML: {group['id']}")
            status = group["document_parse_status"]
            require(status in ("SINGLE", "STRUCTURAL", "UNSPLIT"), f"Invalid document status: {group['id']}")
            require((status == "UNSPLIT" and not docs) or (status == "SINGLE" and len(docs) == 1) or (status == "STRUCTURAL" and len(docs) in (2, 3)), f"Document status/count mismatch: {group['id']}")
            if docs:
                require(all(doc["parse_status"] == status for doc in docs), f"Document status differs: {group['id']}")
                projection = [token for doc in sorted(docs, key=lambda row: row["ordinal"]) for token in visible_tokens(doc["html"])]
                require(projection == visible_tokens(group["render_html"]), f"Document projection loses content: {group['id']}")
        else:
            require(group["render_html"] is None and group["source_html_sha256"] is None and not docs and group["document_parse_status"] == "NOT_APPLICABLE", f"Unexpected HTML projection: {group['id']}")
        if group["part"] == 6:
            require(group["render_html"] and len(members) == 4, f"Part 6 group invalid: {group['id']}")
            for member in members:
                number = member["question_number"]
                require(len(re.findall(rf"(?<!\d){number}(?!\d)", " ".join(visible_tokens(group["render_html"])))) == 1, f"Part 6 gap missing: {group['id']}")
    for subtopic in subtopics.values():
        require(subtopic["topic_id"] in topics, f"Orphan clean subtopic: {subtopic['id']}")
    for membership in memberships.values():
        require(membership["item_id"] in items and "grammar" in items[membership["item_id"]]["source_domains"], f"Orphan Grammar membership: {membership['id']}")
        require(membership["mode"] in ("topic", "bank", "difficulty"), f"Unknown Grammar mode: {membership['id']}")
        if membership["topic_id"]:
            require(membership["topic_id"] in topics, f"Unknown topic: {membership['id']}")
        if membership["subtopic_id"]:
            require(membership["subtopic_id"] in subtopics, f"Unknown subtopic: {membership['id']}")
        if membership["bank_set_id"]:
            require(membership["bank_set_id"] in banks, f"Unknown bank: {membership['id']}")
        if membership["difficulty_level"]:
            require(str(membership["difficulty_level"]) in levels, f"Unknown level: {membership['id']}")
        if membership["item_id"] in placements and membership["source_test_id"] in tests:
            require(placements[membership["item_id"]]["test_id"] == membership["source_test_id"], f"Shared item test conflict: {membership['id']}")
    shared = sum(item["source_domains"] == ["toeic", "grammar"] for item in item_list)
    require(shared == sum(item["id"] in placements for item in item_list if "grammar" in item["source_domains"]), "Shared item mismatch")
    require(issue_ids["MULTI_DOCUMENT_UNSPLIT"] == {group["id"] for group in group_list if group["document_parse_status"] == "UNSPLIT"}, "Unresolved document issue set differs")
    require(all(group["part"] == 7 and len(grouped[group["id"]]) == 5 and group["render_html"] and visible_tokens(group["render_html"]) for group in group_list if group["id"] in issue_ids["MULTI_DOCUMENT_UNSPLIT"]), "Incomplete unresolved document fallback")
    for group_id in issue_ids["UNRESOLVED_IMAGE_REFERENCE"]:
        group = groups.get(group_id)
        require(group is not None and group["part"] in (3, 4) and group["transcript_en"], f"Missing image lacks transcript fallback: {group_id}")
        members = grouped[group_id]
        require(members and all(items[row["item_id"]]["stem_en"] and not GRAPHIC_RE.search(items[row["item_id"]]["stem_en"]) for row in members), f"Missing image is required by a question: {group_id}")
        uses = [use for use in asset_uses.values() if use["owner_type"] == "group" and use["owner_id"] == group_id]
        require(any(use["role"] == "audio" for use in uses) and not any(use["role"] == "image" for use in uses), f"Missing image lacks playable audio fallback: {group_id}")
    require({issue["code"] for issue in issue_report["issues"] if issue["status"] == "UNRESOLVED"} == {"MULTI_DOCUMENT_UNSPLIT", "UNRESOLVED_IMAGE_REFERENCE"}, "Unknown unresolved issue lacks fallback validation")
    require(issue_ids["EMPTY_UNREFERENCED_PASSAGE"] == set(quarantined_passages), "Quarantined passage issue set differs")
    for passage in quarantined_passages.values():
        source = passage["source_row"]
        require(source["id"] == passage["id"] and passage["id"] not in groups and not any(place["group_id"] == passage["id"] for place in placements.values()), f"Quarantined passage is active: {passage['id']}")
        require(not any(source.get(field) for field in ("passage_text", "transcript", "audio_url", "image_url")), f"Quarantined passage contains content: {passage['id']}")
    require(issue_ids["ORPHAN_GRAMMAR_SUBTOPIC"] == set(quarantined_subtopics), "Quarantined subtopic issue set differs")
    for subtopic in quarantined_subtopics.values():
        require(subtopic["source_row"]["id"] == subtopic["id"] and subtopic["id"] not in subtopics and not any(row["subtopic_id"] == subtopic["id"] for row in memberships.values()), f"Quarantined subtopic is active: {subtopic['id']}")
    require(issue_ids["EXTERNAL_GRAMMAR_TEST"] == {row["id"] for row in memberships.values() if row["source_test_id"] and row["source_test_id"] not in tests}, "External Grammar test source refs differ")
    require(issue_ids["ORPHAN_SOURCE_DIFFICULTY_STAT"] == {row["source_item_id"] for row in stats if not row["matched"]}, "Orphan difficulty stat source refs differ")
    require(all(row["usage_status"] == "SOURCE_ONLY" for row in stats), "Difficulty stat usage status differs")
    for code, field in (("PART6_DUPLICATE_SOURCE_TEXT", "part6_question_text"), ("UNCLASSIFIED_EXPLANATION_EN", "unclassified_explanation_en")):
        require(issue_ids[code] == {item["id"] for item in item_list if field in item.get("source_only_fields", {})}, f"{code} source-only set differs")
        require(all(items[item_id]["source_only_fields"][field] for item_id in issue_ids[code]), f"{code} source value missing")
    require({issue["code"] for issue in issue_report["issues"] if issue["status"] == "QUARANTINED"} == {"EMPTY_UNREFERENCED_PASSAGE", "ORPHAN_GRAMMAR_SUBTOPIC"}, "Unknown quarantined issue lacks retention validation")
    require({issue["code"] for issue in issue_report["issues"] if issue["status"] == "SOURCE_ONLY"} == {"EXTERNAL_GRAMMAR_TEST", "ORPHAN_SOURCE_DIFFICULTY_STAT", "PART6_DUPLICATE_SOURCE_TEXT", "UNCLASSIFIED_EXPLANATION_EN"}, "Unknown source-only issue lacks retention validation")
    if raw:
        for source in manifest["source"]["files"]:
            path = raw / source["path"]
            require(path.is_file() and path.stat().st_size == source["size_bytes"] and sha256(path) == source["sha256"], f"Raw source changed: {path}")
        raw_passages = index(json.loads((raw / "mock_test_data/all_passages.json").read_text(encoding="utf-8")), "raw passage")
        raw_subtopics = index(json.loads((raw / "grammar_data/grammar_subtopics.json").read_text(encoding="utf-8")), "raw subtopic")
        raw_memberships = index(json.loads((raw / "grammar_data/grammar_question_memberships.json").read_text(encoding="utf-8")), "raw Grammar membership")
        raw_stats = json.loads((raw / "mock_test_data/all_difficulty_stats.json").read_text(encoding="utf-8"))
        require(set(raw_passages) == set(groups) | set(quarantined_passages), "Raw passage partition differs")
        require(set(raw_subtopics) == set(subtopics) | set(quarantined_subtopics), "Raw subtopic partition differs")
        require(set(raw_memberships) == set(memberships), "Raw Grammar memberships differ")
        require(len(raw_stats) == len(stats) and {f"{row['item_type']}:{row['item_id']}" for row in raw_stats} == {row["id"] for row in stats}, "Raw difficulty stats differ")
        require(all(raw_passages.get(row_id) == row["source_row"] for row_id, row in quarantined_passages.items()), "Quarantined passage source row differs")
        require(all(raw_subtopics.get(row_id) == row["source_row"] for row_id, row in quarantined_subtopics.items()), "Quarantined subtopic source row differs")
        for group_id in issue_ids["UNRESOLVED_IMAGE_REFERENCE"]:
            source = raw_passages[group_id]
            test = tests[groups[group_id]["test_id"]]
            require(source.get("image_url"), f"Unresolved image has no source reference: {group_id}")
            basename = Path(unquote(urlparse(source["image_url"]).path)).name
            expected_image = raw / "mock_test_data/downloads" / str(test["year"]) / test["name"] / "images" / basename
            require(not expected_image.exists(), f"Unresolved image has a local source file: {group_id}")
        for path_text, digest in source_media:
            path = raw / path_text
            require(path.is_file() and sha256(path) == digest, f"Raw media changed: {path}")
    archive_hash = None
    if archive:
        require(archive.is_file(), f"Archive absent: {archive}")
        archive_hash = sha256(archive)
        with tarfile.open(archive, "r:gz") as tar:
            members_in_archive = tar.getmembers()
            names = {entry.name for entry in members_in_archive}
            archive_roots = {PurePosixPath(entry.name).parts[0] for entry in members_in_archive if PurePosixPath(entry.name).parts}
            require(len(archive_roots) == 1, "Archive must have exactly one release root")
            archive_root = archive_roots.pop()
            require(archive_root in (f"grammar-toeic-{manifest['package']['version']}", manifest["package"]["version"], root.name), "Unexpected archive release root")
            expected = {f"{archive_root}/{name}" for name in actual_paths | {"manifest.json"}}
            require(names == expected and len(names) == len(members_in_archive), "Archive inventory mismatch")
            require(all(entry.isfile() and entry.uid == 0 and entry.gid == 0 and entry.mtime == 0 for entry in members_in_archive), "Archive metadata not reproducible")
    ordered_tests = sorted(tests.values(), key=lambda row: (row["year"], row["test_number"]))
    representative = [ordered_tests[0], ordered_tests[len(ordered_tests) // 2], ordered_tests[-1]]
    sample_summary = [{"year": test["year"], "name": test["name"], "questions": len(by_test[test["id"]]), "groups": sum(group["test_id"] == test["id"] for group in group_list), "parts": dict(sorted(collections.Counter(row["part"] for row in by_test[test["id"]]).items()))} for test in representative]
    return {"status": "PASS", "package": manifest["package"], "counts": manifest["counts"], "shared_items": shared, "part_counts_per_test": profile, "representative_tests": sample_summary, "archive_sha256": archive_hash, "issues": issue_report["issue_counts"], "unresolved_fallbacks_verified": sum(issue["status"] == "UNRESOLVED" for issue in issue_report["issues"])}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--raw", type=Path)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = validate(args.release.resolve(), args.raw.resolve() if args.raw else None, args.archive.resolve() if args.archive else None)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
