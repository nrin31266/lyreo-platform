import hashlib

import pytest

from lesson_prep.models import (
    AlignPrep,
    AudioMediaItem,
    ContentBlock,
    MediaBlock,
    PreparationBlock,
    PreparedSource,
    Sentence,
    SourceBlock,
    SttPrep,
    ThumbnailMediaItem,
    TtsPrep,
    Word,
)
from lesson_prep.validation import validate_export

VALID_SHA = hashlib.sha256(b"dummy-audio-content").hexdigest()
VALID_THUMB_SHA = hashlib.sha256(b"dummy-image-content").hexdigest()


def base_source(**overrides):
    data = dict(
        source=SourceBlock(kind="AUDIO", origin="UPLOAD", title="Unit audio"),
        media=MediaBlock(
            audio=AudioMediaItem(
                path="media/audio.wav",
                contentType="audio/wav",
                sizeBytes=1024,
                sha256=VALID_SHA,
                durationMs=1000,
            )
        ),
        content=ContentBlock(
            text="Hello world.",
            sentences=[
                Sentence(
                    position=0,
                    text="Hello world.",
                    start_ms=0,
                    end_ms=1000,
                    words=[
                        Word(position=0, text="Hello", start_ms=0, end_ms=500),
                        Word(position=1, text="world.", start_ms=500, end_ms=1000),
                    ],
                )
            ],
        ),
    )
    data.update(overrides)
    return PreparedSource(**data)


def test_valid_audio_source_passes():
    assert validate_export(base_source()) == []


def test_rejects_obsolete_canonical_audio_object_key():
    source_dict = base_source().export_dict()
    source_dict["media"]["canonicalAudioObjectKey"] = "lessons/media/audio/uuid.wav"
    problems = validate_export(source_dict)
    assert any("obsolete environment-specific key" in p for p in problems)


def test_rejects_obsolete_thumbnail_object_key():
    source_dict = base_source().export_dict()
    source_dict["media"]["thumbnailObjectKey"] = "lessons/media/image/uuid.jpg"
    problems = validate_export(source_dict)
    assert any("obsolete environment-specific key" in p for p in problems)


def test_rejects_signed_url_in_values():
    source = base_source()
    exported = source.export_dict()
    exported["notes"] = "https://cdn.invalid/a.wav?X-Amz-Signature=dead"
    problems = validate_export(exported)
    assert any("forbidden value" in p for p in problems)


def test_rejects_local_absolute_path_in_media():
    source = base_source(
        media=MediaBlock(
            audio=AudioMediaItem(
                path="/home/operator/audio.wav",
                contentType="audio/wav",
                sizeBytes=100,
                sha256=VALID_SHA,
                durationMs=500,
            )
        )
    )
    problems = validate_export(source)
    assert any("relative path" in p for p in problems)


def test_rejects_file_uri_in_media():
    source = base_source(
        media=MediaBlock(
            audio=AudioMediaItem(
                path="file:///tmp/audio.wav",
                contentType="audio/wav",
                sizeBytes=100,
                sha256=VALID_SHA,
                durationMs=500,
            )
        )
    )
    problems = validate_export(source)
    assert any("relative path" in p for p in problems)


def test_rejects_path_traversal_in_media():
    source = base_source(
        media=MediaBlock(
            audio=AudioMediaItem(
                path="../audio.wav",
                contentType="audio/wav",
                sizeBytes=100,
                sha256=VALID_SHA,
                durationMs=500,
            )
        )
    )
    problems = validate_export(source)
    assert any("traversal" in p for p in problems)


def test_rejects_non_hex_sha256():
    source = base_source(
        media=MediaBlock(
            audio=AudioMediaItem(
                path="media/audio.wav",
                contentType="audio/wav",
                sizeBytes=100,
                sha256="not-a-64-char-hex",
                durationMs=500,
            )
        )
    )
    problems = validate_export(source)
    assert any("64-character hex string" in p for p in problems)


def test_rejects_secret_like_fields():
    exported = base_source().export_dict()
    exported["apiKey"] = "hidden"
    problems = validate_export(exported)
    assert any("secret-like" in p for p in problems)


def test_rejects_malformed_document():
    problems = validate_export({"schemaVersion": 1})
    assert any("does not match the schema" in p for p in problems)


def test_rejects_wrong_schema_version():
    source = base_source()
    source.schemaVersion = 2
    problems = validate_export(source)
    assert any("schemaVersion" in p for p in problems)


def test_rejects_non_contiguous_sentences():
    source = base_source(
        content=ContentBlock(
            text="A. B.",
            sentences=[
                Sentence(position=0, text="A.", start_ms=0, end_ms=100),
                Sentence(position=2, text="B.", start_ms=100, end_ms=200),
            ],
        )
    )
    problems = validate_export(source)
    assert any("contiguous" in p for p in problems)


def test_rejects_overlapping_sentences():
    source = base_source(
        content=ContentBlock(
            text="A. B.",
            sentences=[
                Sentence(position=0, text="A.", start_ms=0, end_ms=1000),
                Sentence(position=1, text="B.", start_ms=500, end_ms=1500),
            ],
        )
    )
    problems = validate_export(source)
    assert any("overlap" in p for p in problems)


def test_rejects_words_outside_sentence_bounds():
    source = base_source(
        content=ContentBlock(
            text="A.",
            sentences=[
                Sentence(
                    position=0,
                    text="A.",
                    start_ms=0,
                    end_ms=100,
                    words=[Word(position=0, text="A", start_ms=50, end_ms=250)],
                )
            ],
        )
    )
    problems = validate_export(source)
    assert any("does not fit" in p for p in problems)


def test_rejects_tts_metadata_on_upload_audio():
    source = base_source(
        preparation=PreparationBlock(
            tts=TtsPrep(provider="LOCAL_KOKORO", model="Kokoro-82M", voice="af_heart")
        )
    )
    problems = validate_export(source)
    assert any("only valid for TTS_GENERATED" in p for p in problems)


def test_requires_tts_metadata_on_tts_generated_audio():
    source = base_source(
        source=SourceBlock(kind="AUDIO", origin="TTS_GENERATED", title="TTS unit"),
        preparation=PreparationBlock(tts=None),
    )
    problems = validate_export(source)
    assert any("TTS_GENERATED audio requires preparation.tts" in p for p in problems)


def test_valid_video_source_passes():
    source = base_source(
        source=SourceBlock(
            kind="VIDEO",
            origin="YOUTUBE",
            externalId="dQw4w9WgXcQ",
            originalUrl="https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title="Video lesson",
        ),
        media=MediaBlock(
            audio=AudioMediaItem(
                path="media/audio.wav",
                contentType="audio/wav",
                sizeBytes=1024,
                sha256=VALID_SHA,
                durationMs=1000,
            ),
            thumbnail=ThumbnailMediaItem(
                path="media/thumbnail.jpg",
                contentType="image/jpeg",
                sizeBytes=512,
                sha256=VALID_THUMB_SHA,
            ),
        ),
    )
    assert validate_export(source) == []


def test_video_requires_external_id():
    source = base_source(
        source=SourceBlock(kind="VIDEO", origin="YOUTUBE", title="Video lesson")
    )
    problems = validate_export(source)
    assert any("externalId" in p for p in problems)


def test_rejects_zero_duration_words():
    source = base_source(
        content=ContentBlock(
            text="Hello.",
            sentences=[
                Sentence(
                    position=0,
                    text="Hello.",
                    start_ms=0,
                    end_ms=500,
                    words=[Word(position=0, text="Hello", start_ms=200, end_ms=200)],
                )
            ],
        )
    )
    problems = validate_export(source)
    assert any("endMs must be strictly greater than startMs" in p for p in problems)


def test_rejects_overlapping_words_within_sentence():
    source = base_source(
        content=ContentBlock(
            text="Hello world.",
            sentences=[
                Sentence(
                    position=0,
                    text="Hello world.",
                    start_ms=0,
                    end_ms=1000,
                    words=[
                        Word(position=0, text="Hello", start_ms=0, end_ms=500),
                        Word(position=1, text="world", start_ms=450, end_ms=1000),
                    ],
                )
            ],
        )
    )
    problems = validate_export(source)
    assert any("overlaps with preceding word" in p for p in problems)
