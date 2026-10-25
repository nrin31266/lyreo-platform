# Lyreo AI Service

FastAPI executes technical STT, alignment, TTS, NLP, generic LLM, and judging capabilities. Core owns product prompts, routing decisions, workflow, and durable business state. The stable boundary is in [AI execution](../../docs/architecture/ai-execution.md); current endpoints and schemas are in the running OpenAPI document and code.

## Local development

Copy `.env.example` to `.env`, then use the project environment:

```bash
uv sync --locked --extra dev
set -a; source .env; set +a
uv run uvicorn app.main:app --reload --port 8000
uv run --locked --extra dev python -m pytest
```

`mock` mode is the normal deterministic development/CI runtime and needs no GPU or paid provider. For local Qwen inference, sync the `qwen` extra and select the local runtime as described in `.env.example`. Kokoro TTS uses its separate `kokoro` extra and system phonemization dependency. Model weights are cached locally and never committed.

Internal capability calls require the service token. FastAPI does not persist provider credentials or business records. Do not log incoming credentials. Use authorized media references for large audio; storage ownership remains outside this process.

## Lesson authoring execution contract

STT returns `text`, `timestamps` (`index`, `word`, `start_ms`, `end_ms`) and coarse `segments` (`text`, `start_ms`, `end_ms`). All times are milliseconds relative to the supplied audio. Groq requests word and segment granularities; local Qwen processes sequential windows of at most 120 seconds on the measured 4 GB worker. Fixed ASR boundaries require transcript review for seam errors.

Alignment accepts `input.audio_url`, the complete approved `input.text` and optional `input.segments` with the same millisecond anchors. For audio over 120 seconds, anchors are mandatory. Segments must cover text in order, not overlap, and fit 120 seconds including up to 500 ms padding inside neighboring anchors. The runtime never proportionally distributes text or repairs model word durations; Core validates raw output and owns approval.

LLM options accept concrete `json_schema`, symbolic `response_schema`, `max_output_tokens` and `temperature`. Gemini also accepts `thinking_level`; Groq accepts the existing allowlisted `provider_options` such as `reasoning_effort` and `max_completion_tokens`. Concrete Groq schemas use strict structured output. Missing usage is omitted, not reported as zero. Completion status/finish reason is returned as metadata so Core can reject incomplete generations. Upstream 429 preserves status and `Retry-After` for resumable jobs without exposing provider error bodies.

Measured provider and timing limits are documented in [ASR research](../../docs/research/lesson-authoring-asr.md). Providers still require an explicitly verified free-only configuration in Core; adapter availability is not billing verification.
