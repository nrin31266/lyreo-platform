from __future__ import annotations

import json
import math
import re
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

        if "temperature" in request.options and "temperature" not in provider_options:
            body["temperature"] = request.options["temperature"]
        budget = request.options.get("max_output_tokens")
        if budget is not None and not {"max_tokens", "max_completion_tokens"} & provider_options.keys():
            if not isinstance(budget, int) or isinstance(budget, bool) or budget <= 0:
                raise ValueError("max_output_tokens must be a positive integer")
            body["max_completion_tokens"] = budget
        if "max_tokens" in body and "max_completion_tokens" in body:
            raise ValueError("Specify only one completion token budget")
        schema = request.options.get("json_schema")
        if isinstance(schema, dict) and schema:
            name = request.options.get("response_schema") or "lyreo_response"
            if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", name):
                raise ValueError("response_schema must be a valid schema name")
            body["response_format"] = {"type": "json_schema", "json_schema": {
                "name": name, "strict": True, "schema": schema,
            }}
        elif request.options.get("response_schema"):
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
        choice = data["choices"][0]
        content = choice["message"].get("content")
        if not isinstance(content, str) or not content.strip():
            raise ValueError("AI completion returned no text output")
        structured = _json_object_or_none(content)
        output: dict[str, Any] = {"content": content}
        if structured is not None:
            output["structured"] = structured

        return ExecuteResponse(
            output=output,
            usage={
                target: usage[source]
                for source, target in (("prompt_tokens", "input_tokens"), ("completion_tokens", "output_tokens"))
                if isinstance(usage.get(source), (int, float)) and not isinstance(usage[source], bool)
                and math.isfinite(usage[source]) and usage[source] >= 0
            },
            metadata={"provider": request.provider, "model": request.model,
                      "finish_reason": choice.get("finish_reason")},
        )


def _json_object_or_none(content: Any) -> dict[str, Any] | None:
    if not isinstance(content, str):
        return None
    try:
        parsed = json.loads(content)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None
