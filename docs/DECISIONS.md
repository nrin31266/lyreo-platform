# Lyreo Architecture Decisions

This is the compact decision log. If a future change contradicts one of these, update the decision explicitly instead of silently drifting architecture. Unless otherwise noted, D-001–D-016 were locked on 2026-09-06.

## D-001 — Modular Monolith

Status: Accepted.
Use Spring Boot + Spring Modulith. Do not split business modules into network services by default.

## D-002 — No Kafka at MVP

Status: Accepted.
Decision: do not introduce Kafka at MVP.

## D-003 — No Redis at MVP

Status: Accepted.
Decision: do not introduce Redis at MVP.

## D-004 — Java orchestrates AI workflows

Status: Accepted.
Business/product prompts, output expectations and workflow live in Java. FastAPI executes capabilities.

## D-005 — PostgreSQL Job Queue

Status: Accepted.
Use `FOR UPDATE SKIP LOCKED`, lease, heartbeat, retry, cancellation and fencing.

## D-006 — R2 for object artifacts

Status: Accepted.
PostgreSQL stores normalized/queryable state; R2 stores audio/images/recordings/raw artifacts. DB stores object key, not presigned URL.

## D-007 — Flyway owns schema

Status: Accepted.
Hibernate `ddl-auto=validate`; large datasets use importers.

## D-008 — Content / Annotation / Activity separation

Status: Accepted.
Contextual notes are reusable support data and are not automatically dedicated practice modes.

## D-009 — Lexicon and Vocabulary separate

Status: Accepted.
Lexicon is global dictionary; Vocabulary is learner SRS referencing Lexicon.

## D-010 — Progress ownership

Status: Accepted.
Each business module owns detailed progress. Analytics consumes events and projects summaries.

## D-011 — Configurable experience with precedence

Status: Accepted.
Decision: use layered configuration with domain invariants remaining code/schema rules.
Authoritative precedence and override semantics: [`CONFIGURATION.md`](CONFIGURATION.md).

## D-012 — Login required initially

Status: Accepted.
Avoid guest progress/SRS/Curriculum/Diamond merge complexity in first release.

## D-013 — Brand

Status: Accepted.
Product `Lyreo`; mascot `Lyrebird`; Java namespace `com.lyreo`.

## D-014 — Runtime config documents

Status: Accepted.
Admin-tunable module policy uses versioned PostgreSQL JSONB documents behind typed module policy repositories. Do not spread raw config maps through domain code.

## D-015 — Qwen runtime remains Python capability implementation

Status: Accepted.
Qwen3-ASR/ForcedAligner may run directly in FastAPI Python process or a later dedicated model host. Docker is packaging, not domain architecture.

## D-016 — Chat boundary exists but is low priority

Status: Accepted.
Keep module boundary so future English-only tutor can reuse AI routing, but do not prioritize chat over Lesson/TOEIC/Vocabulary/Curriculum core flows.

## D-017 — Semantic theming and shared localization (2026-09-07)

Status: Accepted.
Use shared primitive/semantic design tokens with light/dark themes and `@lyreo/i18n` for intentional
shared translations. Platform adapters own locale/theme persistence.

## D-018 — Platform-specific UI component ownership (2026-09-07)

Status: Accepted.
Admin uses Tailwind/shadcn-style owned Web components; Mobile uses NativeWind/RNR-style owned Native
components. Share semantic contracts, not component implementations/configuration.

## D-019 — External dataset bootstrap (2026-09-07)

Status: Accepted.
Grammar/TOEIC raw data stays outside Git. Developer setup may fetch the configured shared ZIP into a
Git-ignored local data directory and must validate importer-facing structure before use. Semantics: `DATA_PIPELINES.md`.

## D-020 — Task-routed documentation ownership (2026-09-09)

Status: Superseded by D-023.
The repository uses `docs/README.md` as its single task/domain/code route and assigns product,
requirement, story, feature, technical and evidence facts to separate owners. The legacy master path
is a compatibility index only. Source: the documentation setup directive accepted for this change;
this decision governs documentation structure, not unresolved product behavior.

## D-021 — RFC 9457 Problem Details and Core HTTP Foundation (2026-09-09)

Status: Accepted.
Decision: Core public HTTP API uses direct resource/DTO returns for success (no global envelope) and
RFC 9457 Problem Details (`application/problem+json`) with stable uppercase error codes and correlation ID
for errors. Generic Java exceptions are never mapped wholesale to client 4xx responses; infrastructure
failures yield safe 500 responses without leaking internals. Shared baseline semantic application errors
(`ResourceNotFoundException`, `StateConflictException`, `RequestValidationException`) are owned by
`libs/contracts/errors` (an open shared contract module) so that business modules and application services
can express application boundary outcomes without coupling domain logic to Spring MVC or HTTP runtime
classes. Swagger/OpenAPI is generated via springdoc with dev/test enablement and production disabled by default.
Rationale: [`http-api-contract.md`](architecture/http-api-contract.md).

## D-022 — Standalone Lesson Prep Tool and Portable Package Format (2026-09-13)

Status: Superseded by D-024. Standalone source and hooks retired on 2026-10-26 after integrated functional verification. The description below records the former operator workflow.
Decision: Lesson source acquisition, speech synthesis, transcription, and alignment are executed
locally inside a dedicated operator workstation (`tools/lesson-prep`) that exports one self-contained,
portable `*.lesson-source.zip` package (`lesson-source.json` manifest + media bytes). The tool does
not depend on or mutate Core database tables, Keycloak users/clients, or Cloudflare R2 object storage.
FastAPI AI Service exposes technical capabilities (`/v1/tts`, `/v1/tts/voices`, `/v1/stt`, `/v1/align`)
via local file/audio references (`file:///...`) without receiving YouTube URLs or business lesson DTOs.
Package ingestion into Core database and storage is a separate future phase. Rationale: `DATA_PIPELINES.md`,
`architecture/ai-execution.md`, and the former workstation README (retained in Git history).

## D-023 — Lean documentation and repository command ownership (2026-09-25)

Status: Accepted. Supersedes D-020's document taxonomy and evidence register.

Context: The foundation scaffold produced duplicate technology inventories, implementation-status documentation, and commands repeated across Make, scripts, and CI.

Decision: Route tasks through `docs/README.md`; keep one owner per normative fact. Requirements state intended behavior, code and manifests state current implementation, and fresh checks state current evidence. Keep architecture at C4-lite boundaries. The root Makefile is the stable command interface, owner scripts implement complex actions, and CI consumes Make targets. Academic traceability is a snapshot outside canonical engineering docs.

Consequences: Document merges and valid implementation refactors are possible without updating a fixed leaf-doc taxonomy. Link/ID checks remain structural; behavior and module boundaries are verified by tests and targeted validators.

Confirmation: `make validate-docs`, `make validate-repo`, `make test-tooling`, and relevant application checks.


## D-024 — Integrated YouTube Lesson authoring and free-only research (2026-10-25)

Status: Accepted. Supersedes D-022 for the current authoring phase.

Admin authors one contiguous YouTube range per Lesson, reviews transcript and karaoke before
semantic generation, reviews contextual annotations/candidates, and publishes explicitly. Core owns
business prompts, lifecycle, validation and PostgreSQL checkpoints; FastAPI only executes technical
capabilities. Text/Audio authoring and mobile playback are deferred, not new work for this phase.

Draft revision and transcript revision are separate: candidate selection cannot invalidate approved
content. Publication atomically projects immutable content and selected candidate membership. Old
writers must not mutate published content or cascade-delete learner attempts/progress. Additive Flyway
migrations preserve existing records; scaffold APIs and implementation are not product contracts.

Free-only execution permits only benchmarked allowlisted models and requires operator confirmation
of the external projects' free billing path. No billing changes or paid fallback are automatic.
Quota waits are durable, resumable jobs. Existing encrypted Core credentials remain authoritative.
See the bounded [ASR evidence](research/lesson-authoring-asr.md) and
[semantic evidence](research/lesson-authoring-semantic.md); these are measured research, not release guarantees.

Lesson Prep retirement requires regression coverage, replacement workflow verification and no runtime
dependency. These technical retirement conditions were met on 2026-10-26: the real OIDC workflow
completed publication, reload showed immutable content, direct mutation returned 409, and replacement
regressions cover cancellation, late results, quota/resume and atomic publication. The standalone
source and setup/CI hooks are retired; local secrets, media and exported artifacts are preserved.
This does not approve human timing/pedagogical quality or independently verify provider billing.
The old package ingestion proposal is not part of this phase. Feature owner:
[Lesson Build](features/lesson-build.md).
