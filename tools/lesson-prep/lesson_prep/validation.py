"""Export validation: the prepared source must be importable and must not leak
signed URLs, local absolute paths or secrets."""

from __future__ import annotations

import re

from .models import (
    AUDIO_ORIGINS,
    SCHEMA_VERSION,
    SOURCE_KINDS,
    VIDEO_ORIGINS,
    PreparedSource,
    Sentence,
)

_FORBIDDEN_VALUE_PATTERNS = [
    re.compile(r"https?://[^\"']*[?&](sig|signature|token|X-Amz-)="),
    re.compile(r"(^|[\"'])(/home/|/Users/|C:\\)"),
    re.compile(r"base64,"),
]

_SECRET_KEY_PATTERN = re.compile(
    r"(secret|password|api[_-]?key|credential|token)", re.IGNORECASE
)


def validate_export(source: PreparedSource | dict) -> list[str]:
    """Returns a list of human-readable problems; empty means the file is valid."""
    problems: list[str] = []
    if isinstance(source, dict):
        # Scan the raw document for leak patterns before schema coercion so that
        # secret-like fields can never hide behind model validation.
        problems.extend(_scan_document(source))
        if problems:
            return problems
        try:
            source = PreparedSource.model_validate(source, by_alias=True)
        except Exception as exc:
            return [f"prepared source does not match the schema: {exc}"]
    if source.schemaVersion != SCHEMA_VERSION:
        problems.append(f"schemaVersion must be {SCHEMA_VERSION}, got {source.schemaVersion}")

    if source.source.kind not in SOURCE_KINDS:
        problems.append(f"source.kind must be one of {SOURCE_KINDS}, got {source.source.kind!r}")
    elif source.source.kind == "AUDIO":
        if source.source.origin not in AUDIO_ORIGINS:
            problems.append(f"AUDIO origin must be one of {AUDIO_ORIGINS}, got {source.source.origin!r}")
        if source.source.origin == "TTS_GENERATED" and source.preparation.tts is None:
            problems.append("TTS_GENERATED audio requires preparation.tts")
        if source.source.origin != "TTS_GENERATED" and source.preparation.tts is not None:
            problems.append("preparation.tts is only valid for TTS_GENERATED audio")
    else:
        if source.source.origin not in VIDEO_ORIGINS:
            problems.append(f"VIDEO origin must be one of {VIDEO_ORIGINS}, got {source.source.origin!r}")
        if not source.source.external_id:
            problems.append("VIDEO source requires externalId (YouTube video ID)")
        if not source.source.original_url:
            problems.append("VIDEO source requires originalUrl")

    if not source.source.title or not source.source.title.strip():
        problems.append("source.title is required")

    problems.extend(_validate_object_key("media.canonicalAudioObjectKey",
                                         source.media.canonical_audio_object_key, required=True))
    if source.media.thumbnail_object_key:
        problems.extend(_validate_object_key("media.thumbnailObjectKey",
                                             source.media.thumbnail_object_key, required=False))

    problems.extend(_validate_content(source))

    exported = source.export_dict()
    problems.extend(_scan_document(exported))

    return problems


def _scan_document(document) -> list[str]:
    """Leak/secret scan that works on raw dicts, independent of schema validation."""
    problems: list[str] = []
    for pattern in _FORBIDDEN_VALUE_PATTERNS:
        for value in _stringify_values(document):
            if pattern.search(value):
                problems.append("exported file contains a forbidden value (signed URL, "
                                "absolute path or embedded media)")
                break

    for key in _iter_keys(document):
        if _SECRET_KEY_PATTERN.search(str(key)):
            problems.append(f"exported file contains a secret-like field: {key}")
    return problems


def _validate_object_key(field: str, value: str | None, required: bool) -> list[str]:
    problems: list[str] = []
    if not value or not value.strip():
        if required:
            problems.append(f"{field} is required")
        return problems
    if "://" in value or value.startswith("/") or "\\" in value or value.startswith("~"):
        problems.append(f"{field} must be a canonical storage key, not a URL or local path")
    return problems


def _validate_content(source: PreparedSource) -> list[str]:
    problems: list[str] = []
    if not source.content.text or not source.content.text.strip():
        problems.append("content.text is required")
    sentences = source.content.sentences
    if not sentences:
        problems.append("content.sentences must not be empty")

    previous_end: int | None = None
    for expected, sentence in enumerate(sentences):
        problems.extend(_validate_sentence(sentence, expected))
        if sentence.start_ms is None:
            continue
        if previous_end is not None and sentence.start_ms < previous_end:
            problems.append(f"sentences overlap: sentence {expected - 1} ends at "
                            f"{previous_end}ms but sentence {expected} starts at {sentence.start_ms}ms")
        previous_end = sentence.end_ms
    return problems


def _validate_sentence(sentence: Sentence, expected_position: int) -> list[str]:
    problems: list[str] = []
    if sentence.position != expected_position:
        problems.append(f"content.sentences positions must be contiguous; expected "
                        f"{expected_position}, got {sentence.position}")
    if not sentence.text or not sentence.text.strip():
        problems.append(f"content.sentences[{expected_position}].text is required")
    if (sentence.start_ms is None) != (sentence.end_ms is None):
        problems.append(f"content.sentences[{expected_position}] must set both startMs and endMs")
    if sentence.start_ms is not None and sentence.end_ms is not None:
        if sentence.start_ms < 0 or sentence.end_ms < sentence.start_ms:
            problems.append(f"content.sentences[{expected_position}] has invalid timestamps")

    for expected_word, word in enumerate(sentence.words):
        if word.position != expected_word:
            problems.append(f"content.sentences[{expected_position}].words positions must be contiguous")
        if word.start_ms < 0 or word.end_ms <= word.start_ms:
            problems.append(f"content.sentences[{expected_position}].words[{expected_word}] "
                            f"has invalid timestamps (endMs must be greater than startMs)")
        if expected_word > 0 and word.start_ms < sentence.words[expected_word - 1].end_ms:
            problems.append(f"content.sentences[{expected_position}].words[{expected_word}] "
                            f"overlaps with preceding word")
        if (sentence.start_ms is not None
                and not (word.start_ms >= sentence.start_ms and word.end_ms <= sentence.end_ms)):
            problems.append(f"content.sentences[{expected_position}].words[{expected_word}] "
                            f"does not fit the sentence audio bounds")
    return problems


def _stringify_values(node) -> list[str]:
    values: list[str] = []
    if isinstance(node, dict):
        for value in node.values():
            values.extend(_stringify_values(value))
    elif isinstance(node, list):
        for value in node:
            values.extend(_stringify_values(value))
    elif node is not None:
        values.append(str(node))
    return values


def _iter_keys(node):
    if isinstance(node, dict):
        for key, value in node.items():
            yield key
            yield from _iter_keys(value)
    elif isinstance(node, list):
        for value in node:
            yield from _iter_keys(value)
