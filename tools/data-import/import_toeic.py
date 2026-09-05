#!/usr/bin/env python3
"""Import the scraped dautoeic mock-test dataset into Lyreo.

The scraper output is an external dataset, not a Flyway seed. This importer:
- validates referential relationships before mutating the DB;
- generates deterministic Lyreo UUIDs from external IDs;
- keeps the original row in metadata_json for provenance/debugging;
- stores media as object keys, never signed URLs/local absolute paths;
- uploads each local media file at most once when --upload-media is enabled.

Run without --apply first. A dry-run intentionally needs no database/R2 credentials.
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urlparse

from common import begin_import, checksum, db, finish_import, load_json, s3_client, stable_uuid


TEST_FILES = ("all_mock_tests.json",)
PASSAGE_FILES = ("all_passages.json",)
QUESTION_FILES = ("all_questions_updated.json", "all_questions.json")


def choose(directory: Path, *names: str) -> Path:
    for name in names:
        candidate = directory / name
        if candidate.exists():
            return candidate
    raise SystemExit(f"Missing one of {names} under {directory}")


def first(row: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        value = row.get(key)
        if value is not None and value != "":
            return value
    return None


def external_id(row: dict[str, Any], *keys: str) -> str:
    value = first(row, *keys)
    if value is None:
        raise ValueError(f"Row is missing an external id ({', '.join(keys)}): {row}")
    return str(value)


def is_remote_url(value: str) -> bool:
    parsed = urlparse(value)
    return parsed.scheme in {"http", "https"}


@dataclass
class MediaCatalog:
    dataset_root: Path
    files: list[Path]
    by_name: dict[str, list[Path]] = field(default_factory=dict)
    uploaded_keys: set[str] = field(default_factory=set)
    unresolved: set[str] = field(default_factory=set)

    @classmethod
    def build(cls, dataset_root: Path, downloads: Path) -> "MediaCatalog":
        files = sorted(path for path in downloads.rglob("*") if path.is_file()) if downloads.exists() else []
        catalog = cls(dataset_root=dataset_root, files=files)
        for path in files:
            catalog.by_name.setdefault(path.name, []).append(path)
        return catalog

    def resolve(self, raw_value: Any) -> Path | None:
        if raw_value is None:
            return None
        text = str(raw_value).strip()
        if not text or is_remote_url(text):
            return None

        raw_path = Path(text).expanduser()
        candidates: list[Path] = []
        if raw_path.is_absolute():
            candidates.append(raw_path)
        else:
            candidates.extend(
                [
                    self.dataset_root / raw_path,
                    self.dataset_root / "mock_test_data" / raw_path,
                    self.dataset_root / "mock_test_data" / "downloads" / raw_path,
                ]
            )

        for candidate in candidates:
            if candidate.exists() and candidate.is_file():
                return candidate.resolve()

        # Scraper exports sometimes retain only a filename. Use it only when unambiguous;
        # silently choosing between duplicate `audio.mp3` files would corrupt questions.
        same_name = self.by_name.get(raw_path.name, [])
        if len(same_name) == 1:
            return same_name[0].resolve()

        self.unresolved.add(text)
        return None

    def object_key(self, raw_value: Any, s3, bucket: str | None) -> str | None:
        path = self.resolve(raw_value)
        if path is None:
            return None
        try:
            relative = path.relative_to(self.dataset_root.resolve())
        except ValueError as exc:
            raise ValueError(f"Media must stay under dataset root: {path}") from exc

        key = "toeic/" + relative.as_posix()
        if s3 and bucket and key not in self.uploaded_keys:
            content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
            s3.upload_file(str(path), bucket, key, ExtraArgs={"ContentType": content_type})
            self.uploaded_keys.add(key)
        return key


@dataclass(frozen=True)
class IntegrityReport:
    duplicate_test_ids: int
    duplicate_passage_ids: int
    duplicate_question_ids: int
    passages_without_test: int
    questions_without_test: int
    questions_with_unknown_passage: int

    @property
    def fatal_count(self) -> int:
        return sum(
            (
                self.duplicate_test_ids,
                self.duplicate_passage_ids,
                self.duplicate_question_ids,
                self.passages_without_test,
                self.questions_without_test,
                self.questions_with_unknown_passage,
            )
        )


def duplicates(values: Iterable[str]) -> int:
    seen: set[str] = set()
    repeated: set[str] = set()
    for value in values:
        if value in seen:
            repeated.add(value)
        seen.add(value)
    return len(repeated)


def validate_integrity(
    tests: list[dict[str, Any]],
    passages: list[dict[str, Any]],
    questions: list[dict[str, Any]],
) -> IntegrityReport:
    test_external_ids = [external_id(row, "id", "test_id") for row in tests]
    passage_external_ids = [external_id(row, "id", "passage_id") for row in passages]
    question_external_ids = [external_id(row, "id", "question_id") for row in questions]
    known_tests = set(test_external_ids)
    known_passages = set(passage_external_ids)

    passages_without_test = sum(
        1 for row in passages if str(first(row, "test_id") or "") not in known_tests
    )
    questions_without_test = sum(
        1 for row in questions if str(first(row, "test_id") or "") not in known_tests
    )
    questions_with_unknown_passage = sum(
        1
        for row in questions
        if first(row, "passage_id") is not None and str(first(row, "passage_id")) not in known_passages
    )
    return IntegrityReport(
        duplicate_test_ids=duplicates(test_external_ids),
        duplicate_passage_ids=duplicates(passage_external_ids),
        duplicate_question_ids=duplicates(question_external_ids),
        passages_without_test=passages_without_test,
        questions_without_test=questions_without_test,
        questions_with_unknown_passage=questions_with_unknown_passage,
    )


def media_value(row: dict[str, Any], kind: str) -> Any:
    if kind == "audio":
        return first(row, "audio_path", "audio_file", "audio", "local_audio_path")
    if kind == "image":
        return first(row, "image_path", "image_file", "image", "local_image_path")
    raise ValueError(f"Unknown media kind: {kind}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Import dautoeic TOEIC mock tests into Lyreo")
    parser.add_argument("--data-dir", default=os.getenv("DAUTOEIC_DATA_DIR"))
    parser.add_argument("--apply", action="store_true", help="Write to PostgreSQL")
    parser.add_argument(
        "--upload-media",
        action="store_true",
        help="Upload referenced local media to configured R2 bucket (requires --apply)",
    )
    parser.add_argument(
        "--allow-integrity-errors",
        action="store_true",
        help="Diagnostic escape hatch only; normal imports fail when references are invalid",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.upload_media and not args.apply:
        raise SystemExit("--upload-media requires --apply")

    root = Path(args.data_dir or "").expanduser().resolve()
    data_dir = root / "mock_test_data"
    tests_file = choose(data_dir, *TEST_FILES)
    passages_file = choose(data_dir, *PASSAGE_FILES)
    questions_file = choose(data_dir, *QUESTION_FILES)

    tests = load_json(tests_file)
    passages = load_json(passages_file)
    questions = load_json(questions_file)
    for name, rows in (("tests", tests), ("passages", passages), ("questions", questions)):
        if not isinstance(rows, list):
            raise SystemExit(f"{name} source must contain a JSON array")

    report = validate_integrity(tests, passages, questions)
    catalog = MediaCatalog.build(root, data_dir / "downloads")
    sha = checksum([tests_file, passages_file, questions_file])
    summary = {
        "dataset": "toeic",
        "source": str(data_dir),
        "tests": len(tests),
        "passages": len(passages),
        "questions": len(questions),
        "discoveredMediaFiles": len(catalog.files),
        "checksum": sha,
        "integrity": report.__dict__,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))

    if report.fatal_count and not args.allow_integrity_errors:
        raise SystemExit(
            f"Dataset integrity validation failed with {report.fatal_count} issue group(s)/rows. "
            "Fix the scraper export or inspect with --allow-integrity-errors before applying."
        )
    if not args.apply:
        return

    storage = s3_client() if args.upload_media else None
    bucket = os.getenv("R2_BUCKET")
    if args.upload_media and (storage is None or not bucket):
        raise SystemExit("R2_ENDPOINT/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_BUCKET required for --upload-media")

    with db(os.getenv("DATABASE_URL")) as conn:
        import_id = begin_import(conn, "DAUTOEIC_TOEIC", "2019-2026", data_dir, sha)
        test_ids: dict[str, Any] = {}
        passage_ids: dict[str, Any] = {}

        for row in tests:
            ext = external_id(row, "id", "test_id")
            test_id = stable_uuid("toeic-test", ext)
            test_ids[ext] = test_id
            conn.execute(
                """INSERT INTO toeic_mock_test(id,external_id,test_year,test_name,source_url,metadata_json)
                   VALUES(%s,%s,%s,%s,%s,%s::jsonb)
                   ON CONFLICT(external_id) DO UPDATE SET
                     test_year=excluded.test_year,
                     test_name=excluded.test_name,
                     source_url=excluded.source_url,
                     metadata_json=excluded.metadata_json""",
                (
                    test_id,
                    ext,
                    first(row, "year", "test_year"),
                    first(row, "name", "test_name") or ext,
                    row.get("source_url"),
                    json.dumps(row, ensure_ascii=False),
                ),
            )

        for row in passages:
            ext = external_id(row, "id", "passage_id")
            passage_id = stable_uuid("toeic-passage", ext)
            passage_ids[ext] = passage_id
            test_external_id = str(first(row, "test_id") or "")
            test_id = test_ids.get(test_external_id)
            if test_id is None:
                if args.allow_integrity_errors:
                    continue
                raise RuntimeError(f"Passage {ext} references unknown test {test_external_id}")

            conn.execute(
                """INSERT INTO toeic_passage(
                       id,external_id,test_id,part,content,position,image_object_key,audio_object_key,metadata_json
                   ) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)
                   ON CONFLICT(external_id) DO UPDATE SET
                     test_id=excluded.test_id,
                     part=excluded.part,
                     content=excluded.content,
                     position=excluded.position,
                     image_object_key=excluded.image_object_key,
                     audio_object_key=excluded.audio_object_key,
                     metadata_json=excluded.metadata_json""",
                (
                    passage_id,
                    ext,
                    test_id,
                    row.get("part"),
                    first(row, "content", "passage_text", "text"),
                    first(row, "position", "order_index"),
                    catalog.object_key(media_value(row, "image"), storage, bucket),
                    catalog.object_key(media_value(row, "audio"), storage, bucket),
                    json.dumps(row, ensure_ascii=False),
                ),
            )

        imported_questions = 0
        for row in questions:
            ext = external_id(row, "id", "question_id")
            question_id = stable_uuid("toeic-question", ext)
            test_external_id = str(first(row, "test_id") or "")
            test_id = test_ids.get(test_external_id)
            if test_id is None:
                if args.allow_integrity_errors:
                    continue
                raise RuntimeError(f"Question {ext} references unknown test {test_external_id}")

            passage_external_id = first(row, "passage_id")
            passage_id = passage_ids.get(str(passage_external_id)) if passage_external_id is not None else None
            if passage_external_id is not None and passage_id is None and not args.allow_integrity_errors:
                raise RuntimeError(f"Question {ext} references unknown passage {passage_external_id}")

            conn.execute(
                """INSERT INTO toeic_question(
                       id,external_id,test_id,passage_id,part,question_number,question_text,
                       option_a,option_b,option_c,option_d,correct_answer,difficulty_level,
                       audio_object_key,image_object_key,explanation_vi,metadata_json
                   ) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)
                   ON CONFLICT(external_id) DO UPDATE SET
                     test_id=excluded.test_id,
                     passage_id=excluded.passage_id,
                     part=excluded.part,
                     question_number=excluded.question_number,
                     question_text=excluded.question_text,
                     option_a=excluded.option_a,
                     option_b=excluded.option_b,
                     option_c=excluded.option_c,
                     option_d=excluded.option_d,
                     correct_answer=excluded.correct_answer,
                     difficulty_level=excluded.difficulty_level,
                     audio_object_key=excluded.audio_object_key,
                     image_object_key=excluded.image_object_key,
                     explanation_vi=excluded.explanation_vi,
                     metadata_json=excluded.metadata_json""",
                (
                    question_id,
                    ext,
                    test_id,
                    passage_id,
                    row.get("part"),
                    row.get("question_number"),
                    row.get("question_text"),
                    row.get("option_a"),
                    row.get("option_b"),
                    row.get("option_c"),
                    row.get("option_d"),
                    row.get("correct_answer"),
                    row.get("difficulty_level"),
                    catalog.object_key(media_value(row, "audio"), storage, bucket),
                    catalog.object_key(media_value(row, "image"), storage, bucket),
                    row.get("explanation_vi"),
                    json.dumps(row, ensure_ascii=False),
                ),
            )
            imported_questions += 1

        total_records = len(tests) + len(passages) + imported_questions
        finish_import(conn, import_id, total_records, len(catalog.uploaded_keys))

    result = {
        "status": "SUCCEEDED",
        "recordsImported": total_records,
        "mediaUploaded": len(catalog.uploaded_keys),
        "unresolvedMediaReferences": sorted(catalog.unresolved)[:50],
        "unresolvedMediaReferenceCount": len(catalog.unresolved),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
