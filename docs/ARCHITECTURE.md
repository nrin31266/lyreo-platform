# Lyreo Architecture

## 1. System view

```text
                        ┌───────────────────────┐
                        │      Keycloak         │
                        │  OIDC / PKCE / roles  │
                        └───────────┬───────────┘
                                    │
Admin Web ───────────────┐           │
                         ├───────────┼───────────────┐
Mobile ──────────────────┘           │               │
                                     ▼               │
                         ┌───────────────────────┐    │
                         │     Core Service      │    │
                         │ Spring Boot + Modulith│    │
                         └───────┬─────┬─────────┘    │
                                 │     │              │
                                 │     └── HTTP ──────┼────→ FastAPI AI Service
                                 │                    │        ├─ Qwen3-ASR
                                 │                    │        ├─ ForcedAligner
                                 │                    │        ├─ Groq
                                 │                    │        ├─ Gemini
                                 │                    │        └─ DeepSeek
                                 │                    │
                        ┌────────▼────────┐    ┌──────▼───────┐
                        │   PostgreSQL    │    │ Cloudflare R2│
                        │ state/jobs/data │    │ artifacts    │
                        └─────────────────┘    └──────────────┘
```

One deployable Core Service, many internal business modules. AI Service is a separate process because Python/model runtime is a different operational concern.

## 2. Architectural style

Lyreo combines:

- **Modular Monolith** at deployable/business-boundary level;
- **Spring Modulith** for module verification and internal events;
- **Pragmatic Clean / Hexagonal Architecture** inside modules;
- **PostgreSQL durable jobs** for long-running workflows;
- **thin FastAPI capability runtime** for AI execution.

## 3. Clean Architecture dependency direction

```text
API / delivery
      ↓
Application / use cases
      ↓
Domain
      ↑
Infrastructure implements ports
```

Domain/application must not know:

- JDBC/JPA implementation details;
- R2/AWS SDK;
- FastAPI HTTP details;
- Keycloak SDK;
- browser/mobile frameworks.

Simple reference CRUD may be less ceremonious, but business modules with real rules keep explicit ports/use cases.

## 4. Business module boundaries

```text
identity
learner
ai
lesson
speech-assessment
lexicon
vocabulary
grammar
toeic
curriculum
gamification
analytics
notification
chat            # low priority product feature
```

Technical platform:

```text
platform/cache
platform/config
platform/jobs
platform/storage
platform/security
platform/observability
```

Detailed product/domain ownership is defined in the routed area requirements from
[`requirements/analysis.md`](requirements/analysis.md). Enforceable module ownership rules are
defined in [`AGENTS.md`](../AGENTS.md#4-domain-ownership).

Frontend shared-package boundaries:

```text
packages/design-system  primitive/foundation tokens + semantic light/dark theme contract
packages/i18n           intentionally shared common/admin/mobile translation resources
apps/admin-web          Web component implementation and browser adapters
apps/mobile             Native component implementation and device adapters
```

Web and Mobile share semantic names/resources where appropriate, not component implementation or
platform-specific Tailwind configuration.

Theme resolution is likewise adapter-owned while the color contract remains shared:

```text
semanticThemes.light / semanticThemes.dark
        ├── Admin AppThemeProvider → prefers-color-scheme → .dark + CSS variables
        └── Mobile AppThemeProvider → useColorScheme() → NativeWind vars on root View
```

Feature screens consume semantic roles only; they do not own raw hex palettes or duplicate a
second light/dark theme map.

## 5. Cross-module communication

Allowed:

1. named/public module API;
2. event contract from `libs/contracts` + Spring Modulith.

Forbidden:

- importing another module's repository;
- importing another module's infrastructure class;
- sharing JPA entity as cross-module contract.

Example:

```text
LessonCompletedEvent
       ↓
Curriculum → complete referenced item
Gamification → mission/reward update
Analytics → project study summary
```

The eventing transport decision and its rationale are recorded in `TECH_CHOICES.md` and
`DECISIONS.md` D-002.

## 6. Lesson model: Content ≠ Annotation ≠ Activity

```text
Lesson
├─ Content
│  ├─ source type/reference
│  ├─ transcript
│  ├─ canonical audio/reference media
│  ├─ sentences
│  └─ word timestamps
│
├─ Annotation
│  ├─ translation
│  ├─ lexical units
│  ├─ grammar points
│  ├─ entity/dictation hints
│  ├─ sentence IPA (optional)
│  ├─ thought groups
│  └─ learning tips
│
└─ Activity
   ├─ dictation
   ├─ shadowing
   ├─ vocabulary practice
   └─ grammar practice
```

A contextual vocabulary note shown after Dictation is **not** automatically Vocabulary Practice.

## 7. Lesson build planning

`LessonBuildPlanner` derives required steps from source + creator options.

Examples:

```text
TEXT + Vocabulary/Grammar annotations
→ no TTS/alignment unless another selected activity needs them

TEXT + Dictation/Shadowing
→ TTS → alignment → activity build

AUDIO/YouTube
→ STT → optional alignment → annotations/activities
```

This avoids running every expensive AI step for every lesson.

Admin runtime policy defines the allowed envelope. Creator choice becomes an immutable build snapshot.

Provider routing snapshot is diagnostic metadata, not an execution pin: each AI invocation resolves the current enabled route so operators can disable a broken provider/fallback while a queued job is waiting. The actual provider/model used is persisted in `ai_invocation`.

## 8. Background job architecture

Long operations never hold the original browser/mobile HTTP request open.

```text
POST /api/v1/admin/lessons/build
  ↓
create lesson DRAFT
  ↓
INSERT background_job + lesson_build_job
  ↓
HTTP 202 {lessonId, jobId}

BackgroundJobWorker
  ↓
SELECT ... FOR UPDATE SKIP LOCKED
  ↓
lease + heartbeat + fencing
  ↓
run persisted idempotent steps
  ↓
SUCCEEDED / RETRY_WAIT / CANCELLED / FAILED
```

Spring Modulith event registry is **not** used as a workflow engine. PostgreSQL job/step state is authoritative.

## 9. Job lease/fencing

Each claimed job has `lease_owner` and `lease_until`.

If worker A stalls and worker B recovers the expired lease, worker A is stale and must not overwrite B's state. Repository updates include worker ownership checks; stale updates become lease-lost conditions.

## 10. AI boundary

Java owns:

- product intent;
- business prompt;
- output schema expectation;
- provider capability request;
- retry/cancel/workflow state.

FastAPI owns:

- model/provider adapter;
- model loading;
- technical audio/NLP preprocessing;
- execution + normalized response.

This lets provider/model implementation change without moving Lesson/Curriculum business logic into Python.

## 11. Progress architecture

No God `progress` module.

```text
lesson      → lesson progress/attempts
vocabulary  → SRS/review history
grammar     → grammar attempts
toeic       → TOEIC attempts
curriculum  → item/path progress
```

`analytics` consumes events and projects:

- daily activity;
- skill summary;
- weaknesses;
- dashboard/recommendation inputs.

## 12. Runtime configuration precedence

The authoritative configuration layers, precedence, persistence, and override semantics live in
`CONFIGURATION.md`.

## 13. Storage

PostgreSQL stores normalized/queryable state.

R2 stores:

- canonical/derived audio;
- TOEIC images/audio;
- learner recording;
- raw AI output/debug artifact.

DB stores object keys, never presigned URL. Storage semantics are documented in
[`CONFIGURATION.md`](CONFIGURATION.md#7-storage-configuration) and the owning feature/data docs.

## 14. Cache / rate limit / resilience

- Caffeine: local read cache only.
- Bucket4j: inbound single-node MVP API quota.
- Resilience4j: outbound provider/FastAPI retry, circuit breaker, timeout, bulkhead.

Technology selection rationale, including the current Redis decision, lives in
`TECH_CHOICES.md` and `DECISIONS.md`.

## 15. Schema ownership

Mandatory schema/persistence rules are defined in
[`AGENTS.md`](../AGENTS.md#8-database-storage-and-data). Large dataset import
semantics are defined in `DATA_PIPELINES.md`.
