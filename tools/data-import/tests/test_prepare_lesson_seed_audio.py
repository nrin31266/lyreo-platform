from io import BytesIO
import wave
from unittest.mock import Mock

from scripts.prepare_lesson_seed_audio import has_audible_pcm, unavailable_keys


def wav(samples):
    buffer = BytesIO()
    with wave.open(buffer, "wb") as audio:
        audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        audio.writeframes(samples)
    return buffer.getvalue()


def test_seed_checks_local_pcm_and_detects_missing_audio(tmp_path, monkeypatch):
    monkeypatch.setenv("LOCAL_STORAGE_ROOT", str(tmp_path))
    (tmp_path / "silent.wav").write_bytes(wav(b"\x00\x00" * 32000))
    (tmp_path / "ready.wav").write_bytes(wav(b"\x01\x00" * 8192))
    assert list(unavailable_keys(["missing.wav", "silent.wav", "ready.wav"], "local")) == [
        "missing.wav", "silent.wav",
    ]
    assert not has_audible_pcm(BytesIO(b"invalid"))


def test_seed_reads_r2_audio_instead_of_using_object_size(monkeypatch):
    import release_media

    monkeypatch.setenv("R2_ENDPOINT", "https://fixture.invalid")
    monkeypatch.setenv("R2_BUCKET", "fixture")
    client = Mock()
    body = BytesIO(wav(b"\x00\x00" * 32000))
    client.get_object.return_value = {"Body": body}
    monkeypatch.setattr(release_media, "media_configuration", lambda: (client, "fixture"))
    assert list(unavailable_keys(["silent.wav"], "r2")) == ["silent.wav"]
    client.get_object.assert_called_once_with(Bucket="fixture", Key="silent.wav")
    assert body.closed
