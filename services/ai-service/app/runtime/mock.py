from __future__ import annotations

import base64
import hashlib

from ..schemas import ExecuteRequest, ExecuteResponse


class MockRuntime:
    """Deterministic runtime for local development/CI. Never pretend this is model quality."""

    async def stt(self, request: ExecuteRequest) -> ExecuteResponse:
        text = str(
            request.input.get("expected_text")
            or request.input.get("text")
            or "Mock transcription for Lyreo development."
        )
        return ExecuteResponse(
            output={"text": text, "language": "English", "timestamps": []},
            metadata={"runtime": "mock"},
        )

    async def align(self, request: ExecuteRequest) -> ExecuteResponse:
        text = str(request.input.get("text") or "")
        words = text.split()
        cursor = 0
        timestamps: list[dict] = []

        for index, word in enumerate(words):
            start = cursor
            end = start + max(240, len(word) * 55)
            cursor = end + 70
            timestamps.append(
                {
                    "index": index,
                    "word": word,
                    "start_ms": start,
                    "end_ms": end,
                }
            )

        return ExecuteResponse(
            output={"words": timestamps},
            metadata={"runtime": "mock"},
        )

    async def tts(self, request: ExecuteRequest) -> ExecuteResponse:
        digest = hashlib.sha256(str(request.input.get("text", "")).encode()).hexdigest()[:12]
        return ExecuteResponse(
            output={
                "audio_base64": base64.b64encode(b"ID3LYREO-MOCK-AUDIO").decode(),
                "mime_type": "audio/mpeg",
                "suggested_artifact_key": f"mock/tts/{digest}.mp3",
            },
            metadata={"runtime": "mock", "binary_generated": False},
        )

    async def nlp(self, request: ExecuteRequest) -> ExecuteResponse:
        raw = request.input.get("sentences")
        sentences = (
            [str(value) for value in raw]
            if isinstance(raw, list)
            else [str(request.input.get("text") or "")]
        )
        groups: list[dict] = []
        tokens: list[dict] = []

        for sentence_index, text in enumerate(sentences):
            entities: list[dict] = []
            for word in text.split():
                stripped = word.strip('.,!?()[]"')
                if len(stripped) > 2 and stripped[:1].isupper():
                    entities.append({"text": stripped, "type": "PROPER_NOUN"})
                tokens.append(
                    {
                        "surface": stripped or word,
                        "sentence_index": sentence_index,
                    }
                )
            groups.append({"sentence_index": sentence_index, "entities": entities})

        return ExecuteResponse(
            output={
                "text": " ".join(sentences),
                "sentence_entities": groups,
                "tokens": tokens,
            },
            metadata={"runtime": "mock"},
        )

    async def judge(self, request: ExecuteRequest) -> ExecuteResponse:
        return ExecuteResponse(
            output={
                "overall": 82,
                "fluency": 80,
                "timing": 84,
                "feedback": (
                    "Mock only — use a configured multimodal provider for real feedback."
                ),
            },
            metadata={"runtime": "mock"},
        )

    async def llm(
        self,
        request: ExecuteRequest,
        credential: str | None,
    ) -> ExecuteResponse:
        schema = request.options.get("response_schema")
        sentences = request.input.get("sentences") or []

        if schema == "lesson_lexical_annotations_v1":
            structured = {"items": []}
        elif schema == "lesson_grammar_annotations_v1":
            structured = {"items": []}
        elif schema == "sentence_translation_v1":
            structured = {
                "items": [
                    {"sentence_index": index, "translation_vi": f"[mock] {sentence}"}
                    for index, sentence in enumerate(sentences)
                ]
            }
        elif schema == "sentence_thought_groups_v1":
            structured = {
                "items": [
                    {"sentence_index": index, "groups": [str(sentence)]}
                    for index, sentence in enumerate(sentences)
                ]
            }
        elif schema == "lesson_learning_tips_v1":
            structured = {"items": []}
        elif schema == "sentence_ipa_v1":
            structured = {
                "items": [
                    {
                        "sentence_index": index,
                        "ipa": "/mock/",
                        "accent": request.input.get("accent", "US"),
                    }
                    for index, _ in enumerate(sentences)
                ]
            }
        else:
            structured = {}

        return ExecuteResponse(
            output={
                "content": "Mock LLM response. Business prompts are built by Java Core Service.",
                "structured": structured,
            },
            usage={"input_tokens": 0, "output_tokens": 0},
            metadata={
                "runtime": "mock",
                "provider": request.provider,
                "credential_present": bool(credential),
            },
        )
