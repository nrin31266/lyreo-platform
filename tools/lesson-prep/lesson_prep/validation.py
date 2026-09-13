"""Export validation: the prepared source must be portable and must not leak
signed URLs, local absolute paths, object keys, or secrets."""

from __future__ import annotations

import re
from typing import Any

from .models import (
    AUDIO_ORIGINS,
    SCHEMA_VERSION,
    SOURCE_KINDS,
    VIDEO_ORIGINS,
    PreparedSource,
    Sentence,
)

_FORBIDDEN_VALUE_PATTERNS = [
    re.compile(r"https?://[^\"']*[?&](sig|signature|token|x-amz-[a-z0-9_-]+)=", re.IGNORECASE),
    re.compile(r"(^|[\"'])(/home/|/Users/|[A-Za-z]:\\|file://)"),
    re.compile(r"base64,"),
]

_FORBIDDEN_SENSITIVE_KEYS = {
    "apikey",
    "api_key",
    "password",
    "secret",
    "credential",
    "credentials",
    "authtoken",
    "auth_token",
    "accesstoken",
    "access_token",
    "refreshtoken",
    "refresh_token",
}

_OBSOLETE_KEYS = {
    "canonicalAudioObjectKey",
    "thumbnailObjectKey",
    "canonicalAudioSha256",
}

_SHA256_HEX_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")


def validate_export(source: PreparedSource | dict[str, Any]) -> list[str]:
    """Returns a list of human-readable problems; empty means the file is valid."""
    problems: list[str] = []
    if isinstance(source, dict):
        problems.extend(_scan_document(source))
        for key in _OBSOLETE_KEYS:
            if key in source or (isinstance(source.get("media"), dict) and key in source["media"]):
                problems.append(f"exported file contains obsolete environment-specific key: {key}")
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

    # Validate portable media paths and metadata
    problems.extend(_validate_media_path("media.audio.path", source.media.audio.path))
    if not source.media.audio.content_type.startswith("audio/"):
        problems.append(f"media.audio.contentType must be an audio MIME type, got {source.media.audio.content_type!r}")
    if source.media.audio.size_bytes <= 0:
        problems.append("media.audio.sizeBytes must be positive")
    if not _SHA256_HEX_PATTERN.match(source.media.audio.sha256):
        problems.append("media.audio.sha256 must be a 64-character hex string")
    if source.media.audio.duration_ms <= 0:
        problems.append("media.audio.durationMs must be positive")

    if source.media.thumbnail is not None:
        if source.media.thumbnail.path == source.media.audio.path:
            problems.append(
                f"media.thumbnail.path ({source.media.thumbnail.path!r}) must not be identical to media.audio.path"
            )
        problems.extend(_validate_media_path("media.thumbnail.path", source.media.thumbnail.path))
        if not source.media.thumbnail.content_type.startswith("image/"):
            problems.append(f"media.thumbnail.contentType must be an image MIME type, got {source.media.thumbnail.content_type!r}")
        if source.media.thumbnail.size_bytes <= 0:
            problems.append("media.thumbnail.sizeBytes must be positive")
        if not _SHA256_HEX_PATTERN.match(source.media.thumbnail.sha256):
            problems.append("media.thumbnail.sha256 must be a 64-character hex string")

    problems.extend(_validate_content(source, audio_duration_ms=source.media.audio.duration_ms))

    exported = source.export_dict()
    problems.extend(_scan_document(exported))

    return problems


def _validate_media_path(field: str, path: str) -> list[str]:
    problems: list[str] = []
    if not path or not path.strip():
        problems.append(f"{field} is required")
        return problems
    if path.startswith("/") or path.startswith("\\") or path.startswith("~") or ":" in path:
        problems.append(f"{field} must be a relative path inside the package, not an absolute path or URL: {path}")
    if ".." in path.split("/"):
        problems.append(f"{field} must not contain path traversal (..): {path}")
    return problems


def _scan_document(document: Any) -> list[str]:
    """Leak/secret scan that works on raw dicts, independent of schema validation."""
    problems: list[str] = []
    for pattern in _FORBIDDEN_VALUE_PATTERNS:
        for value in _stringify_values(document):
            if pattern.search(value):
                problems.append(
                    f"exported file contains a forbidden value (signed URL, absolute path or URL): {value[:60]}"
                )
                break

    for key in _iter_keys(document):
        k_str = str(key).lower()
        k_norm = re.sub(r"[_\-]", "", k_str)
        if k_str in _FORBIDDEN_SENSITIVE_KEYS or k_norm in _FORBIDDEN_SENSITIVE_KEYS:
            problems.append(f"exported file contains a forbidden sensitive field: {key}")
    return problems


def _validate_content(source: PreparedSource, audio_duration_ms: int) -> list[str]:
    problems: list[str] = []
    if not source.content.text or not source.content.text.strip():
        problems.append("content.text is required")
    sentences = source.content.sentences
    if not sentences:
        problems.append("content.sentences must not be empty")

    previous_end: int | None = None
    for expected, sentence in enumerate(sentences):
        problems.extend(_validate_sentence(sentence, expected, audio_duration_ms))
        if sentence.start_ms is None:
            continue
        if previous_end is not None and sentence.start_ms < previous_end:
            problems.append(
                f"sentences overlap: sentence {expected - 1} ends at {previous_end}ms but sentence {expected} starts at {sentence.start_ms}ms"
            )
        previous_end = sentence.end_ms
    return problems


def _validate_sentence(sentence: Sentence, expected_position: int, audio_duration_ms: int) -> list[str]:
    problems: list[str] = []
    if sentence.position != expected_position:
        problems.append(
            f"content.sentences positions must be contiguous; expected {expected_position}, got {sentence.position}"
        )
    if not sentence.text or not sentence.text.strip():
        problems.append(f"content.sentences[{expected_position}].text is required")
    if (sentence.start_ms is None) != (sentence.end_ms is None):
        problems.append(f"content.sentences[{expected_position}] must set both startMs and endMs")
    if sentence.start_ms is not None and sentence.end_ms is not None:
        if sentence.start_ms < 0 or sentence.end_ms < sentence.start_ms:
            problems.append(f"content.sentences[{expected_position}] has invalid timestamps")
        if sentence.end_ms > audio_duration_ms:
            problems.append(
                f"content.sentences[{expected_position}] ends at {sentence.end_ms}ms, which exceeds audio duration of {audio_duration_ms}ms"
            )

    for expected_word, word in enumerate(sentence.words):
        if word.position != expected_word:
            problems.append(f"content.sentences[{expected_position}].words positions must be contiguous")
        if word.start_ms < 0 or word.end_ms <= word.start_ms:
            problems.append(
                f"content.sentences[{expected_position}].words[{expected_word}] has invalid timestamps (endMs must be strictly greater than startMs)"
            )
        if expected_word > 0 and word.start_ms < sentence.words[expected_word - 1].end_ms:
            problems.append(
                f"content.sentences[{expected_position}].words[{expected_word}] overlaps with preceding word"
            )
        if (
            sentence.start_ms is not None
            and not (word.start_ms >= sentence.start_ms and word.end_ms <= sentence.end_ms)
        ):
            problems.append(
                f"content.sentences[{expected_position}].words[{expected_word}] does not fit the sentence audio bounds"
            )
        if word.end_ms > audio_duration_ms:
            problems.append(
                f"content.sentences[{expected_position}].words[{expected_word}] ends at {word.end_ms}ms, exceeding audio duration of {audio_duration_ms}ms"
            )
    return problems


def _stringify_values(node: Any) -> list[str]:
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


def _iter_keys(node: Any):
    if isinstance(node, dict):
        for key, value in node.items():
            yield key
            yield from _iter_keys(value)
    elif isinstance(node, list):
        for value in node:
            yield from _iter_keys(value)
