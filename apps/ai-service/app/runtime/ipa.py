"""Local reference IPA capability. No lesson policy or language-learning prompts."""
from __future__ import annotations

import asyncio
from functools import lru_cache
import re
import subprocess

from fastapi import HTTPException

from ..schemas import ExecuteRequest, ExecuteResponse

# These forms need context or a proper-name check; reference IPA is never audio-derived.
AMBIGUOUS = re.compile(r"\b(read|lead|live|record|present|wind|tear|bow|close|minute|object|invalid|use|used)\b", re.I)
SPECIAL = re.compile(r"\d|\b[A-Z]{2,}\b|\b(?:Mr|Mrs|Ms|Dr)\.")


@lru_cache(maxsize=2048)
def _transcribe(text: str, accent: str, version: str) -> str:
    try:
        result = subprocess.run(
            ["espeak-ng", "-q", "--ipa", "-v", "en-us" if accent == "US" else "en-gb", "--stdin"],
            input=text, text=True, capture_output=True, check=True, timeout=10,
        )
    except FileNotFoundError as error:
        raise HTTPException(503, "Local IPA requires espeak-ng") from error
    except (subprocess.SubprocessError, OSError) as error:
        raise HTTPException(503, "Local IPA execution failed") from error
    ipa = " ".join(result.stdout.split())
    if not ipa or len(ipa) > 30000:
        raise ValueError("Local IPA returned empty or oversized output")
    return ipa


@lru_cache(maxsize=1)
def _version() -> str:
    try:
        result = subprocess.run(["espeak-ng", "--version"], capture_output=True, text=True, check=True, timeout=5)
    except (OSError, subprocess.SubprocessError) as error:
        raise HTTPException(503, "Local IPA requires espeak-ng") from error
    version = re.search(r"\b\d+\.\d+(?:\.\d+)?\b", result.stdout)
    return "espeak-ng:" + (version.group(0) if version else "unknown")


class IpaRuntime:
    async def analyze(self, request: ExecuteRequest) -> ExecuteResponse:
        sentences = request.input.get("sentences")
        accent = request.options.get("accent", "US")
        if accent not in {"US", "UK"} or not isinstance(sentences, list) or not 1 <= len(sentences) <= 1000:
            raise ValueError("Provide sentences and a supported IPA accent")
        if any(not isinstance(text, str) or not text.strip() or len(text) > 10000 for text in sentences):
            raise ValueError("Invalid IPA sentence")
        if sum(map(len, sentences)) > 200000:
            raise ValueError("IPA input is too large")
        version = await asyncio.to_thread(_version)
        items = []
        for index, text in enumerate(sentences):
            ipa = await asyncio.to_thread(_transcribe, text, accent, version)
            # Names and context-sensitive forms are visible review candidates, not silent guesses.
            names = any(token[0].isupper() for token in re.findall(r"\b[A-Za-z]+\b", text)[1:])
            items.append({"i": index + 1, "ipa_raw": ipa, "display_version": "learner-ipa-v1", "ipa": ipa.replace("ɹ", "r").replace("ᵻ", "(ɪ/ə)"), "needs_review": bool(AMBIGUOUS.search(text) or SPECIAL.search(text) or names)})
        return ExecuteResponse(output={"items": items, "accent": accent, "version": version}, metadata={"runtime": "local-espeak-ng", "reference_only": True})
