# Lyreo Tech Choices — architecture lock September 2026

This file records current choices and **why** they exist. Version numbers may receive compatible patch/minor updates after tests; architecture-changing swaps require `DECISIONS.md` update.

| Area | Choice | Reason |
|---|---|---|
| Core | Spring Boot 4.1.x | Current Java platform baseline |
| Modules | Spring Modulith 2.1.x | Internal module verification/events |
| Java | 25 | Team target / current LTS baseline |
| Architecture | Modular Monolith + Pragmatic Clean Architecture | Strong boundaries without distributed-system overhead |
| DB | PostgreSQL 18 | Business data + durable queue + analytics/reference data |
| Schema | Flyway | Explicit reproducible migrations |
| ORM/data | JDBC + selective JPA/Hibernate | Hibernate validation; JDBC useful for explicit job/import/query SQL |
| Runtime config | PostgreSQL JSONB documents + typed module policy | Flexible settings without untyped global key/value sprawl |
| AI API | FastAPI / Python 3.12 | Qwen/ML ecosystem and thin capability runtime |
| STT | Qwen3-ASR | Local/self-hostable primary |
| STT fallback | Groq speech models | API fallback / low setup cost |
| Alignment | Qwen3-ForcedAligner | Word timestamps for Dictation/Shadowing |
| General LLM | Runtime route, initially Groq → Gemini | Provider/model must remain replaceable |
| Reasoning LLM | Runtime route, initially DeepSeek → fallback | Strong reasoning where value justifies API use |
| TTS | Gemini initially | Server reference audio; provider remains capability-routed |
| Storage | Cloudflare R2 via S3 abstraction | Audio/raw artifacts; S3 portability; simple dev/prod model |
| Dev storage | Local filesystem adapter | Clone/run without cloud credentials |
| Cache | Caffeine | Local read cache, no durable state |
| API rate limit | Bucket4j + bounded Caffeine bucket cache | Lightweight inbound protection |
| Outbound resilience | Resilience4j | Retry/circuit breaker/bulkhead/timeouts |
| Jobs | PostgreSQL queue | Durable cancel/retry/lease without broker |
| Auth | Keycloak 26.7.x | OIDC/PKCE/realm roles/bootstrapable dev topology |
| Admin | React + Vite | Internal/admin SPA, no SEO requirement |
| Mobile | Expo SDK 57 / RN 0.86 | Modern RN + Development Build/native escape hatch |
| Monorepo JS | pnpm workspaces | Shared design system, predictable package boundaries |

## Kafka deliberately not selected

Lyreo currently has no demonstrated high-throughput event-stream/replay/multi-service consumer requirement.

- Java module events → Spring Modulith.
- Core↔AI → HTTP.
- long-running workflow → PostgreSQL jobs.

Adding Kafka would add producer/consumer/topic/offset/ops complexity without removing the need for durable business state.

## Redis deliberately not selected

Old responsibilities were separated:

```text
cancel/job state → PostgreSQL
read cache       → Caffeine
API rate limit   → Bucket4j
raw artifacts    → R2
module events    → Spring Modulith
```

Redis can return later only when multi-instance distributed cache/global rate quota/session use case is measured.

## R2 instead of Cloudinary for core storage

Lyreo mostly needs object storage for:

- audio;
- images;
- recordings;
- raw AI JSON.

Media transform is handled by FFmpeg/AI runtime where needed. Cloudinary is stronger as a media transformation platform, but that is not Lyreo's primary storage requirement.

## Caffeine is cache, not state

Restart may erase cache safely. Never put cancellation, progress, Diamond balance source-of-truth or workflow checkpoint in Caffeine.

## JDBC + JPA/Hibernate

JPA is persistence specification; Hibernate is the provider. Lyreo does not rely on Hibernate schema generation.

Explicit SQL/JDBC is intentionally used for:

- `FOR UPDATE SKIP LOCKED` job protocol;
- bulk/reference queries;
- simple module adapters where it is clearer than ORM.

This is not a requirement that every table must have a JPA entity.

## Qwen and Docker

Qwen3-ASR/ForcedAligner are loaded via Python package/runtime. Docker GPU is packaging/deployment convenience, not a requirement for the Python code itself.

## Mobile: Development Build, not Expo Go-only

Audio recording/playback and future native integrations require an escape hatch. Development Build keeps Expo tooling without treating Expo Go as the production runtime constraint.

## Upgrade policy

Patch/minor version upgrades require tests and changelog review. Major changes to auth, persistence, eventing, object storage or job protocol require architecture decision update.
