# Lyreo Architecture Decisions

This is the compact decision log. If a future change contradicts one of these, update the decision explicitly instead of silently drifting architecture.

## D-001 — Modular Monolith
Use Spring Boot + Spring Modulith. Do not split business modules into network services by default.

## D-002 — No Kafka at MVP
Internal event isolation = Spring Modulith. Core↔AI = HTTP. Durable workflows = PostgreSQL jobs.

## D-003 — No Redis at MVP
Cancellation/job = PostgreSQL; read cache = Caffeine; inbound rate limit = Bucket4j.

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
`deployment → admin policy → build snapshot → learner preference → session override`.
Domain invariants remain code/schema rules.

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
