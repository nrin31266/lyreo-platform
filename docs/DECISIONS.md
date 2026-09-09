# Lyreo Architecture Decisions

This is the compact decision log. If a future change contradicts one of these, update the decision explicitly instead of silently drifting architecture. Unless otherwise noted, D-001–D-016 were locked on 2026-09-06.

## D-001 — Modular Monolith
Use Spring Boot + Spring Modulith. Do not split business modules into network services by default.

## D-002 — No Kafka at MVP
Decision: do not introduce Kafka at MVP. Rationale: [`TECH_CHOICES.md`](TECH_CHOICES.md#kafka-deliberately-not-selected).

## D-003 — No Redis at MVP
Decision: do not introduce Redis at MVP. Rationale: [`TECH_CHOICES.md`](TECH_CHOICES.md#redis-deliberately-not-selected).

## D-004 — Java orchestrates AI workflows
Business/product prompts, output expectations and workflow live in Java. FastAPI executes capabilities.

## D-005 — PostgreSQL Job Queue
Use `FOR UPDATE SKIP LOCKED`, lease, heartbeat, retry, cancellation and fencing.

## D-006 — R2 for object artifacts
PostgreSQL stores normalized/queryable state; R2 stores audio/images/recordings/raw artifacts. DB stores object key, not presigned URL.

## D-007 — Flyway owns schema
Hibernate `ddl-auto=validate`; large datasets use importers.

## D-008 — Content / Annotation / Activity separation
Contextual notes are reusable support data and are not automatically dedicated practice modes.

## D-009 — Lexicon and Vocabulary separate
Lexicon is global dictionary; Vocabulary is learner SRS referencing Lexicon.

## D-010 — Progress ownership
Each business module owns detailed progress. Analytics consumes events and projects summaries.

## D-011 — Configurable experience with precedence
Decision: use layered configuration with domain invariants remaining code/schema rules.
Authoritative precedence and override semantics: [`CONFIGURATION.md`](CONFIGURATION.md).

## D-012 — Login required initially
Avoid guest progress/SRS/Curriculum/Diamond merge complexity in first release.

## D-013 — Brand
Product `Lyreo`; mascot `Lyrebird`; Java namespace `com.lyreo`.

## D-014 — Runtime config documents
Admin-tunable module policy uses versioned PostgreSQL JSONB documents behind typed module policy repositories. Do not spread raw config maps through domain code.

## D-015 — Qwen runtime remains Python capability implementation
Qwen3-ASR/ForcedAligner may run directly in FastAPI Python process or a later dedicated model host. Docker is packaging, not domain architecture.

## D-016 — Chat boundary exists but is low priority
Keep module boundary so future English-only tutor can reuse AI routing, but do not prioritize chat over Lesson/TOEIC/Vocabulary/Curriculum core flows.

## D-017 — Semantic theming and shared localization (2026-09-07)
Use shared primitive/semantic design tokens with light/dark themes and `@lyreo/i18n` for intentional
shared translations. Platform adapters own locale/theme persistence. Rationale: `TECH_CHOICES.md`.

## D-018 — Platform-specific UI component ownership (2026-09-07)
Admin uses Tailwind/shadcn-style owned Web components; Mobile uses NativeWind/RNR-style owned Native
components. Share semantic contracts, not component implementations/configuration. Rationale: `TECH_CHOICES.md`.

## D-019 — External dataset bootstrap (2026-09-07)
Grammar/TOEIC raw data stays outside Git. Developer setup may fetch the configured shared ZIP into a
Git-ignored local data directory and must validate importer-facing structure before use. Semantics: `DATA_PIPELINES.md`.

## D-020 — Task-routed documentation ownership (2026-09-09)
The repository uses `docs/README.md` as its single task/domain/code route and assigns product,
requirement, story, feature, technical and evidence facts to separate owners. The legacy master path
is a compatibility index only. Source: the documentation setup directive accepted for this change;
this decision governs documentation structure, not unresolved product behavior.

## D-021 — RFC 9457 Problem Details and Core HTTP Foundation (2026-09-09)
Decision: Core public HTTP API uses direct resource/DTO returns for success (no global envelope) and
RFC 9457 Problem Details (`application/problem+json`) with stable uppercase error codes and correlation ID
for errors. Generic Java exceptions are never mapped wholesale to client 4xx responses; infrastructure
failures yield safe 500 responses without leaking internals. Shared baseline semantic application errors
(`ResourceNotFoundException`, `StateConflictException`, `RequestValidationException`) are owned by
`libs/contracts/errors` (an open shared contract module) so that business modules and application services
can express application boundary outcomes without coupling domain logic to Spring MVC or HTTP runtime
classes. Swagger/OpenAPI is generated via springdoc with dev/test enablement and production disabled by default.
Rationale: [`http-api-contract.md`](architecture/http-api-contract.md).
