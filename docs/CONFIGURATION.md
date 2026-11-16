# Lyreo configuration model

This document owns configuration categories, precedence, and authority. Exact environment keys and defaults belong to the owning executable's `.env.example` and configuration code.

## Precedence and ownership

```text
Deployment capability and secrets
  -> Admin runtime policy
  -> Accepted Lesson build snapshot
  -> Learner persistent preference
  -> Current session override
```

A lower layer cannot exceed a higher layer's capability or safety limit. For example, an Admin-disabled pronunciation feature cannot be enabled by a learner preference. A temporary playback-speed change stays in session state until explicitly saved as a cross-device preference.

Deployment configuration supplies endpoints, credentials, storage mode, runtime resources, and service limits. Every executable has its own `.env.example`; there is no root `.env`. `scripts/init-dev-env.sh` creates local files and synchronizes values that genuinely cross process boundaries. `VITE_*` and `EXPO_PUBLIC_*` values are public build data and must never contain server secrets.

Admin policy controls provider/model routing, enabled capabilities, allowable Lesson processing, rate/cost envelopes, and controlled reward policy. Model identifiers are runtime strings, not Java enums. Provider API keys are encrypted in Core with an environment-held master key; Admin reads masked metadata only.

The accepted Lesson options and initial routing context are durable audit snapshots. Execution resolves the currently enabled provider route; the invocation record stores the actual provider and model. A snapshot is not a promise to pin a provider. [Lesson requirements](requirements/lesson.md) and [AI requirements](requirements/ai.md) own product behavior.

Learner preferences are persistent, cross-device study choices within the Admin envelope. Device-local locale and visual theme use non-secret browser/device storage. Ephemeral interaction state stays in the current client session. Authentication tokens use secure storage; ordinary locale/theme preferences do not.

## Storage and jobs

Development defaults to local filesystem storage. Full Grammar/TOEIC import integration uses
each developer's local PostgreSQL database and the team's shared `lyreo-dev` Cloudflare R2
bucket; production also uses R2. Each developer keeps R2 credentials in ignored local env files.
PostgreSQL stores object keys, never expiring access URLs. Storage mode changes where bytes live,
not the authority of normalized state or AI cost accounting. See [Data Import](../tools/data-import/README.md)
for the import workflow.

Job polling, lease, and concurrency are deployment tuning settings constrained by the [jobs protocol](architecture/background-jobs.md). Heartbeats, durable cancellation, idempotency, and fencing are invariants, not toggles.

## Adding a setting

Identify its owner, secret status, persistence needs, override authority, and audit/snapshot needs. Add exact environment keys to the owning `.env.example` and code. Update this document only when precedence or semantics change. Keep authorization, scoring authority, domain transitions, schema authority, and module boundaries in code and architecture contracts. Public API documentation availability follows the [HTTP contract](architecture/http-api-contract.md).


### Local speech recognition for authoring

For local authoring, configure **Admin → AI settings → Routing** with provider `QWEN`:

| Capability | Model | Priority | Enabled |
|---|---|---|---|
| STT | `Qwen/Qwen3-ASR-0.6B` | 1 | Yes |
| ALIGNMENT | `Qwen/Qwen3-ForcedAligner-0.6B` | 1 | Yes |

Disable the Groq STT route when using this local-only setup; other Groq capabilities can stay enabled.
Routes are persisted in PostgreSQL and survive restarts and lesson-only reset. No hard-coded provider
fallback overrides Admin routing. Run `make ai-local` with the Qwen dependencies/weights available.
Local recognition can still make mistakes; transcript review remains required before alignment.
An explicitly enabled mock development bootstrap remains a separate test configuration.

### Local IPA runtime

The NLP local route (`LOCAL_NLP`) supports reference sentence IPA through system `espeak-ng`.
Install that binary for host execution; the AI Docker image includes it. Admin chooses LOCAL or AI,
a US/UK target reference accent and disabled/on-demand/pregenerate timing policy per draft.
AI uses the configured free-eligible GENERAL_LLM route. Accent selection does not detect source accent.

### Kokoro voice previews

Keep Kokoro library 0.9.4 / `hexgrad/Kokoro-82M` v1.0 for the English catalog.
Start `make ai-local`, enable a `TTS` route to `LOCAL_KOKORO` (no provider key), then use
**Prepare missing voice previews** in the Admin voice picker. One Core job generates 28 samples
with fixed English text and speed 1.0, persists a versioned manifest in the configured object storage
and skips valid successes on retry. Discovery does not load the heavy model; preview playback
only downloads existing audio through authenticated Admin endpoints. Generation failures are visible
in the picker and jobs screen. Model weights/GPU availability are still required for generation.
