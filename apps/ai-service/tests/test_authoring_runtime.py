import asyncio
import base64
import json
from unittest.mock import AsyncMock

import httpx
import pytest

from app.providers.gemini import GeminiProvider, _interaction_usage
from app.providers.groq import GroqProvider
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.runtime.audio import alignment_windows
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


def test_alignment_requires_real_anchors_for_long_input():
    with pytest.raises(ValueError, match='requires anchored'):
        alignment_windows(Waveform(121 * 16_000), 'hello', None)
    with pytest.raises(ValueError, match='complete approved transcript'):
        alignment_windows(Waveform(10 * 16_000), 'hello world', [
            {'text': 'hello', 'start_ms': 0, 'end_ms': 1000},
        ])
    with pytest.raises(ValueError, match='ordered non-overlapping'):
        alignment_windows(Waveform(10 * 16_000), 'hello world', [
            {'text': 'hello', 'start_ms': 0, 'end_ms': 2000},
            {'text': 'world', 'start_ms': 1000, 'end_ms': 3000},
        ])
    with pytest.raises(ValueError, match='at most 120 seconds'):
        alignment_windows(Waveform(125 * 16_000), 'hello', [
            {'text': 'hello', 'start_ms': 0, 'end_ms': 121000},
        ])


@pytest.mark.parametrize('start_ms,end_ms,duration_ms', [
    (0, 120000, 125000),
    (120000, 240000, 245000),
    (1000, 120500, 125000),
])
def test_alignment_padding_stays_within_budget_when_silent_windows_are_absent(start_ms, end_ms, duration_ms):
    windows = alignment_windows(Waveform(duration_ms * 16), 'hello', [
        {'text': 'hello', 'start_ms': start_ms, 'end_ms': end_ms},
    ])
    start, window, text = windows[0]
    assert start <= start_ms * 16
    assert start + len(window) >= end_ms * 16
    assert len(window) <= 120 * 16_000
    assert text == 'hello'


def test_alignment_windows_split_the_shared_padding_gap_without_cutting_anchors():
    segments = [
        {'text': 'hello', 'start_ms': 1000, 'end_ms': 2000},
        {'text': 'world', 'start_ms': 2321, 'end_ms': 3200},
    ]
    first, second = alignment_windows(Waveform(5000 * 16), 'hello world', segments)
    seam = ((2000 + 2321) // 2) * 16
    assert first[0] + len(first[1]) == second[0] == seam


def test_alignment_clamps_only_small_rounding_at_the_end_of_the_audio():
    waveform = Waveform(226_138 * 16)
    segment = {'text': 'hello', 'start_ms': 221_599, 'end_ms': 226_159}
    [(start, window, _)] = alignment_windows(waveform, 'hello', [segment], end_tolerance_ms=50)
    assert start + len(window) == len(waveform)
    with pytest.raises(ValueError, match='in-bounds'):
        alignment_windows(waveform, 'hello', [{**segment, 'end_ms': 226_300}], end_tolerance_ms=50)
    with pytest.raises(ValueError, match='in-bounds'):
        alignment_windows(waveform, 'hello', [{**segment, 'start_ms': 226_139}], end_tolerance_ms=50)


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

    provider._interaction.return_value['usage'] = {
        'total_input_tokens': 12, 'total_output_tokens': 5, 'total_thought_tokens': 11,
    }
    maintenance = asyncio.run(provider.complete(request(options={
        'include_reasoning_usage': True,
    }), 'test-credential'))
    assert maintenance.output['reasoning_tokens'] == 11
    assert maintenance.usage == {'input_tokens': 12, 'output_tokens': 16}


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


@pytest.mark.parametrize('source', ['signed_url', 'local_file', 'base64'])
def test_groq_multipart_requests_both_granularities_and_normalizes_segments(monkeypatch, tmp_path, source):
    audio = tmp_path / 'audio.wav'
    audio.write_bytes(b'placeholder')
    signed_url = 'https://media.example.invalid/audio.wav?signature=test&expires=600'
    inputs = {
        'signed_url': {'audio_url': signed_url},
        'local_file': {'audio_url': audio.as_uri()},
        'base64': {'audio_base64': base64.b64encode(audio.read_bytes()).decode()},
    }

    def handler(req):
        assert req.method == 'POST' and req.url.path == '/audio/transcriptions'
        assert req.headers['content-type'].startswith('multipart/form-data; boundary=')
        assert b'name="model"\r\n\r\nmodel' in req.content
        assert b'name="response_format"\r\n\r\nverbose_json' in req.content
        assert b'name="timestamp_granularities[]"\r\n\r\nword' in req.content
        assert b'name="timestamp_granularities[]"\r\n\r\nsegment' in req.content
        if source == 'signed_url':
            assert b'name="url"\r\n\r\n' + signed_url.encode() in req.content
            assert b'name="file"' not in req.content
        else:
            assert b'name="file"; filename="audio.wav"' in req.content
            assert b'name="url"' not in req.content
        return httpx.Response(200, json={
            'text': 'Hello.', 'duration': 1.5,
            'words': [{'word': 'Hello.', 'start': .2, 'end': .8}],
            'segments': [{'text': 'Hello.', 'start': .1, 'end': 1.2}],
        })

    install_transport(monkeypatch, handler)
    output = asyncio.run(GroqProvider('https://example.invalid').transcribe(request(
        input=inputs[source], options={'timestamps': True},
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


def test_provider_error_keeps_original_status_and_redacted_message(monkeypatch):
    from fastapi.testclient import TestClient
    from app import main

    def rejected(req):
        return httpx.Response(503, json={'error': {
            'message': ('Model is overloaded. test-credential test-internal '
                        'https://host/private?key=url-secret '
                        '"api_key": "other-secret" Authorization: Bearer bearer-secret '
                        'Prompt: private lesson text'),
            'details': [{'input': 'do-not-forward', 'audio_base64': 'private-media'}],
        }}, headers={'X-Secret': 'do-not-forward'})

    install_transport(monkeypatch, rejected)
    monkeypatch.setattr(main.cfg, 'ai_runtime_mode', 'real')
    monkeypatch.setattr(main.cfg, 'ai_service_internal_token', 'test-internal')
    response = TestClient(main.app).post('/v1/llm/generate', headers={
        'X-Lyreo-Internal-Token': 'test-internal', 'X-Lyreo-Provider-Credential': 'test-credential',
    }, json=ExecuteRequest(invocation_id='failure', provider='GEMINI', model='model',
                          prompt='private lesson text').model_dump())
    assert response.status_code == 502
    assert response.json()['provider_status'] == 503
    assert 'Model is overloaded.' in response.json()['provider_message']
    for secret in ('test-credential', 'test-internal', 'url-secret', 'other-secret',
                   'bearer-secret', 'private lesson text', 'do-not-forward', 'private-media'):
        assert secret not in response.text


@pytest.mark.parametrize('body', [b'<html>private proxy error</html>', b'{broken',
                                b'{"error":{"message":' + b'"' + b'x' * 66000 + b'"}}'])
def test_provider_error_ignores_unstructured_or_oversized_bodies(monkeypatch, body):
    from fastapi.testclient import TestClient
    from app import main

    install_transport(monkeypatch, lambda _: httpx.Response(503, content=body))
    monkeypatch.setattr(main.cfg, 'ai_runtime_mode', 'real')
    monkeypatch.setattr(main.cfg, 'ai_service_internal_token', 'test-token')
    response = TestClient(main.app).post('/v1/llm/generate', headers={
        'X-Lyreo-Internal-Token': 'test-token', 'X-Lyreo-Provider-Credential': 'test-credential',
    }, json=ExecuteRequest(invocation_id='failure', provider='GEMINI', model='model').model_dump())
    assert response.status_code == 502
    assert response.json()['provider_status'] == 503
    assert 'provider_message' not in response.json()
    assert len(response.content) < 256


@pytest.mark.parametrize('sensitive', [
    'Authorization: Bearer bearer-secret', 'authorization="Basic basic-secret"',
    '"password": "a secret with spaces"', "'api_key': 'quoted-secret'",
    'token=token-secret', 'credential: credential-secret',
])
def test_provider_error_redacts_labelled_secrets(sensitive):
    from starlette.requests import Request
    from app.providers.errors import provider_error_message
    req = httpx.Request('POST', 'https://example.invalid')
    response = httpx.Response(503, json={'error': {'message': f'Overloaded. {sensitive}'}}, request=req)
    error = httpx.HTTPStatusError('upstream', request=req, response=response)
    message = provider_error_message(error, Request({'type': 'http', 'headers': []}))
    assert message.startswith('Overloaded.')
    for secret in ('bearer-secret', 'basic-secret', 'a secret with spaces', 'quoted-secret',
                   'token-secret', 'credential-secret'):
        assert secret not in message


@pytest.mark.parametrize('style', ['nested', 'string', 'message'])
def test_provider_message_is_bounded_after_redacting_unicode_input_and_secrets(style):
    from starlette.requests import Request
    from app.providers.errors import provider_error_message
    text = 'Câu riêng tư.'
    message = f'Model unavailable. {text} {json.dumps(text)[1:-1]} ' + 'x' * 2000 + 'test-credential'
    body = {'error': {'message': message}} if style == 'nested' else {style if style == 'message' else 'error': message}
    req = httpx.Request('POST', 'https://example.invalid', json={'messages': [{'role': 'user', 'content': text}]})
    response = httpx.Response(503, json=body, request=req)
    error = httpx.HTTPStatusError('upstream', request=req, response=response)
    incoming = Request({'type': 'http', 'headers': [(b'x-lyreo-provider-credential', b'test-credential')]})
    result = provider_error_message(error, incoming)
    assert result.startswith('Model unavailable.')
    assert len(result) <= 2048
    assert text not in result and json.dumps(text)[1:-1] not in result
    assert 'test-credential' not in result


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


@pytest.mark.parametrize('thinking', ['enabled', 'disabled'])
def test_deepseek_maintenance_uses_a_fixed_budget_including_reasoning(monkeypatch, thinking):
    captured = {}
    async def post(self, url, **kwargs):
        captured.update(kwargs['json'])
        return httpx.Response(200, request=httpx.Request('POST', url), json={
            'choices': [{'message': {'content': '{"changes": [], "issues": []}', 'reasoning_content': 'internal provider reasoning'}, 'finish_reason': 'stop'}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 20},
        })
    monkeypatch.setattr(httpx.AsyncClient, 'post', post)
    req = request(options={'thinking': thinking, 'max_output_tokens': 8192, 'response_schema': 'maintenance', 'provider_options': {'reasoning_effort': 'high'}})
    req.provider = 'DEEPSEEK'
    result = asyncio.run(OpenAICompatibleProvider('https://api.deepseek.com').complete(req, 'test-only'))
    assert captured['thinking'] == {'type': thinking}
    assert captured['reasoning_effort'] == 'high'
    assert captured['max_tokens'] == 8192
    assert 'max_completion_tokens' not in captured
    assert result.output == {'content': '{"changes": [], "issues": []}', 'structured': {'changes': [], 'issues': []}}
    assert result.usage == {'input_tokens': 10, 'output_tokens': 20}


def test_deepseek_truncation_without_text_keeps_billable_usage(monkeypatch):
    async def post(self, url, **kwargs):
        return httpx.Response(200, request=httpx.Request('POST', url), json={
            'choices': [{'message': {'content': None}, 'finish_reason': 'length'}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 8192,
                      'completion_tokens_details': {'reasoning_tokens': 8192}},
        })
    monkeypatch.setattr(httpx.AsyncClient, 'post', post)
    req = request(options={'thinking': 'enabled', 'max_output_tokens': 8192})
    req.provider = 'DEEPSEEK'
    result = asyncio.run(OpenAICompatibleProvider('https://api.deepseek.com').complete(req, 'test-only'))
    assert result.metadata['finish_reason'] == 'length'
    assert result.output == {'content': '', 'reasoning_tokens': 8192}
    assert result.usage == {'input_tokens': 10, 'output_tokens': 8192}


def test_deepseek_lesson_uses_low_effort_with_bounded_headroom(monkeypatch):
    async def post(self, url, **kwargs):
        body = kwargs['json']
        assert body['max_tokens'] == 256 + 2048
        assert body['thinking'] == {'type': 'enabled'}
        assert body['reasoning_effort'] == 'low'
        assert 'max_completion_tokens' not in body
        return httpx.Response(200, request=httpx.Request('POST', url), json={
            'choices': [{'message': {'content': '{"v": [], "g": []}'}, 'finish_reason': 'stop'}],
        })
    monkeypatch.setattr(httpx.AsyncClient, 'post', post)
    req = request(options={'max_output_tokens': 256, 'reasoning_token_headroom': 2048, 'thinking_level': 'low'})
    req.provider = 'DEEPSEEK'
    result = asyncio.run(OpenAICompatibleProvider('https://api.deepseek.com').complete(req, 'test-only'))
    assert result.output['structured'] == {'v': [], 'g': []}
