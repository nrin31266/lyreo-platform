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

### Ownership highlights

- `lesson`: content, annotations, activities, lesson-build job state, lesson practice attempts.
- `speech-assessment`: learner speech recording assessment, ASR/timing/fluency/deep judge.
- `lexicon`: global dictionary entries/senses/forms/pronunciation/source/license.
- `vocabulary`: learner-owned SRS cards referencing Lexicon.
- `grammar`: grammar taxonomy/question bank/practice attempts.
- `toeic`: tests/passages/questions/attempts/scoring.
- `curriculum`: paths/sections/items/enrollment/progress mapping other content IDs.
- `gamification`: XP/level, Diamond ledger, missions.
- `analytics`: projections/read models only, not source-of-truth detailed progress.
- `ai`: provider/model routing, encrypted credentials, invocation audit.

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

No Kafka is required for this in-process module isolation.

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

```text
deployment secret/capability
        ↓
admin runtime policy
        ↓
lesson build snapshot
        ↓
learner preference
        ↓
session override
```

See `CONFIGURATION.md` for persistence/override details.

## 13. Storage

PostgreSQL stores normalized/queryable state.

R2 stores:

- canonical/derived audio;
- TOEIC images/audio;
- learner recording;
- raw AI output/debug artifact.

DB stores object keys, never presigned URL.

## 14. Cache / rate limit / resilience

- Caffeine: local read cache only.
- Bucket4j: inbound single-node MVP API quota.
- Resilience4j: outbound provider/FastAPI retry, circuit breaker, timeout, bulkhead.

Redis is not needed until a measured distributed-cache/global-quota use case appears.

## 15. Schema ownership

Flyway owns schema history. Hibernate runs with `ddl-auto=validate`.

Large Lexicon/Grammar/TOEIC data is imported by tools, not Flyway migrations.
