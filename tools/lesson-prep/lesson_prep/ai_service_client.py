"""Thin HTTP client for the Lyreo AI Service (/v1/stt, /v1/align, /v1/tts, /v1/tts/voices).

The AI service receives only usable audio references and business-agnostic text —
never YouTube URLs or Lesson DTOs.
"""

from __future__ import annotations

import base64
import uuid
from typing import Any

import httpx


class AiServiceError(RuntimeError):
    pass


class AiServiceClient:
    def __init__(self, base_url: str, internal_token: str, timeout: float = 600.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._headers = {"X-Lyreo-Internal-Token": internal_token}
        self._http = httpx.Client(timeout=timeout)

    def _post(self, path: str, provider: str, model: str, input_data: dict[str, Any],
              options: dict[str, Any] | None = None) -> dict[str, Any]:
        payload = {
            "invocation_id": str(uuid.uuid4()),
            "provider": provider,
            "model": model,
            "prompt": "",
            "input": input_data,
            "options": options or {},
        }
        try:
            response = self._http.post(
                f"{self.base_url}{path}",
                json=payload,
                headers=self._headers,
            )
            response.raise_for_status()
            return response.json()
        except httpx.HTTPStatusError as failure:
            detail = _safe_detail(failure.response)
            hint = ""
            if failure.response.status_code == 401:
                hint = (" (internal token mismatch — make sure AI_SERVICE_INTERNAL_TOKEN in "
                        "tools/lesson-prep/.env equals apps/ai-service/.env; run `make init-env`)")
            raise AiServiceError(
                f"AI service {path} failed with HTTP {failure.response.status_code}: {detail}{hint}"
            ) from failure
        except httpx.HTTPError as failure:
            raise AiServiceError(f"AI service {path} request failed: {failure}") from failure

    def stt(self, audio_ref: str, provider: str = "LOCAL_QWEN", model: str | None = None,
            language: str = "English") -> dict[str, Any]:
        result = self._post(
            "/v1/stt", provider, model or "Qwen/Qwen3-ASR-0.6B",
            {"audio_url": audio_ref}, {"language": language},
        )
        output = result.get("output") or {}
        text = str(output.get("text") or "").strip()
        if not text:
            raise AiServiceError("STT returned no transcript text")
        return {
            "text": text,
            "language": output.get("language", language),
            "provider": provider,
            "model": result.get("metadata", {}).get("model", model),
        }

    def align(self, audio_ref: str, text: str, provider: str = "LOCAL_QWEN",
              model: str | None = None, language: str = "English") -> dict[str, Any]:
        result = self._post(
            "/v1/align", provider, model or "Qwen/Qwen3-ForcedAligner-0.6B",
            {"audio_url": audio_ref, "text": text}, {"language": language},
        )
        output = result.get("output") or {}
        words = output.get("words")
        if not isinstance(words, list):
            raise AiServiceError("Alignment returned no normalized word timestamps")
        return {
            "words": words,
            "provider": provider,
            "model": result.get("metadata", {}).get("model", model),
        }

    def tts(self, text: str, voice: str, accent: str, speed: float = 1.0,
            provider: str = "LOCAL_KOKORO", model: str | None = None) -> dict[str, Any]:
        result = self._post(
            "/v1/tts", provider, model or "hexgrad/Kokoro-82M",
            {"text": text}, {"voice": voice, "accent": accent, "speed": speed},
        )
        output = result.get("output") or {}
        encoded = output.get("audio_base64")
        if not encoded:
            raise AiServiceError("TTS returned no audio data")
        return {
            "audio_bytes": base64.b64decode(encoded),
            "mime_type": output.get("mime_type", "audio/wav"),
            "provider": provider,
            "model": result.get("metadata", {}).get("model", model),
            "voice": result.get("metadata", {}).get("voice", voice),
            "accent": result.get("metadata", {}).get("accent", accent),
        }

    def voices(self) -> list[dict[str, Any]]:
        try:
            response = self._http.get(
                f"{self.base_url}/v1/tts/voices",
                headers=self._headers,
            )
            response.raise_for_status()
            voices = response.json().get("voices") or []
            if not isinstance(voices, list):
                raise AiServiceError("Voice discovery returned an unexpected shape")
            return voices
        except httpx.HTTPStatusError as failure:
            hint = ""
            if failure.response.status_code == 401:
                hint = (" — internal token mismatch; run `make init-env` to sync "
                        "AI_SERVICE_INTERNAL_TOKEN between apps/ai-service/.env and "
                        "tools/lesson-prep/.env")
            raise AiServiceError(
                f"Voice discovery failed with HTTP {failure.response.status_code}{hint}"
            ) from failure
        except httpx.HTTPError as failure:
            raise AiServiceError(f"Voice discovery request failed: {failure}") from failure


def _safe_detail(response: httpx.Response) -> str:
    try:
        detail = response.json().get("detail")
        return str(detail)[:200]
    except Exception:
        return response.text[:200]
