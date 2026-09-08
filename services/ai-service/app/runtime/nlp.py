from __future__ import annotations

import re

from ..schemas import ExecuteRequest, ExecuteResponse

TOKEN_RE = re.compile(r"[A-Za-z]+(?:['’-][A-Za-z]+)*|\d+(?:[.,:]\d+)*|[^\w\s]", re.UNICODE)
PROPER_NOUN_RE = re.compile(r"\b(?:Mr|Mrs|Ms|Dr)\.\s+[A-Z][A-Za-z-]+|\b[A-Z][A-Za-z-]{2,}\b")
NUMBER_RE = re.compile(r"\b\d+(?:[.,:]\d+)*\b")
ACRONYM_RE = re.compile(r"\b[A-Z]{2,}\b")


class LightweightNlpRuntime:
    """Cheap deterministic baseline for tokenization and dictation hints.

    This intentionally does not pretend to be semantic NLP. Expensive/subjective lexical and
    grammar selection is a separate LLM capability whose product prompt is owned by Java Core.
    """

    async def analyze(self, request: ExecuteRequest) -> ExecuteResponse:
        raw_sentences = request.input.get("sentences")
        if isinstance(raw_sentences, list):
            sentences = [str(value) for value in raw_sentences]
        else:
            sentences = [str(request.input.get("text") or "")]

        all_tokens: list[dict] = []
        sentence_entities: list[dict] = []
        global_index = 0

        for sentence_index, text in enumerate(sentences):
            for local_index, token in enumerate(TOKEN_RE.findall(text)):
                all_tokens.append(
                    {
                        "surface": token,
                        "index": global_index,
                        "sentence_index": sentence_index,
                        "local_index": local_index,
                    }
                )
                global_index += 1

            sentence_entities.append(
                {
                    "sentence_index": sentence_index,
                    "entities": _entities(text),
                }
            )

        return ExecuteResponse(
            output={
                "text": " ".join(sentences),
                "tokens": all_tokens,
                "sentence_entities": sentence_entities,
            },
            metadata={"runtime": "lightweight-nlp"},
        )


def _entities(text: str) -> list[dict]:
    found: dict[tuple[int, int, str], dict] = {}

    for match in PROPER_NOUN_RE.finditer(text):
        found[(match.start(), match.end(), "PROPER_NOUN")] = {
            "text": match.group(0),
            "type": "PROPER_NOUN",
            "start": match.start(),
            "end": match.end(),
        }

    for match in ACRONYM_RE.finditer(text):
        found[(match.start(), match.end(), "ACRONYM")] = {
            "text": match.group(0),
            "type": "ACRONYM",
            "start": match.start(),
            "end": match.end(),
        }

    for match in NUMBER_RE.finditer(text):
        found[(match.start(), match.end(), "NUMBER")] = {
            "text": match.group(0),
            "type": "NUMBER",
            "start": match.start(),
            "end": match.end(),
        }

    return sorted(found.values(), key=lambda item: (item["start"], item["end"]))
