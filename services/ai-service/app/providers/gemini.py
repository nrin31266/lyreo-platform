from __future__ import annotations

import base64
import io
import json
import mimetypes
import wave
from typing import Any

import httpx

from ..schemas import ExecuteRequest, ExecuteResponse


class GeminiProvider:
    """Gemini Interactions adapters for audio-specific Lyreo capabilities.

    Lyreo deliberately keeps product prompts/routing in Java. This adapter only maps
    the stable internal ExecuteRequest contract to the current Gemini wire contract.
    """

    def __init__(self, api_base: str):
        self.api_base = api_base.rstrip('/')

    async def tts(self, request: ExecuteRequest, credential: str) -> ExecuteResponse:
        if not credential:
            raise ValueError('GEMINI credential is required')
        text = str(request.input.get('text') or '').strip()
        if not text:
            raise ValueError('Gemini TTS requires input.text')

        voice = str(request.options.get('voice') or 'Kore')
        accent = str(request.options.get('accent') or 'US').upper()
        style = str(request.options.get('style') or '').strip()
        natural_accent = 'American' if accent == 'US' else 'British' if accent == 'UK' else accent
        direction = f'Speak in natural {natural_accent} English.'
        if style:
            direction += f' Style: {style}.'
        instruction = f'{direction}\n{text}'

        # Gemini Interactions TTS returns raw PCM audio in the current API. Keep the
        # provider wire contract here; Java receives a generic base64 audio result.
        body = {
            'model': request.model,
            'input': instruction,
            'response_format': {'type': 'audio'},
            'generation_config': {
                'speech_config': [{'voice': voice, 'language': _language_code(accent)}],
            },
        }
        data = await self._interaction(body, credential, timeout=240)
        audio = _interaction_audio(data)
        encoded = audio.get('data') if isinstance(audio, dict) else None
        if not encoded:
            raise ValueError('Gemini TTS response did not contain inline audio data')

        # Gemini TTS returns raw signed 16-bit mono PCM at 24 kHz. Lyreo normalizes
        # that transport-specific payload into a standard WAV container here so every
        # caller receives a self-describing audio artifact. This avoids persisting raw
        # PCM bytes with a misleading .mp3/.wav extension in the Java lesson pipeline.
        pcm = base64.b64decode(encoded)
        wav_bytes = _pcm_s16le_mono_to_wav(pcm, sample_rate=24_000)
        return ExecuteResponse(
            output={
                'audio_base64': base64.b64encode(wav_bytes).decode(),
                'mime_type': 'audio/wav',
            },
            usage=_interaction_usage(data),
            metadata={
                'provider': 'GEMINI',
                'model': request.model,
                'voice': voice,
                'accent': accent,
                'interaction_id': data.get('id'),
            },
        )

    async def complete(self, request: ExecuteRequest, credential: str) -> ExecuteResponse:
        """Execute a generic LLM capability through Gemini Interactions.

        The exact product prompt and symbolic response-schema name stay in Java. If Java later
        supplies a concrete JSON schema, this adapter can pass it through without changing the
        business module.
        """
        if not credential:
            raise ValueError('GEMINI credential is required')

        prompt = request.prompt or ''
        if request.input:
            prompt += '\n\nCaller input JSON:\n' + json.dumps(
                request.input, ensure_ascii=False, sort_keys=True
            )

        response_format: dict[str, Any] = {'type': 'text'}
        if request.options.get('response_schema') or request.options.get('json_schema'):
            response_format['mime_type'] = 'application/json'
        json_schema = request.options.get('json_schema')
        if isinstance(json_schema, dict) and json_schema:
            response_format['schema'] = json_schema

        body = {
            'model': request.model,
            'input': prompt,
            'response_format': response_format,
            'generation_config': {
                'temperature': request.options.get('temperature', 0.2),
            },
        }
        data = await self._interaction(body, credential, timeout=180)
        content = _interaction_text(data)
        structured = _json_object_or_none(content)
        output: dict[str, Any] = {'content': content}
        if structured is not None:
            output['structured'] = structured
        return ExecuteResponse(
            output=output,
            usage=_interaction_usage(data),
            metadata={
                'provider': 'GEMINI',
                'model': request.model,
                'interaction_id': data.get('id'),
            },
        )

    async def judge(self, request: ExecuteRequest, credential: str) -> ExecuteResponse:
        if not credential:
            raise ValueError('GEMINI credential is required')
        prompt = request.prompt or 'Assess this English speech attempt and return concise structured feedback.'
        audio_bytes, mime_type = await _resolve_audio(request)
        response_format: dict[str, Any] = {
            'type': 'text',
            'mime_type': 'application/json',
        }
        json_schema = request.options.get('json_schema')
        if isinstance(json_schema, dict) and json_schema:
            response_format['schema'] = json_schema

        body = {
            'model': request.model,
            'input': [
                {'type': 'text', 'text': prompt},
                {
                    'type': 'audio',
                    'data': base64.b64encode(audio_bytes).decode(),
                    'mime_type': mime_type,
                },
            ],
            'response_format': response_format,
            'generation_config': {
                'temperature': request.options.get('temperature', 0.2),
            },
        }
        data = await self._interaction(body, credential, timeout=240)
        content = _interaction_text(data)
        structured = _json_object_or_none(content)
        output: dict[str, Any] = {'content': content}
        if structured is not None:
            output['structured'] = structured
        return ExecuteResponse(
            output=output,
            usage=_interaction_usage(data),
            metadata={
                'provider': 'GEMINI',
                'model': request.model,
                'interaction_id': data.get('id'),
            },
        )

    async def _interaction(self, body: dict[str, Any], credential: str, timeout: int) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(
                f'{self.api_base}/v1beta/interactions',
                headers={
                    'x-goog-api-key': credential,
                    'Content-Type': 'application/json',
                    # Explicitly pin the 2026 Interactions schema used by this adapter.
                    'Api-Revision': '2026-05-20',
                },
                json=body,
            )
            response.raise_for_status()
            return response.json()


def _pcm_s16le_mono_to_wav(pcm: bytes, sample_rate: int = 24_000) -> bytes:
    """Wrap raw little-endian signed 16-bit mono PCM in a WAV container."""
    if sample_rate <= 0:
        raise ValueError('sample_rate must be positive')
    if len(pcm) % 2 != 0:
        raise ValueError('16-bit PCM byte length must be even')
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(pcm)
    return buffer.getvalue()


def _language_code(accent: str) -> str:
    normalized = accent.upper()
    if normalized == 'UK':
        return 'en-GB'
    if normalized == 'US':
        return 'en-US'
    return 'en-US'


def _json_object_or_none(value: str) -> dict[str, Any] | None:
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


async def _resolve_audio(request: ExecuteRequest) -> tuple[bytes, str]:
    encoded = request.input.get('audio_base64')
    if encoded:
        return base64.b64decode(encoded), str(request.input.get('mime_type') or 'audio/wav')

    url = request.input.get('audio_url')
    if not url:
        raise ValueError('Multimodal judge requires input.audio_base64 or input.audio_url')
    async with httpx.AsyncClient(timeout=120, follow_redirects=True) as client:
        response = await client.get(str(url))
        response.raise_for_status()
        content_type = response.headers.get('content-type', '').split(';')[0]
    if not content_type.startswith('audio/'):
        guessed = mimetypes.guess_type(str(url))[0]
        content_type = guessed if guessed and guessed.startswith('audio/') else 'audio/wav'
    return response.content, content_type


def _interaction_text(data: dict[str, Any]) -> str:
    # Current Interactions REST responses expose generated content in model_output steps.
    fragments: list[str] = []
    for step in data.get('steps') or []:
        if not isinstance(step, dict) or step.get('type') != 'model_output':
            continue
        for content in step.get('content') or []:
            if isinstance(content, dict) and content.get('type') == 'text':
                fragments.append(str(content.get('text') or ''))
    text = ''.join(fragments).strip()
    if text:
        return text

    # Defensive compatibility with convenience/older response projections.
    output_text = data.get('output_text')
    if isinstance(output_text, str) and output_text.strip():
        return output_text.strip()
    raise ValueError('Gemini interaction response contains no text model output')


def _interaction_audio(data: dict[str, Any]) -> dict[str, Any]:
    direct = data.get('output_audio')
    if isinstance(direct, dict) and direct.get('data'):
        return direct
    for step in data.get('steps') or []:
        if not isinstance(step, dict) or step.get('type') != 'model_output':
            continue
        for content in step.get('content') or []:
            if isinstance(content, dict) and content.get('type') == 'audio' and content.get('data'):
                return content
    return {}


def _interaction_usage(data: dict[str, Any]) -> dict[str, int]:
    usage = data.get('usage') or data.get('usage_metadata') or data.get('usageMetadata') or {}
    if not isinstance(usage, dict):
        return {}

    def number(*keys: str) -> int:
        for key in keys:
            value = usage.get(key)
            if isinstance(value, (int, float)):
                return int(value)
        return 0

    return {
        'input_tokens': number('input_tokens', 'prompt_token_count', 'promptTokenCount'),
        'output_tokens': number('output_tokens', 'output_token_count', 'candidatesTokenCount'),
    }
