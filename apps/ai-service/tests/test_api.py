import os

os.environ["AI_RUNTIME_MODE"] = "mock"
os.environ["AI_SERVICE_INTERNAL_TOKEN"] = "test-token"

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
AUTH = {"X-Lyreo-Internal-Token": "test-token"}


def request(provider: str = "GROQ", model: str = "mock", **overrides):
    payload = {
        "invocation_id": "test-invocation",
        "provider": provider,
        "model": model,
        "prompt": None,
        "input": {},
        "options": {},
    }
    payload.update(overrides)
    return payload


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["runtime_mode"] == "mock"


def test_internal_auth_required():
    response = client.post("/v1/llm/generate", json=request())
    assert response.status_code == 401


def test_mock_llm_contract():
    response = client.post(
        "/v1/llm/generate",
        headers=AUTH,
        json=request(prompt="Classify grammar", options={"response_schema": "lesson_grammar_annotations_v1"}),
    )
    assert response.status_code == 200
    body = response.json()
    assert "output" in body
    assert body["output"]["structured"] == {"items": []}


def test_mock_nlp_returns_sentence_scoped_entities():
    response = client.post(
        "/v1/nlp/analyze",
        headers=AUTH,
        json=request(
            provider="LOCAL_NLP",
            input={"sentences": ["Jennifer arrives at 6:00.", "NASA called John on Thursday."]},
        ),
    )
    assert response.status_code == 200
    output = response.json()["output"]
    assert len(output["sentence_entities"]) == 2
    assert output["sentence_entities"][0]["sentence_index"] == 0
    assert output["sentence_entities"][1]["sentence_index"] == 1


def test_mock_alignment_contract_uses_milliseconds():
    response = client.post(
        "/v1/align",
        headers=AUTH,
        json=request(
            provider="QWEN3_FORCED_ALIGNER",
            input={"audio_url": "https://example.invalid/audio.wav", "text": "Hello world"},
        ),
    )
    assert response.status_code == 200
    words = response.json()["output"]["words"]
    assert words
    assert {"index", "word", "start_ms", "end_ms"}.issubset(words[0])
    assert words[0]["end_ms"] >= words[0]["start_ms"]


def test_mock_tts_contract_returns_inline_audio():
    response = client.post(
        "/v1/tts",
        headers=AUTH,
        json=request(provider="GEMINI", input={"text": "Hello Lyreo"}),
    )
    assert response.status_code == 200
    output = response.json()["output"]
    assert output["audio_base64"]
    assert output["mime_type"].startswith("audio/")


def test_gemini_interaction_helpers_accept_2026_step_schema():
    from app.providers.gemini import _interaction_audio, _interaction_text, _interaction_usage

    payload = {
        'id': 'int_test',
        'steps': [
            {'type': 'model_output', 'content': [{'type': 'text', 'text': '{"score": 88}'}]},
            {'type': 'model_output', 'content': [{'type': 'audio', 'data': 'YWJj', 'mime_type': 'audio/mp3'}]},
        ],
        'usage': {'input_tokens': 12, 'output_tokens': 5},
    }
    assert _interaction_text(payload) == '{"score": 88}'
    assert _interaction_audio(payload)['data'] == 'YWJj'
    assert _interaction_usage(payload) == {'input_tokens': 12, 'output_tokens': 5}

def test_gemini_pcm_is_wrapped_as_valid_wav():
    import wave
    from io import BytesIO
    from app.providers.gemini import _pcm_s16le_mono_to_wav

    pcm = b'\x00\x00\x01\x00' * 120
    wav_bytes = _pcm_s16le_mono_to_wav(pcm, sample_rate=24_000)

    assert wav_bytes[:4] == b'RIFF'
    with wave.open(BytesIO(wav_bytes), 'rb') as wav_file:
        assert wav_file.getnchannels() == 1
        assert wav_file.getsampwidth() == 2
        assert wav_file.getframerate() == 24_000
        assert wav_file.readframes(wav_file.getnframes()) == pcm



def test_voice_discovery_requires_internal_token():
    response = client.get("/v1/tts/voices")
    assert response.status_code == 401


def test_voice_discovery_lists_local_kokoro_voices():
    response = client.get("/v1/tts/voices", headers=AUTH)
    assert response.status_code == 200
    voices = response.json()["voices"]
    assert voices
    by_id = {voice["voice_id"]: voice for voice in voices}
    assert all(voice["provider"] == "LOCAL_KOKORO" for voice in voices)
    assert by_id["af_heart"]["accent"] == "US"
    assert by_id["bf_emma"]["accent"] == "UK"


def test_mock_tts_local_kokoro_contract():
    response = client.post(
        "/v1/tts",
        headers=AUTH,
        json=request(
            provider="LOCAL_KOKORO",
            model="hexgrad/Kokoro-82M",
            input={"text": "Hello there."},
            options={"voice": "af_heart", "accent": "US", "speed": 1.0},
        ),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["output"]["audio_base64"]
    assert body["metadata"]["runtime"] == "mock"
    assert body["metadata"]["voice"] == "af_heart"


def test_generic_exception_handler_returns_safe_detail(monkeypatch):
    from app import main

    def broken_handler(*args, **kwargs):
        raise RuntimeError("database password leaked: super_secret_123")

    monkeypatch.setattr(main.mock, "stt", broken_handler)

    safe_client = TestClient(main.app, raise_server_exceptions=False)
    response = safe_client.post(
        "/v1/stt",
        headers=AUTH,
        json=request(provider="LOCAL_QWEN", input={"audio_url": "https://example.invalid/audio.wav"}),
    )
    assert response.status_code == 500
    assert response.json() == {"detail": "Internal server error"}
    assert "super_secret_123" not in response.text
    assert "RuntimeError" not in response.text


def test_qwen_inference_lock_serializes_execution():
    import asyncio
    from unittest.mock import AsyncMock, MagicMock
    from app.config import Settings
    from app.runtime.qwen import QwenRuntime
    from app.schemas import ExecuteRequest

    cfg = Settings(_env_file=None)
    runtime = QwenRuntime(cfg)

    execution_order = []

    async def fake_transcribe(*args, **kwargs):
        execution_order.append("start_stt")
        await asyncio.sleep(0.05)
        execution_order.append("end_stt")
        res = MagicMock()
        res.text = "transcribed"
        res.language = "English"
        return [res]

    async def fake_align(*args, **kwargs):
        execution_order.append("start_align")
        await asyncio.sleep(0.05)
        execution_order.append("end_align")
        return []

    mock_asr = MagicMock()
    mock_asr.transcribe = lambda *a, **kw: asyncio.run(fake_transcribe(*a, **kw))
    runtime._load_asr = AsyncMock(return_value=mock_asr)
    runtime._align_words = AsyncMock(side_effect=fake_align)

    req1 = ExecuteRequest(
        invocation_id="1", provider="LOCAL_QWEN", model="qwen",
        input={"audio": "/tmp/a.wav"}, options={},
    )
    req2 = ExecuteRequest(
        invocation_id="2", provider="LOCAL_QWEN", model="qwen",
        input={"audio": "/tmp/b.wav", "text": "hello"}, options={},
    )

    async def run_concurrent():
        await asyncio.gather(runtime.stt(req1), runtime.align(req2))

    asyncio.run(run_concurrent())

    # Because of _inference_lock, the operations cannot interleave
    # (i.e. one must finish before the other starts)
    assert execution_order in (
        ["start_stt", "end_stt", "start_align", "end_align"],
        ["start_align", "end_align", "start_stt", "end_stt"],
    )
