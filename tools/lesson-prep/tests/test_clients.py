import base64
import json
from pathlib import Path

import httpx
import pytest

from lesson_prep.ai_service_client import AiServiceClient, AiServiceError
from lesson_prep.core_api_client import CoreApiClient, CoreApiError, OidcClient


def _mock_transport(handler):
    return httpx.MockTransport(handler)


def test_ai_client_stt_normalizes_output():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/stt"
        assert request.headers["X-Lyreo-Internal-Token"] == "secret-token"
        body = json.loads(request.content)
        assert body["input"]["audio_url"] == "file:///tmp/a.wav"
        return httpx.Response(200, json={
            "output": {"text": "Hello.", "language": "English"},
            "metadata": {"model": "Qwen/Qwen3-ASR-0.6B"},
        })

    client = AiServiceClient("http://ai.invalid", "secret-token")
    client._http = httpx.Client(transport=_mock_transport(handler))
    result = client.stt("file:///tmp/a.wav")
    assert result["text"] == "Hello."
    assert result["provider"] == "LOCAL_QWEN"


def test_ai_client_rejects_empty_stt_text():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"output": {"text": "  "}})

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    with pytest.raises(AiServiceError, match="no transcript"):
        client.stt("file:///tmp/a.wav")


def test_ai_client_tts_decodes_base64():
    def handler(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        assert body["options"]["voice"] == "bf_emma"
        assert body["options"]["accent"] == "UK"
        return httpx.Response(200, json={
            "output": {"audio_base64": base64.b64encode(b"RIFFwav").decode(),
                       "mime_type": "audio/wav"},
            "metadata": {"voice": "bf_emma", "accent": "UK"},
        })

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    result = client.tts("text", voice="bf_emma", accent="UK")
    assert result["audio_bytes"] == b"RIFFwav"


def test_ai_client_voices_contract():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/tts/voices"
        return httpx.Response(200, json={"voices": [
            {"provider": "LOCAL_KOKORO", "voice_id": "af_heart", "accent": "US"},
        ]})

    client = AiServiceClient("http://ai.invalid", "t")
    client._http = httpx.Client(transport=_mock_transport(handler))
    voices = client.voices()
    assert voices[0]["voice_id"] == "af_heart"


def test_core_client_uploads_multipart_with_kind():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/admin/lessons/media"
        assert request.headers["Authorization"].startswith("Bearer ")
        captured["body"] = request.content
        captured["content_type"] = request.headers["content-type"]
        return httpx.Response(200, json={
            "objectKey": "lessons/media/audio/uuid.wav",
            "contentType": "audio/wav", "size": 12,
            "sha256": "abc", "downloadUrl": "https://storage.invalid/x",
        })

    oidc = OidcClient("http://kc.invalid/realms/lyreo", "client", "http://localhost/cb")
    oidc._access_token = "jwt"
    oidc._expires_at = 9999999999
    client = CoreApiClient("http://core.invalid", oidc)
    client._http = httpx.Client(transport=_mock_transport(handler))
    audio = Path("/tmp/fake.wav")
    audio.write_bytes(b"RIFFfake")
    result = client.upload_media("AUDIO", audio)

    assert result["objectKey"] == "lessons/media/audio/uuid.wav"
    assert b'name="kind"' in captured["body"]
    assert b"AUDIO" in captured["body"]


def test_core_client_upload_error_is_safe():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"detail": "unsupported media type"})

    oidc = OidcClient("http://kc.invalid/realms/lyreo", "client", "http://localhost/cb")
    oidc._access_token = "jwt"
    oidc._expires_at = 9999999999
    client = CoreApiClient("http://core.invalid", oidc)
    client._http = httpx.Client(transport=_mock_transport(handler))
    audio = Path("/tmp/fake.wav")
    audio.write_bytes(b"RIFFfake")
    with pytest.raises(CoreApiError, match="HTTP 400"):
        client.upload_media("AUDIO", audio)


def test_oidc_pkce_exchange_sends_verifier():
    captured = {}

    def token_handler(request: httpx.Request) -> httpx.Response:
        captured["data"] = request.content.decode()
        return httpx.Response(200, json={"access_token": "jwt", "expires_in": 300})

    oidc = OidcClient("http://kc.invalid/realms/lyreo", "prep", "http://localhost/cb")
    oidc._config = {
        "authorization_endpoint": "http://kc.invalid/auth",
        "token_endpoint": "http://kc.invalid/token",
    }
    oidc._http = httpx.Client(transport=_mock_transport(token_handler))
    oidc._pending["the-state"] = ("the-verifier", 0.0)
    oidc.complete("the-code", "the-state")
    assert "code=the-code" in captured["data"]
    assert "code_verifier=the-verifier" in captured["data"]
    assert "grant_type=authorization_code" in captured["data"]
    assert oidc._access_token == "jwt"


def test_oidc_unknown_state_rejected():
    oidc = OidcClient("http://kc.invalid/realms/lyreo", "prep", "http://localhost/cb")
    with pytest.raises(CoreApiError, match="unknown state"):
        oidc.complete("code", "never-registered")
