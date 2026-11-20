from __future__ import annotations

import asyncio
import io
import logging
import math
from typing import Any
from urllib.parse import unquote, urlparse

import httpx
from fastapi import HTTPException

from ..config import Settings
from ..schemas import ExecuteRequest, ExecuteResponse
from .execution import run_serialized

logger = logging.getLogger(__name__)


def _empty_cuda_cache() -> None:
    try:
        import torch

        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except (ImportError, AttributeError):
        pass


class QwenRuntime:
    """Lazy official `qwen-asr` runtime for local/self-host inference.

    ASR and ForcedAligner are loaded independently. Lyreo usually transcribes first and
    performs a dedicated alignment step only when the selected lesson activities need
    precise timestamps. Keeping the models separate avoids loading the aligner for plain
    transcription. Both wrappers stay cached, with only the active model on GPU.
    """

    def __init__(self, cfg: Settings):
        self.cfg = cfg
        self._asr = None
        self._aligner = None
        self._active_model = None
        self._inference_lock = asyncio.Lock()

    def _dtype(self):
        import torch

        try:
            return getattr(torch, self.cfg.qwen_dtype)
        except AttributeError as exc:
            raise ValueError(f'Unsupported torch dtype: {self.cfg.qwen_dtype}') from exc

    async def _load_asr(self):
        if self._asr is None:
            from qwen_asr import Qwen3ASRModel

            self._asr = await asyncio.to_thread(
                Qwen3ASRModel.from_pretrained,
                self.cfg.qwen_asr_model,
                dtype=self._dtype(),
                device_map='cpu',
                max_inference_batch_size=self.cfg.qwen_max_inference_batch_size,
                max_new_tokens=self.cfg.qwen_max_new_tokens,
            )
        return await self._activate(self._asr)

    async def _load_aligner(self):
        if self._aligner is None:
            from qwen_asr import Qwen3ForcedAligner

            self._aligner = await asyncio.to_thread(
                Qwen3ForcedAligner.from_pretrained,
                self.cfg.qwen_aligner_model,
                dtype=self._dtype(),
                device_map='cpu',
            )
        return await self._activate(self._aligner)

    async def _activate(self, model):
        if self._active_model is model:
            return model
        try:
            if self._active_model is not None:
                await asyncio.to_thread(_move_model, self._active_model, 'cpu')
                self._active_model = None
                _empty_cuda_cache()
            await asyncio.to_thread(_move_model, model, self.cfg.qwen_device)
        except Exception:
            # A failed .to() may leave weights on mixed devices; never reuse that state.
            self._active_model = None
            for name in ('_asr', '_aligner'):
                cached = getattr(self, name)
                if cached is not None:
                    try:
                        await asyncio.to_thread(cached.model.to, 'cpu')
                        cached.device = cached.model.device
                    except Exception:
                        setattr(self, name, None)
                        logger.exception('Could not return Qwen %s to CPU; invalidated cache', name)
            _empty_cuda_cache()
            raise
        self._active_model = model
        return model

    async def stt(self, request: ExecuteRequest) -> ExecuteResponse:
        return await run_serialized(self._inference_lock, self._stt, request)

    async def _stt(self, request: ExecuteRequest) -> ExecuteResponse:
        source = _normalize_audio_source(request.input.get('audio') or request.input.get('audio_url'))
        if not source:
            raise ValueError('STT requires input.audio or input.audio_url')
        audio = await _resolve_audio(source)
        language = request.options.get('language', 'English')
        model = await self._load_asr()
        segments = []
        # Measured on the 4 GB worker: an unchunked 300 s input exhausts VRAM.
        for start, window in _audio_windows(audio):
            _empty_cuda_cache()
            try:
                results = await asyncio.to_thread(
                    model.transcribe, audio=(window, 16_000), language=language,
                    context=request.options.get('context'), return_time_stamps=False,
                )
            finally:
                _empty_cuda_cache()
            if not results:
                raise ValueError('Qwen ASR returned no transcription result')
            text = str(getattr(results[0], 'text', '') or '').strip()
            if text:
                segments.append({'text': text, 'start_ms': round(start / 16),
                                 'end_ms': round((start + len(window)) / 16)})
        del model
        text = ' '.join(segment['text'] for segment in segments)
        timestamps = []
        if request.options.get('timestamps', False) and request.options.get('allow_alignment', True) and text:
            # Switch models once before alignment, rather than once per window.
            timestamps = await self._align_words((audio, 16_000), text, language, segments)
        return ExecuteResponse(
            output={'text': text, 'language': language, 'timestamps': timestamps, 'segments': segments},
            metadata={'runtime': 'qwen', 'model': self.cfg.qwen_asr_model},
        )

    async def align(self, request: ExecuteRequest) -> ExecuteResponse:
        return await run_serialized(self._inference_lock, self._align, request)

    async def _align(self, request: ExecuteRequest) -> ExecuteResponse:
        audio = _normalize_audio_source(request.input.get('audio') or request.input.get('audio_url'))
        text = request.input.get('text')
        if not audio or not isinstance(text, str) or not text.strip():
            raise ValueError('Alignment requires audio/audio_url and text')
        words = await self._align_words(
            audio, text, request.options.get('language', 'English'), request.input.get('segments'),
        )
        return ExecuteResponse(output={'words': words}, metadata={
            'runtime': 'qwen', 'model': self.cfg.qwen_aligner_model,
        })

    async def _align_words(self, audio: Any, text: str, language: Any, segments=None) -> list[dict[str, Any]]:
        waveform = await _resolve_audio(audio)
        model = await self._load_aligner()
        windows = _alignment_windows(
            waveform, text, segments,
            timestamp_precision_ms=getattr(model, 'timestamp_segment_time', 0),
        )
        words = []
        for start, window, window_text in windows:
            _empty_cuda_cache()
            try:
                results = await asyncio.to_thread(
                    model.align, audio=(window, 16_000), text=window_text, language=language,
                )
            finally:
                _empty_cuda_cache()
            items = _flatten_alignment_results(results)
            if not items:
                raise ValueError('Qwen aligner returned no words')
            for item in items:
                word = _bound_alignment_word(_alignment_word(item), len(window) / 16,
                                             getattr(model, 'timestamp_segment_time', 0))
                word['index'] = len(words)
                word['start_ms'] += round(start / 16)
                word['end_ms'] += round(start / 16)
                words.append(word)
        return words


def _move_model(runtime, device: str):
    if str(runtime.model.device) != device:
        runtime.model.to(device)
    runtime.device = runtime.model.device


def _bound_alignment_word(word: dict[str, Any], duration_ms: float, precision_ms: float) -> dict[str, Any]:
    # A measured final model frame can straddle the actual audio end; do not repair other errors.
    if (math.isfinite(precision_ms) and precision_ms > 0
            and 0 <= word['start_ms'] < duration_ms < word['end_ms'] <= math.ceil(duration_ms / precision_ms) * precision_ms):
        return {**word, 'end_ms': round(duration_ms)}
    return word


async def _resolve_audio(source: Any):
    if isinstance(source, str) and urlparse(source).scheme in ('http', 'https'):
        # Async HTTPX races IPv4/IPv6 addresses, unlike qwen-asr's blocking urllib download.
        try:
            async with asyncio.timeout(120):
                async with httpx.AsyncClient(timeout=httpx.Timeout(30, connect=5), follow_redirects=True) as client:
                    response = await client.get(source)
                    response.raise_for_status()
                    source = response.content
        except (httpx.TimeoutException, TimeoutError):
            raise HTTPException(504, 'Audio download timed out') from None
        except httpx.HTTPError:
            raise HTTPException(502, 'Audio download failed') from None
    return await asyncio.to_thread(_read_audio, source)


def _read_audio(source: Any):
    from qwen_asr.inference.utils import normalize_audio_input
    if isinstance(source, bytes):
        import soundfile

        source = soundfile.read(io.BytesIO(source), dtype='float32')
    waveform = normalize_audio_input(source)
    if not len(waveform):
        raise ValueError('Audio contains no samples')
    return waveform


def _audio_windows(waveform):
    # shortcut: fixed ASR windows can cut a word; transcript review is required at seams.
    limit = 115 * 16_000
    return [(start, waveform[start:start + limit]) for start in range(0, len(waveform), limit)]


def _alignment_windows(waveform, text: str, segments, *, timestamp_precision_ms=0):
    duration_ms = len(waveform) / 16
    # Qwen quantizes timestamps to model frames; slicing still stops at real audio samples.
    anchor_end_ms = (math.ceil(duration_ms / timestamp_precision_ms) * timestamp_precision_ms
                     if timestamp_precision_ms > 0 else math.ceil(duration_ms))
    if segments is None or segments == []:
        if len(waveform) > 120 * 16_000:
            raise ValueError('Alignment over 120 seconds requires anchored transcript segments')
        return [(0, waveform, text)]
    if not isinstance(segments, list) or not all(isinstance(s, dict) for s in segments):
        raise ValueError('Alignment segments must be a list of text/start_ms/end_ms objects')
    previous_end = 0
    for segment in segments:
        start = segment.get('start_ms')
        end = segment.get('end_ms')
        if (not isinstance(start, int) or isinstance(start, bool)
                or not isinstance(end, int) or isinstance(end, bool)
                or start < previous_end or start >= duration_ms or end <= start or end > anchor_end_ms
                or not isinstance(segment.get('text'), str) or not segment['text'].strip()):
            raise ValueError('Alignment segments require ordered non-overlapping in-bounds millisecond anchors')
        previous_end = end
    if ' '.join(' '.join(s['text'].split()) for s in segments) != ' '.join(text.split()):
        raise ValueError('Alignment segment text must cover the complete approved transcript in order')
    windows = []
    for index, segment in enumerate(segments):
        previous_end = segments[index - 1]['end_ms'] if index else 0
        next_start = segments[index + 1]['start_ms'] if index + 1 < len(segments) else duration_ms
        segment_ms = segment['end_ms'] - segment['start_ms']
        padding_ms = min(500, max(0, (120_000 - segment_ms) / 2))
        start = round(max(previous_end, segment['start_ms'] - padding_ms) * 16)
        end = min(len(waveform), round(min(next_start, segment['end_ms'] + padding_ms) * 16))
        if end - start > 120 * 16_000:
            raise ValueError('Each padded alignment segment must be at most 120 seconds; split at a known audio anchor')
        windows.append((start, waveform[start:end], segment['text']))
    return windows


def _alignment_word(item: Any) -> dict[str, Any]:
    mapped = _to_mapping(item)
    if not isinstance(mapped, dict):
        raise ValueError('Qwen aligner returned an invalid word')
    text = mapped.get('text') or mapped.get('word')
    if not isinstance(text, str) or not text.strip():
        raise ValueError('Qwen aligner returned an empty word')
    if 'start_time' in mapped and 'end_time' in mapped:
        start, end, factor = mapped['start_time'], mapped['end_time'], 1000
    elif 'start_ms' in mapped and 'end_ms' in mapped:
        start, end, factor = mapped['start_ms'], mapped['end_ms'], 1
    else:
        raise ValueError('Qwen aligner returned a word without timing')
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in (start, end)):
        raise ValueError('Qwen aligner returned non-finite word timing')
    return {'word': text, 'start_ms': round(start * factor), 'end_ms': round(end * factor)}


def _flatten_alignment_results(results: Any) -> list[Any]:
    """Normalize both qwen-asr output shapes into one flat item list.

    ForcedAligner returns ``ForcedAlignResult(items=[ForcedAlignItem(...)])`` wrappers;
    ASR returns flat word objects. Either way the caller gets raw alignment items.
    """
    first = results[0] if results and isinstance(results[0], list) else results
    items: list[Any] = []
    for entry in first or []:
        mapped = _to_mapping(entry)
        nested = mapped.get('items') if isinstance(mapped, dict) else None
        if isinstance(nested, list):
            items.extend(nested)
        else:
            items.append(entry)
    return items


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
