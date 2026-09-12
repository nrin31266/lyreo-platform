# AI execution boundary and protocol

Mục đích: giải thích contract Core↔FastAPI và ownership của routing/audit. Product obligations ở
[AI requirements](../requirements/ai.md); workflow detail ở [AI Routing](../features/ai-routing.md).

## Boundary

Java/Core owns business reason, product prompt, expected schema, orchestration, route/fallback,
retry, persistence and transition. FastAPI authenticates internal calls and executes technical
capabilities: STT, alignment, TTS, NLP, generic LLM and multimodal judge. It has no business DB/ORM
or endpoints named after Lesson/Curriculum/Reward workflows.

The **Lesson Prep Tool** (`tools/lesson-prep`) is a separate local operator tool that calls AI
Service over HTTP for source preparation (STT/alignment/TTS) and Core over HTTP for canonical
media upload. It exports one versioned `*.lesson-source.json` file; it does not persist lessons.
AI Service must never receive YouTube URLs or business Lesson DTOs — only usable audio references
and text. YouTube acquisition in the Tool is tool-local; AI Service receives only the extracted
canonical audio reference.

## Wire contract

Current FastAPI endpoints are `GET /health`, `POST /v1/stt`, `/v1/align`, `/v1/tts`,
`GET /v1/tts/voices`, `/v1/nlp/analyze`, `/v1/llm/generate`, `/v1/multimodal/judge`.
`/v1/*` requires `X-Lyreo-Internal-Token`. Canonical DTOs are code-owned in
`apps/ai-service/app/schemas.py`; Java gateway must be reviewed with them whenever
fields/errors/credential transport change.

Core (and the Prep Tool) send selected capability/provider/model and business-built
prompt/input/options. FastAPI dispatches runtime/provider and returns normalized capability
output. Large media should use an authorized URL/file reference rather than embedding base64;
storage authority remains outside AI.

## Runtime and routing

`mock` is deterministic dev/CI capability execution; `local` lazy-loads Qwen ASR/ForcedAligner.
`LOCAL_KOKORO` is the local TTS runtime (lazy-loaded, chunked deterministically, WAV output);
voice discovery (`/v1/tts/voices`) is static metadata and never loads the model, so the Prep
Tool never hard-codes voices. External providers are Core route choices, not a third
`AI_RUNTIME_MODE`. Acceptance snapshot is audit context; execution uses enabled route and
`ai_invocation` records actual provider/model.

## Failure, artifacts and security

Calls need timeout/resilience and cancellation check before business commit. Core maps stable safe
errors; raw provider details live only in protected logs/artifacts. Credentials are encrypted in
Core DB, forwarded only for the selected internal call, never persisted/logged by FastAPI.

Raw result is audit/debug; PostgreSQL normalized state is query/workflow truth. Mock contract tests
do not certify live model accuracy, latency, cost or provider compatibility.
