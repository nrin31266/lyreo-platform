from pathlib import Path

import pytest

from lesson_prep.youtube import (
    YoutubeError,
    extract_audio,
    normalize_youtube_url,
)


def test_normalize_youtube_url_variants():
    assert normalize_youtube_url("https://www.youtube.com/watch?v=dQw4w9WgXcQ") \
        == "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    assert normalize_youtube_url("https://youtu.be/dQw4w9WgXcQ") \
        == "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    assert normalize_youtube_url("https://www.youtube.com/shorts/dQw4w9WgXcQ") \
        == "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    assert normalize_youtube_url("not a url") is None
    assert normalize_youtube_url("") is None


def test_extract_audio_runs_ffmpeg_when_ytdlp_outputs(tmp_path, monkeypatch):
    class FakeYdl:
        def __init__(self, options=None):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return None

        def extract_info(self, url, download):
            return {"id": "x" * 11, "title": "t"}

        def prepare_filename(self, info):
            return str(tmp_path / "youtube-source.m4a")

    import sys
    monkeypatch.setitem(sys.modules, "yt_dlp", type("m", (), {"YoutubeDL": FakeYdl}))
    (tmp_path / "youtube-source.m4a").write_bytes(b"fake-m4a")

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
            return {"id": "x" * 11}

        def prepare_filename(self, info):
            return str(tmp_path / "youtube-source.m4a")

    import sys
    monkeypatch.setitem(sys.modules, "yt_dlp", type("m", (), {"YoutubeDL": FakeYdl}))
    (tmp_path / "youtube-source.m4a").write_bytes(b"fake-m4a")

    def fake_run(command, **kwargs):
        raise FileNotFoundError("ffmpeg")

    monkeypatch.setattr("lesson_prep.youtube.subprocess.run", fake_run)
    with pytest.raises(YoutubeError, match="ffmpeg is not installed"):
        extract_audio("https://www.youtube.com/watch?v=dQw4w9WgXcQ", tmp_path)
