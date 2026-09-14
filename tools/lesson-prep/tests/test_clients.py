import base64
import json

import httpx
import pytest

from lesson_prep.ai_service_client import AiServiceClient, AiServiceError


def _mock_transport(handler):
    return httpx.MockTransport(handler)


def test_ai_client_stt_normalizes_output():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/stt"
        assert request.headers["X-Lyreo-Internal-Token"] == "secret-token"
        body = json.loads(request.content)
        assert body["input"]["audio_url"] == "file:///tmp/a.wav"
        return httpx.Response(
            200,
            json={
                "output": {"text": "Hello.", "language": "English"},
                "metadata": {"model": "Qwen/Qwen3-ASR-0.6B"},
            },
        )

    client = AiServiceClient("http://ai.invalid", "secret-token")
    client._http = httpx.Client(transport=_mock_transport(handler))
    result = client.stt("file:///tmp/a.wav")
    assert result["text"] == "Hello."
    assert result["provider"] == "LOCAL_QWEN"
    assert result["model"] == "Qwen/Qwen3-ASR-0.6B"


def test_ai_client_rejects_empty_stt_text():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"output": {"text": "  "}})

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    with pytest.raises(AiServiceError, match="no transcript"):
        client.stt("file:///tmp/a.wav")


def test_ai_client_align_normalizes_output():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/align"
        body = json.loads(request.content)
        assert body["input"]["audio_url"] == "file:///tmp/a.wav"
        assert body["input"]["text"] == "Hello world."
        return httpx.Response(
            200,
            json={
                "output": {
                    "words": [
                        {"word": "Hello", "start_ms": 0, "end_ms": 400},
                        {"word": "world", "start_ms": 400, "end_ms": 800},
                    ]
                },
                "metadata": {"model": "Qwen/Qwen3-ForcedAligner-0.6B"},
            },
        )

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    result = client.align("file:///tmp/a.wav", "Hello world.")
    assert len(result["words"]) == 2
    assert result["provider"] == "LOCAL_QWEN"
    assert result["model"] == "Qwen/Qwen3-ForcedAligner-0.6B"


def test_ai_client_align_rejects_missing_words():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"output": {}})

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    with pytest.raises(AiServiceError, match="no normalized word timestamps"):
        client.align("file:///tmp/a.wav", "text")


def test_ai_client_tts_decodes_base64():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["options"]["voice"] == "af_heart"
        assert body["options"]["accent"] == "US"
        return httpx.Response(
            200,
            json={
                "output": {
                    "audio_base64": base64.b64encode(b"RIFFwav").decode(),
                    "mime_type": "audio/wav",
                },
                "metadata": {"voice": "af_heart", "accent": "US"},
            },
        )

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    result = client.tts("text", voice="af_heart", accent="US")
    assert result["audio_bytes"] == b"RIFFwav"
    assert result["voice"] == "af_heart"


def test_ai_client_tts_rejects_missing_audio():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"output": {}})

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    with pytest.raises(AiServiceError, match="no audio data"):
        client.tts("text", voice="af_heart", accent="US")


def test_ai_client_voices_contract():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/tts/voices"
        return httpx.Response(
            200,
            json={
                "voices": [
                    {"provider": "LOCAL_KOKORO", "voice_id": "af_heart", "accent": "US"},
                ]
            },
        )

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    voices = client.voices()
    assert voices[0]["voice_id"] == "af_heart"


def test_ai_client_auth_error_gives_helpful_hint():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"detail": "Unauthorized"})

    client = AiServiceClient("http://ai.invalid", "bad-token")
    client._http = httpx.Client(transport=_mock_transport(handler))
    with pytest.raises(AiServiceError, match="internal token mismatch"):
        client.stt("file:///tmp/a.wav")
