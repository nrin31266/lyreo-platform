# AI execution boundary and protocol

Mục đích: giải thích contract Core↔FastAPI và ownership của routing/audit. Product obligations ở
[AI requirements](../requirements/ai.md).

## Boundary

Java/Core owns business reason, product prompt, expected schema, orchestration, route/fallback,
retry, persistence and transition. FastAPI authenticates internal calls and executes technical
capabilities: STT, alignment, TTS, NLP, generic LLM and multimodal judge. It has no business DB/ORM
or endpoints named after Lesson/Curriculum/Reward workflows.

Integrated Admin Lesson authoring uses Core-owned acquisition, prompts and durable review checkpoints.
AI Service receives only usable audio references and text, never YouTube URLs or business Lesson DTOs.
Portable ZIP ingestion is outside the current integrated workflow; see
[D-024](../DECISIONS.md#d-024--integrated-youtube-lesson-authoring-and-free-only-research-2026-10-25).

## Wire contract

The internal capability API is versioned under `/v1/*` and requires an internal token. Current endpoints and DTOs are owned by FastAPI OpenAPI/code. Review the Java gateway when fields, errors, or credential transport change.

Core sends selected capability/provider/model and business-built
prompt/input/options. FastAPI dispatches runtime/provider and returns normalized capability
output. Large media should use an authorized URL/file reference rather than embedding base64;
storage authority remains outside AI.

For `PRONUNCIATION_ASSESSMENT`, Core sends one bounded normalized PCM WAV and `referenceText`
as multipart to `/v1/speech/pronunciation-assessment`. FastAPI consumes Azure Speech SDK Continuous
Recognition through end of stream, aligns final words to the reference for omissions and insertions,
and returns versioned feedback with nullable scores and word/phoneme ranges relative to the WAV.
Core owns routing and retries. The Azure F0 pilot runs one FastAPI replica with one worker process
and a one-slot semaphore until recognition stops. The response contract contains normalized feedback.
Core retains invocation metadata and discards the normalized attempt details when their TTL expires.

## Runtime and routing

`mock` is deterministic dev/CI capability execution; `local` lazy-loads WhisperX STT and English forced alignment.
`LOCAL_KOKORO` is the local TTS runtime (lazy-loaded, chunked deterministically, WAV output);
voice discovery (`/v1/tts/voices`) is static metadata and never loads the model, so the Prep
Tool never hard-codes voices. External providers are Core route choices, not a third
`AI_RUNTIME_MODE`. Acceptance snapshot is audit context; execution uses enabled route and
`ai_invocation` records actual provider/model.

## Failure, artifacts and security

Calls need timeout/resilience and cancellation check before business commit. Core maps stable safe
errors; raw provider details live only in protected logs/artifacts. Credentials are encrypted in
Core DB, forwarded only for the selected internal call, never persisted/logged by FastAPI.

On upstream HTTP errors, FastAPI retains `provider_status` (the original HTTP status, even when
the internal response is normalized to 502) and an optional `provider_message` extracted only from
the standard JSON `error.message`/`error`/`message` string. Bodies over 64 KiB, malformed JSON and HTML are
omitted. Messages are redacted before truncation to 2,048 characters: actual forwarded credentials,
labelled secrets, URLs and identifiable request/prompt echoes are removed; arbitrary error fields
and headers are never forwarded. Core allowlists and redacts these fields again, carries them
through each invocation observer and stores them only in the existing Admin-only diagnostic
artifact, including failed attempts followed by successful fallback. Public errors and retry/fallback
classification stay generic. Historical jobs cannot recover details that were not recorded.

Raw result is audit/debug; PostgreSQL normalized state is query/workflow truth. Mock execution does not certify live model quality, latency, cost, or provider compatibility.
