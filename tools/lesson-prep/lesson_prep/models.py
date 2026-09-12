"""Versioned prepared lesson source models (schemaVersion 1).

The exported JSON is the single final artifact an operator keeps/imports.
It never contains base64 media, signed URLs, local absolute paths or secrets.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field

SCHEMA_VERSION = 1

SOURCE_KINDS = ("AUDIO", "VIDEO")
AUDIO_ORIGINS = ("UPLOAD", "TTS_GENERATED")
VIDEO_ORIGINS = ("YOUTUBE",)


class Word(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    position: int
    text: str
    start_ms: int = Field(alias="startMs")
    end_ms: int = Field(alias="endMs")


class Sentence(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    position: int
    text: str
    start_ms: int | None = Field(default=None, alias="startMs")
    end_ms: int | None = Field(default=None, alias="endMs")
    words: list[Word] = Field(default_factory=list)


class SourceBlock(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    kind: str
    origin: str
    external_id: str | None = Field(default=None, alias="externalId")
    original_url: str | None = Field(default=None, alias="originalUrl")
    title: str


class MediaBlock(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    canonical_audio_object_key: str = Field(alias="canonicalAudioObjectKey")
    canonical_audio_sha256: str | None = Field(default=None, alias="canonicalAudioSha256")
    thumbnail_object_key: str | None = Field(default=None, alias="thumbnailObjectKey")


class ContentBlock(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    text: str
    sentences: list[Sentence]


class SttPrep(BaseModel):
    provider: str
    model: str


class AlignPrep(BaseModel):
    provider: str
    model: str


class TtsPrep(BaseModel):
    provider: str
    model: str
    voice: str | None = None
    accent: str | None = None
    speed: float | None = None


class PreparationBlock(BaseModel):
    stt: SttPrep | None = None
    alignment: AlignPrep | None = None
    tts: TtsPrep | None = None


class PreparedSource(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    schemaVersion: int = SCHEMA_VERSION
    source: SourceBlock
    media: MediaBlock
    content: ContentBlock
    preparation: PreparationBlock = Field(default_factory=PreparationBlock)

    def export_dict(self) -> dict[str, Any]:
        return self.model_dump(by_alias=True, exclude_none=True)
