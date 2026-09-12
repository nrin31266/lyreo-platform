"""Preparation state machine.

Business/orchestration logic lives here, never inside UI callbacks. The single
final artifact is one versioned `*.lesson-source.json` file; assets are uploaded
to Core storage once before export.
"""

from __future__ import annotations

import hashlib
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .ai_service_client import AiServiceClient
from .core_api_client import CoreApiClient
from .models import (
    AlignPrep,
    ContentBlock,
    MediaBlock,
    PreparationBlock,
    PreparedSource,
    Sentence,
    SourceBlock,
    SttPrep,
    TtsPrep,
    Word,
)
from .validation import validate_export
from .youtube import (
    YoutubeError,
    YoutubeMeta,
    download_thumbnail,
    extract_audio,
    fetch_metadata,
    normalize_youtube_url,
)

_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")


class PrepError(RuntimeError):
    pass


@dataclass
class PrepState:
    mode: str = "upload"  # upload | tts | youtube
    title: str = ""
    local_audio_path: Path | None = None
    audio_object_key: str | None = None
    audio_sha256: str | None = None
    audio_download_url: str | None = None
    thumbnail_object_key: str | None = None
    thumbnail_path: Path | None = None
    transcript: str = ""
    sentences: list[Sentence] = field(default_factory=list)
    stt: SttPrep | None = None
    alignment: AlignPrep | None = None
    alignment_fresh: bool = False
    tts: TtsPrep | None = None
    youtube: YoutubeMeta | None = None


class LessonPrepService:
    def __init__(self, ai: AiServiceClient, core: CoreApiClient, work_dir: Path):
        self.ai = ai
        self.core = core
        self.work_dir = work_dir
        self.work_dir.mkdir(parents=True, exist_ok=True)
        self.state = PrepState()

    def clear_work_dir(self) -> int:
        """Purges temporary files in the work directory and resets state."""
        deleted_count = 0
        if self.work_dir.exists():
            for p in list(self.work_dir.iterdir()):
                if p.is_file():
                    try:
                        p.unlink()
                        deleted_count += 1
                    except OSError:
                        pass
        self.state = PrepState()
        return deleted_count

    # ---------------------------------------------------------------- audio input

    def select_uploaded_audio(self, source_path: str | Path) -> Path:
        source = Path(source_path)
        if not source.is_file():
            raise PrepError(f"Audio file not found: {source}")
        target = self.work_dir / f"uploaded-{source.name}"
        shutil.copyfile(source, target)
        self.state = PrepState(mode="upload")
        self.state.local_audio_path = target
        return target

    def generate_audio_from_text(self, text: str, voice: str, accent: str,
                                 speed: float = 1.0) -> Path:
        text = (text or "").strip()
        if not text:
            raise PrepError("Text is empty; nothing to synthesize")
        result = self.ai.tts(text, voice=voice, accent=accent, speed=speed)
        target = self.work_dir / "tts-generated.wav"
        target.write_bytes(result["audio_bytes"])
        self.state = PrepState(mode="tts")
        self.state.local_audio_path = target
        self.state.transcript = text
        self.state.tts = TtsPrep(
            provider=result["provider"], model=result["model"],
            voice=result["voice"], accent=result["accent"], speed=float(speed),
        )
        return target

    def prepare_youtube(self, url: str) -> YoutubeMeta:
        normalized = normalize_youtube_url(url)
        if not normalized:
            raise PrepError("Invalid YouTube URL; expected an 11-character video ID")
        meta = fetch_metadata(normalized)
        audio = extract_audio(normalized, self.work_dir)
        thumbnail = download_thumbnail(meta, self.work_dir)
        self.state = PrepState(mode="youtube")
        self.state.youtube = meta
        self.state.local_audio_path = audio
        self.state.thumbnail_path = thumbnail
        self.state.title = meta.title
        return meta

    # ---------------------------------------------------------------- canonical upload

    def upload_canonical_audio(self) -> dict[str, Any]:
        if self.state.audio_object_key:
            return self._audio_status()
        if not self.state.local_audio_path:
            raise PrepError("No local audio to upload")
        uploaded = self.core.upload_media("AUDIO", self.state.local_audio_path)
        self.state.audio_object_key = uploaded["objectKey"]
        self.state.audio_sha256 = _sha256(self.state.local_audio_path)
        self.state.audio_download_url = uploaded.get("downloadUrl")
        return self._audio_status()

    def upload_thumbnail(self) -> dict[str, Any] | None:
        if self.state.thumbnail_object_key:
            return {"objectKey": self.state.thumbnail_object_key}
        path = getattr(self.state, "thumbnail_path", None)
        if not path:
            return None
        uploaded = self.core.upload_media("IMAGE", path)
        self.state.thumbnail_object_key = uploaded["objectKey"]
        return uploaded

    def _audio_status(self) -> dict[str, Any]:
        return {
            "objectKey": self.state.audio_object_key,
            "sha256": self.state.audio_sha256,
            "downloadUrl": self.state.audio_download_url,
        }

    # ---------------------------------------------------------------- STT / alignment

    def run_stt(self) -> dict[str, Any]:
        self._require_audio_reference()
        result = self.ai.stt(self.state.audio_download_url)
        self.state.transcript = result["text"]
        self.state.stt = SttPrep(provider=result["provider"], model=result["model"])
        self._invalidate_alignment()
        return result

    def set_transcript(self, text: str) -> None:
        text = (text or "").strip()
        if not text:
            raise PrepError("Transcript must not be empty")
        if text != self.state.transcript:
            self.state.transcript = text
            self._invalidate_alignment()

    def run_alignment(self) -> list[dict[str, Any]]:
        self._require_audio_reference()
        if not self.state.transcript.strip():
            raise PrepError("Transcript is empty; run STT or edit the transcript first")
        result = self.ai.align(self.state.audio_download_url, self.state.transcript)
        self.state.sentences = build_sentences(self.state.transcript, result["words"])
        self.state.alignment = AlignPrep(provider=result["provider"], model=result["model"])
        self.state.alignment_fresh = True
        return result["words"]

    def _invalidate_alignment(self) -> None:
        self.state.alignment_fresh = False
        self.state.sentences = []
        self.state.alignment = None

    def _require_audio_reference(self) -> None:
        if not self.state.audio_download_url or not self.state.audio_object_key:
            raise PrepError("Canonical audio is not uploaded yet; upload assets first")

    # ---------------------------------------------------------------- export

    def export_readiness(self) -> tuple[bool, list[str]]:
        problems: list[str] = []
        if not self.state.title.strip():
            problems.append("title is missing")
        if not self.state.audio_object_key:
            problems.append("canonical audio is not uploaded")
        if not self.state.transcript.strip():
            problems.append("transcript is missing (run STT or generate from text)")
        if not self.state.alignment_fresh:
            problems.append("alignment is stale or missing; rerun alignment")
        if self.state.mode == "youtube" and self.state.youtube is None:
            problems.append("YouTube metadata is missing")
        if problems:
            return False, problems
        # Surface schema/timestamp problems too, so "Validate" is authoritative.
        try:
            source = self._assemble_export()
            problems.extend(validate_export(source))
        except PrepError as exc:
            problems.append(str(exc))
        return not problems, problems

    def build_export(self) -> PreparedSource:
        ready, problems = self.export_readiness()
        if not ready:
            raise PrepError("Preparation is not ready to export: " + "; ".join(problems))
        return self._assemble_export()

    def _assemble_export(self) -> PreparedSource:
        if self.state.mode == "youtube":
            source = SourceBlock(
                kind="VIDEO", origin="YOUTUBE",
                externalId=self.state.youtube.video_id,
                originalUrl=self.state.youtube.original_url,
                title=self.state.title,
            )
        else:
            source = SourceBlock(
                kind="AUDIO",
                origin="TTS_GENERATED" if self.state.mode == "tts" else "UPLOAD",
                externalId=None,
                originalUrl=None,
                title=self.state.title,
            )

        media = MediaBlock(
            canonicalAudioObjectKey=self.state.audio_object_key,
            canonicalAudioSha256=self.state.audio_sha256,
            thumbnailObjectKey=self.state.thumbnail_object_key,
        )
        preparation = PreparationBlock(stt=self.state.stt, alignment=self.state.alignment,
                                       tts=self.state.tts)
        content = ContentBlock(text=self.state.transcript, sentences=self.state.sentences)
        source_model = PreparedSource(source=source, media=media, content=content,
                                      preparation=preparation)
        problems = validate_export(source_model)
        if problems:
            raise PrepError("Export validation failed: " + "; ".join(problems))
        return source_model

    def export_json(self) -> str:
        import json
        return json.dumps(self.build_export().export_dict(), indent=2, ensure_ascii=False)

    def write_export_file(self, export_dir: Path) -> Path:
        """Writes the final artifact to the shared export folder.

        Filename = sanitized title + timestamp + short random suffix, so repeated
        exports never overwrite each other.
        """
        import datetime
        import json
        import random
        import re
        source = self.build_export()
        slug = re.sub(r"[^A-Za-z0-9._-]+", "-", source.source.title.strip()).strip("-")
        slug = slug[:60] or "lesson"
        stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        suffix = f"{random.randrange(0, 9999):04d}"
        export_dir.mkdir(parents=True, exist_ok=True)
        target = export_dir / f"{slug}-{stamp}-{suffix}.lesson-source.json"
        target.write_text(json.dumps(source.export_dict(), indent=2, ensure_ascii=False),
                          encoding="utf-8")
        return target


def normalize_word_timestamps(
    raw_words: list[dict[str, Any]],
    sentence_start_min: int = 0,
    min_duration_ms: int = 50,
) -> list[tuple[str, int, int]]:
    """Normalizes raw word alignment timestamps for a sentence.

    Guarantees:
    1. Every word has strictly positive duration (end_ms > start_ms >= 0).
    2. Words are strictly monotonic and non-overlapping: word[i].start >= word[i-1].end.
    3. Instantaneous/zero-duration words (common in CTC aligners for unstressed words
       like 'are', 'to') borrow duration from shared acoustic segments or expand safely.
    """
    if not raw_words:
        return []

    n = len(raw_words)
    texts = [str(w.get("word", "")) for w in raw_words]
    starts = [max(0, int(w.get("start_ms", 0))) for w in raw_words]
    ends = [max(0, int(w.get("end_ms", 0))) for w in raw_words]

    # 1. Forward monotonic pass: starts must be >= sentence_start_min and ends >= starts
    cur = sentence_start_min
    for i in range(n):
        starts[i] = max(starts[i], cur)
        ends[i] = max(ends[i], starts[i])
        cur = starts[i]

    # 2. Allocate duration for zero or sub-minimum duration words
    for i in range(n):
        if ends[i] - starts[i] < min_duration_ms:
            j = i + 1
            while j < n and ends[j] <= ends[i]:
                j += 1
            if j < n:
                total_span = ends[j] - starts[i]
                num_words = j - i + 1
                alloc = max(20, min(min_duration_ms, total_span // num_words))
                ends[i] = starts[i] + alloc
                for k in range(i + 1, j):
                    starts[k] = ends[k - 1]
                    ends[k] = starts[k] + alloc
                starts[j] = ends[j - 1]
            else:
                ends[i] = starts[i] + min_duration_ms

    # 3. Final sequential clamp: strictly monotonic, non-overlapping, positive duration
    cur = sentence_start_min
    result: list[tuple[str, int, int]] = []
    for i in range(n):
        s = max(starts[i], cur)
        e = ends[i]
        if e <= s:
            e = s + min_duration_ms
        result.append((texts[i], s, e))
        cur = e

    return result


def _clean_token(s: str) -> str:
    return re.sub(r"[^a-zA-Z0-9]", "", s).lower()


def match_original_words_to_alignments(
    original_tokens: list[str],
    raw_slice: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Maps original lexical tokens (preserving punctuation, colons, commas, dots)
    to acoustic alignment timestamps."""
    if not original_tokens:
        return []
    if not raw_slice:
        return [{"word": t, "start_ms": 0, "end_ms": 0} for t in original_tokens]

    # Fast-path: 1-to-1 match (vast majority of cases where aligner tokenized the sentence)
    if len(original_tokens) == len(raw_slice):
        return [
            {
                "word": orig,
                "start_ms": raw.get("start_ms", 0),
                "end_ms": raw.get("end_ms", 0),
            }
            for orig, raw in zip(original_tokens, raw_slice)
        ]

    # Fallback: align when token counts differ slightly (e.g. hyphenated words or aligner splits)
    orig_clean = [_clean_token(t) for t in original_tokens]
    raw_clean = [_clean_token(str(w.get("word", ""))) for w in raw_slice]

    results: list[dict[str, Any]] = []
    raw_idx = 0
    raw_len = len(raw_slice)

    for i, orig in enumerate(original_tokens):
        c_orig = orig_clean[i]
        if raw_idx >= raw_len:
            last_end = results[-1]["end_ms"] if results else 0
            results.append({"word": orig, "start_ms": last_end, "end_ms": last_end})
            continue

        c_raw = raw_clean[raw_idx]

        if c_orig == c_raw:
            results.append({
                "word": orig,
                "start_ms": raw_slice[raw_idx].get("start_ms", 0),
                "end_ms": raw_slice[raw_idx].get("end_ms", 0),
            })
            raw_idx += 1
        elif c_orig.startswith(c_raw):
            start_ms = raw_slice[raw_idx].get("start_ms", 0)
            end_ms = raw_slice[raw_idx].get("end_ms", 0)
            accum = c_raw
            raw_idx += 1
            while raw_idx < raw_len and len(accum) < len(c_orig):
                accum += raw_clean[raw_idx]
                end_ms = raw_slice[raw_idx].get("end_ms", end_ms)
                raw_idx += 1
            results.append({"word": orig, "start_ms": start_ms, "end_ms": end_ms})
        else:
            found_idx = -1
            for k in range(raw_idx + 1, min(raw_idx + 4, raw_len)):
                if raw_clean[k] == c_orig:
                    found_idx = k
                    break
            if found_idx != -1:
                raw_idx = found_idx
                results.append({
                    "word": orig,
                    "start_ms": raw_slice[raw_idx].get("start_ms", 0),
                    "end_ms": raw_slice[raw_idx].get("end_ms", 0),
                })
                raw_idx += 1
            else:
                results.append({
                    "word": orig,
                    "start_ms": raw_slice[raw_idx].get("start_ms", 0),
                    "end_ms": raw_slice[raw_idx].get("end_ms", 0),
                })
                raw_idx += 1

    return results


def build_sentences(transcript: str, words: list[dict[str, Any]]) -> list[Sentence]:
    """Splits the transcript into sentences and distributes aligned words
    deterministically by lexical word count (mirrors the Core alignment writer),
    preserving the original lexical tokens and punctuation."""
    raw = _SENTENCE_END.split(transcript.strip())
    texts = [part.strip() for part in raw if part.strip()] or [transcript.strip()]

    sentences: list[Sentence] = []
    global_index = 0
    previous_end: int | None = None
    for sentence_index, text in enumerate(texts):
        remaining_sentences = len(texts) - sentence_index - 1
        remaining_words = len(words) - global_index
        original_tokens = text.split()
        expected = max(1, len(original_tokens))
        take = min(expected, max(0, remaining_words - remaining_sentences))
        if remaining_sentences == 0:
            take = remaining_words

        raw_slice = words[global_index : global_index + take]
        global_index += len(raw_slice)

        matched = match_original_words_to_alignments(original_tokens, raw_slice)

        normalized = normalize_word_timestamps(
            matched,
            sentence_start_min=previous_end or 0,
            min_duration_ms=50,
        )

        sentence_words = [
            Word(position=pos, text=w_text, start_ms=w_start, end_ms=w_end)
            for pos, (w_text, w_start, w_end) in enumerate(normalized)
        ]

        start_ms = sentence_words[0].start_ms if sentence_words else None
        end_ms = sentence_words[-1].end_ms if sentence_words else None

        sentences.append(Sentence(
            position=sentence_index, text=text,
            start_ms=start_ms, end_ms=end_ms, words=sentence_words,
        ))
        if end_ms is not None:
            previous_end = end_ms
    return sentences


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8192), b""):
            digest.update(chunk)
    return digest.hexdigest()
