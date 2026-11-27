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

Deployment configuration supplies endpoints, credentials, storage mode, runtime resources, and service limits. Each executable owns its `.env.example`. `scripts/init-dev-env.sh` creates private local application files and synchronizes values that cross process boundaries. Optional data maintenance settings have a [research template](../tools/research/.env.example). `VITE_*` and `EXPO_PUBLIC_*` values are public build data and must never contain server secrets.

Admin policy controls provider/model routing, enabled capabilities, allowable Lesson processing, rate/cost envelopes, and controlled reward policy. Model identifiers are runtime strings, not Java enums. Provider API keys are encrypted in Core with an environment-held master key; Admin reads masked metadata only.

The accepted Lesson options and initial routing context are durable audit snapshots. Execution resolves the currently enabled provider route; the invocation record stores the actual provider and model. A snapshot is not a promise to pin a provider. [Lesson requirements](requirements/lesson.md) and [AI requirements](requirements/ai.md) own product behavior.

Learner preferences are persistent, cross-device study choices within the Admin envelope. Device-local locale and visual theme use non-secret browser/device storage. Ephemeral interaction state stays in the current client session. Authentication tokens use secure storage; ordinary locale/theme preferences do not.

## Storage and jobs

Development defaults to local filesystem storage. Full Grammar/TOEIC import integration uses
each developer's local PostgreSQL database and the team's shared `lyreo-dev` Cloudflare R2
bucket; production also uses R2. Each developer keeps R2 credentials in ignored local env files.
Core and the importer must select the same storage mode and target. Local importer storage is
available for isolated development/tests; selecting it does not migrate existing R2 objects.
`make data-storage-check` verifies configuration alignment without reading or writing cloud data.
PostgreSQL stores object keys, never expiring access URLs. Storage mode changes where bytes live,
not the authority of normalized state or AI cost accounting. See [Data Import](../tools/data-import/README.md)
for the import workflow.

Job polling, lease, and concurrency are deployment tuning settings constrained by the [jobs protocol](architecture/background-jobs.md). Heartbeats, durable cancellation, idempotency, and fencing are invariants, not toggles.

## Adding a setting

Identify its owner, secret status, persistence needs, override authority, and audit/snapshot needs. Add exact environment keys to the owning `.env.example` and code. Update this document only when precedence or semantics change. Keep authorization, scoring authority, domain transitions, schema authority, and module boundaries in code and architecture contracts. Public API documentation availability follows the [HTTP contract](architecture/http-api-contract.md).


### Development AI defaults

`make setup` and `make seed` add the following routes for new local providers:

| Capability | Provider | Model | Priority |
|---|---|---|---|
| STT | WhisperX local | `small.en` | 1 |
| ALIGNMENT | WhisperX local | `WAV2VEC2_ASR_BASE_960H` | 1 |
| TTS | Kokoro | `hexgrad/Kokoro-82M` | 1 |
| NLP | Local NLP | `espeak-ng` | 1 |

DeepSeek Flash, Gemini 3.5 Flash-Lite and Groq GPT-OSS 120B are prepared for `GENERAL_LLM` in
priority order 1–3. To use an
external provider, enter its key and enable the connection in **Admin → AI settings → Providers**.
Gemini and Groq still require the Core free-tier eligibility configuration.

Routes survive restarts and lesson-only resets. After a full database recreation, run `make seed`
and enter the external keys again. Rerunning leaves existing providers and their routes alone.
Run `make ai-local` for WhisperX and Kokoro execution. The optional mock bootstrap is for tests against
`AI_RUNTIME_MODE=mock` before external keys are configured.

### Local IPA runtime

The NLP local route (`LOCAL_NLP`) supports reference sentence IPA through system `espeak-ng`.
Install that binary for host execution; the AI Docker image includes it. Admin chooses LOCAL or AI,
a US/UK target reference accent and disabled/on-demand/pregenerate timing policy per draft.
AI uses the configured free-eligible GENERAL_LLM route. Accent selection does not detect source accent.

### Admin pronunciation pilot

Use an Azure Speech F0 resource and append `AZURE_SPEECH` to Core's
`AI_CONFIRMED_FREE_PROVIDERS`. In **Admin → AI settings → Providers**, save and enable
`AZURE_SPEECH` with its key. Enable the route created by `make seed`, or add a
`PRONUNCIATION_ASSESSMENT` route with model `pronunciation-assessment`, priority 0 and fallback
disabled. Core encrypts and forwards the key.
FastAPI uses the resource region, `en-US`, and disabled Prosody. Run a single FastAPI replica
with a single worker process against the F0 resource.

`lyreo.speech-assessment.recording` defines `speed-factor`, `buffer-seconds`, `min-seconds`,
`hard-cap-seconds`, and `max-upload-bytes`. The default recording window is twice the sample
duration plus six seconds, clamped to 12–60 seconds, with an 8 MiB upload limit. Core allows 1.5
extra seconds when validating decoded audio. Core blocks access at 24 hours and its scheduled
cleanup removes expired objects and details. The pilot review uses 40–60 samples annotated separately
by two listeners. Record observations within the 24-hour window and retain aggregate results.

### Kokoro voice previews

Keep Kokoro library 0.9.4 / `hexgrad/Kokoro-82M` v1.0 for the English catalog.
Start `make ai-local` with the seeded `LOCAL_KOKORO` TTS route enabled, then use
**Prepare missing voice previews** in the Admin voice picker. One Core job generates 28 samples
with fixed English text and speed 1.0, persists a versioned manifest in the configured object storage
and skips valid successes on retry. Discovery does not load the heavy model; preview playback
only downloads existing audio through authenticated Admin endpoints. Generation failures are visible
in the picker and jobs screen. Model weights/GPU availability are still required for generation.
