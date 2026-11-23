import asyncio
import io
import threading
import wave

import httpx
import pytest

from app.main import app
from app.providers.azure_speech import AssessmentError, AzureSpeech, Utterance, normalize


def wav_bytes(sample: bytes) -> bytes:
    output = io.BytesIO()
    with wave.open(output, 'wb') as wav:
        wav.setparams((1, 2, 16_000, 0, 'NONE', 'not compressed'))
        wav.writeframes(sample * 16_000)
    return output.getvalue()


def spoken(text, offset, score=90, **extra):
    return {'Word': text, 'Offset': offset * 10_000, 'Duration': 200 * 10_000,
            'PronunciationAssessment': {'AccuracyScore': score}, **extra}


def utterance(offset, duration, words, fluency):
    return Utterance(offset * 10_000, duration * 10_000, ' '.join(word['Word'] for word in words), {
        'NBest': [{'Words': words, 'PronunciationAssessment': {'FluencyScore': fluency}}],
    })


def test_continuous_results_use_global_time_and_align_repeated_words():
    first = utterance(0, 200, [spoken('go', 0)], 70)
    second = utterance(25_000, 800, [spoken('go', 25_000), spoken('wrong', 25_250),
                         spoken('now', 25_500, 42, Phonemes=[{'Phoneme': 'aʊ'}])], 90)
    result = normalize([second, first], ['Go', 'go', 'right', 'now'], 26_000)
    assert result.transcript == 'go go wrong now'
    assert [word.error for word in result.words] == [
        'NONE', 'NONE', 'OMISSION', 'INSERTION', 'MISPRONUNCIATION',
    ]
    assert [word.referenceIndex for word in result.words] == [0, 1, 2, None, 3]
    assert [word.startMs for word in result.words] == [0, 25_000, None, 25_250, 25_500]
    assert result.words[-1].phonemes[0].endMs is None
    assert result.scores.fluency == 86
    assert result.scores.completeness == 75
    assert result.scores.prosody is None
    assert normalize([], ['word'], 1000).scores.overall is None


@pytest.mark.asyncio
async def test_multipart_contract_and_silence(monkeypatch):
    from app import main

    token = main.settings().ai_service_internal_token
    auth = {'X-Lyreo-Internal-Token': token, 'X-Lyreo-Provider-Credential': 'fixture'}
    def recognize(_audio, reference, credential, *_):
        assert reference == ["Don't", 'skip', 'these', 'words']
        assert credential == 'fixture'
        return [utterance(0, 800, [spoken(word, 200 * index)
                                   for index, word in enumerate(reference)], 85)]

    monkeypatch.setattr(main.azure_speech, '_recognize', recognize)
    path = '/v1/speech/pronunciation-assessment'
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url='http://test') as service:
        assert (await service.post(path, data={'referenceText': 'Read this'},
                                   files={'audio': wav_bytes(b'\x00\x00')})).status_code == 401
        silence = await service.post(path, headers=auth, data={'referenceText': 'Read this'},
                                     files={'audio': wav_bytes(b'\x00\x00')})
        assert silence.status_code == 200
        assert silence.json()['outcome'] == 'NO_SPEECH'
        assert all(value is None for value in silence.json()['scores'].values())
        invalid = await service.post(path, headers=auth, data={'referenceText': 'Read this'}, files={'audio': b'invalid'})
        assert invalid.status_code == 422
        response = await service.post(path, headers=auth, data={'referenceText': 'Don’t skip (these words).'},
                                      files={'audio': wav_bytes(b'\x50\x01')})
    assert response.status_code == 200
    assert response.json()['schemaVersion'] == 1
    assert response.json()['outcome'] == 'SCORED'
    assert response.json()['words'][-1]['endMs'] == 800


@pytest.mark.asyncio
@pytest.mark.parametrize('timed_out', [False, True])
async def test_abandoned_request_holds_slot_until_recognizer_stops(monkeypatch, timed_out):
    if timed_out:
        monkeypatch.setattr('app.providers.azure_speech.RECOGNITION_OVERHEAD_SECONDS', -0.8)
    adapter = AzureSpeech('region', '')
    started = threading.Event()
    stop_requested = threading.Event()
    stopped = threading.Event()
    audio = wav_bytes(b'\x50\x01')

    def recognize(_audio, _reference, _credential, wake, abandoned, _deadline):
        started.set()
        assert wake.wait(2) and abandoned.is_set()
        stop_requested.set()
        assert stopped.wait(2)
        return []

    monkeypatch.setattr(adapter, '_recognize', recognize)
    task = asyncio.create_task(adapter.assess(audio, 'try again', 'fixture'))
    try:
        assert await asyncio.to_thread(started.wait, 2)
        if timed_out:
            with pytest.raises(AssessmentError, match='AZURE_SPEECH_TIMEOUT'):
                await task
        else:
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        assert await asyncio.to_thread(stop_requested.wait, 2)
        with pytest.raises(AssessmentError, match='AZURE_SPEECH_BUSY'):
            await adapter.assess(audio, 'second request', 'fixture')
    finally:
        stopped.set()
