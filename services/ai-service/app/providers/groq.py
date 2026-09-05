from __future__ import annotations

import base64
from pathlib import Path
from urllib.parse import unquote, urlparse

import httpx

from ..schemas import ExecuteRequest, ExecuteResponse


class GroqProvider:
    """Groq capability adapter. Product/business decisions stay in Java."""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip('/')

    @staticmethod
    def _auth(credential: str) -> dict[str, str]:
        if not credential:
            raise ValueError('GROQ credential is required')
        return {'Authorization': f'Bearer {credential}'}

    async def transcribe(self, request: ExecuteRequest, credential: str) -> ExecuteResponse:
        audio_url = request.input.get('audio_url') or request.input.get('url')
        audio_base64 = request.input.get('audio_base64')
        model = request.model or 'whisper-large-v3-turbo'
        language = request.options.get('language', 'en')
        want_timestamps = bool(request.options.get('timestamps', False))
        if isinstance(language, str) and language.lower().startswith('english'):
            language = 'en'

        async with httpx.AsyncClient(timeout=180) as client:
            if isinstance(audio_url, str) and audio_url.startswith('file://'):
                path = Path(unquote(urlparse(audio_url).path))
                raw = path.read_bytes()
                response = await _multipart_transcription(
                    client, self.base_url, self._auth(credential), model, language,
                    raw, path.name or 'audio.bin', want_timestamps
                )
            elif audio_url:
                # R2 presigned URL avoids a useless Core -> FastAPI -> Groq relay.
                body = {
                    'url': audio_url,
                    'model': model,
                    'language': language,
                    'response_format': 'verbose_json' if want_timestamps else 'json',
                    'temperature': 0,
                }
                if want_timestamps:
                    body['timestamp_granularities'] = ['word', 'segment']
                response = await client.post(
                    f'{self.base_url}/audio/transcriptions',
                    headers={**self._auth(credential), 'Content-Type': 'application/json'},
                    json=body,
                )
            elif audio_base64:
                raw = base64.b64decode(audio_base64)
                response = await _multipart_transcription(
                    client, self.base_url, self._auth(credential), model, language,
                    raw, 'audio.wav', want_timestamps
                )
            else:
                raise ValueError('Groq STT requires input.audio_url or input.audio_base64')
            response.raise_for_status()
            data = response.json()

        words = []
        for i, item in enumerate(data.get('words') or []):
            words.append({
                'index': i,
                'word': item.get('word', ''),
                'start_ms': round(float(item.get('start', 0)) * 1000),
                'end_ms': round(float(item.get('end', 0)) * 1000),
            })
        return ExecuteResponse(
            output={
                'text': data.get('text', ''),
                'timestamps': words,
                'segments': data.get('segments') or [],
            },
            metadata={'provider': 'GROQ', 'model': model},
        )

    async def tts(self, request: ExecuteRequest, credential: str) -> ExecuteResponse:
        text = str(request.input.get('text') or '')
        if not text:
            raise ValueError('Groq TTS requires input.text')
        # Current Groq Orpheus TTS accepts short English snippets (<=200 chars)
        # and WAV output. Lyreo lesson TTS should normally route to Gemini; this adapter
        # remains useful for preview/snippet fallback without pretending provider parity.
        if len(text) > 200:
            raise ValueError('Groq Orpheus TTS currently accepts at most 200 characters per request')
        voice = str(request.options.get('voice') or 'austin')
        response_format = 'wav'
        async with httpx.AsyncClient(timeout=180) as client:
            response = await client.post(
                f'{self.base_url}/audio/speech',
                headers={**self._auth(credential), 'Content-Type': 'application/json'},
                json={
                    'model': request.model,
                    'voice': voice,
                    'input': text,
                    'response_format': response_format,
                },
            )
            response.raise_for_status()
            audio = response.content
        return ExecuteResponse(
            output={
                'audio_base64': base64.b64encode(audio).decode(),
                'mime_type': f'audio/{response_format}',
            },
            metadata={'provider': 'GROQ', 'model': request.model, 'voice': voice},
        )


async def _multipart_transcription(
    client: httpx.AsyncClient,
    base_url: str,
    headers: dict[str, str],
    model: str,
    language: str,
    raw: bytes,
    filename: str,
    want_timestamps: bool,
) -> httpx.Response:
    return await client.post(
        f'{base_url}/audio/transcriptions',
        headers=headers,
        data={
            'model': model,
            'language': language,
            'response_format': 'verbose_json' if want_timestamps else 'json',
            'temperature': '0',
            **({'timestamp_granularities[]': 'word'} if want_timestamps else {}),
        },
        files={'file': (filename, raw, 'application/octet-stream')},
    )
