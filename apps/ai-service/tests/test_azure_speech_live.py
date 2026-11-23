"""Opt-in, no learner audio. Run with LYREO_AZURE_SMOKE=1 and Azure F0 credentials."""

import os
import subprocess
import wave

import pytest
from dotenv import dotenv_values
from fastapi.testclient import TestClient

from app import main
from app.providers.azure_speech import AzureSpeech


@pytest.mark.skipif(os.getenv('LYREO_AZURE_SMOKE') != '1', reason='needs an Azure Speech F0 resource')
@pytest.mark.parametrize(('segments', 'minimum_ms'), [
    (['Good morning everyone', 'please open your window'], 0),
    (['The little garden is quiet in the morning',
      'the flowers will open when the sun is high',
      'we will walk past the river after lunch',
      'then we will come home before the sunrise'], 20_000),
])
def test_azure_f0_reads_to_end_across_pauses(tmp_path, monkeypatch, segments, minimum_ms):
    env = dotenv_values('.env')
    credential = os.environ.get('AZURE_SPEECH_KEY') or env.get('AZURE_SPEECH_KEY')
    if not credential:
        pytest.fail('Azure Speech smoke credential is missing')
    region = env.get('AZURE_SPEECH_REGION') or os.environ.get('AZURE_SPEECH_REGION', '')
    endpoint = env.get('AZURE_SPEECH_ENDPOINT') or os.environ.get('AZURE_SPEECH_ENDPOINT', '')
    monkeypatch.setattr(main, 'azure_speech', AzureSpeech(region, endpoint))
    recording = tmp_path / 'assessment.wav'
    with wave.open(str(recording), 'wb') as output:
        output.setparams((1, 2, 16_000, 0, 'NONE', 'not compressed'))
        output.writeframes(bytes(16_000 * 2 * 2))
        for index, segment in enumerate(segments):
            utterance = tmp_path / f'utterance-{index}.wav'
            subprocess.run(['espeak-ng', '-v', 'en-us', '-s', '100', '-w', str(utterance), segment], check=True)
            audio = subprocess.run(
                ['ffmpeg', '-v', 'error', '-i', str(utterance), '-ar', '16000', '-ac', '1',
                 '-f', 's16le', 'pipe:1'], check=True, capture_output=True,
            ).stdout
            if index:
                output.writeframes(bytes(16_000 * 2 * 2))
            output.writeframes(audio)
    response = TestClient(main.app).post(
        '/v1/speech/pronunciation-assessment',
        headers={'X-Lyreo-Internal-Token': main.settings().ai_service_internal_token,
                 'X-Lyreo-Provider-Credential': credential},
        data={'referenceText': ' '.join(segments)},
        files={'audio': ('assessment.wav', recording.read_bytes(), 'audio/wav')},
    )
    assert response.status_code == 200, f'Azure smoke HTTP {response.status_code}: {response.json().get("detail")}'
    result = response.json()
    assert result['durationMs'] > minimum_ms
    assert result['outcome'] == 'SCORED'
    assert result['scores']['prosody'] is None
    timed = [word for word in result['words'] if word['startMs'] is not None]
    starts = [word['startMs'] for word in timed]
    assert starts == sorted(starts)
    assert timed[-1]['text'].lower() == segments[-1].split()[-1]
    assert timed[-1]['startMs'] >= minimum_ms
