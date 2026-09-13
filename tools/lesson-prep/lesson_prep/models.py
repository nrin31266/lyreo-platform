"""Versioned prepared lesson source models (schemaVersion 1) for portable packages.

The primary artifact is a portable *.lesson-source.zip containing:
- lesson-source.json
- media/audio.<ext>
- media/thumbnail.<ext> (optional)

It never contains base64 media, signed URLs, local absolute paths or secrets.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field

SCHEMA_VERSION = 1

SOURCE_KINDS = ("AUDIO", "VIDEO")
AUDIO_ORIGINS = ("UPLOAD", "TTS_GENERATED")
VIDEO_ORIGINS = ("YOUTUBE",)


class StrictModel(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")


class Word(StrictModel):
    position: int
    text: str
    start_ms: int = Field(alias="startMs")
    end_ms: int = Field(alias="endMs")


class Sentence(StrictModel):
    position: int
    text: str
    start_ms: int | None = Field(default=None, alias="startMs")
    end_ms: int | None = Field(default=None, alias="endMs")
    words: list[Word] = Field(default_factory=list)


class SourceBlock(StrictModel):
    kind: str
    origin: str
    external_id: str | None = Field(default=None, alias="externalId")
    original_url: str | None = Field(default=None, alias="originalUrl")
    title: str
    channel: str | None = None


class AudioMediaItem(StrictModel):
    path: str
    content_type: str = Field(alias="contentType")
    size_bytes: int = Field(alias="sizeBytes")
    sha256: str
    duration_ms: int = Field(alias="durationMs")


class ThumbnailMediaItem(StrictModel):
    path: str
    content_type: str = Field(alias="contentType")
    size_bytes: int = Field(alias="sizeBytes")
    sha256: str


class MediaBlock(StrictModel):
    audio: AudioMediaItem
    thumbnail: ThumbnailMediaItem | None = None


class ContentBlock(StrictModel):
    text: str
    sentences: list[Sentence]


class SttPrep(StrictModel):
    provider: str
    model: str


class AlignPrep(StrictModel):
    provider: str
    model: str
    normalized: bool | None = None
    repaired_word_count: int | None = Field(default=None, alias="repairedWordCount")


class TtsPrep(StrictModel):
    provider: str
    model: str
    voice: str | None = None
    accent: str | None = None
    speed: float | None = None


class PreparationBlock(StrictModel):
    stt: SttPrep | None = None
    alignment: AlignPrep | None = None
    tts: TtsPrep | None = None


class PreparedSource(StrictModel):
    schemaVersion: int = SCHEMA_VERSION
    source: SourceBlock
    media: MediaBlock
    content: ContentBlock
    preparation: PreparationBlock = Field(default_factory=PreparationBlock)

    def export_dict(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True, exclude_none=True)
