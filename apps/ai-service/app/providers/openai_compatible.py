from __future__ import annotations

import json
from typing import Any

import httpx

from ..schemas import ExecuteRequest, ExecuteResponse

# Provider-specific options remain intentionally narrow. Do not allow callers to replace
# `messages`, `model`, credentials or target URL through an arbitrary pass-through mapping.
_ALLOWED_PROVIDER_OPTIONS = {
    "temperature",
    "top_p",
    "max_tokens",
    "max_completion_tokens",
    "seed",
    "frequency_penalty",
    "presence_penalty",
    "reasoning_effort",
}


class OpenAICompatibleProvider:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    async def complete(self, request: ExecuteRequest, credential: str) -> ExecuteResponse:
        if not credential:
            raise ValueError(f"{request.provider} credential is required")

        context = ""
        if request.input:
            context = "\n\nCaller input JSON:\n" + json.dumps(
                request.input,
                ensure_ascii=False,
                sort_keys=True,
            )

        raw_provider_options = request.options.get("provider_options")
        provider_options: dict[str, Any] = {}
        if isinstance(raw_provider_options, dict):
            provider_options = {
                key: value
                for key, value in raw_provider_options.items()
                if key in _ALLOWED_PROVIDER_OPTIONS
            }

        body: dict[str, Any] = {
            "model": request.model,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "You are an AI execution capability inside Lyreo. Follow the caller "
                        "prompt exactly; do not invent product workflow."
                    ),
                },
                {"role": "user", "content": (request.prompt or "") + context},
            ],
            **provider_options,
        }

        # Java owns the exact schema contract. OpenAI-compatible providers only receive a generic
        # JSON-object hint here; Core still validates/projects the returned structure.
        if request.options.get("response_schema"):
            body["response_format"] = {"type": "json_object"}

        async with httpx.AsyncClient(timeout=120) as client:
            response = await client.post(
                f"{self.base_url}/chat/completions",
                headers={"Authorization": f"Bearer {credential}"},
                json=body,
            )
            response.raise_for_status()
            data = response.json()

        usage = data.get("usage") or {}
        content = data["choices"][0]["message"]["content"]
        structured = _json_object_or_none(content)
        output: dict[str, Any] = {"content": content}
        if structured is not None:
            output["structured"] = structured

        return ExecuteResponse(
            output=output,
            usage={
                "input_tokens": usage.get("prompt_tokens", 0),
                "output_tokens": usage.get("completion_tokens", 0),
            },
            metadata={"provider": request.provider, "model": request.model},
        )


def _json_object_or_none(content: Any) -> dict[str, Any] | None:
    if not isinstance(content, str):
        return None
    try:
        parsed = json.loads(content)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None
