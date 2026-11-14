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

STT returns `text`, `timestamps` (`index`, `word`, `start_ms`, `end_ms`) and coarse `segments` (`text`, `start_ms`, `end_ms`). All times are milliseconds relative to the supplied audio. Groq requests word and segment granularities; local Qwen processes sequential windows of at most 115 seconds on the measured 4 GB worker. Fixed ASR boundaries require transcript review for seam errors.
`options.allow_alignment=false` defers Qwen forced alignment even if `timestamps=true`; Core uses
this during preparation so approved text is aligned once later. Native provider timestamps remain available.

Alignment accepts `input.audio_url`, the complete approved `input.text` and optional `input.segments` with the same millisecond anchors. For audio over 120 seconds, anchors are mandatory. Segments must cover text in order, not overlap, and fit 120 seconds including up to 500 ms padding inside neighboring anchors. The runtime never proportionally distributes text or invents word duration. Qwen may end a positive
word at the model frame covering the audio boundary: only that final straddling frame is clipped to
the actual sample duration using the loaded model precision. Other invalid intervals remain invalid.
Core validates normalized output and owns approval. Research probes capture native output separately.

LLM options accept concrete `json_schema`, symbolic `response_schema`, `max_output_tokens` and `temperature`. Gemini also accepts `thinking_level`; Groq accepts the existing allowlisted `provider_options` such as `reasoning_effort` and `max_completion_tokens`. Concrete Groq schemas use strict structured output. Missing usage is omitted, not reported as zero. Completion status/finish reason is returned as metadata so Core can reject incomplete generations. Upstream 429 preserves status and `Retry-After` for resumable jobs without exposing provider error bodies.

Measured provider and timing limits are documented in [ASR research](../../docs/research/lesson-authoring-asr.md). Providers still require an explicitly verified free-only configuration in Core; adapter availability is not billing verification.


Local reference IPA is exposed through NLP with `options.operation=sentence_ipa_v1`, sentences input
and US/UK accent. Install `espeak-ng` on the host (included in the Docker image). No Kokoro weights are
needed for IPA. Results include engine version and review flags for ambiguous/special forms; they do
not transcribe the actual speaker's accent or connected speech. Core owns routing and review policy.
