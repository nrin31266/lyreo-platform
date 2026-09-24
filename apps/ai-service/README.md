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
