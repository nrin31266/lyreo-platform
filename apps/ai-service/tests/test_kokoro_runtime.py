import io
import wave
import asyncio
import base64
import sys
import threading
from types import SimpleNamespace

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
    assert sum(v['gender'] == 'FEMALE' for v in voices) == 15
    assert sum(v['gender'] == 'MALE' for v in voices) == 13
    assert next(v for v in voices if v['voice_id'] == 'af_heart')['display']['name'] == 'Heart'
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


def test_sentence_synthesis_returns_observed_pcm_boundaries(runtime):
    runtime._synthesize = MagicMock(side_effect=[([1] * 2400, 1), ([2] * 4800, 2)])
    samples, chunks, segments = runtime._synthesize_sentences(None, None, ['First.', 'Second.'], 1.0)
    assert samples == [1] * 2400 + [2] * 4800
    assert chunks == 3
    assert segments == [
        {'text': 'First.', 'start_ms': 0, 'end_ms': 100},
        {'text': 'Second.', 'start_ms': 100, 'end_ms': 300},
    ]


def test_sentence_synthesis_stops_when_one_sentence_exceeds_alignment_window(runtime):
    runtime._synthesize = MagicMock(return_value=([0] * (runtime.cfg.kokoro_sample_rate * 116), 1))
    with pytest.raises(ValueError, match='Split long text sentences'):
        runtime._synthesize_sentences(None, None, ['Long sentence.'], 1.0)


@pytest.mark.asyncio
async def test_sentence_text_must_match_before_loading_model(runtime):
    from app.schemas import ExecuteRequest
    request = ExecuteRequest(invocation_id='1', provider='LOCAL_KOKORO', model='kokoro',
                             input={'text': 'Original.', 'sentences': ['Different.']}, options={})
    with pytest.raises(ValueError, match='cover input.text'):
        await runtime.tts(request)


@pytest.mark.asyncio
async def test_us_uk_tts_share_one_model_and_cache_voices(runtime, monkeypatch):
    from app.schemas import ExecuteRequest

    model = object()
    build_model = MagicMock(return_value=model)
    pipelines = []

    def pipeline(**kwargs):
        result = SimpleNamespace(model=None, load_voice=MagicMock(return_value=object()))
        pipelines.append(result)
        return result

    monkeypatch.setitem(sys.modules, 'kokoro', SimpleNamespace(KPipeline=pipeline))
    monkeypatch.setattr('app.runtime.kokoro._build_model', build_model)
    runtime._synthesize = MagicMock(return_value=([1, 2, 3], 1))

    def request(voice):
        return ExecuteRequest(invocation_id=voice, provider='LOCAL_KOKORO', model='kokoro',
                              input={'text': 'Hello'}, options={'voice': voice})

    responses = await asyncio.gather(
        runtime.tts(request('af_heart')), runtime.tts(request('bf_emma')),
        runtime.tts(request('af_heart')),
    )
    build_model.assert_called_once_with(runtime.cfg.kokoro_model, 'cpu')
    assert len(pipelines) == 2
    assert all(p.model is model for p in pipelines)
    assert all(p.load_voice.call_count == 1 for p in pipelines)
    assert runtime._synthesize.call_args_list[0].args[1] is runtime._synthesize.call_args_list[2].args[1]
    for response in responses:
        with wave.open(io.BytesIO(base64.b64decode(response.output['audio_base64']))) as wav:
            assert wav.getnframes() == 3
            assert wav.getframerate() == runtime.cfg.kokoro_sample_rate


@pytest.mark.asyncio
async def test_cancelled_tts_finishes_worker_before_next_synthesis(runtime, monkeypatch):
    from app.schemas import ExecuteRequest
    from unittest.mock import AsyncMock

    monkeypatch.setattr(runtime, '_load', AsyncMock(return_value=object()))
    monkeypatch.setattr(runtime, '_load_voice', AsyncMock(return_value=object()))
    started, finish = threading.Event(), threading.Event()
    calls = []

    def synthesize(*_):
        calls.append('start')
        started.set()
        assert finish.wait(5)
        calls.append('end')
        return [1], 1

    monkeypatch.setattr(runtime, '_synthesize', synthesize)
    request = ExecuteRequest(invocation_id='1', provider='LOCAL_KOKORO', model='kokoro',
                             input={'text': 'Hello'}, options={})
    first = asyncio.create_task(runtime.tts(request))
    second = None
    try:
        assert await asyncio.to_thread(started.wait, 5)
        first.cancel()
        second = asyncio.create_task(runtime.tts(request))
        await asyncio.sleep(0.02)
        assert calls == ['start']
        assert not first.done()
        assert not second.done()
    finally:
        finish.set()
        with pytest.raises(asyncio.CancelledError):
            await first
        if second is not None:
            await second
    assert calls == ['start', 'end', 'start', 'end']
