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
    r"(?:v=|/v/|/shorts/|youtu\.be/|/embed/|/live/)([A-Za-z0-9_-]{11})"
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


def extract_youtube_id(url: str) -> str | None:
    """Extracts the 11-char video ID or returns None for invalid input."""
    match = _YOUTUBE_ID_PATTERN.search((url or "").strip())
    return match.group(1) if match else None


def normalize_youtube_url(url: str) -> str | None:
    """Normalizes any valid YouTube URL variant to canonical watch form, or None."""
    video_id = extract_youtube_id(url)
    if not video_id:
        return None
    return f"https://www.youtube.com/watch?v={video_id}"



def sniff_image_type(data: bytes) -> tuple[str, str]:
    """Sniffs image type from magic bytes. Returns (contentType, extension_with_dot)."""
    if len(data) >= 3 and data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg", ".jpg"
    if len(data) >= 8 and data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png", ".png"
    if len(data) >= 12 and data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp", ".webp"
    return "image/jpeg", ".jpg"


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


def extract_audio(url: str, run_dir: Path, sample_rate: int = 16000) -> Path:
    """Downloads the best audio-only stream with yt-dlp and normalizes it to mono
    WAV with ffmpeg (deterministic STT input)."""
    try:
        import yt_dlp
    except ImportError as exc:
        raise YoutubeError("yt-dlp is not installed; run: uv sync") from exc

    run_dir.mkdir(parents=True, exist_ok=True)

    # 1. Purge any previous download candidates in this run directory to avoid stale files
    for old_file in list(run_dir.glob("youtube-source.*")):
        try:
            old_file.unlink()
        except OSError:
            pass
    old_wav = run_dir / "canonical-audio.wav"
    if old_wav.exists():
        try:
            old_wav.unlink()
        except OSError:
            pass

    raw_template = str(run_dir / "youtube-source.%(ext)s")
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
            run_dir.glob("youtube-source.*"),
            key=lambda path: path.stat().st_size,
            reverse=True,
        )
        if not candidates:
            raise YoutubeError("yt-dlp completed but produced no audio artifact")
        source = candidates[0]

    wav = run_dir / "canonical-audio.wav"
    _ffmpeg_to_wav(source, wav, sample_rate)
    if not wav.exists() or wav.stat().st_size == 0:
        raise YoutubeError("ffmpeg produced no usable WAV audio; install ffmpeg and retry")
    return wav


def download_thumbnail(meta: YoutubeMeta, run_dir: Path) -> tuple[Path, str] | None:
    """Downloads thumbnail, inspects bytes for correct image format, and saves with proper extension.
    Returns (path, content_type) or None."""
    if not meta.thumbnail_url:
        return None
    run_dir.mkdir(parents=True, exist_ok=True)

    # Clean old thumbnail artifacts
    for old_thumb in list(run_dir.glob("thumbnail.*")):
        try:
            old_thumb.unlink()
        except OSError:
            pass

    try:
        response = httpx.get(meta.thumbnail_url, timeout=60, follow_redirects=True)
        response.raise_for_status()
        data = response.content
        content_type, ext = sniff_image_type(data)
        target = run_dir / f"thumbnail{ext}"
        target.write_bytes(data)
        return target, content_type
    except (httpx.HTTPError, OSError):
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
