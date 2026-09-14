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

# Standard Kokoro-82M English voice catalog from official VOICES.md.
# Voice prefix encodes accent: 'a*' = American English, 'b*' = British English.
KOKORO_VOICES: dict[str, str] = {
    # American English - Female (11)
    'af_heart': 'US',
    'af_alloy': 'US',
    'af_aoede': 'US',
    'af_bella': 'US',
    'af_jessica': 'US',
    'af_kore': 'US',
    'af_nicole': 'US',
    'af_nova': 'US',
    'af_river': 'US',
    'af_sarah': 'US',
    'af_sky': 'US',
    # American English - Male (9)
    'am_adam': 'US',
    'am_echo': 'US',
    'am_eric': 'US',
    'am_fenrir': 'US',
    'am_liam': 'US',
    'am_michael': 'US',
    'am_onyx': 'US',
    'am_puck': 'US',
    'am_santa': 'US',
    # British English - Female (4)
    'bf_alice': 'UK',
    'bf_emma': 'UK',
    'bf_isabella': 'UK',
    'bf_lily': 'UK',
    # British English - Male (4)
    'bm_daniel': 'UK',
    'bm_fable': 'UK',
    'bm_george': 'UK',
    'bm_lewis': 'UK',
}

_ACCEPTED_SPEED_RANGE = (0.5, 2.0)


class KokoroRuntime:
    """Lazy local Kokoro TTS runtime.

    The pipeline and model are loaded only when a TTS call actually arrives; voice
    discovery and option validation never import the heavy runtime. Long text is
    streamed via Kokoro's native phoneme chunking; all yielded audio segments are
    combined in order into a single mono WAV.
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

        pipeline = await self._load(accent)
        voice = await self._load_voice(accent, voice_id)

        samples, segment_count = await asyncio.to_thread(
            self._synthesize, pipeline, voice, text, speed
        )

        wav = _to_wav(samples, sample_rate=self.cfg.kokoro_sample_rate)
        return ExecuteResponse(
            output={
                'audio_base64': base64.b64encode(wav).decode(),
                'mime_type': 'audio/wav',
                'sample_rate': self.cfg.kokoro_sample_rate,
                'chunks': segment_count,
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

    def _synthesize(self, pipeline, voice, text: str, speed: float) -> tuple[list[int], int]:
        try:
            generator = pipeline(text, voice=voice, speed=speed)
            samples: list[int] = []
            segment_count = 0
            for result in generator:
                if result is not None and getattr(result, 'audio', None) is not None:
                    converted = _tensor_to_int16(result.audio)
                    if converted:
                        samples.extend(converted)
                        segment_count += 1
            return samples, segment_count
        except Exception as exc:
            raise ValueError(f'Kokoro synthesis failed: {_safe(exc)}') from exc


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
