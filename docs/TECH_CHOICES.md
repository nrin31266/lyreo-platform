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
| Admin UI | Tailwind CSS 4.3.x + shadcn/Radix-style owned components | Product-owned primitives without rebuilding accessibility-heavy Web controls |
| Mobile | Expo SDK 57 / RN 0.86 | Modern RN + Development Build/native escape hatch |
| Mobile UI | NativeWind 4.2.x + Tailwind 3.4.x + selected RNR-style owned primitives | Semantic utility contract on native without forcing Web component sharing |
| Localization | i18next 26.x + react-i18next 17.x; expo-localization on Mobile | Same translation model across Web/Native with platform-specific locale detection |
| Themes | Shared semantic light/dark tokens; `system | light | dark` preference | Prevent feature-level color literals and make dark mode a foundation concern |
| Monorepo JS | pnpm workspaces | Shared design-system/i18n contracts with platform-specific component ownership |
| HTTP errors | RFC 9457 Problem Details | Native Spring `ProblemDetail`, stable codes, correlation ID, safe 500s |
| Validation | Jakarta Bean Validation / Hibernate Validator | Standard request boundary shape/syntax constraints |
| API Docs | Springdoc OpenAPI 3.1.x / Swagger UI | Automated OpenAPI contract, Bearer JWT auth, dev/test profile controlled |

## Kafka deliberately not selected

Kafka is not part of the MVP architecture. The full rationale is owned by
[`DECISIONS.md`](DECISIONS.md#d-002--no-kafka-at-mvp) D-002.

## Redis deliberately not selected

Redis is not part of the MVP architecture. The full rationale is owned by
[`DECISIONS.md`](DECISIONS.md#d-003--no-redis-at-mvp) D-003.

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

## Frontend styling and localization

`packages/design-system` owns brand primitives and semantic color roles. Both Admin and Mobile consume
the semantic contract, but their component implementations remain platform-owned. Admin uses
Tailwind CSS v4 with shadcn/Radix-style copied components; Mobile stays on stable NativeWind v4 with
its Tailwind v3.4 toolchain and selected React Native Reusables-style copied primitives. This avoids
forcing two different rendering/accessibility systems through one component abstraction.

`packages/i18n` owns intentionally shared `common`, `admin`, and `mobile` resources. Admin detects
browser locale and stores an explicit override in localStorage. Mobile detects OS locale through
`expo-localization` and stores ordinary locale/theme preferences in AsyncStorage, not SecureStore.

Dark mode is semantic rather than component-specific. Admin resolves `system` through
`prefers-color-scheme`, toggles the `.dark` DOM class and exposes semantic CSS variables. Mobile
uses `expo.userInterfaceStyle=automatic` + React Native `useColorScheme()`, then exposes the same
semantic roles through NativeWind `vars()`. Feature screens never own raw theme colors.

Expo Router dependencies that its installation contract expects (`expo-constants`, `expo-linking`,
`expo-status-bar`, `react-native-safe-area-context`, `react-native-screens`) are direct Mobile
dependencies. They must be kept on Expo SDK-compatible versions rather than inherited transitively.

## Upgrade policy

Patch/minor version upgrades require tests and changelog review. Major changes to auth, persistence, eventing, object storage or job protocol require architecture decision update.

## Official references checked at architecture lock (2026-09-09)

These links reflect the versions known at the time this file was written. Version authority is the
manifest/lockfile; these links are a starting point for upgrade research, not a promise of
current-latest.

- Spring Boot 4.1.1 release: <https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/>
- Spring Modulith 2.1.1 release: <https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/>
- Spring Modulith event docs: <https://docs.spring.io/spring-modulith/reference/events.html>
- Keycloak 26.7.3 release: <https://www.keycloak.org/2026/08/keycloak-2673-released>
- PostgreSQL 18 docs: <https://www.postgresql.org/docs/release/18.6/>
- Cloudflare R2 pricing: <https://developers.cloudflare.com/r2/pricing/>
- Cloudflare R2 S3 API: <https://developers.cloudflare.com/r2/api/s3/>
- Qwen3-ASR official repo: <https://github.com/QwenLM/Qwen3-ASR>
- FastAPI docs: <https://fastapi.tiangolo.com/>
- Expo SDK 57 changelog: <https://expo.dev/changelog/sdk-57>
- Expo SDK reference: <https://docs.expo.dev/versions/latest/>
- Bucket4j: <https://github.com/bucket4j/bucket4j>
- Resilience4j: <https://resilience4j.readme.io/>
- Wiktextract: <https://github.com/tatuylonen/wiktextract>
- Kaikki: <https://kaikki.org/>
