import asyncio
import base64
import sys
from unittest.mock import AsyncMock
from types import SimpleNamespace

import pytest

from app.config import Settings
from app.runtime.whisperx import WhisperXRuntime
from app.schemas import ExecuteRequest


def test_whisperx_uses_stt_anchors_for_separate_alignment(monkeypatch):
    runtime = WhisperXRuntime(Settings(_env_file=None))
    waveform = [0.0] * 64_000
    monkeypatch.setattr(runtime, '_audio', AsyncMock(return_value=waveform))
    segments = [{'text': 'First.', 'start_ms': 0, 'end_ms': 1000},
                {'text': 'Second.', 'start_ms': 2000, 'end_ms': 3000}]
    monkeypatch.setattr(runtime, '_transcribe', lambda audio: segments)

    def force_align(audio, padded):
        assert audio is waveform
        assert padded == [{'text': 'First.', 'start': 0.0, 'end': 1.5},
                          {'text': 'Second.', 'start': 1.5, 'end': 3.5}]
        return [{'index': 0, 'word': 'First.', 'start_ms': 90, 'end_ms': 500}]

    monkeypatch.setattr(runtime, '_force_align', force_align)
    request = ExecuteRequest(invocation_id='1', provider='LOCAL_WHISPERX',
                             model='small.en', input={'audio_url': 'https://media.example/a.wav'})
    result = asyncio.run(runtime.stt(request))
    assert result.output == {'text': 'First. Second.', 'language': 'en', 'segments': segments, 'timestamps': []}

    align_request = request.model_copy(update={'input': {'text': result.output['text'], 'segments': segments}})
    aligned = asyncio.run(runtime.align(align_request))
    assert aligned.output['words'][0]['start_ms'] == 90
    assert aligned.metadata['model'] == 'WAV2VEC2_ASR_BASE_960H'

    align_request.input['text'] = 'Edited transcript.'
    with pytest.raises(ValueError, match='complete approved transcript'):
        asyncio.run(runtime.align(align_request))


def test_whisperx_retries_full_audio_when_vad_finds_no_speech(monkeypatch):
    class FakeModel:
        model = SimpleNamespace(model_is_loaded=True)

        def transcribe(self, waveform, **options):
            assert options['vad_filter'] is False
            return iter([SimpleNamespace(text='Lyrics continue', start=0.2, end=1.4)]), None

        def unload_model(self, to_cpu=True):
            return None

    class FakePipeline:
        model = FakeModel()

        def transcribe(self, *args, **kwargs):
            return {'segments': []}

    monkeypatch.setitem(sys.modules, 'whisperx', SimpleNamespace(
        load_model=lambda *args, **kwargs: FakePipeline(),
    ))
    runtime = WhisperXRuntime(Settings(_env_file=None, whisperx_device='cpu'))
    result = runtime._transcribe([0.0] * 16_000)
    assert result == [{'text': 'Lyrics continue.', 'start_ms': 200, 'end_ms': 1000}]


@pytest.mark.parametrize('vad_segments', [[], [{'text': 'Hello.', 'start': 0.1, 'end': 0.5}]])
def test_whisperx_music_fallback_keeps_window_offsets_and_adds_boundaries(monkeypatch, vad_segments):
    class FakeModel:
        model = SimpleNamespace(model_is_loaded=True)

        def transcribe(self, waveform, **options):
            assert options['vad_filter'] is False
            offset = 0 if len(waveform) == 30 * 16_000 else 30
            return iter([SimpleNamespace(text=f'line {offset}', start=0, end=31)]), None

        def unload_model(self, to_cpu=True):
            return None

    class FakePipeline:
        model = FakeModel()

        def transcribe(self, *args, **kwargs):
            return {'segments': vad_segments}

    monkeypatch.setitem(sys.modules, 'whisperx', SimpleNamespace(
        load_model=lambda *args, **kwargs: FakePipeline(),
    ))
    runtime = WhisperXRuntime(Settings(_env_file=None, whisperx_device='cpu'))
    result = runtime._transcribe([0.0] * (31 * 16_000))
    assert result == [
        {'text': 'line 0.', 'start_ms': 0, 'end_ms': 30_000},
        {'text': 'line 30.', 'start_ms': 30_000, 'end_ms': 31_000},
    ]


def test_whisperx_decodes_audio_base64_input(monkeypatch):
    captured = {}

    def fake_decode(source):
        captured['source'] = source
        return [0.1] * 1600

    monkeypatch.setattr('app.runtime.whisperx._decode_audio', fake_decode)
    payload = base64.b64encode(b'OggS-fake-audio-bytes').decode('ascii')
    request = ExecuteRequest(
        invocation_id='b64-1',
        provider='LOCAL_WHISPERX',
        model='small.en',
        input={'audio_base64': payload, 'mime_type': 'audio/webm'},
    )
    waveform = asyncio.run(WhisperXRuntime._audio(request))
    assert len(waveform) == 1600
    assert captured['source'] == b'OggS-fake-audio-bytes'


def test_whisperx_fast_short_turn_keeps_asr_loaded_and_uses_greedy_decode(monkeypatch):
    unloaded = []

    class FakeModel:
        model = SimpleNamespace(
            model_is_loaded=True,
            load_model=lambda: None,
            unload_model=lambda to_cpu=True: unloaded.append(to_cpu),
        )

        def transcribe(self, waveform, **options):
            assert options['beam_size'] == 1
            assert options['best_of'] == 1
            assert options['vad_filter'] is False
            assert options['without_timestamps'] is True
            return iter([SimpleNamespace(text='Hello, table for two.', start=0.1, end=1.2)]), None

    monkeypatch.setitem(sys.modules, 'whisperx', SimpleNamespace(
        load_model=lambda *args, **kwargs: SimpleNamespace(model=FakeModel()),
    ))
    runtime = WhisperXRuntime(Settings(_env_file=None, whisperx_device='cuda'))
    monkeypatch.setattr(runtime, '_audio', AsyncMock(return_value=[0.0] * (2 * 16_000)))
    req = ExecuteRequest(
        invocation_id='fast-1',
        provider='LOCAL_WHISPERX',
        model='small.en',
        input={'audio_base64': 'ZmFrZQ=='},
        options={'language': 'English', 'timestamps': False, 'allow_alignment': False},
    )
    res = asyncio.run(runtime.stt(req))
    assert res.output['text'] == 'Hello, table for two.'
    assert unloaded == []
