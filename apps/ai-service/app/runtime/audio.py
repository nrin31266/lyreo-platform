import asyncio
import math
from typing import Any
from urllib.parse import unquote, urlparse

import httpx
from fastapi import HTTPException


async def resolve_audio(source: Any, decoder):
    if isinstance(source, str) and urlparse(source).scheme in ('http', 'https'):
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
    return await asyncio.to_thread(decoder, source)


def alignment_windows(waveform, text: str, segments, *, timestamp_precision_ms=0):
    duration_ms = len(waveform) / 16
    # Some models quantize timestamps to frames, while the slice stops at real samples.
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
        # Adjacent windows can use opposite halves of the gap without aligning the same samples.
        left_limit = (segments[index - 1]['end_ms'] + segment['start_ms']) // 2 if index else 0
        right_limit = ((segment['end_ms'] + segments[index + 1]['start_ms']) // 2
                       if index + 1 < len(segments) else duration_ms)
        segment_ms = segment['end_ms'] - segment['start_ms']
        padding_ms = min(500, max(0, (120_000 - segment_ms) // 2))
        start = round(max(left_limit, segment['start_ms'] - padding_ms) * 16)
        end = min(len(waveform), round(min(right_limit, segment['end_ms'] + padding_ms) * 16))
        if end - start > 120 * 16_000:
            raise ValueError('Each padded alignment segment must be at most 120 seconds; split at a known audio anchor')
        windows.append((start, waveform[start:end], segment['text']))
    return windows


def normalize_audio_source(value: Any) -> Any:
    if isinstance(value, str) and value.startswith('file://'):
        parsed = urlparse(value)
        return unquote(parsed.path)
    return value
