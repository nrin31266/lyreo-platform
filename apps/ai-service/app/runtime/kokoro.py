from __future__ import annotations

import asyncio
import base64
import io
import re
import wave
from array import array
from typing import Any

from ..config import Settings
from ..schemas import ExecuteRequest, ExecuteResponse

# Standard Kokoro-82M voice set. Voice prefix encodes accent: 'a*' = American English,
# 'b*' = British English. This is the exact set supported by the configured local model.
KOKORO_VOICES: dict[str, str] = {
    'af_heart': 'US', 'af_bella': 'US', 'af_nicole': 'US', 'af_aoede': 'US',
    'af_kore': 'US', 'af_sarah': 'US', 'af_nova': 'US', 'af_sky': 'US',
    'am_adam': 'US', 'am_michael': 'US', 'am_emma': 'US', 'am_isa': 'US',
    'am_eric': 'US', 'am_george': 'US', 'am_liam': 'US',
    'bf_alice': 'UK', 'bf_emma': 'UK', 'bf_isabella': 'UK', 'bf_lily': 'UK',
    'bm_daniel': 'UK', 'bm_fable': 'UK', 'bm_george': 'UK', 'bm_lewis': 'UK',
}

_ACCEPTED_SPEED_RANGE = (0.5, 2.0)

_SENTENCE_END = re.compile(r'(?<=[.!?;:])\s+')


class KokoroRuntime:
    """Lazy local Kokoro TTS runtime.

    The pipeline and model are loaded only when a TTS call actually arrives; voice
    discovery and option validation never import the heavy runtime. Long text is
    chunked deterministically and concatenated with a fixed silence gap so callers
    never submit an unbounded single generation request.
    """

    def __init__(self, cfg: Settings):
        self.cfg = cfg
        self._pipelines: dict[str, Any] = {}
        self._voices: dict[tuple[str, str], Any] = {}

    def voices(self) -> list[dict[str, Any]]:
        return [
            {
                'provider': 'LOCAL_KOKORO',
                'voice_id': voice_id,
                'language': 'English',
                'accent': accent,
                'display': {'name': voice_id.replace('_', ' ').title(), 'accent': accent},
            }
            for voice_id, accent in KOKORO_VOICES.items()
        ]

    async def tts(self, request: ExecuteRequest) -> ExecuteResponse:
        text = str(request.input.get('text') or '').strip()
        if not text:
            raise ValueError('Kokoro TTS requires input.text')

        voice_id, accent = self.resolve_voice(
            str(request.options.get('voice') or self.cfg.kokoro_default_voice).strip(),
            str(request.options.get('accent') or '').strip().upper(),
        )
        speed = _normalize_speed(request.options.get('speed'))
        chunks = chunk_text(text, self.cfg.kokoro_chunk_limit_chars)

        pipeline = await self._load(accent)
        voice = await self._load_voice(accent, voice_id)

        segments: list[list[int]] = []
        for chunk in chunks:
            samples = await asyncio.to_thread(self._synthesize, pipeline, voice, chunk, speed)
            segments.append(samples)

        wav = concatenate_wav(segments, sample_rate=self.cfg.kokoro_sample_rate,
                              gap_ms=self.cfg.kokoro_chunk_gap_ms)
        return ExecuteResponse(
            output={
                'audio_base64': base64.b64encode(wav).decode(),
                'mime_type': 'audio/wav',
                'sample_rate': self.cfg.kokoro_sample_rate,
                'chunks': len(chunks),
            },
            metadata={
                'runtime': 'kokoro',
                'model': self.cfg.kokoro_model,
                'voice': voice_id,
                'accent': accent,
                'speed': speed,
            },
        )

    def resolve_voice(self, voice_id: str, requested_accent: str = '') -> tuple[str, str]:
        """Validate voice/accent without touching the heavy runtime. Returns (voiceId, accent)."""
        self._validate_voice(voice_id)
        accent = KOKORO_VOICES[voice_id]
        if requested_accent and requested_accent != accent:
            raise ValueError(
                f'Voice {voice_id} is {accent} English, not {requested_accent} English'
            )
        return voice_id, accent

    def _validate_voice(self, voice_id: str) -> None:
        if voice_id not in KOKORO_VOICES:
            supported = ', '.join(sorted(KOKORO_VOICES))
            raise ValueError(
                f'Unsupported Kokoro voice {voice_id!r}. Supported voices: {supported}'
            )

    async def _load(self, accent: str):
        lang_code = _lang_code(accent)
        if lang_code not in self._pipelines:
            try:
                from kokoro import KModel, KPipeline
            except ImportError as exc:
                raise ValueError(
                    'Kokoro runtime is not installed. Install the optional dependency '
                    "group: `uv sync --extra kokoro` (also requires the 'espeak-ng' "
                    'system package for phonemization).'
                ) from exc
            pipeline = await asyncio.to_thread(
                KPipeline, lang_code=lang_code, repo_id=self.cfg.kokoro_model, model=False
            )
            pipeline.model = await asyncio.to_thread(
                _build_model, self.cfg.kokoro_model, self.cfg.kokoro_device
            )
            self._pipelines[lang_code] = pipeline
        return self._pipelines[lang_code]

    async def _load_voice(self, accent: str, voice_id: str):
        key = (_lang_code(accent), voice_id)
        if key not in self._voices:
            pipeline = self._pipelines[_lang_code(accent)]
            try:
                self._voices[key] = await asyncio.to_thread(
                    pipeline.load_voice, voice_id
                )
            except Exception as exc:
                raise ValueError(
                    f'Unable to load Kokoro voice {voice_id!r}: {_safe(exc)} '
                    "(check that 'espeak-ng' is installed and the model is downloaded)"
                ) from exc
        return self._voices[key]

    def _synthesize(self, pipeline, voice, text: str, speed: float) -> list[int]:
        try:
            generator = pipeline(text, voice=voice, speed=speed)
            samples: list[int] = []
            for result in generator:
                samples.extend(_tensor_to_int16(result.audio))
            return samples
        except Exception as exc:
            raise ValueError(f'Kokoro synthesis failed: {_safe(exc)}') from exc


def chunk_text(text: str, limit: int) -> list[str]:
    """Deterministic sentence-aware chunking; never splits a word mid-way."""
    if limit < 32:
        limit = 32
    raw = re.split(r'(?<=[.!?;:])\s+', text.strip())
    sentences = [part.strip() for part in raw if part.strip()]
    if not sentences:
        return []
    chunks: list[str] = []
    current = ''
    for sentence in sentences:
        if not current:
            current = sentence
        elif len(current) + 1 + len(sentence) <= limit:
            current = f'{current} {sentence}'
        else:
            if len(current) > 0:
                chunks.append(current)
            current = sentence
    if current:
        chunks.append(current)
    return chunks


def concatenate_wav(segments: list[list[int]], sample_rate: int, gap_ms: int) -> bytes:
    """Concatenate mono int16 segments deterministically with a fixed silence gap."""
    gap_samples = max(0, int(sample_rate * gap_ms / 1000))
    merged: list[int] = []
    for index, segment in enumerate(segments):
        if index > 0 and gap_samples:
            merged.extend([0] * gap_samples)
        merged.extend(segment)
    return _to_wav(merged, sample_rate)


def _to_wav(samples: list[int], sample_rate: int) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, 'wb') as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(array('h', samples).tobytes())
    return buffer.getvalue()


def _tensor_to_int16(tensor) -> list[int]:
    values = tensor.detach().cpu().numpy().reshape(-1).tolist()
    return [max(-32768, min(32767, round(float(value) * 32767))) for value in values]


def _lang_code(accent: str) -> str:
    # Kokoro G2P language codes: 'a' = American English, 'b' = British English.
    return 'b' if accent.upper() == 'UK' else 'a'


def _build_model(repo_id: str, device: str):
    from kokoro import KModel

    return KModel(repo_id=repo_id).to(device).eval()


def _normalize_speed(value: Any) -> float:
    try:
        speed = float(value) if value is not None else 1.0
    except (TypeError, ValueError):
        raise ValueError(f'Invalid Kokoro speed {value!r}; expected a number') from None
    if not (_ACCEPTED_SPEED_RANGE[0] <= speed <= _ACCEPTED_SPEED_RANGE[1]):
        raise ValueError(
            f'Kokoro speed {speed} is outside the safe range '
            f'{_ACCEPTED_SPEED_RANGE[0]}..{_ACCEPTED_SPEED_RANGE[1]}'
        )
    return speed


def _safe(exc: Exception) -> str:
    text = str(exc) or exc.__class__.__name__
    return text[:300]
