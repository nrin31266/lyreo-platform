#!/usr/bin/env python3
"""Build a self-contained, reproducible Grammar/TOEIC content release.

This is offline dataset preparation, not the production database importer. The
generic part is the release envelope (manifest/files/checksums); all source
semantics intentionally stay in this domain-specific builder.
"""
from __future__ import annotations

import argparse
import collections
import datetime as dt
import gzip
import hashlib
import json
import os
import re
import shutil
import tarfile
from pathlib import Path
from urllib.parse import unquote, urlparse

import bleach
from bleach.css_sanitizer import CSSSanitizer
from bs4 import BeautifulSoup, Tag


BUILDER_VERSION = "1.0.1"
SCHEMA_VERSION = "1.0.0"
TAGS = ["div", "p", "span", "b", "strong", "i", "em", "u", "br", "hr", "h1", "h2", "h3", "h4", "small", "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "colgroup", "col", "sup", "sub", "blockquote"]
ATTRS = {"*": ["class", "style"], "table": ["border", "cellpadding", "cellspacing"], "td": ["colspan", "rowspan"], "th": ["colspan", "rowspan"], "col": ["span"], "div": ["class", "style"]}
CSS = CSSSanitizer(allowed_css_properties=["color", "background-color", "border", "border-top", "border-bottom", "border-left", "border-right", "border-collapse", "border-radius", "padding", "padding-left", "padding-right", "padding-top", "padding-bottom", "margin", "margin-left", "margin-right", "margin-top", "margin-bottom", "text-align", "font-size", "font-weight", "font-style", "text-decoration", "width", "max-width", "min-width", "table-layout", "vertical-align", "display", "gap", "column-count", "column-gap", "text-indent", "list-style-type"])
GRAPHIC_RE = re.compile(r"(?:look at|refer to|according to) the (?:graphic|map|diagram|chart|table|picture)", re.I)
VI_RE = re.compile(r"[àáạảãăằắặẳẵâầấậẩẫđèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹ]", re.I)


def fail(message: str) -> None:
    raise ValueError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(canonical_json(value) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as output:
        for row in sorted(rows, key=lambda item: item["id"]):
            output.write(canonical_json(row) + "\n")


def load_json(path: Path) -> list[dict]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, list):
        fail(f"Expected JSON array: {path}")
    return value


def unique(rows: list[dict], label: str) -> dict[str, dict]:
    index = {row["id"]: row for row in rows}
    if len(index) != len(rows):
        fail(f"Duplicate {label} IDs")
    return index


def clean(value: object) -> str | None:
    if value is None:
        return None
    normalized = str(value).replace("\r\n", "\n").replace("\r", "\n").strip()
    return normalized or None


def clean_html(source: str) -> str:
    # Links and img tags are removed: their visible text remains and media is
    # represented by separately validated assets. Style is allowlisted.
    return bleach.clean(source, tags=TAGS, attributes=ATTRS, css_sanitizer=CSS, strip=True).strip()


def visible_tokens(source: str) -> list[str]:
    text = " ".join(BeautifulSoup(source, "html.parser").stripped_strings)
    return re.findall(r"\w+|[^\w\s]", text, flags=re.UNICODE)


def source_translation(row: dict) -> str | None:
    value = clean(row.get("explanation_en"))
    return value if value and VI_RE.search(value) else None


def item_from_toeic(row: dict) -> dict:
    part = row["part"]
    annotations = {
        "rationale_vi": clean(row.get("explanation_vi")),
        "question_options_translation_vi": source_translation(row),
        "option_translation_note_vi": clean(row.get("dich_nghia_dap_an")),
        "vocabulary_note_vi": clean(row.get("tu_vung")) if part in (1, 2, 5) else None,
        "content_translation_vi": clean(row.get("dich_nghia")) if part in (1, 2, 5) else None,
    }
    return {
        "id": row["id"],
        "kind": "CLOZE" if part == 6 else "MULTIPLE_CHOICE",
        "stem_en": clean(row.get("question_text")) if part != 6 else None,
        "transcript_en": clean(row.get("passage_text")) if part in (1, 2) else None,
        "options": [{"key": key.upper(), "text": clean(row.get("option_" + key))} for key in "abcd" if clean(row.get("option_" + key))],
        "correct_option": row["correct_answer"],
        "difficulty_level": row["difficulty_level"],
        "explanation_preference": "AI_PREFERRED" if row.get("prefer_ai_explanation") else "SOURCE",
        "annotations": {key: value for key, value in annotations.items() if value},
        "source_domains": ["toeic"],
        "provenance": [{"source_file": "mock_test_data/all_questions.json", "source_id": row["id"]}],
    }


def item_from_grammar(row: dict) -> dict:
    annotations = {
        "rationale_vi": clean(row.get("explanation_vi")),
        "question_options_translation_vi": source_translation(row),
        "option_translation_note_vi": clean(row.get("answer_translation_vi")),
        "vocabulary_note_vi": clean(row.get("vocabulary")),
        "content_translation_vi": clean(row.get("translation_vi")),
    }
    return {
        "id": row["id"],
        "kind": "MULTIPLE_CHOICE",
        "stem_en": clean(row.get("question_text")),
        "transcript_en": None,
        "options": [{"key": key.upper(), "text": clean(row.get("option_" + key))} for key in "abcd" if clean(row.get("option_" + key))],
        "correct_option": row["correct_answer"],
        "difficulty_level": row["difficulty_level"],
        "explanation_preference": "AI_PREFERRED" if row.get("prefer_ai_explanation") else "SOURCE",
        "annotations": {key: value for key, value in annotations.items() if value},
        "source_domains": ["grammar"],
        "provenance": [{"source_file": "grammar_data/grammar_questions.json", "source_id": row["id"], "source_table": row.get("source_table")}],
    }


def validate_item(item: dict) -> None:
    keys = [option["key"] for option in item["options"]]
    if not 3 <= len(keys) <= 4 or keys != list("ABCD")[:len(keys)] or item["correct_option"] not in keys:
        fail(f"Invalid options/answer: {item['id']}")
    if item["difficulty_level"] is not None and item["difficulty_level"] not in range(1, 6):
        fail(f"Invalid difficulty: {item['id']}")


def common(rows: list[dict], field: str) -> str | None:
    values = [clean(row.get(field)) for row in rows]
    return values[0] if values and values[0] and len(set(values)) == 1 else None


def detect_document_type(fragment: str) -> str:
    soup = BeautifulSoup(fragment, "html.parser")
    known = ("email", "article", "notice", "letter", "memo", "webpage", "textmsg", "chat", "brochure", "report", "form", "advertisement", "jobad")
    for tag in soup.find_all(True):
        for class_name in tag.get("class", []):
            if class_name.lower() in known:
                return class_name.lower()
    return "unknown"


def document_fragments(source: str, part: int, question_count: int) -> tuple[list[str], str]:
    soup = BeautifulSoup(source, "html.parser")
    roots = [tag for tag in soup.contents if isinstance(tag, Tag) and tag.name != "br"]
    # A five-question reading group may have multiple documents. Only split
    # when the DOM exposes clear sibling wrappers; preserve the full group HTML
    # for every case, including uncertain ones.
    if part == 7 and question_count == 5:
        candidates = roots
        if len(roots) == 1 and roots[0].name == "div":
            candidates = [tag for tag in roots[0].children if isinstance(tag, Tag) and tag.name == "div"]
        wrappers = [tag for tag in candidates if tag.name == "div" and ("border" in tag.get("style", "") or any(tag.find(class_=name) for name in ("email", "article", "webpage", "report", "notice", "letter", "jobad")))]
        if len(wrappers) in (2, 3) and len(wrappers) == len(candidates):
            fragments = [clean_html(str(tag)) for tag in wrappers]
            if visible_tokens(clean_html(source)) == [token for fragment in fragments for token in visible_tokens(fragment)]:
                return fragments, "STRUCTURAL"
        return [], "UNSPLIT"
    return [clean_html(source)], "SINGLE"


def magic_type(path: Path) -> tuple[str, str]:
    head = path.open("rb").read(16)
    if head.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png", ".png"
    if head.startswith(b"RIFF") and head[8:12] == b"WEBP":
        return "image/webp", ".webp"
    if head.startswith(b"\xff\xd8\xff"):
        return "image/jpeg", ".jpg"
    if head.startswith(b"ID3") or head[:2] in (b"\xff\xfb", b"\xff\xf3", b"\xff\xf2"):
        return "audio/mpeg", ".mp3"
    fail(f"Unknown media type: {path}")


def issue(issues: list[dict], code: str, status: str, source_id: str, detail: str) -> None:
    issues.append({"code": code, "status": status, "source_id": source_id, "detail": detail})


def media_catalog(raw: Path, release: Path, tests: dict[str, dict], questions: list[dict], passages: list[dict], issues: list[dict]) -> tuple[list[dict], list[dict], list[dict]]:
    downloads = raw / "mock_test_data" / "downloads"
    files = sorted(path for path in downloads.rglob("*") if path.is_file() and path.suffix.lower() in (".mp3", ".png", ".webp"))
    assets_by_hash: dict[str, dict] = {}
    media_sources: list[dict] = []
    media_by_path: dict[Path, str] = {}
    for path in files:
        mime, ext = magic_type(path)
        digest = sha256(path)
        rel = f"media/{'audio' if mime.startswith('audio/') else 'images'}/{digest}{ext}"
        target = release / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            shutil.copyfile(path, target)
        elif sha256(target) != digest:
            fail(f"Media hash collision: {digest}")
        media_by_path[path] = digest
        source_path = str(path.relative_to(raw))
        media_sources.append({"path": source_path, "sha256": digest})
        if digest not in assets_by_hash:
            assets_by_hash[digest] = {"id": digest, "path": rel, "mime_type": mime, "size_bytes": path.stat().st_size, "source_paths": []}
        assets_by_hash[digest]["source_paths"].append(source_path)
        if mime == "image/jpeg" and path.suffix.lower() != ".jpg" or mime == "image/png" and path.suffix.lower() != ".png":
            issue(issues, "MEDIA_EXTENSION_MISMATCH", "NORMALIZED", str(path.relative_to(raw)), f"Detected {mime}")
    uses: list[dict] = []
    referenced: set[Path] = set()
    groups_questions: dict[str, list[dict]] = collections.defaultdict(list)
    for row in questions:
        if row.get("passage_id"):
            groups_questions[row["passage_id"]].append(row)
    for owner_type, rows in (("item", questions), ("group", passages)):
        for row in rows:
            test = tests[row["test_id"]]
            for kind, field, folder in (("audio", "audio_url", "audio"), ("image", "image_url", "images")):
                source_url = row.get(field)
                if not source_url:
                    continue
                basename = Path(unquote(urlparse(source_url).path)).name
                path = downloads / str(test["_year"]) / test["name"] / folder / basename
                if path not in media_by_path:
                    questions_in_group = groups_questions.get(row["id"], [])
                    stale = owner_type == "group" and kind == "image" and row["part"] in (3, 4) and not any(GRAPHIC_RE.search(q.get("question_text") or "") for q in questions_in_group)
                    if stale:
                        issue(issues, "UNRESOLVED_IMAGE_REFERENCE", "UNRESOLVED", row["id"], f"No local file and no graphic question: {path.relative_to(raw)}")
                        continue
                    fail(f"Missing required media: {path}")
                referenced.add(path)
                uses.append({"id": f"{owner_type}:{row['id']}:{kind}", "owner_type": owner_type, "owner_id": row["id"], "role": kind, "asset_id": media_by_path[path], "source_reference": source_url, "media_version": test.get("media_version")})
    if referenced != set(files):
        fail(f"Unreferenced media: {len(set(files) - referenced)}")
    return list(assets_by_hash.values()), uses, media_sources


def package_release(release: Path, archive: Path, root_name: str | None = None) -> str:
    archive.parent.mkdir(parents=True, exist_ok=True)
    tar_root = root_name or (f"grammar-toeic-{release.name}" if not release.name.startswith("grammar-toeic") else release.name)
    with archive.open("wb") as output, gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0, compresslevel=6) as compressed, tarfile.open(fileobj=compressed, mode="w") as tar:
        for path in sorted(release.rglob("*")):
            if not path.is_file():
                continue
            info = tar.gettarinfo(str(path), arcname=f"{tar_root}/{path.relative_to(release).as_posix()}")
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 0
            info.mode = 0o644
            with path.open("rb") as stream:
                tar.addfile(info, stream)
    return sha256(archive)


def build(raw: Path, release: Path, archive: Path, generated_at: str) -> dict:
    if release.exists() or archive.exists():
        fail("Output already exists; choose a new release path/version")
    if release == raw or raw in release.parents or archive == raw or raw in archive.parents:
        fail("Release and archive must be outside the raw dataset")
    if archive in release.parents or release in archive.parents:
        fail("Archive and release directory must be separate")
    grammar = raw / "grammar_data"
    mock = raw / "mock_test_data"
    source_files = [grammar / name for name in ("manifest.json", "grammar_topics.json", "grammar_subtopics.json", "grammar_bank_sets.json", "grammar_difficulty_levels.json", "grammar_questions.json", "grammar_question_memberships.json")] + [mock / name for name in ("all_mock_tests.json", "all_passages.json", "all_questions.json", "all_difficulty_stats.json")]
    source_inventory = [{"path": str(path.relative_to(raw)), "sha256": sha256(path), "size_bytes": path.stat().st_size} for path in source_files]
    topics = load_json(grammar / "grammar_topics.json")
    subtopics = load_json(grammar / "grammar_subtopics.json")
    banks = load_json(grammar / "grammar_bank_sets.json")
    levels = load_json(grammar / "grammar_difficulty_levels.json")
    grammar_questions = load_json(grammar / "grammar_questions.json")
    memberships = load_json(grammar / "grammar_question_memberships.json")
    tests_raw = load_json(mock / "all_mock_tests.json")
    passages_raw = load_json(mock / "all_passages.json")
    questions_raw = load_json(mock / "all_questions.json")
    stats_raw = load_json(mock / "all_difficulty_stats.json")
    tests_by_id = unique(tests_raw, "test")
    passages_by_id = unique(passages_raw, "passage")
    questions_by_id = unique(questions_raw, "TOEIC question")
    grammar_by_id = unique(grammar_questions, "Grammar question")
    membership_by_id = unique(memberships, "Grammar membership")
    topics_by_id = unique(topics, "Grammar topic")
    subtopics_by_id = unique(subtopics, "Grammar subtopic")
    banks_by_id = {row["set_id"]: row for row in banks}
    if len(banks_by_id) != len(banks):
        fail("Duplicate Grammar bank set")
    issues: list[dict] = []
    release.mkdir(parents=True)
    try:
        assets, asset_uses, media_sources = media_catalog(raw, release, tests_by_id, questions_raw, passages_raw, issues)
        active_group_ids = {row["passage_id"] for row in questions_raw if row.get("passage_id")}
        empty_passages = [row for row in passages_raw if row["id"] not in active_group_ids]
        for row in empty_passages:
            if any(row.get(field) for field in ("passage_text", "transcript", "audio_url", "image_url")):
                fail(f"Unreferenced passage has content: {row['id']}")
            issue(issues, "EMPTY_UNREFERENCED_PASSAGE", "QUARANTINED", row["id"], "No question or renderable content")
        question_groups: dict[str, list[dict]] = collections.defaultdict(list)
        for row in questions_raw:
            if row.get("passage_id"):
                if row["passage_id"] not in passages_by_id or passages_by_id[row["passage_id"]]["test_id"] != row["test_id"]:
                    fail(f"Invalid question group: {row['id']}")
                question_groups[row["passage_id"]].append(row)
        tests: list[dict] = []
        for row in tests_raw:
            match = re.fullmatch(r"Test (\d+)", row["name"])
            if str(row["_year"]) != clean(row["_set_name"]) or not match:
                fail(f"Ambiguous test year/name: {row['id']}")
            if row["_set_name"] != clean(row["_set_name"]):
                issue(issues, "TEST_SET_NAME_WHITESPACE", "NORMALIZED", row["id"], "Trimmed set label")
            if row.get("year") is not None and row["year"] != row["_year"]:
                issue(issues, "TEST_YEAR_CONFLICT", "DERIVED", row["id"], f"year={row['year']}; set_year={row['_year']}")
            tests.append({"id": row["id"], "year": row["_year"], "set_id": row["set_id"], "name": row["name"], "test_number": int(match.group(1)), "order_index": row.get("order_index"), "total_questions": row["total_questions"], "source_label": row.get("source"), "listening_duration_seconds": row.get("listening_duration_seconds"), "reading_duration_seconds": row.get("reading_duration_seconds"), "difficulty_level": row.get("difficulty_level"), "is_free": row.get("is_free"), "is_hidden": row.get("is_hidden"), "media_version": row.get("media_version")})
        items: dict[str, dict] = {}
        placements: list[dict] = []
        by_test: dict[str, list[dict]] = collections.defaultdict(list)
        for row in questions_raw:
            if row["test_id"] not in tests_by_id:
                fail(f"Unknown test: {row['id']}")
            by_test[row["test_id"]].append(row)
            item = item_from_toeic(row)
            validate_item(item)
            items[item["id"]] = item
            part = row["part"]
            if part not in range(1, 8):
                fail(f"Unknown Part: {row['id']}")
            placements.append({"id": row["id"], "item_id": row["id"], "test_id": row["test_id"], "part": part, "section": "listening" if part <= 4 else "reading", "question_number": row["question_number"], "group_id": row.get("passage_id"), "order_index": row.get("order_index"), "gap_number": row["question_number"] if part == 6 else None})
        part_profile = None
        for test in tests:
            rows = by_test[test["id"]]
            numbers = sorted(row["question_number"] for row in rows)
            if numbers != list(range(1, tests_by_id[test["id"]]["total_questions"] + 1)):
                fail(f"Incomplete test numbering: {test['id']}")
            parts = collections.Counter(row["part"] for row in rows)
            if set(parts) != set(range(1, 8)) or [row["part"] for row in sorted(rows, key=lambda q: q["question_number"])] != sorted(row["part"] for row in rows):
                fail(f"Missing or unordered Part: {test['id']}")
            if part_profile is None:
                part_profile = dict(sorted(parts.items()))
            elif parts != part_profile:
                fail(f"Inconsistent Part counts within release: {test['id']}")
        for row in grammar_questions:
            item = item_from_grammar(row)
            validate_item(item)
            previous = items.get(item["id"])
            if previous:
                comparable = ("kind", "stem_en", "options", "correct_option", "difficulty_level", "explanation_preference", "annotations")
                if any(previous[key] != item[key] for key in comparable):
                    fail(f"Grammar/TOEIC shared item conflict: {item['id']}")
                previous["source_domains"].append("grammar")
                previous["provenance"].extend(item["provenance"])
                toeic_raw = questions_by_id[item["id"]]
                fields = ("option_a", "option_b", "option_c", "option_d", "explanation_en", "explanation_vi")
                raw_differences = [field for field in fields if toeic_raw.get(field) != row.get(field)]
                if raw_differences:
                    issue(issues, "SHARED_SOURCE_WHITESPACE", "NORMALIZED", item["id"], ",".join(raw_differences))
            else:
                items[item["id"]] = item
        grammar_memberships: list[dict] = []
        for row in memberships:
            if row["question_id"] not in grammar_by_id:
                fail(f"Grammar membership without item: {row['id']}")
            mode = row["source_mode"]
            if mode not in ("bank", "difficulty", "topic"):
                fail(f"Unknown membership mode: {row['id']}")
            if row.get("bank_set_id") and row["bank_set_id"] not in banks_by_id:
                fail(f"Unknown bank set: {row['id']}")
            if row.get("topic_id") and row["topic_id"] not in topics_by_id:
                fail(f"Unknown topic: {row['id']}")
            if row.get("subtopic_id") and row["subtopic_id"] not in subtopics_by_id:
                fail(f"Unknown subtopic: {row['id']}")
            if row.get("test_id") and row["test_id"] not in tests_by_id:
                issue(issues, "EXTERNAL_GRAMMAR_TEST", "SOURCE_ONLY", row["id"], f"test_id={row['test_id']}")
            grammar_memberships.append({"id": row["id"], "item_id": row["question_id"], "mode": mode, "topic_id": row.get("topic_id"), "subtopic_id": row.get("subtopic_id"), "bank_set_id": row.get("bank_set_id"), "difficulty_level": row.get("difficulty_level"), "source_test_id": row.get("test_id"), "source_question_number": row.get("question_number"), "order_index": row.get("order_index"), "source_url": row.get("source_url")})
        active_subtopics = []
        quarantined_subtopics = []
        for row in subtopics:
            if row["topic_id"] in topics_by_id:
                active_subtopics.append(row)
            else:
                quarantined_subtopics.append(row)
                issue(issues, "ORPHAN_GRAMMAR_SUBTOPIC", "QUARANTINED", row["id"], f"topic_id={row['topic_id']}")
        if any(row.get("subtopic_id") in {entry["id"] for entry in quarantined_subtopics} for row in memberships):
            fail("Quarantined Grammar subtopic has live memberships")
        groups: list[dict] = []
        documents: list[dict] = []
        resolved_group_titles: dict[str, str | None] = {}
        for row in passages_raw:
            if row["id"] not in active_group_ids:
                continue
            members = sorted(question_groups[row["id"]], key=lambda q: q["question_number"])
            part = row["part"]
            title = clean(row.get("title"))
            if part == 6 and not title:
                title = common(members, "question_text")
                if title:
                    issue(issues, "GROUP_TITLE_DERIVED", "DERIVED", row["id"], "Unanimous Part 6 question instruction")
            resolved_group_titles[row["id"]] = title
            if any(q["part"] != part for q in members):
                fail(f"Mixed Part group: {row['id']}")
            html_source = row.get("passage_text") if part in (6, 7) else None
            html = clean_html(html_source) if html_source else None
            fragments, parse_status = document_fragments(html_source, part, len(members)) if html_source else ([], "NOT_APPLICABLE")
            if html_source and not html:
                fail(f"HTML vanished after sanitizing: {row['id']}")
            if html_source and visible_tokens(html_source) != visible_tokens(html):
                fail(f"HTML text changed after sanitizing: {row['id']}")
            if parse_status == "UNSPLIT":
                issue(issues, "MULTI_DOCUMENT_UNSPLIT", "UNRESOLVED", row["id"], "Complete safe group HTML retained; complete document projection unavailable")
            for ordinal, fragment in enumerate(fragments, 1):
                documents.append({"id": f"{row['id']}:{ordinal}", "group_id": row["id"], "ordinal": ordinal, "document_type": detect_document_type(fragment), "html": fragment, "parse_status": parse_status})
            translation = common(members, "dich_nghia")
            vocabulary = common(members, "tu_vung")
            for member in members:
                if not translation and clean(member.get("dich_nghia")):
                    items[member["id"]]["annotations"]["content_translation_vi"] = clean(member["dich_nghia"])
                if not vocabulary and clean(member.get("tu_vung")):
                    items[member["id"]]["annotations"]["vocabulary_note_vi"] = clean(member["tu_vung"])
            if part == 6:
                visible = BeautifulSoup(html_source, "html.parser").get_text(" ", strip=True)
                for member in members:
                    number = member["question_number"]
                    if len(re.findall(rf"(?<!\d){number}(?!\d)", visible)) != 1:
                        fail(f"Part 6 marker missing/ambiguous: {row['id']} Q{number}")
            groups.append({"id": row["id"], "test_id": row["test_id"], "part": part, "kind": "AUDIO" if part in (3, 4) else "TEXT", "title": title, "order_index": row.get("order_index"), "difficulty_level": row.get("difficulty_level"), "question_numbers": [q["question_number"] for q in members], "transcript_en": clean(row.get("transcript")), "content_translation_vi": translation, "vocabulary_note_vi": vocabulary, "render_html": html, "source_html_sha256": hashlib.sha256(html_source.encode("utf-8")).hexdigest() if html_source else None, "document_parse_status": parse_status, "document_count": len(fragments)})
            groups[-1]["source_html"] = html_source
        for row in questions_raw:
            untranslated = clean(row.get("explanation_en"))
            if untranslated and not VI_RE.search(untranslated):
                items[row["id"]]["source_only_fields"] = {"unclassified_explanation_en": untranslated}
                issue(issues, "UNCLASSIFIED_EXPLANATION_EN", "SOURCE_ONLY", row["id"], "No Vietnamese signal; content retained for review")
            if row["part"] == 6 and clean(row.get("question_text")) != resolved_group_titles[row["passage_id"]]:
                items[row["id"]].setdefault("source_only_fields", {})["part6_question_text"] = row["question_text"]
                issue(issues, "PART6_DUPLICATE_SOURCE_TEXT", "SOURCE_ONLY", row["id"], "Question text differs from group title; source value retained")
        stats: list[dict] = []
        for row in stats_raw:
            matched = row["item_id"] in (questions_by_id if row["item_type"] == "question" else passages_by_id)
            if not matched:
                issue(issues, "ORPHAN_SOURCE_DIFFICULTY_STAT", "SOURCE_ONLY", row["item_id"], f"item_type={row['item_type']}")
            stats.append({"id": f"{row['item_type']}:{row['item_id']}", "source_item_id": row["item_id"], "item_type": row["item_type"], "matched": matched, "difficulty_level": row["difficulty_level"], "total_attempts": row["total_attempts"], "wrong_count": row["wrong_count"], "error_rate": row["error_rate"], "usage_status": "SOURCE_ONLY"})
        clean_topics = [{"id": row["id"], "title_en": clean(row.get("title_en")), "title_vi": clean(row.get("title_vi")), "description_en": clean(row.get("description_en")), "description_vi": clean(row.get("description_vi")), "order_index": row.get("order_index"), "access_level": row.get("access_level"), "is_hidden": row.get("is_hidden"), "icon": row.get("icon")} for row in topics]
        clean_subtopics = [{"id": row["id"], "topic_id": row["topic_id"], "title_en": clean(row.get("title_en")), "title_vi": clean(row.get("title_vi")), "description_en": clean(row.get("description_en")), "description_vi": clean(row.get("description_vi")), "level": row.get("level"), "order_index": row.get("order_index"), "access_level": row.get("access_level"), "is_hidden": row.get("is_hidden")} for row in active_subtopics]
        clean_banks = [{"id": row["set_id"], "name": row["set_name"], "year": row.get("year"), "order_index": row.get("order_index"), "access_level": row.get("access_level"), "source_counts": {key: row.get(key) for key in ("grammar_count", "test_count", "total_count")}} for row in banks]
        clean_levels = [{"id": str(row["level"]), "level": row["level"], "name_vi": row.get("name_vi"), "description_vi": row.get("description_vi")} for row in levels]
        collections_out = {
            "shared/items.jsonl": list(items.values()),
            "shared/assets.jsonl": assets,
            "shared/asset_uses.jsonl": asset_uses,
            "toeic/tests.jsonl": tests,
            "toeic/groups.jsonl": groups,
            "toeic/documents.jsonl": documents,
            "toeic/placements.jsonl": placements,
            "grammar/topics.jsonl": clean_topics,
            "grammar/subtopics.jsonl": clean_subtopics,
            "grammar/bank_sets.jsonl": clean_banks,
            "grammar/difficulty_levels.jsonl": clean_levels,
            "grammar/memberships.jsonl": grammar_memberships,
            "source_only/difficulty_observations.jsonl": stats,
            "quarantine/grammar_subtopics.jsonl": [{"id": row["id"], "source_row": row} for row in quarantined_subtopics],
            "quarantine/toeic_passages.jsonl": [{"id": row["id"], "source_row": row} for row in empty_passages],
        }
        for name, rows in collections_out.items():
            write_jsonl(release / name, rows)
        report = {"status": "PASS_WITH_ISSUES", "counts": {name.removesuffix(".jsonl"): len(rows) for name, rows in collections_out.items()}, "part_counts_per_test": part_profile, "issues": sorted(issues, key=lambda row: (row["code"], row["source_id"])), "validation": {"identity_unique": True, "relations_integral": True, "test_reconstruction": True, "answers_valid": True, "html_preserved": True, "media_resolved_or_reported": True, "shared_item_equal": True}}
        # JSON object keys cannot be tuples, so expose issue summaries as rows.
        report["issue_counts"] = [{"code": code, "status": status, "count": count} for (code, status), count in sorted(collections.Counter((row["code"], row["status"]) for row in issues).items())]
        write_json(release / "validation_report.json", report)
        inventory = []
        for path in sorted(release.rglob("*")):
            if path.is_file():
                rel = path.relative_to(release).as_posix()
                inventory.append({"path": rel, "size_bytes": path.stat().st_size, "sha256": sha256(path), "records": len(collections_out[rel]) if rel in collections_out else None})
        manifest = {"format": "lyreo.dataset-release", "manifest_schema_version": 1, "package": {"domain": "grammar-toeic", "version": "1.0.1", "schema_version": SCHEMA_VERSION, "generated_at": generated_at}, "builder": {"name": "build_grammar_toeic_release.py", "version": BUILDER_VERSION}, "source": {"root_kind": "dautoeic-archive", "files": source_inventory, "media_files": len(media_sources), "media_sha256_manifest": hashlib.sha256("\n".join(f"{source['path']} {source['sha256']}" for source in sorted(media_sources, key=lambda value: value["path"])).encode()).hexdigest()}, "files": inventory, "counts": report["counts"], "validation": {"status": report["status"], "report_path": "validation_report.json", "issue_counts": report["issue_counts"]}}
        write_json(release / "manifest.json", manifest)
        archive_sha = package_release(release, archive, root_name=f"{manifest['package']['domain']}-{manifest['package']['version']}")
        return {"release": str(release), "archive": str(archive), "archive_sha256": archive_sha, "manifest_sha256": sha256(release / "manifest.json"), "counts": manifest["counts"], "issues": report["issue_counts"]}
    except Exception:
        shutil.rmtree(release, ignore_errors=True)
        archive.unlink(missing_ok=True)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--generated-at", default=dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat())
    args = parser.parse_args()
    result = build(args.raw.resolve(), args.output.resolve(), args.archive.resolve(), args.generated_at)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
