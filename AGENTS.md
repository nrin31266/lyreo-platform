# AGENTS.md — Lyreo Engineering Contract

File này là **source of truth** cho Codex, Claude, Gemini và developer. Nếu task mâu thuẫn với file này, dừng và yêu cầu architecture decision thay vì tự ý đổi nền tảng.

## Product

Lyreo là English-learning platform: lesson từ Text/Audio/YouTube, Dictation, Shadowing,
contextual annotations, Lexicon, Vocabulary SRS, Grammar, TOEIC, Curriculum, Analytics,
Level/Diamond/Mission và Chat (low priority).

Mascot **Lyrebird** chỉ là presentation/brand concern; không đặt tên business service theo mascot.

## Architecture

- Java 25 + Spring Boot 4.1.x.
- Modular Monolith + Spring Modulith.
- Pragmatic Clean/Hexagonal Architecture bên trong module.
- PostgreSQL + Flyway.
- FastAPI AI capability service.
- Cloudflare R2 qua S3-compatible port; local filesystem adapter cho dev.
- Keycloak/OIDC.
- Caffeine read cache.
- Versioned runtime config documents trong PostgreSQL sau typed module policy.
- Bucket4j inbound rate limiting.
- Resilience4j outbound resilience.
- PostgreSQL-backed background jobs (`FOR UPDATE SKIP LOCKED`, lease, heartbeat, fencing).
- React/Vite Admin.
- Expo SDK 57 + React Native 0.86 mobile.

## Dependency rule

```text
api -> application -> domain
        ↑
infrastructure implements ports
```

Infrastructure may depend inward. Domain/application must not depend on infrastructure.
Domain không được biết JPA/JDBC adapter, R2, HTTP, FastAPI, Keycloak SDK hay UI framework.

Cross-module communication:

1. named/public module API;
2. Spring Modulith event contract;
3. tuyệt đối không import repository/JPA entity/infrastructure nội bộ module khác.

## Domain ownership

- `identity`: app user provisioning around Keycloak subject.
- `learner`: onboarding/profile/persistent learner preferences.
- `ai`: provider/model routing, encrypted credentials, invocation audit.
- `lesson`: content, annotations, activities, build jobs, lesson practice.
- `speech-assessment`: user speech recording/ASR/timing/fluency/deep judge.
- `lexicon`: global dictionary.
- `vocabulary`: learner SRS referencing Lexicon.
- `grammar`: taxonomy/question/practice.
- `toeic`: test/question/attempt/score.
- `curriculum`: path/section/item/enrollment/progress mapping content IDs.
- `gamification`: level/XP, Diamond ledger, missions/rewards.
- `analytics`: projections/read models only; không sở hữu detailed progress.
- `notification`: realtime/push boundary.
- `chat`: low-priority English tutor boundary.

Technical `platform/*` modules must not depend on business modules.

## Hard prohibitions

DO NOT:

- introduce Kafka;
- introduce Redis without accepted architecture decision and measured need;
- put Lesson/Curriculum/Gamification workflow in FastAPI;
- put business/product LLM prompts in Python;
- set Hibernate `ddl-auto=update/create/create-drop`;
- use R2 raw JSON as workflow source of truth;
- store provider API keys plaintext;
- store signed/presigned R2 URLs in DB;
- trust client-provided score, XP or Diamond amount;
- access another module's repository/JPA entity/infrastructure;
- create a God `progress` module;
- merge Lexicon and Vocabulary;
- treat contextual vocab/grammar notes as Vocabulary/Grammar Practice;
- hard-code provider model names in Java enums;
- commit raw TOEIC/Kaikki datasets into Git;
- copy the legacy Kafka/Redis lesson pipeline into Lyreo;
- hide a failed/unrun test by calling the feature “done”.

## Background jobs

PostgreSQL is authoritative. Cancellation is durable DB state.

Long handler requirements:

- check cancellation between expensive steps and before committing external output;
- use persisted idempotent step state;
- heartbeat independently from UI progress;
- respect lease ownership/fencing;
- never let a stale worker overwrite a recovered job;
- store raw AI response as artifact, not checkpoint.

## AI

Java owns intent/prompt/schema/orchestration. `ai` module resolves provider/model. FastAPI executes capability only.

Implemented runtime modes:

- `mock`: no GPU/download, default for normal dev/CI;
- `local`: `qwen-asr` + Qwen3-ForcedAligner loaded in Python process.

External provider credentials remain server-side and must never enter frontend env/logs.

## Database

Every schema change needs a **new** Flyway migration. Never edit an already-released migration after it reached a shared environment.

Flyway handles schema/small stable reference seeds. Large Lexicon/TOEIC/Grammar/Curriculum content goes through importer tooling.

Hibernate validates; it does not own schema evolution.

## Configuration precedence

```text
deployment capability/secret
    -> admin runtime policy
    -> lesson build snapshot
    -> learner persistent preference
    -> session override
```

Business modules should consume runtime config through typed policy objects/ports. Do not scatter `Map<String,Object>` settings or turn invariants into checkboxes.

When adding a new env variable:

- place it in the owning service `.env.example`;
- add inline comment if purpose/format/security classification is not obvious;
- update `docs/CONFIGURATION.md` when behavior/precedence changes;
- never add server secret to `VITE_*` or `EXPO_PUBLIC_*`.

## Docker / development

Development default: app source runs local; Docker runs dependency infrastructure only.

- `compose.dev.yml`: PostgreSQL + Keycloak.
- `compose.prod.yml`: self-host/staging full topology, not an HA guarantee.
- `compose.gpu.yml`: optional NVIDIA/Qwen override.

Do not add infrastructure container just because a library supports it. Explain operational need first.

## Comments / docs

Comments should explain **why, invariant, trade-off, failure mode or non-obvious protocol**, not narrate obvious syntax.

If code changes:

- architecture → update `ARCHITECTURE.md` / `DECISIONS.md`;
- env/config → update `.env.example` + `CONFIGURATION.md`;
- dev command/topology → update `README.md` / `DEVELOPMENT.md` / `infra/docker/README.md`;
- operational behavior → update `OPERATIONS.md`;
- test limitation của artifact/handoff hiện tại → update `TESTING_NOTES.md` (temporary; không phải architecture source of truth).

Do not create many duplicate markdown files for the same concern.

## Security

- Keycloak roles: `ADMIN` / `LEARNER`.
- Mobile/Admin: Authorization Code + PKCE.
- Provider API keys encrypted with AES-GCM using env master key.
- Public frontend env never contains server/provider secret.
- Dev-only endpoints disabled outside `dev`.
- Client cannot choose reward/score authority.
- Sensitive learner recordings/artifacts stay private by default.

## Before completing a task

Run applicable checks:

- Java tests + Spring Modulith architecture verification;
- Flyway migration startup/validation;
- Python tests/compile;
- importer tests/dry-run when data code changes;
- TypeScript typecheck/build;
- shell/config validation;
- no secret accidentally committed.

`make validate` is the minimum offline guardrail, not a replacement for compilation/integration tests.

If unable to run a check, state exactly which one and why.
