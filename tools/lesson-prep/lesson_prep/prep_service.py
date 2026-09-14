"""Preparation state machine and workflow owner.

Business/orchestration logic lives here, never inside UI callbacks. The primary
final artifact is a portable *.lesson-source.zip containing lesson-source.json
and exact prepared media bytes.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import uuid
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .ai_service_client import AiServiceClient
from .models import (
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
from .package_writer import write_lesson_package
from .validation import validate_export
from .youtube import (
    YoutubeError,
    YoutubeMeta,
    download_thumbnail,
    extract_audio,
    fetch_metadata,
    normalize_youtube_url,
    sniff_image_type,
)

_SENTENCE_BOUNDARY_PATTERN = re.compile(r'([.!?]["\'”’\)\]}]*)\s+')

_HONORIFICS = {"mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st"}
_ABBREVIATIONS = {"e.g", "i.e", "vs", "etc"}


def split_into_sentences(transcript: str) -> list[str]:
    """Splits a transcript into individual sentence texts.

    Correctly handles:
    - Terminal punctuation followed by closing quotes or parentheses (e.g., 'said, "Fine." Next...')
    - Common abbreviations and titles (e.g., 'Dr. Smith', 'e.g. apples', '8:30 a.m. sharp')
    - Terminal abbreviation at sentence boundary (e.g., 'before 8:30 a.m. First, she...')
    """
    text = (transcript or "").strip()
    if not text:
        return []

    splits: list[tuple[int, int]] = []
    last_idx = 0

    for m in _SENTENCE_BOUNDARY_PATTERN.finditer(text):
        punct_with_closing = m.group(1)
        punct = punct_with_closing[0]
        end_punct = m.end(1)
        next_start = m.end()

        rest = text[next_start:]
        whitespace = text[end_punct:next_start]

        preceding = text[last_idx:m.start()].rstrip()
        word_match = re.search(r"([A-Za-z0-9.]+)$", preceding)
        word = word_match.group(1).lower() if word_match else ""

        rest_stripped = rest.lstrip("\"'“”‘’([{")

        if punct == ".":
            if word in _HONORIFICS:
                continue
            if word in _ABBREVIATIONS:
                continue
            if len(word) == 1 and word.isalpha():
                continue
            if word in {"a.m", "p.m"}:
                if "\n" not in whitespace and rest_stripped and rest_stripped[0].islower():
                    continue

        if "\n" not in whitespace and rest_stripped and rest_stripped[0].islower():
            continue

        splits.append((last_idx, end_punct))
        last_idx = next_start

    splits.append((last_idx, len(text)))
    sentences = [text[start:end].strip() for start, end in splits]
    return [s for s in sentences if s]


class PrepError(RuntimeError):
    pass


@dataclass
class AudioMetadata:
    path: Path
    content_type: str
    size_bytes: int
    sha256: str
    duration_ms: int


@dataclass
class CoverageMetrics:
    audio_duration_ms: int
    last_aligned_ms: int
    trailing_unaligned_ms: int
    coverage_pct: float
    warnings: list[str] = field(default_factory=list)


@dataclass
class PrepState:
    mode: str = "upload"  # upload | tts | youtube
    title: str = ""
    local_audio_path: Path | None = None
    audio_meta: AudioMetadata | None = None
    thumbnail_path: Path | None = None
    thumbnail_content_type: str | None = None
    transcript: str = ""
    sentences: list[Sentence] = field(default_factory=list)
    stt: SttPrep | None = None
    alignment: AlignPrep | None = None
    alignment_fresh: bool = False
    coverage: CoverageMetrics | None = None
    tts: TtsPrep | None = None
    youtube: YoutubeMeta | None = None


class LessonPrepService:
    def __init__(
        self,
        ai: AiServiceClient,
        work_dir: Path,
        run_id: str | None = None,
        max_duration_seconds: int = 300,
    ):
        self.ai = ai
        self.work_dir = work_dir.resolve()
        self.run_id = run_id or uuid.uuid4().hex[:12]
        self.run_dir = self.work_dir / self.run_id
        self.max_duration_seconds = max_duration_seconds
        self.state = PrepState()

    def ensure_run_dir(self) -> Path:
        """Lazily creates and returns the isolated run workspace directory."""
        self.run_dir.mkdir(parents=True, exist_ok=True)
        return self.run_dir

    def clear_work_dir(self) -> int:
        """Purges ONLY this service instance's owned run directory and resets state."""
        deleted_count = 0
        if self.run_dir.exists():
            for p in self.run_dir.rglob("*"):
                if p.is_file():
                    deleted_count += 1
            shutil.rmtree(self.run_dir, ignore_errors=True)
        self.state = PrepState()
        return deleted_count

    # ---------------------------------------------------------------- media inspection

    def inspect_audio(self, path: Path) -> AudioMetadata:
        """Inspects audio using ffprobe or wave header fallback, verifies <= 5min limit."""
        if not path.is_file():
            raise PrepError(f"Audio file not found: {path}")

        size_bytes = path.stat().st_size
        if size_bytes == 0:
            raise PrepError(f"Audio file is empty: {path}")

        sha256 = _sha256(path)
        duration_ms, content_type = _inspect_media_details(path)

        max_ms = self.max_duration_seconds * 1000
        if duration_ms > max_ms:
            raise PrepError(
                f"Audio duration ({duration_ms / 1000:.1f}s) exceeds the maximum supported "
                f"alignment limit of {self.max_duration_seconds}s (5 minutes). "
                f"This is the maximum duration of one prepared/aligned lesson source in the "
                f"current foundation. Please provide an audio clip under 5 minutes."
            )

        meta = AudioMetadata(
            path=path,
            content_type=content_type,
            size_bytes=size_bytes,
            sha256=sha256,
            duration_ms=duration_ms,
        )
        self.state.audio_meta = meta
        return meta

    # ---------------------------------------------------------------- audio inputs

    def select_uploaded_audio(self, source_path: str | Path) -> Path:
        source = Path(source_path)
        if not source.is_file():
            raise PrepError(f"Audio file not found: {source}")

        self.ensure_run_dir()
        target = self.run_dir / f"uploaded{source.suffix}"
        shutil.copyfile(source, target)
        self.state = PrepState(mode="upload")
        self.state.local_audio_path = target
        self.inspect_audio(target)
        return target

    def generate_audio_from_text(
        self, text: str, voice: str, accent: str, speed: float = 1.0
    ) -> Path:
        text = (text or "").strip()
        if not text:
            raise PrepError("Text is empty; nothing to synthesize")

        result = self.ai.tts(text, voice=voice, accent=accent, speed=speed)
        self.ensure_run_dir()
        target = self.run_dir / "tts-generated.wav"
        target.write_bytes(result["audio_bytes"])

        self.state = PrepState(mode="tts")
        self.state.local_audio_path = target
        self.state.transcript = text
        self.state.tts = TtsPrep(
            provider=result["provider"],
            model=result["model"],
            voice=result["voice"],
            accent=result["accent"],
            speed=float(speed),
        )
        self.inspect_audio(target)
        return target

    def prepare_youtube(self, url: str) -> YoutubeMeta:
        normalized = normalize_youtube_url(url)
        if not normalized:
            raise PrepError("Invalid YouTube URL; expected an 11-character video ID")

        meta = fetch_metadata(normalized)
        self.ensure_run_dir()
        audio = extract_audio(normalized, self.run_dir)
        self.inspect_audio(audio)

        thumb_result = download_thumbnail(meta, self.run_dir)
        thumb_path = thumb_result[0] if thumb_result else None
        thumb_type = thumb_result[1] if thumb_result else None

        self.state = PrepState(mode="youtube")
        self.state.youtube = meta
        self.state.local_audio_path = audio
        self.state.audio_meta = self.inspect_audio(audio)
        self.state.thumbnail_path = thumb_path
        self.state.thumbnail_content_type = thumb_type
        self.state.title = meta.title
        return meta

    # ---------------------------------------------------------------- STT / alignment

    def run_stt(self) -> dict[str, Any]:
        self._require_local_audio()
        audio_uri = self.state.local_audio_path.resolve().as_uri()
        result = self.ai.stt(audio_uri)
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
        self._require_local_audio()
        if not self.state.transcript.strip():
            raise PrepError("Transcript is empty; run STT or edit the transcript first")

        audio_meta = self.state.audio_meta or self.inspect_audio(self.state.local_audio_path)
        audio_uri = self.state.local_audio_path.resolve().as_uri()
        result = self.ai.align(audio_uri, self.state.transcript)
        raw_words = result["words"]

        sentences, repaired_count = build_sentences_with_monotonic_mapping(
            self.state.transcript, raw_words, audio_duration_ms=audio_meta.duration_ms
        )
        self.state.sentences = sentences
        self.state.alignment = AlignPrep(
            provider=result["provider"],
            model=result["model"],
            normalized=True,
            repairedWordCount=repaired_count,
        )
        self.state.alignment_fresh = True
        self.state.coverage = compute_coverage(sentences, audio_meta.duration_ms, self.state.transcript)
        return raw_words

    def _invalidate_alignment(self) -> None:
        self.state.alignment_fresh = False
        self.state.sentences = []
        self.state.alignment = None
        self.state.coverage = None

    def _require_local_audio(self) -> None:
        if not self.state.local_audio_path or not self.state.local_audio_path.is_file():
            raise PrepError("No local audio available; provide an audio source first")

    # ---------------------------------------------------------------- export readiness

    def export_readiness(self) -> tuple[bool, list[str]]:
        problems: list[str] = []
        if not self.state.title.strip():
            problems.append("title is missing")
        if not self.state.local_audio_path or not self.state.local_audio_path.is_file():
            problems.append("local audio is missing")
        if not self.state.transcript.strip():
            problems.append("transcript is missing (run STT or generate from text)")
        if not self.state.alignment_fresh:
            problems.append("alignment is stale or missing; rerun alignment")
        if self.state.mode == "youtube" and self.state.youtube is None:
            problems.append("YouTube metadata is missing")

        # Check coverage warnings for potential blocking issues
        if self.state.coverage:
            for warn in self.state.coverage.warnings:
                if "strongly suspicious" in warn.lower() or "truncated" in warn.lower():
                    problems.append(warn)

        if problems:
            return False, problems

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
        audio_meta = self.state.audio_meta or self.inspect_audio(self.state.local_audio_path)

        if self.state.mode == "youtube":
            source = SourceBlock(
                kind="VIDEO",
                origin="YOUTUBE",
                externalId=self.state.youtube.video_id,
                originalUrl=self.state.youtube.original_url,
                title=self.state.title,
                channel=self.state.youtube.channel,
            )
        else:
            source = SourceBlock(
                kind="AUDIO",
                origin="TTS_GENERATED" if self.state.mode == "tts" else "UPLOAD",
                externalId=None,
                originalUrl=None,
                title=self.state.title,
            )

        audio_ext = self.state.local_audio_path.suffix.lower() or ".wav"
        audio_item = AudioMediaItem(
            path=f"media/audio{audio_ext}",
            contentType=audio_meta.content_type,
            sizeBytes=audio_meta.size_bytes,
            sha256=audio_meta.sha256,
            durationMs=audio_meta.duration_ms,
        )

        thumbnail_item = None
        if self.state.thumbnail_path and self.state.thumbnail_path.is_file():
            thumb_ext = self.state.thumbnail_path.suffix.lower() or ".jpg"
            thumb_bytes = self.state.thumbnail_path.read_bytes()
            sniffed_type, _ = sniff_image_type(thumb_bytes)
            content_type = self.state.thumbnail_content_type or sniffed_type
            thumbnail_item = ThumbnailMediaItem(
                path=f"media/thumbnail{thumb_ext}",
                contentType=content_type,
                sizeBytes=len(thumb_bytes),
                sha256=hashlib.sha256(thumb_bytes).hexdigest(),
            )

        media = MediaBlock(audio=audio_item, thumbnail=thumbnail_item)
        preparation = PreparationBlock(
            stt=self.state.stt,
            alignment=self.state.alignment,
            tts=self.state.tts,
        )
        content = ContentBlock(text=self.state.transcript, sentences=self.state.sentences)

        source_model = PreparedSource(
            source=source,
            media=media,
            content=content,
            preparation=preparation,
        )
        problems = validate_export(source_model)
        if problems:
            raise PrepError("Export validation failed: " + "; ".join(problems))
        return source_model

    def export_package(self, export_dir: Path | None = None) -> Path:
        """Exports the primary portable artifact: a verified *.lesson-source.zip package.

        If export_dir is None, stages the package inside the session workspace:
        .work/<run-id>/export/<name>.lesson-source.zip
        """
        source = self.build_export()
        target_dir = export_dir if export_dir is not None else (self.ensure_run_dir() / "export")
        target_dir.mkdir(parents=True, exist_ok=True)
        return write_lesson_package(
            source=source,
            local_audio_path=self.state.local_audio_path,
            export_dir=target_dir,
            local_thumbnail_path=self.state.thumbnail_path,
        )

    def export_json(self) -> str:
        """Convenience method for JSON preview in the UI."""
        return json.dumps(self.build_export().export_dict(), indent=2, ensure_ascii=False)


# ---------------------------------------------------------------- Media inspection helpers


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8192), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _inspect_media_details(path: Path) -> tuple[int, str]:
    """Inspects media duration in milliseconds and MIME type using ffprobe with wave fallback."""
    # 1. Try ffprobe
    try:
        cmd = [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "format=duration,format_name:stream=codec_name",
            "-of",
            "json",
            str(path),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
        if result.returncode == 0:
            parsed = json.loads(result.stdout)
            format_info = parsed.get("format", {})
            duration_sec = float(format_info.get("duration", 0))
            duration_ms = round(duration_sec * 1000)
            format_name = format_info.get("format_name", "").lower()

            if "wav" in format_name:
                content_type = "audio/wav"
            elif "mp3" in format_name:
                content_type = "audio/mpeg"
            elif "mp4" in format_name or "m4a" in format_name:
                content_type = "audio/mp4"
            elif "ogg" in format_name:
                content_type = "audio/ogg"
            else:
                content_type = "audio/wav"

            if duration_ms > 0:
                return duration_ms, content_type
    except Exception:
        pass

    # 2. Fallback for standard WAV files
    try:
        with wave.open(str(path), "rb") as wf:
            frames = wf.getnframes()
            rate = wf.getframerate()
            if rate > 0:
                return round((frames / float(rate)) * 1000), "audio/wav"
    except Exception:
        pass

    # 3. Last-resort estimation based on file extension
    ext = path.suffix.lower()
    content_type = "audio/wav" if ext == ".wav" else f"audio/{ext.lstrip('.')}"
    return 1000, content_type


# ---------------------------------------------------------------- Monotonic Alignment Mapping


def _clean_token(s: str) -> str:
    return re.sub(r"[^a-zA-Z0-9]", "", s).lower()


def build_sentences_with_monotonic_mapping(
    transcript: str,
    raw_words: list[dict[str, Any]],
    audio_duration_ms: int,
) -> tuple[list[Sentence], int]:
    """Maps original display tokens from the transcript monotonically against the
    full raw alignment token stream, preserving punctuation, contractions, and word shapes.

    Groups mapped tokens into sentences and applies bounded timestamp repair.
    Returns (sentences, total_repaired_word_count).
    """
    sentence_texts = split_into_sentences(transcript) or [transcript.strip()]

    # Collect all display tokens across all sentences with sentence indices
    token_entries: list[tuple[int, str]] = []
    for s_idx, s_text in enumerate(sentence_texts):
        for tok in s_text.split():
            token_entries.append((s_idx, tok))

    if not token_entries:
        return [], 0

    total_tokens = len(token_entries)
    raw_len = len(raw_words)

    mapped_words: list[tuple[int, str, int, int]] = []
    raw_idx = 0

    for tok_idx, (s_idx, orig_tok) in enumerate(token_entries):
        c_orig = _clean_token(orig_tok)

        if raw_idx >= raw_len:
            last_end = mapped_words[-1][3] if mapped_words else 0
            mapped_words.append((s_idx, orig_tok, last_end, last_end))
            continue

        c_raw = _clean_token(str(raw_words[raw_idx].get("word", "")))

        # Case 1: Exact clean token match
        if c_orig == c_raw:
            s_ms = int(raw_words[raw_idx].get("start_ms", 0))
            e_ms = int(raw_words[raw_idx].get("end_ms", 0))
            mapped_words.append((s_idx, orig_tok, s_ms, e_ms))
            raw_idx += 1

        # Case 2: One original word expanded into multiple aligner tokens (e.g. ice-cream -> ice, cream)
        elif c_orig.startswith(c_raw) and c_raw != "":
            start_ms = int(raw_words[raw_idx].get("start_ms", 0))
            end_ms = int(raw_words[raw_idx].get("end_ms", 0))
            accum = c_raw
            raw_idx += 1
            while raw_idx < raw_len and len(accum) < len(c_orig):
                next_raw = _clean_token(str(raw_words[raw_idx].get("word", "")))
                accum += next_raw
                end_ms = int(raw_words[raw_idx].get("end_ms", end_ms))
                raw_idx += 1
            mapped_words.append((s_idx, orig_tok, start_ms, end_ms))

        # Case 3: Token mismatch — look ahead up to 4 aligner tokens to re-sync
        else:
            found_idx = -1
            for k in range(raw_idx + 1, min(raw_idx + 5, raw_len)):
                if _clean_token(str(raw_words[k].get("word", ""))) == c_orig:
                    found_idx = k
                    break
            if found_idx != -1:
                raw_idx = found_idx
                s_ms = int(raw_words[raw_idx].get("start_ms", 0))
                e_ms = int(raw_words[raw_idx].get("end_ms", 0))
                mapped_words.append((s_idx, orig_tok, s_ms, e_ms))
                raw_idx += 1
            else:
                s_ms = int(raw_words[raw_idx].get("start_ms", 0))
                e_ms = int(raw_words[raw_idx].get("end_ms", 0))
                mapped_words.append((s_idx, orig_tok, s_ms, e_ms))
                raw_idx += 1

    # Group mapped tokens by sentence index
    sentence_tokens_map: dict[int, list[tuple[str, int, int]]] = {
        idx: [] for idx in range(len(sentence_texts))
    }
    for s_idx, tok_text, s_ms, e_ms in mapped_words:
        sentence_tokens_map[s_idx].append((tok_text, s_ms, e_ms))

    # Apply bounded monotonic timestamp repair per sentence
    sentences: list[Sentence] = []
    total_repaired = 0
    previous_end = 0

    for s_idx, s_text in enumerate(sentence_texts):
        tokens = sentence_tokens_map[s_idx]
        normalized_words, repaired_count = normalize_word_timestamps(
            tokens,
            sentence_start_min=previous_end,
            audio_duration_ms=audio_duration_ms,
            min_duration_ms=50,
        )
        total_repaired += repaired_count

        word_models = [
            Word(position=pos, text=w_text, startMs=w_start, endMs=w_end)
            for pos, (w_text, w_start, w_end) in enumerate(normalized_words)
        ]

        start_ms = word_models[0].start_ms if word_models else None
        end_ms = word_models[-1].end_ms if word_models else None

        sentences.append(
            Sentence(
                position=s_idx,
                text=s_text,
                startMs=start_ms,
                endMs=end_ms,
                words=word_models,
            )
        )
        if end_ms is not None:
            previous_end = end_ms

    return sentences, total_repaired


def normalize_word_timestamps(
    tokens: list[tuple[str, int, int]],
    sentence_start_min: int,
    audio_duration_ms: int,
    min_duration_ms: int = 50,
) -> tuple[list[tuple[str, int, int]], int]:
    """Applies bounded normalization so every word has positive duration, monotonic
    non-overlapping timestamps, and remains within the audio duration.
    Returns (normalized_tokens, repaired_count).
    """
    if not tokens:
        return [], 0

    n = len(tokens)
    texts = [t[0] for t in tokens]
    starts = [max(0, t[1]) for t in tokens]
    ends = [max(0, t[2]) for t in tokens]

    repaired_count = 0

    # 1. Forward pass: ensure monotonic sequence starting at sentence_start_min
    cur = sentence_start_min
    for i in range(n):
        orig_s, orig_e = starts[i], ends[i]
        starts[i] = max(starts[i], cur)
        ends[i] = max(ends[i], starts[i])
        cur = starts[i]

    # 2. Repair zero or sub-minimum duration words
    for i in range(n):
        if ends[i] - starts[i] < min_duration_ms:
            repaired_count += 1
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

    # 3. Final clamp: non-overlapping, strictly positive, bounded by audio_duration_ms
    result: list[tuple[str, int, int]] = []
    cur = sentence_start_min
    for i in range(n):
        s = max(starts[i], cur)
        e = max(ends[i], s + min_duration_ms)
        # Clamp to audio_duration_ms
        if e > audio_duration_ms:
            e = audio_duration_ms
            if s >= e:
                s = max(0, e - min_duration_ms)
        result.append((texts[i], s, e))
        cur = e

    return result, repaired_count


# ---------------------------------------------------------------- Completeness / Coverage detection


def compute_coverage(
    sentences: list[Sentence], audio_duration_ms: int, transcript: str
) -> CoverageMetrics:
    """Computes alignment coverage and detects suspicious truncation."""
    last_aligned = sentences[-1].end_ms if (sentences and sentences[-1].end_ms is not None) else 0
    trailing_unaligned = max(0, audio_duration_ms - last_aligned)
    coverage_pct = (last_aligned / audio_duration_ms * 100) if audio_duration_ms > 0 else 0.0

    warnings: list[str] = []
    # Check if transcript ends abruptly without ending punctuation
    stripped = transcript.strip()
    ends_without_punct = stripped and stripped[-1] not in ".!?:;"

    if trailing_unaligned > 15000 and coverage_pct < 85.0:
        if ends_without_punct:
            warnings.append(
                f"Transcript appears truncated mid-sentence ({trailing_unaligned / 1000:.1f}s unaligned "
                f"trailing gap, {coverage_pct:.1f}% coverage). Check STT max_new_tokens or input audio."
            )
        else:
            warnings.append(
                f"Significant trailing unaligned audio ({trailing_unaligned / 1000:.1f}s trailing gap, "
                f"{coverage_pct:.1f}% coverage). Video may contain trailing silence/music."
            )
    elif ends_without_punct and trailing_unaligned > 5000:
        warnings.append(
            f"Transcript may be incomplete (ends without punctuation with {trailing_unaligned / 1000:.1f}s trailing gap)."
        )

    return CoverageMetrics(
        audio_duration_ms=audio_duration_ms,
        last_aligned_ms=last_aligned,
        trailing_unaligned_ms=trailing_unaligned,
        coverage_pct=round(coverage_pct, 1),
        warnings=warnings,
    )
