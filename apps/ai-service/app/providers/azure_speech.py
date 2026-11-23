import asyncio
import io
import json
import re
import sys
import threading
import wave
from array import array
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Literal

from pydantic import BaseModel, Field


SAMPLE_RATE = 16_000
MAX_SECONDS = 181.5
MAX_WAV_BYTES = 6 * 1024 * 1024
RECOGNITION_OVERHEAD_SECONDS = 30
WORDS = re.compile(r"[^\W_]+(?:'[^\W_]+)*", re.UNICODE)


class AssessmentError(Exception):
    def __init__(self, status: int, code: str):
        super().__init__(code)
        self.status = status
        self.code = code


class Scores(BaseModel):
    overall: float | None = None
    accuracy: float | None = None
    fluency: float | None = None
    completeness: float | None = None
    prosody: None = None


class Phoneme(BaseModel):
    ipa: str
    accuracy: float | None
    startMs: int | None
    endMs: int | None


class Word(BaseModel):
    text: str
    referenceIndex: int | None = None
    error: Literal['NONE', 'OMISSION', 'INSERTION', 'MISPRONUNCIATION'] = 'NONE'
    accuracy: float | None = None
    startMs: int | None = None
    endMs: int | None = None
    phonemes: list[Phoneme] = Field(default_factory=list)


class Assessment(BaseModel):
    schemaVersion: Literal[1] = 1
    outcome: Literal['SCORED', 'NO_SPEECH']
    language: Literal['en-US'] = 'en-US'
    durationMs: int
    transcript: str | None = None
    scores: Scores = Field(default_factory=Scores)
    words: list[Word] = Field(default_factory=list)


@dataclass
class Utterance:
    offset: int
    duration: int
    text: str
    detail: dict


def _reference_tokens(value: str) -> list[str]:
    return WORDS.findall(value.replace('’', "'").replace('‘', "'"))


def _key(value: str) -> str:
    return ''.join(_reference_tokens(value)).casefold()


def _score(value) -> float | None:
    if type(value) in (int, float) and 0 <= value <= 100:
        return float(value)
    return None


def _span(details: dict, duration_ms: int) -> tuple[int | None, int | None]:
    offset, length = details.get('Offset'), details.get('Duration')
    if type(offset) is not int or type(length) is not int or offset < 0 or length <= 0:
        return None, None
    start = offset // 10_000
    end = min(duration_ms, (offset + length + 9_999) // 10_000)
    return (start, end) if start < end else (None, None)


def _word(raw: dict, duration_ms: int) -> Word:
    start, end = _span(raw, duration_ms)
    details = raw.get('PronunciationAssessment') or {}
    accuracy = _score(details.get('AccuracyScore'))
    phonemes = []
    for phoneme in raw.get('Phonemes') or []:
        if phoneme.get('Phoneme'):
            first, last = _span(phoneme, duration_ms)
            phonemes.append(Phoneme(
                ipa=phoneme['Phoneme'], startMs=first, endMs=last,
                accuracy=_score((phoneme.get('PronunciationAssessment') or {}).get('AccuracyScore')),
            ))
    mispronounced = details.get('ErrorType') == 'Mispronunciation' or (accuracy is not None and accuracy < 60)
    return Word(
        text=raw['Word'], startMs=start, endMs=end, accuracy=accuracy, phonemes=phonemes,
        error='MISPRONUNCIATION' if mispronounced else 'NONE',
    )


def normalize(utterances: list[Utterance], reference: list[str], duration_ms: int) -> Assessment:
    utterances = sorted(utterances, key=lambda utterance: utterance.offset)
    recognized = []
    fluency_signals = []
    for utterance in utterances:
        best = (utterance.detail.get('NBest') or [{}])[0]
        recognized.extend(
            _word(raw, duration_ms) for raw in (best.get('Words') or []) if raw.get('Word')
        )
        fluency = _score((best.get('PronunciationAssessment') or {}).get('FluencyScore'))
        if fluency is not None and utterance.duration > 0:
            fluency_signals.append((fluency, utterance.duration))

    if not recognized:
        return Assessment(outcome='NO_SPEECH', durationMs=duration_ms)

    alignment = SequenceMatcher(None, [_key(word) for word in reference],
                                [_key(word.text) for word in recognized], autojunk=False)
    words = []
    matched = []
    for operation, start, end, spoken_start, spoken_end in alignment.get_opcodes():
        if operation == 'equal':
            for index, word in enumerate(recognized[spoken_start:spoken_end], start):
                word.referenceIndex = index
                words.append(word)
                matched.append(word)
        else:
            words.extend(Word(text=reference[index], referenceIndex=index, error='OMISSION')
                         for index in range(start, end))
            words.extend(word.model_copy(update={'error': 'INSERTION'})
                         for word in recognized[spoken_start:spoken_end])

    accuracy_signals = [word.accuracy for word in matched if word.accuracy is not None]
    accuracy = sum(accuracy_signals) / len(accuracy_signals) if accuracy_signals else None
    fluency = (sum(value * duration for value, duration in fluency_signals)
               / sum(duration for _, duration in fluency_signals)) if fluency_signals else None
    completeness = 100 * len(matched) / len(reference)
    overall = None
    if accuracy is not None and fluency is not None:
        lowest, middle, highest = sorted((accuracy, fluency, completeness))
        overall = 0.6 * lowest + 0.2 * middle + 0.2 * highest
    return Assessment(
        outcome='SCORED', durationMs=duration_ms,
        transcript=' '.join(item.text for item in utterances if item.text) or None,
        scores=Scores(overall=overall, accuracy=accuracy, fluency=fluency, completeness=completeness),
        words=words,
    )


def _read_wav(audio: bytes) -> tuple[int, bool]:
    try:
        with wave.open(io.BytesIO(audio), 'rb') as wav:
            frames = wav.getnframes()
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, SAMPLE_RATE):
                raise AssessmentError(422, 'INVALID_ASSESSMENT_AUDIO')
            if not 0 < frames <= MAX_SECONDS * SAMPLE_RATE:
                raise AssessmentError(422, 'INVALID_ASSESSMENT_DURATION')
            data = wav.readframes(frames + 1)
            if len(data) != frames * 2:
                raise AssessmentError(422, 'INVALID_ASSESSMENT_AUDIO')
    except (wave.Error, EOFError) as error:
        raise AssessmentError(422, 'INVALID_ASSESSMENT_AUDIO') from error
    samples = array('h', data)
    if sys.byteorder != 'little':
        samples.byteswap()
    return (frames + 15) // 16, all(-32 < sample < 32 for sample in samples)


class AzureSpeech:
    def __init__(self, region: str, endpoint: str):
        self.region = region
        self.endpoint = endpoint
        self.slot = threading.BoundedSemaphore(1)

    async def assess(self, audio: bytes, reference_text: str, credential: str) -> Assessment:
        reference = _reference_tokens(reference_text)
        if not reference or len(reference) > 1_000:
            raise AssessmentError(422, 'INVALID_ASSESSMENT_REFERENCE')
        duration_ms, silent = await asyncio.to_thread(_read_wav, audio)
        if silent:
            return Assessment(outcome='NO_SPEECH', durationMs=duration_ms)
        if not self.slot.acquire(blocking=False):
            raise AssessmentError(429, 'AZURE_SPEECH_BUSY')

        wake = threading.Event()
        abandoned = threading.Event()
        deadline = duration_ms / 1_000 + RECOGNITION_OVERHEAD_SECONDS
        task = asyncio.create_task(asyncio.to_thread(
            self._using_slot, audio, reference, credential, wake, abandoned, deadline,
        ))
        # A timed-out caller leaves the SDK task in charge of stopping and releasing the slot.
        task.add_done_callback(lambda finished: None if finished.cancelled() else finished.exception())
        try:
            utterances = await asyncio.wait_for(asyncio.shield(task), deadline)
        except TimeoutError:
            raise AssessmentError(504, 'AZURE_SPEECH_TIMEOUT') from None
        finally:
            abandoned.set()
            wake.set()
        return normalize(utterances, reference, duration_ms)

    def _using_slot(self, audio, reference, credential, wake, abandoned, deadline):
        try:
            return self._recognize(audio, reference, credential, wake, abandoned, deadline)
        finally:
            self.slot.release()

    def _recognize(self, audio, reference, credential, wake, abandoned, deadline):
        try:
            import azure.cognitiveservices.speech as sdk
        except (ImportError, OSError):
            raise AssessmentError(503, 'AZURE_SPEECH_SDK_UNAVAILABLE') from None
        if not (self.region or self.endpoint):
            raise AssessmentError(422, 'AZURE_SPEECH_NOT_CONFIGURED')
        try:
            with TemporaryDirectory(prefix='lyreo-assessment-') as directory:
                path = Path(directory) / 'recording.wav'
                path.write_bytes(audio)
                return self._recognize_file(sdk, path, reference, credential, wake, abandoned, deadline)
        except AssessmentError:
            raise
        except Exception:
            raise AssessmentError(502, 'AZURE_SPEECH_FAILURE') from None

    def _recognize_file(self, sdk, path, reference, credential, wake, abandoned, deadline):
        connection = {'region': self.region} if self.region else {'endpoint': self.endpoint}
        config = sdk.SpeechConfig(subscription=credential, speech_recognition_language='en-US', **connection)
        config.set_property(sdk.PropertyId.SpeechServiceConnection_InitialSilenceTimeoutMs, '9000')
        recognizer = sdk.SpeechRecognizer(speech_config=config, audio_config=sdk.audio.AudioConfig(filename=str(path)))
        pronunciation = sdk.PronunciationAssessmentConfig(
            reference_text=' '.join(reference),
            grading_system=sdk.PronunciationAssessmentGradingSystem.HundredMark,
            granularity=sdk.PronunciationAssessmentGranularity.Phoneme,
        )
        pronunciation.phoneme_alphabet = 'IPA'
        pronunciation.apply_to(recognizer)
        utterances = []
        failures = []

        def recognized(event):
            result = event.result
            if result.reason == sdk.ResultReason.RecognizedSpeech:
                try:
                    detail = json.loads(result.properties.get(sdk.PropertyId.SpeechServiceResponse_JsonResult))
                    utterances.append(Utterance(result.offset, result.duration, result.text, detail))
                except (TypeError, ValueError):
                    failures.append(AssessmentError(502, 'AZURE_SPEECH_INVALID_RESULT'))
                    wake.set()

        def canceled(event):
            details = event.result.cancellation_details
            if details.reason != sdk.CancellationReason.EndOfStream:
                status, code = {
                    sdk.CancellationErrorCode.TooManyRequests: (429, 'AZURE_SPEECH_BUSY'),
                    sdk.CancellationErrorCode.AuthenticationFailure: (401, 'AZURE_SPEECH_AUTH_FAILED'),
                    sdk.CancellationErrorCode.Forbidden: (403, 'AZURE_SPEECH_FORBIDDEN'),
                    sdk.CancellationErrorCode.BadRequest: (422, 'AZURE_SPEECH_BAD_REQUEST'),
                    sdk.CancellationErrorCode.ServiceTimeout: (504, 'AZURE_SPEECH_TIMEOUT'),
                }.get(details.error_code, (502, 'AZURE_SPEECH_FAILURE'))
                failures.append(AssessmentError(status, code))
                wake.set()

        recognizer.recognized.connect(recognized)
        recognizer.canceled.connect(canceled)
        recognizer.session_stopped.connect(lambda _: wake.set())
        try:
            recognizer.start_continuous_recognition_async().get()
            if not wake.wait(deadline) or abandoned.is_set():
                raise AssessmentError(504, 'AZURE_SPEECH_TIMEOUT')
        finally:
            recognizer.stop_continuous_recognition_async().get()
        if failures:
            raise failures[0]
        return utterances
