from pathlib import Path

import httpx
import pytest

from lesson_prep.youtube import (
    YoutubeError,
    YoutubeMeta,
    download_thumbnail,
    extract_audio,
    extract_youtube_id,
    normalize_youtube_url,
    sniff_image_type,
)


def test_normalize_youtube_url_variants():
    assert (
        normalize_youtube_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        == "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    )
    assert (
        normalize_youtube_url("https://youtu.be/dQw4w9WgXcQ")
        == "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    )
    assert (
        normalize_youtube_url("https://www.youtube.com/shorts/dQw4w9WgXcQ")
        == "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    )
    assert normalize_youtube_url("not a url") is None
    assert normalize_youtube_url("") is None


def test_extract_youtube_id_variants():
    assert extract_youtube_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_youtube_id("https://youtu.be/dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_youtube_id("https://www.youtube.com/embed/dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_youtube_id("https://www.youtube.com/v/dQw4w9WgXcQ") == "dQw4w9WgXcQ"
    assert extract_youtube_id("not a url") is None



def test_sniff_image_type():
    # JPEG
    assert sniff_image_type(b"\xff\xd8\xff\xe0extra") == ("image/jpeg", ".jpg")
    # PNG
    assert sniff_image_type(b"\x89PNG\r\n\x1a\nheader") == ("image/png", ".png")
    # WEBP
    webp_bytes = b"RIFF\x00\x00\x00\x00WEBPVP8 "
    assert sniff_image_type(webp_bytes) == ("image/webp", ".webp")
    # Unknown fallback
    assert sniff_image_type(b"randombytes") == ("image/jpeg", ".jpg")


def test_download_thumbnail_sniffs_webp(tmp_path, monkeypatch):
    meta = YoutubeMeta(
        video_id="dQw4w9WgXcQ",
        original_url="https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        title="Rick",
        channel="Rick",
        thumbnail_url="https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.webp",
    )
    webp_bytes = b"RIFF\x20\x00\x00\x00WEBPVP8L"

    def mock_get(url, **kwargs):
        req = httpx.Request("GET", str(url))
        return httpx.Response(200, content=webp_bytes, request=req)

    monkeypatch.setattr("httpx.get", mock_get)

    result = download_thumbnail(meta, tmp_path)
    assert result is not None
    path, content_type = result
    assert content_type == "image/webp"
    assert path.suffix == ".webp"
    assert path.exists()
    assert path.read_bytes() == webp_bytes


def test_extract_audio_runs_ffmpeg_when_ytdlp_outputs(tmp_path, monkeypatch):
    class FakeYdl:
        def __init__(self, options=None):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return None

        def extract_info(self, url, download):
            (tmp_path / "youtube-source.m4a").write_bytes(b"fake-m4a")
            return {"id": "x" * 11, "title": "t"}

        def prepare_filename(self, info):
            return str(tmp_path / "youtube-source.m4a")

    import sys

    monkeypatch.setitem(sys.modules, "yt_dlp", type("m", (), {"YoutubeDL": FakeYdl}))

    calls = []

    def fake_run(command, **kwargs):
        calls.append(command)
        Path(command[-1]).write_bytes(b"RIFFwav")
        return type("R", (), {"returncode": 0, "stderr": ""})()

    monkeypatch.setattr("lesson_prep.youtube.subprocess.run", fake_run)

    wav = extract_audio("https://www.youtube.com/watch?v=dQw4w9WgXcQ", tmp_path)
    assert wav.exists()
    assert calls and calls[0][0] == "ffmpeg"
    assert "-ar" in calls[0] and "16000" in calls[0]


def test_extract_audio_missing_ffmpeg_raises_clear_error(tmp_path, monkeypatch):
    class FakeYdl:
        def __init__(self, options=None):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return None

        def extract_info(self, url, download):
            (tmp_path / "youtube-source.m4a").write_bytes(b"fake-m4a")
            return {"id": "x" * 11}

        def prepare_filename(self, info):
            return str(tmp_path / "youtube-source.m4a")

    import sys

    monkeypatch.setitem(sys.modules, "yt_dlp", type("m", (), {"YoutubeDL": FakeYdl}))

    def fake_run(command, **kwargs):
        raise FileNotFoundError("ffmpeg")

    monkeypatch.setattr("lesson_prep.youtube.subprocess.run", fake_run)
    with pytest.raises(YoutubeError, match="ffmpeg is not installed"):
        extract_audio("https://www.youtube.com/watch?v=dQw4w9WgXcQ", tmp_path)
