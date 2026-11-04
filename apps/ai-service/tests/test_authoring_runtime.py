import asyncio
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import httpx
import pytest

from app.config import Settings
from app.providers.gemini import GeminiProvider, _interaction_usage
from app.providers.groq import GroqProvider
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.runtime.qwen import QwenRuntime, _alignment_windows, _alignment_word, _audio_windows
from app.schemas import ExecuteRequest


class Waveform:
    def __init__(self, length):
        self.length = length

    def __len__(self):
        return self.length

    def __getitem__(self, section):
        return Waveform(len(range(*section.indices(self.length))))


def request(**overrides):
    return ExecuteRequest(invocation_id='authoring-test', provider='GROQ', model='model', **overrides)


def test_asr_windows_preserve_every_sample_and_bound_gpu_input():
    audio = Waveform(300 * 16_000 + 1)
    windows = _audio_windows(audio)
    assert [start for start, _ in windows] == [0, 115 * 16_000, 230 * 16_000]
    assert sum(len(window) for _, window in windows) == len(audio)
    assert all(len(window) <= 115 * 16_000 for _, window in windows)


def test_alignment_accepts_only_terminal_frame_rounding_and_never_pads_audio():
    audio = Waveform(19_979 * 16)
    segments = [{'text': 'hello', 'start_ms': 160, 'end_ms': 20_000}]
    start, window, text = _alignment_windows(audio, 'hello', segments, timestamp_precision_ms=80)[0]
    assert start + len(window) == len(audio)
    assert text == 'hello'
    assert segments[0]['end_ms'] == 20_000
    for invalid in (
        [{'text': 'hello', 'start_ms': 160, 'end_ms': 20_080}],
        [{'text': 'hello', 'start_ms': 19_980, 'end_ms': 20_000}],
    ):
        with pytest.raises(ValueError, match='in-bounds'):
            _alignment_windows(audio, 'hello', invalid, timestamp_precision_ms=80)


def test_alignment_requires_real_anchors_for_long_input():
    with pytest.raises(ValueError, match='requires anchored'):
        _alignment_windows(Waveform(121 * 16_000), 'hello', None)
    with pytest.raises(ValueError, match='complete approved transcript'):
        _alignment_windows(Waveform(10 * 16_000), 'hello world', [
            {'text': 'hello', 'start_ms': 0, 'end_ms': 1000},
        ])
    with pytest.raises(ValueError, match='ordered non-overlapping'):
        _alignment_windows(Waveform(10 * 16_000), 'hello world', [
            {'text': 'hello', 'start_ms': 0, 'end_ms': 2000},
            {'text': 'world', 'start_ms': 1000, 'end_ms': 3000},
        ])
    with pytest.raises(ValueError, match='at most 120 seconds'):
        _alignment_windows(Waveform(125 * 16_000), 'hello', [
            {'text': 'hello', 'start_ms': 0, 'end_ms': 121000},
        ])


@pytest.mark.parametrize('start_ms,end_ms,duration_ms', [
    (0, 120000, 125000),
    (120000, 240000, 245000),
    (1000, 120500, 125000),
])
def test_alignment_padding_stays_within_budget_when_silent_windows_are_absent(start_ms, end_ms, duration_ms):
    windows = _alignment_windows(Waveform(duration_ms * 16), 'hello', [
        {'text': 'hello', 'start_ms': start_ms, 'end_ms': end_ms},
    ])
    start, window, text = windows[0]
    assert start <= start_ms * 16
    assert start + len(window) >= end_ms * 16
    assert len(window) <= 120 * 16_000
    assert text == 'hello'


def test_alignment_offsets_windows_and_preserves_raw_invalid_times(monkeypatch):
    runtime = QwenRuntime(Settings(_env_file=None))
    monkeypatch.setattr('app.runtime.qwen._read_audio', lambda _: Waveform(200 * 16_000))
    align = Mock(side_effect=[
        [SimpleNamespace(items=[SimpleNamespace(text='hello', start_time=0.2, end_time=0.4)])],
        [SimpleNamespace(items=[SimpleNamespace(text='world', start_time=0.5, end_time=0.5)])],
    ])
    runtime._load_aligner = AsyncMock(return_value=SimpleNamespace(align=align))
    result = asyncio.run(runtime.align(request(input={
        'audio_url': 'file:///unused.wav', 'text': 'hello world', 'segments': [
            {'text': 'hello', 'start_ms': 1000, 'end_ms': 2000},
            {'text': 'world', 'start_ms': 150000, 'end_ms': 151000},
        ],
    })))
    assert result.output['words'] == [
        {'index': 0, 'word': 'hello', 'start_ms': 700, 'end_ms': 900},
        {'index': 1, 'word': 'world', 'start_ms': 150000, 'end_ms': 150000},
    ]
    assert all(len(call.kwargs['audio'][0]) <= 120 * 16_000 for call in align.call_args_list)


def test_qwen_asr_chunks_before_inference_and_returns_global_segment_anchors(monkeypatch):
    runtime = QwenRuntime(Settings(_env_file=None))
    monkeypatch.setattr('app.runtime.qwen._read_audio', lambda _: Waveform(125 * 16_000))
    transcribe = Mock(side_effect=[
        [SimpleNamespace(text='First window.')], [SimpleNamespace(text='Second window.')],
    ])
    runtime._load_asr = AsyncMock(return_value=SimpleNamespace(transcribe=transcribe))
    runtime._align_words = AsyncMock(return_value=[{'index': 0, 'word': 'First', 'start_ms': 20, 'end_ms': 100}])
    output = asyncio.run(runtime.stt(request(
        input={'audio_url': 'file:///unused.wav'}, options={'timestamps': True},
    ))).output
    assert output['text'] == 'First window. Second window.'
    assert output['segments'] == [
        {'text': 'First window.', 'start_ms': 0, 'end_ms': 115000},
        {'text': 'Second window.', 'start_ms': 115000, 'end_ms': 125000},
    ]
    assert transcribe.call_count == 2
    assert runtime._align_words.await_count == 1


def test_alignment_millisecond_values_are_not_multiplied_again():
    assert _alignment_word({'word': 'Hi', 'start_ms': 500, 'end_ms': 800}) == {
        'word': 'Hi', 'start_ms': 500, 'end_ms': 800,
    }
    assert _alignment_word({'text': 'Hi', 'start_time': .5, 'end_time': .8})['start_ms'] == 500
    for malformed in ({'word': 'Hi'}, {'word': 'Hi', 'start_time': float('nan'), 'end_time': 1}):
        with pytest.raises(ValueError):
            _alignment_word(malformed)


def test_gemini_real_usage_and_generation_contract():
    assert _interaction_usage({'usage': {'total_input_tokens': 12, 'total_output_tokens': 5}}) == {
        'input_tokens': 12, 'output_tokens': 5,
    }
    assert _interaction_usage({'usage': {'input_tokens': 0}}) == {'input_tokens': 0}
    assert _interaction_usage({}) == {}
    provider = GeminiProvider('https://example.invalid')
    provider._interaction = AsyncMock(return_value={
        'status': 'completed', 'steps': [{'type': 'model_output', 'content': [{'type': 'text', 'text': '{"items":[]}'}]}],
    })
    schema = {'type': 'object', 'properties': {'items': {'type': 'array', 'items': {'type': 'string'}}}}
    result = asyncio.run(provider.complete(request(options={
        'json_schema': schema, 'thinking_level': 'minimal', 'max_output_tokens': 3000,
    }), 'test-credential'))
    body = provider._interaction.call_args.args[0]
    assert body['response_format']['schema'] == schema
    assert body['generation_config']['max_output_tokens'] == 3000
    assert body['generation_config']['thinking_level'] == 'minimal'
    assert body['store'] is False
    assert result.metadata['finish_reason'] == 'stop'
    assert result.usage == {}


def install_transport(monkeypatch, handler):
    real_client = httpx.AsyncClient
    monkeypatch.setattr(httpx, 'AsyncClient', lambda **kwargs: real_client(
        **kwargs, transport=httpx.MockTransport(handler),
    ))


def test_groq_strict_schema_budget_usage_and_truncation_metadata(monkeypatch):
    schema = {'type': 'object', 'properties': {}, 'additionalProperties': False}

    def handler(req):
        body = json.loads(req.content)
        assert body['response_format'] == {'type': 'json_schema', 'json_schema': {
            'name': 'lesson_semantic', 'strict': True, 'schema': schema,
        }}
        assert body['reasoning_effort'] == 'low'
        assert body['max_completion_tokens'] == 3000
        assert 'max_tokens' not in body
        return httpx.Response(200, json={'choices': [{'message': {'content': '{}'}, 'finish_reason': 'length'}]})

    install_transport(monkeypatch, handler)
    result = asyncio.run(OpenAICompatibleProvider('https://example.invalid').complete(request(options={
        'json_schema': schema, 'response_schema': 'lesson_semantic', 'max_output_tokens': 3000,
        'provider_options': {'reasoning_effort': 'low', 'api_key': 'must-not-pass'},
    }), 'test-credential'))
    assert result.metadata['finish_reason'] == 'length'
    assert result.usage == {}


def test_groq_multipart_requests_both_granularities_and_normalizes_segments(monkeypatch, tmp_path):
    audio = tmp_path / 'audio.wav'
    audio.write_bytes(b'placeholder')

    def handler(req):
        assert b'name="timestamp_granularities[]"\r\n\r\nword' in req.content
        assert b'name="timestamp_granularities[]"\r\n\r\nsegment' in req.content
        return httpx.Response(200, json={
            'text': 'Hello.', 'duration': 1.5,
            'words': [{'word': 'Hello.', 'start': .2, 'end': .8}],
            'segments': [{'text': 'Hello.', 'start': .1, 'end': 1.2}],
        })

    install_transport(monkeypatch, handler)
    output = asyncio.run(GroqProvider('https://example.invalid').transcribe(request(
        input={'audio_url': audio.as_uri()}, options={'timestamps': True},
    ), 'test-credential')).output
    assert output['timestamps'][0]['start_ms'] == 200
    assert output['segments'] == [{'text': 'Hello.', 'start_ms': 100, 'end_ms': 1200}]


def test_upstream_rate_limit_preserves_resume_signal_without_provider_body(monkeypatch):
    from fastapi.testclient import TestClient
    from app import main

    async def limited(*args, **kwargs):
        request = httpx.Request('POST', 'https://example.invalid')
        response = httpx.Response(429, headers={'Retry-After': '17', 'X-Secret': 'hidden'},
                                  text='private quota details', request=request)
        raise httpx.HTTPStatusError('rate limit', request=request, response=response)

    monkeypatch.setattr(main.cfg, 'ai_runtime_mode', 'real')
    monkeypatch.setattr(main.cfg, 'ai_service_internal_token', 'test-token')
    monkeypatch.setattr(main.groq, 'transcribe', limited)
    client = TestClient(main.app)
    response = client.post('/v1/stt', headers={
        'X-Lyreo-Internal-Token': 'test-token', 'X-Lyreo-Provider-Credential': 'test-credential',
    }, json=request(input={'audio_url': 'https://example.invalid/audio.wav'}).model_dump())
    assert response.status_code == 429
    assert response.headers['Retry-After'] == '17'
    assert response.json()['provider_status'] == 429
    assert 'private' not in response.text
    assert 'X-Secret' not in response.headers


def test_mock_authoring_only_returns_requested_targets():
    from app.runtime.mock import MockRuntime
    runtime = MockRuntime()
    result = asyncio.run(runtime.llm(request(
        input={'context': [{'sentenceId': 'watch', 'text': 'Watch only.'}], 'targets': [
            {'sentenceId': 'learn', 'text': 'Learn this.', 'dictationEligible': True, 'shadowingEligible': False},
        ]}, options={'response_schema': 'lesson_authoring_v1'},
    ), None))
    item = result.output['structured']['items'][0]
    assert result.output['structured']['targetLocale'] == 'vi-VN'
    assert item['sentenceId'] == 'learn'
    assert item['dictationPriority'] == 'HIGH'
    assert item['shadowingPriority'] == 'MEDIUM'
    assert item['thoughtGroups'] == []
    assert len(result.output['structured']['items']) == 1
    assert result.metadata['finish_reason'] == 'stop'


@pytest.mark.parametrize('upstream_status', [400, 401, 403, 404, 413, 422])
def test_provider_config_errors_keep_nonretryable_classification(monkeypatch, upstream_status):
    from fastapi.testclient import TestClient
    from app import main

    async def rejected(*args, **kwargs):
        req = httpx.Request('POST', 'https://example.invalid')
        response = httpx.Response(upstream_status, text='private provider detail', request=req)
        raise httpx.HTTPStatusError('rejected', request=req, response=response)

    monkeypatch.setattr(main.cfg, 'ai_runtime_mode', 'real')
    monkeypatch.setattr(main.cfg, 'ai_service_internal_token', 'test-token')
    monkeypatch.setattr(main.groq, 'transcribe', rejected)
    response = TestClient(main.app).post('/v1/stt', headers={
        'X-Lyreo-Internal-Token': 'test-token', 'X-Lyreo-Provider-Credential': 'test-only',
    }, json=request(input={'audio_url': 'https://example.invalid/audio.wav'}).model_dump())
    assert response.status_code == upstream_status
    assert 'private' not in response.text


def test_alignment_only_bounds_a_small_quantized_audio_end_without_hiding_bad_intervals():
    from app.runtime.qwen import _bound_alignment_word
    word = {'word':'all', 'start_ms':45440, 'end_ms':45840}
    assert _bound_alignment_word(word, 45837, 80)['end_ms'] == 45837
    assert word['end_ms'] == 45840
    for invalid in [
        {'word':'all','start_ms':45440,'end_ms':46000},
        {'word':'all','start_ms':45440,'end_ms':45890},
        {'word':'all','start_ms':45840,'end_ms':45840},
        {'word':'all','start_ms':-80,'end_ms':80},
    ]:
        assert _bound_alignment_word(invalid,45837,80) == invalid
    assert _bound_alignment_word(word,45837,0) == word


def test_stt_can_defer_forced_alignment_until_the_caller_confirms_text(monkeypatch):
    runtime = QwenRuntime(Settings(_env_file=None))
    monkeypatch.setattr('app.runtime.qwen._read_audio',lambda _: Waveform(120*16000))
    transcribe = Mock(return_value=[SimpleNamespace(text='Some words.')])
    runtime._load_asr = AsyncMock(return_value=SimpleNamespace(transcribe=transcribe))
    runtime._align_words = AsyncMock()
    result = asyncio.run(runtime.stt(request(input={'audio_url':'file:///unused.wav'},options={'timestamps':True,'allow_alignment':False})))
    runtime._align_words.assert_not_awaited()
    assert result.output['timestamps'] == []
    assert max(s['end_ms']-s['start_ms'] for s in result.output['segments']) <= 115000
