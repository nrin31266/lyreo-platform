from __future__ import annotations

import asyncio
import base64
import io
import math

from ..config import Settings
from ..schemas import ExecuteRequest, ExecuteResponse
from .audio import alignment_windows, normalize_audio_source, resolve_audio
from .execution import run_serialized


class WhisperXRuntime:
    """Runs one speech model on the selected device at a time."""

    def __init__(self, cfg: Settings):
        self.cfg = cfg
        self._asr = None
        self._aligner = None
        self._lock = asyncio.Lock()

    async def stt(self, request: ExecuteRequest) -> ExecuteResponse:
        return await run_serialized(self._lock, self._stt, request)

    async def _stt(self, request: ExecuteRequest) -> ExecuteResponse:
        waveform = await self._audio(request)
        fast_turn = request.options.get('timestamps') is False and len(waveform) <= 25 * 16_000
        transcriber = self._transcribe_fast if fast_turn else self._transcribe
        segments = await asyncio.to_thread(transcriber, waveform)
        text = ' '.join(segment['text'] for segment in segments)
        if not text:
            raise ValueError('WhisperX found no speech')
        # Core schedules dedicated alignment and stores these observed segment bounds first.
        return ExecuteResponse(
            output={'text': text, 'language': 'en', 'segments': segments, 'timestamps': []},
            metadata={'runtime': 'whisperx', 'model': self.cfg.whisperx_asr_model},
        )

    def _ensure_asr(self):
        import whisperx

        device = self.cfg.whisperx_device
        if self._asr is None:
            self._asr = whisperx.load_model(
                self.cfg.whisperx_asr_model, device, language='en', vad_method='silero',
                compute_type='int8_float16' if device == 'cuda' else 'int8',
            )
        return self._asr

    def _transcribe_fast(self, waveform):
        asr = self._ensure_asr()
        model = asr.model.model
        if not model.model_is_loaded:
            model.load_model()
        direct, _ = asr.model.transcribe(
            waveform, language='en', beam_size=1, best_of=1,
            vad_filter=False, condition_on_previous_text=False, without_timestamps=True,
        )
        duration_ms = round(len(waveform) / 16)
        segments = []
        for segment in direct:
            text = segment.text.strip()
            if not text:
                continue
            start_ms = max(0, round((segment.start or 0.0) * 1000))
            end_ms = min(duration_ms, max(start_ms, round((segment.end or 0.0) * 1000)))
            segments.append({'text': text, 'start_ms': start_ms, 'end_ms': end_ms})
        return segments

    def _transcribe(self, waveform):
        device = self.cfg.whisperx_device
        asr = self._ensure_asr()
        model = asr.model.model
        try:
            if not model.model_is_loaded:
                model.load_model()
            result = asr.transcribe(waveform, batch_size=1, language='en')
            segments = result['segments']
            word_count = sum(len(segment['text'].split()) for segment in segments)
            duration = len(waveform) / 16_000
            # Silero can detect an isolated fragment while dropping the vocals of a whole clip.
            if word_count == 0 or (duration >= 30 and word_count <= 3):
                direct = self._transcribe_without_vad(waveform)
                if sum(len(segment['text'].split()) for segment in direct) > word_count:
                    segments = direct
        finally:
            if device == 'cuda' and model.model_is_loaded:
                model.unload_model(to_cpu=True)
        return [
            {'text': segment['text'].strip(), 'start_ms': round(segment['start'] * 1000),
             'end_ms': min(round(len(waveform) / 16), round(segment['end'] * 1000))}
            for segment in segments if segment['text'].strip()
        ]

    def _transcribe_without_vad(self, waveform):
        segments = []
        window_size = 30 * 16_000
        for offset in range(0, len(waveform), window_size):
            window = waveform[offset:offset + window_size]
            direct, _ = self._asr.model.transcribe(
                window, language='en', vad_filter=False,
                condition_on_previous_text=False, beam_size=5,
            )
            for segment in direct:
                text = segment.text.strip()
                start = max(0, segment.start)
                # Whisper timestamps can extend past the input window into the next one.
                end = min(len(window) / 16_000, segment.end)
                if not text or end <= start:
                    continue
                if text[-1] not in '.!?':
                    text += '.'
                segments.append({
                    'text': text,
                    'start': offset / 16_000 + start,
                    'end': offset / 16_000 + end,
                })
        return segments

    async def align(self, request: ExecuteRequest) -> ExecuteResponse:
        return await run_serialized(self._lock, self._align, request)

    async def _align(self, request: ExecuteRequest) -> ExecuteResponse:
        text = request.input.get('text')
        if not isinstance(text, str) or not text.strip():
            raise ValueError('Alignment requires text')
        waveform = await self._audio(request)
        windows = alignment_windows(waveform, text, request.input.get('segments'), end_tolerance_ms=50)
        segments = [
            {'text': window_text, 'start': start / 16000, 'end': (start + len(window)) / 16000}
            for start, window, window_text in windows
        ]
        words = await asyncio.to_thread(self._force_align, waveform, segments)
        return ExecuteResponse(output={'words': words}, metadata={
            'runtime': 'whisperx', 'model': self.cfg.whisperx_aligner_model,
        })

    def _force_align(self, waveform, segments):
        import nltk
        import torch
        import whisperx

        device = self.cfg.whisperx_device
        if device == 'cuda' and self._asr is not None:
            asr_model = self._asr.model.model
            if asr_model.model_is_loaded:
                asr_model.unload_model(to_cpu=True)
        if self._aligner is None:
            try:
                nltk.data.find('tokenizers/punkt_tab/english')
            except LookupError:
                if not nltk.download('punkt_tab', quiet=True):
                    raise RuntimeError('WhisperX could not load the English sentence tokenizer') from None
            self._aligner = whisperx.load_align_model(
                language_code='en', device='cpu', model_name=self.cfg.whisperx_aligner_model,
            )
        model, metadata = self._aligner
        try:
            model.to(device)
            result = whisperx.align(
                segments, model, metadata, waveform, device, interpolate_method='ignore',
            )
        finally:
            if device == 'cuda':
                model.to('cpu')
                torch.cuda.empty_cache()

        words = []
        for item in result['word_segments']:
            start, end = item.get('start'), item.get('end')
            if start is None or end is None or not math.isfinite(start) or not math.isfinite(end):
                continue
            words.append({'index': len(words), 'word': item['word'],
                          'start_ms': round(start * 1000), 'end_ms': round(end * 1000)})
        return words

    @staticmethod
    async def _audio(request):
        raw_b64 = request.input.get('audio_base64')
        if isinstance(raw_b64, str) and raw_b64.strip():
            try:
                decoded = base64.b64decode(raw_b64.strip(), validate=True)
            except Exception as exc:
                raise ValueError('WhisperX received invalid audio_base64') from exc
            if not decoded:
                raise ValueError('WhisperX audio_base64 decoded to empty bytes')
            return await asyncio.to_thread(_decode_audio, decoded)
        source = normalize_audio_source(request.input.get('audio') or request.input.get('audio_url'))
        if not source:
            raise ValueError('WhisperX requires input.audio, input.audio_url, or input.audio_base64')
        return await resolve_audio(source, _decode_audio)


def _decode_audio(source):
    from faster_whisper.audio import decode_audio

    waveform = decode_audio(io.BytesIO(source) if isinstance(source, bytes) else source, sampling_rate=16000)
    if not len(waveform):
        raise ValueError('Audio contains no samples')
    return waveform
