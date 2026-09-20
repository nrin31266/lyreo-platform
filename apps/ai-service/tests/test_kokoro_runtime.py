import io
import wave

from unittest.mock import MagicMock

import pytest

from app.config import Settings
from app.runtime.kokoro import (
    KOKORO_VOICES,
    KokoroRuntime,
    _to_wav,
)


@pytest.fixture
def runtime():
    return KokoroRuntime(Settings(_env_file=None))


def test_voice_discovery_serves_supported_local_voices(runtime):
    voices = runtime.voices()
    assert voices
    assert all(item['provider'] == 'LOCAL_KOKORO' for item in voices)
    assert all(item['language'] == 'English' for item in voices)
    ids = {item['voice_id'] for item in voices}
    assert ids == set(KOKORO_VOICES)
    assert {'af_heart', 'bf_emma', 'am_adam'} <= ids
    us = [item for item in voices if item['voice_id'] == 'af_heart']
    uk = [item for item in voices if item['voice_id'] == 'bf_emma']
    assert us and us[0]['accent'] == 'US'
    assert uk and uk[0]['accent'] == 'UK'


def test_unknown_voice_is_rejected_before_any_model_load(runtime):
    with pytest.raises(ValueError, match='Unsupported Kokoro voice'):
        runtime._validate_voice('af_nonexistent')


def test_voice_accent_mismatch_is_rejected(runtime):
    with pytest.raises(ValueError, match='not US English'):
        runtime.resolve_voice('bf_emma', 'US')


def test_voice_accent_resolution_without_requested_accent(runtime):
    voice_id, accent = runtime.resolve_voice('af_heart', '')
    assert voice_id == 'af_heart'
    assert accent == 'US'


def test_invalid_speed_is_rejected_before_model_load():
    from app.runtime.kokoro import _normalize_speed

    with pytest.raises(ValueError, match='outside the safe range'):
        _normalize_speed(5.0)
    with pytest.raises(ValueError, match='Invalid Kokoro speed'):
        _normalize_speed('fast')


def test_synthesize_combines_multiple_pipeline_results_in_order(runtime):
    class FakeResult:
        def __init__(self, audio_data):
            self.audio = MagicMock()
            self.audio.detach.return_value.cpu.return_value.numpy.return_value.reshape.return_value.tolist.return_value = audio_data

    seg_a = [0.1, 0.2]
    seg_b = [0.3, 0.4]
    seg_c = [0.5, 0.6]

    fake_pipeline = MagicMock()
    fake_pipeline.return_value = [FakeResult(seg_a), FakeResult(seg_b), FakeResult(seg_c)]

    samples, count = runtime._synthesize(fake_pipeline, "voice", "text", 1.0)
    assert count == 3
    expected = [round(v * 32767) for v in seg_a + seg_b + seg_c]
    assert samples == expected
    assert len(samples) == len(seg_a) + len(seg_b) + len(seg_c)

    wav = _to_wav(samples, sample_rate=24000)
    with wave.open(io.BytesIO(wav), "rb") as w:
        assert w.getnchannels() == 1
        assert w.getsampwidth() == 2
        assert w.getframerate() == 24000
        assert w.getnframes() == len(expected)


def test_synthesize_single_pipeline_result(runtime):
    class FakeResult:
        def __init__(self, audio_data):
            self.audio = MagicMock()
            self.audio.detach.return_value.cpu.return_value.numpy.return_value.reshape.return_value.tolist.return_value = audio_data

    fake_pipeline = MagicMock()
    fake_pipeline.return_value = [FakeResult([0.1, -0.2])]

    samples, count = runtime._synthesize(fake_pipeline, "voice", "text", 1.0)
    assert count == 1
    assert samples == [round(0.1 * 32767), round(-0.2 * 32767)]


@pytest.mark.asyncio
async def test_tts_rejects_blank_input(runtime):
    from app.schemas import ExecuteRequest

    req = ExecuteRequest(
        invocation_id="1", provider="LOCAL_KOKORO", model="kokoro",
        input={"text": "   "}, options={},
    )
    with pytest.raises(ValueError, match="Kokoro TTS requires input.text"):
        await runtime.tts(req)


def test_kokoro_voice_catalog_integrity(runtime):
    voices = runtime.voices()
    assert len(voices) == 28
    ids = {v["voice_id"] for v in voices}
    # Stale/invalid voices must not be in the catalog
    assert "am_emma" not in ids
    assert "am_isa" not in ids
    assert "am_george" not in ids
    # Correct voices must be present
    assert {"af_heart", "af_alloy", "af_bella", "am_adam", "am_echo", "bf_alice", "bm_daniel"} <= ids
    # Accent matches prefix
    for v in voices:
        if v["voice_id"].startswith("a"):
            assert v["accent"] == "US"
        elif v["voice_id"].startswith("b"):
            assert v["accent"] == "UK"
