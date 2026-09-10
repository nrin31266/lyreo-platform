from __future__ import annotations

import asyncio
from typing import Any
from urllib.parse import unquote, urlparse

from ..config import Settings
from ..schemas import ExecuteRequest, ExecuteResponse


class QwenRuntime:
    """Lazy official `qwen-asr` runtime for local/self-host inference.

    ASR and ForcedAligner are loaded independently. Lyreo usually transcribes first and
    performs a dedicated alignment step only when the selected lesson activities need
    precise timestamps. Keeping the models separate avoids loading the aligner for plain
    transcription and avoids accidentally keeping two aligner copies in GPU memory.
    """

    def __init__(self, cfg: Settings):
        self.cfg = cfg
        self._asr = None
        self._aligner = None
        self._load_lock = asyncio.Lock()

    def _dtype(self):
        import torch

        try:
            return getattr(torch, self.cfg.qwen_dtype)
        except AttributeError as exc:
            raise ValueError(f'Unsupported torch dtype: {self.cfg.qwen_dtype}') from exc

    async def _load_asr(self):
        if self._asr is not None:
            return self._asr

        async with self._load_lock:
            if self._asr is None:
                from qwen_asr import Qwen3ASRModel

                self._asr = await asyncio.to_thread(
                    Qwen3ASRModel.from_pretrained,
                    self.cfg.qwen_asr_model,
                    dtype=self._dtype(),
                    device_map=self.cfg.qwen_device,
                    max_inference_batch_size=self.cfg.qwen_max_inference_batch_size,
                    max_new_tokens=self.cfg.qwen_max_new_tokens,
                )
        return self._asr

    async def _load_aligner(self):
        if self._aligner is not None:
            return self._aligner

        async with self._load_lock:
            if self._aligner is None:
                from qwen_asr import Qwen3ForcedAligner

                self._aligner = await asyncio.to_thread(
                    Qwen3ForcedAligner.from_pretrained,
                    self.cfg.qwen_aligner_model,
                    dtype=self._dtype(),
                    device_map=self.cfg.qwen_device,
                )
        return self._aligner

    async def stt(self, request: ExecuteRequest) -> ExecuteResponse:
        audio = _normalize_audio_source(
            request.input.get('audio') or request.input.get('audio_url')
        )
        if not audio:
            raise ValueError('STT requires input.audio or input.audio_url')

        language = request.options.get('language', 'English')
        model = await self._load_asr()
        results = await asyncio.to_thread(
            model.transcribe,
            audio=audio,
            language=language,
            context=request.options.get('context'),
            # Dedicated alignment is cheaper to reason about and is normally a separate
            # Lyreo build step. If a caller asks for timestamps, we align the transcript
            # below instead of permanently attaching the aligner to the ASR model.
            return_time_stamps=False,
        )
        if not results:
            raise ValueError('Qwen ASR returned no transcription result')

        result = results[0]
        text = str(getattr(result, 'text', '') or '')
        resolved_language = getattr(result, 'language', language)
        timestamps: list[dict[str, Any]] = []

        if bool(request.options.get('timestamps', False)) and text.strip():
            timestamps = await self._align_words(audio, text, resolved_language)

        return ExecuteResponse(
            output={
                'text': text,
                'language': resolved_language,
                'timestamps': timestamps,
            },
            metadata={
                'runtime': 'qwen',
                'model': self.cfg.qwen_asr_model,
            },
        )

    async def align(self, request: ExecuteRequest) -> ExecuteResponse:
        audio = _normalize_audio_source(
            request.input.get('audio') or request.input.get('audio_url')
        )
        text = request.input.get('text')
        if not audio or not text:
            raise ValueError('Alignment requires audio/audio_url and text')

        words = await self._align_words(
            audio,
            str(text),
            request.options.get('language', 'English'),
        )
        return ExecuteResponse(
            output={'words': words},
            metadata={
                'runtime': 'qwen',
                'model': self.cfg.qwen_aligner_model,
            },
        )

    async def _align_words(self, audio: Any, text: str, language: Any) -> list[dict[str, Any]]:
        model = await self._load_aligner()
        results = await asyncio.to_thread(
            model.align,
            audio=audio,
            text=text,
            language=language,
        )

        # Official API returns a list per input sample. Lyreo sends one sample/request,
        # so normalize it to one flat and provider-independent word timestamp contract.
        first = results[0] if results and isinstance(results[0], list) else results
        words: list[dict[str, Any]] = []
        for index, item in enumerate(first or []):
            mapped = _to_mapping(item)
            if isinstance(mapped, dict):
                text_value = mapped.get('text', '')
                start = mapped.get('start_time', 0)
                end = mapped.get('end_time', 0)
            else:
                text_value = str(item)
                start = 0
                end = 0

            words.append(
                {
                    'index': index,
                    'word': text_value,
                    'start_ms': round(float(start) * 1000),
                    'end_ms': round(float(end) * 1000),
                }
            )
        return words


def _to_mapping(value: Any) -> Any:
    if hasattr(value, 'model_dump'):
        return value.model_dump()
    if hasattr(value, '__dict__'):
        return dict(value.__dict__)
    if isinstance(value, (str, int, float, bool, type(None), dict, list)):
        return value
    return str(value)


def _normalize_audio_source(value: Any) -> Any:
    if isinstance(value, str) and value.startswith('file://'):
        parsed = urlparse(value)
        return unquote(parsed.path)
    return value
