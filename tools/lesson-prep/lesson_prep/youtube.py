"""YouTube acquisition for the Lesson Prep Tool (MVP VIDEO source).

The AI service must never receive a YouTube URL as a business source concept;
it only ever receives the extracted canonical audio reference.
"""

from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

import httpx

_YOUTUBE_ID_PATTERN = re.compile(
    r"(?:v=|/shorts/|youtu\.be/|/embed/|/live/)([A-Za-z0-9_-]{11})"
)


@dataclass(frozen=True)
class YoutubeMeta:
    video_id: str
    original_url: str
    title: str
    channel: str | None
    thumbnail_url: str | None


class YoutubeError(RuntimeError):
    pass


def normalize_youtube_url(url: str) -> str | None:
    """Extracts the 11-char video ID or returns None for invalid input."""
    match = _YOUTUBE_ID_PATTERN.search(url or "")
    if not match:
        return None
    video_id = match.group(1)
    return f"https://www.youtube.com/watch?v={video_id}"


def fetch_metadata(url: str) -> YoutubeMeta:
    try:
        import yt_dlp
    except ImportError as exc:
        raise YoutubeError("yt-dlp is not installed; run: uv sync") from exc

    options = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "noplaylist": True,
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as exc:
        raise YoutubeError(f"Unable to read YouTube metadata: {_safe(exc)}") from exc

    video_id = str(info.get("id") or "")
    if not video_id:
        raise YoutubeError("YouTube metadata did not contain a video ID")
    return YoutubeMeta(
        video_id=video_id,
        original_url=f"https://www.youtube.com/watch?v={video_id}",
        title=str(info.get("title") or "Untitled YouTube lesson"),
        channel=info.get("channel") or info.get("uploader"),
        thumbnail_url=info.get("thumbnail"),
    )


def extract_audio(url: str, work_dir: Path, sample_rate: int = 16000) -> Path:
    """Downloads the best audio-only stream with yt-dlp and normalizes it to mono
    WAV with ffmpeg (deterministic STT input)."""
    try:
        import yt_dlp
    except ImportError as exc:
        raise YoutubeError("yt-dlp is not installed; run: uv sync") from exc

    work_dir.mkdir(parents=True, exist_ok=True)
    raw_template = str(work_dir / "youtube-source.%(ext)s")
    options = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "format": "bestaudio[ext=m4a]/bestaudio/best",
        "outtmpl": raw_template,
    }
    try:
        with yt_dlp.YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
        requested = ydl.prepare_filename(info)
    except Exception as exc:
        raise YoutubeError(f"Unable to download YouTube audio: {_safe(exc)}") from exc

    source = Path(requested)
    if not source.exists():
        candidates = sorted(
            work_dir.glob("youtube-source.*"),
            key=lambda path: path.stat().st_size,
            reverse=True,
        )
        if not candidates:
            raise YoutubeError("yt-dlp completed but produced no audio artifact")
        source = candidates[0]

    wav = work_dir / "canonical-audio.wav"
    _ffmpeg_to_wav(source, wav, sample_rate)
    if not wav.exists() or wav.stat().st_size == 0:
        raise YoutubeError("ffmpeg produced no usable WAV audio; install ffmpeg and retry")
    return wav


def download_thumbnail(meta: YoutubeMeta, work_dir: Path) -> Path | None:
    if not meta.thumbnail_url:
        return None
    work_dir.mkdir(parents=True, exist_ok=True)
    target = work_dir / "thumbnail.jpg"
    try:
        response = httpx.get(meta.thumbnail_url, timeout=60, follow_redirects=True)
        response.raise_for_status()
        target.write_bytes(response.content)
        return target
    except httpx.HTTPError:
        return None


def _ffmpeg_to_wav(source: Path, target: Path, sample_rate: int) -> None:
    command = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", str(source),
        "-ac", "1", "-ar", str(sample_rate),
        str(target),
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=600)
    except FileNotFoundError as exc:
        raise YoutubeError("ffmpeg is not installed; install ffmpeg and retry") from exc
    except subprocess.TimeoutExpired as exc:
        raise YoutubeError("ffmpeg conversion timed out") from exc
    if result.returncode != 0:
        raise YoutubeError(f"ffmpeg failed: {result.stderr[:300]}")


def _safe(exc: Exception) -> str:
    text = str(exc) or exc.__class__.__name__
    return text[:300]
