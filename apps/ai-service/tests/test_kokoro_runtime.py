import io
import wave

import pytest

from app.config import Settings
from app.runtime.kokoro import (
    KOKORO_VOICES,
    KokoroRuntime,
    chunk_text,
    concatenate_wav,
)


@pytest.fixture
def runtime():
    return KokoroRuntime(Settings(_env_file=None))


def test_voice_discovery_serves_supported_local_voices(runtime):
    voices = runtime.voices()
    assert voices
    assert all(item['provider'] == 'LOCAL_KOKORO' for item in voices)
    assert all(item['language'] == 'English' for item in voices)
    ids = {item['voice_id'] for item in voices}
    assert ids == set(KOKORO_VOICES)
    assert {'af_heart', 'bf_emma', 'am_adam'} <= ids
    us = [item for item in voices if item['voice_id'] == 'af_heart']
    uk = [item for item in voices if item['voice_id'] == 'bf_emma']
    assert us and us[0]['accent'] == 'US'
    assert uk and uk[0]['accent'] == 'UK'


def test_unknown_voice_is_rejected_before_any_model_load(runtime):
    with pytest.raises(ValueError, match='Unsupported Kokoro voice'):
        runtime._validate_voice('af_nonexistent')


def test_voice_accent_mismatch_is_rejected(runtime):
    with pytest.raises(ValueError, match='not US English'):
        runtime.resolve_voice('bf_emma', 'US')


def test_voice_accent_resolution_without_requested_accent(runtime):
    voice_id, accent = runtime.resolve_voice('af_heart', '')
    assert voice_id == 'af_heart'
    assert accent == 'US'


def test_invalid_speed_is_rejected_before_model_load():
    from app.runtime.kokoro import _normalize_speed

    with pytest.raises(ValueError, match='outside the safe range'):
        _normalize_speed(5.0)
    with pytest.raises(ValueError, match='Invalid Kokoro speed'):
        _normalize_speed('fast')


def test_chunking_is_deterministic_and_keeps_words_whole():
    text = (
        'First sentence of a reasonable length. '
        + 'Second sentence that should join the first when short. '
        + 'Third.'
    )
    assert chunk_text(text, 200) == chunk_text(text, 200)
    for chunk in chunk_text(text, 60):
        assert len(chunk) <= 60


def test_chunking_returns_single_chunk_for_short_text():
    assert chunk_text('Short text.', 450) == ['Short text.']


def test_chunking_handles_blank_text():
    assert chunk_text('   ', 450) == []


def test_concatenate_wav_is_valid_mono_wav_with_gap():
    first = [100, 200, 300]
    second = [400, 500]
    data = concatenate_wav([first, second], sample_rate=1000, gap_ms=100)
    with wave.open(io.BytesIO(data), 'rb') as wav:
        assert wav.getnchannels() == 1
        assert wav.getsampwidth() == 2
        assert wav.getframerate() == 1000
        # one 100 ms silence gap at 1000 Hz = 100 zero samples
        assert wav.getnframes() == len(first) + len(second) + 100


def test_alignment_result_flattening_handles_wrapped_and_flat_shapes():
    from app.runtime.qwen import _flatten_alignment_results

    wrapped = [{"items": [
        {"text": "Good", "start_time": 0.32, "end_time": 0.48},
        {"text": "morning", "start_time": 0.48, "end_time": 0.88},
    ]}]
    flat = [
        {"word": "Good", "start_ms": 320, "end_ms": 480},
        {"word": "morning", "start_ms": 480, "end_ms": 880},
    ]

    assert len(_flatten_alignment_results(wrapped)) == 2
    assert len(_flatten_alignment_results(flat)) == 2
    assert len(_flatten_alignment_results([])) == 0
