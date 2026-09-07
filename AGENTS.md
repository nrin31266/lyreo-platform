# AGENTS.md — Lyreo Engineering Contract

This file is the **single source of truth for Lyreo engineering rules**.

Primary audience:

- coding agents such as Codex, Claude, Gemini, and other agents;
- developers changing code;
- reviewers deciding whether a change preserves the foundation.

This file is **not** the project runbook and does not duplicate the master specification.

- Setup/run/troubleshooting → `README.md`, `docs/DEVELOPMENT.md`
- Product/feature intent → `docs/LYREO_PLATFORM_SPEC.md`
- Architecture explanation → `docs/ARCHITECTURE.md`
- Technology rationale → `docs/TECH_CHOICES.md`
- Mandatory engineering rules → **`AGENTS.md`**

If a task conflicts with this contract, do not silently change the foundation. Surface the conflict
and request an architecture decision/approval first.

---

## 1. Product boundary

Lyreo is an English-learning platform that includes:

- Lessons from Text/Audio/YouTube;
- Dictation;
- Shadowing;
- contextual annotations;
- Lexicon;
- Vocabulary SRS;
- Grammar;
- TOEIC;
- Curriculum;
- Analytics;
- Level/Diamond/Mission;
- low-priority Chat.

The **Lyrebird** mascot is a presentation/brand concern.

Do not name business/domain classes after the mascot.

---

## 2. Architecture baseline

- Java + Spring Boot.
- Modular Monolith + Spring Modulith.
- Pragmatic Clean/Hexagonal Architecture inside business modules.
- PostgreSQL + Flyway.
- FastAPI AI capability service.
- Cloudflare R2 through an S3-compatible storage port; local filesystem adapter for dev.
- Keycloak/OIDC.
- Caffeine read cache.
- Typed/versioned runtime configuration.
- Bucket4j inbound rate limiting.
- Resilience4j outbound resilience.
- PostgreSQL-backed background jobs with lease, heartbeat, and fencing.
- React/Vite Admin.
- Expo/React Native Mobile.

Exact version baselines and upgrade rationale live in `docs/TECH_CHOICES.md`; the dated architecture
snapshot also appears in `docs/LYREO_PLATFORM_SPEC.md` §59.

Do not add infrastructure only because it makes the architecture look more “enterprise”.
There must be a measured or operational need.

---
## 3. Third-party Agent Skills

Project-local skills under `.agents/skills/` provide procedural guidance
for supported coding agents.

Precedence:

1. `AGENTS.md`
2. Canonical Lyreo documentation
3. Existing architecture and technology decisions
4. Third-party `SKILL.md` guidance

Third-party skills must not change Lyreo architecture merely to match
their preferred stack.

In particular, skills must not introduce or migrate to Kafka, Redis,
Expo API Routes, NativeWind v5, Next.js server architecture,
cross-platform shared UI implementations, or business orchestration
inside FastAPI unless the corresponding Lyreo architecture decision
is explicitly changed first.
## 4. Dependency rule

```text
api / adapters-in
        ↓
application
        ↓
domain

infrastructure → implements inward-facing ports
```

Domain/application must not depend on infrastructure.

Domain must not know about:

- JPA/JDBC adapters;
- PostgreSQL;
- R2/S3 SDKs;
- HTTP/FastAPI;
- Keycloak SDK;
- frontend frameworks.

Infrastructure may depend inward to implement ports.

---

## 5. Module communication

Cross-module interaction is allowed only through:

1. named/public module APIs or intentionally shared public contracts;
2. Spring Modulith event contracts.

Do not:

- import another module's repository;
- import another module's JPA entity;
- import another module's internal infrastructure package;
- query another module's tables directly as a shortcut around a public API/event.

Architecture tests/validators must protect these boundaries.

---

## 6. Domain ownership

- `identity`: app-user mapping/JIT provisioning around Keycloak subject.
- `learner`: onboarding/profile/persistent learner preferences.
- `ai`: provider/model routing, encrypted credentials, invocation audit.
- `lesson`: content, annotations, activities, build workflow, lesson practice.
- `speech-assessment`: user recording/ASR/timing/fluency/deep-judge results.
- `lexicon`: global dictionary.
- `vocabulary`: learner SRS referencing Lexicon.
- `grammar`: taxonomy/questions/practice.
- `toeic`: tests/questions/attempts/scoring.
- `curriculum`: path/section/item/enrollment/progress over content references.
- `gamification`: level/XP, Diamond ledger, missions/rewards.
- `analytics`: projections/read models; does not own detailed domain progress.
- `notification`: realtime/push boundary.
- `chat`: low-priority tutor boundary.

`platform/*` contains technical modules and must not depend on business modules.

Lesson composition invariant: Text/Audio/YouTube source selection, selected activities, and selected
enrichment are independent dimensions of a lesson build. Vocabulary Practice and Grammar Practice
are real Lesson activities. A contextual vocabulary/grammar note attached to another activity
(for example, shown after a Dictation sentence) is **not** the same as Vocabulary Practice / Grammar
Practice. Exact activity and option details are owned by `docs/LYREO_PLATFORM_SPEC.md` §14–15.

Do not create a God `progress` module.

---

## 7. Hard prohibitions

Without a new approved architecture decision, **DO NOT**:

- introduce Kafka;
- introduce Redis;
- put Lesson/Curriculum/Gamification workflow in FastAPI;
- put business/product LLM prompts in Python;
- set Hibernate `ddl-auto=update/create/create-drop`;
- use R2 raw JSON as the workflow source of truth;
- store provider API keys in plaintext;
- store signed/presigned R2 URLs in the database;
- trust client-provided score, XP, or Diamond amount;
- access another module's repository/JPA entity/infrastructure;
- create a God `progress` module;
- merge Lexicon and Vocabulary;
- treat contextual vocabulary/grammar notes as Vocabulary/Grammar Practice;
- hard-code provider model names in Java enums;
- commit raw TOEIC/Kaikki datasets into Git;
- copy the legacy Kafka/Redis lesson pipeline into Lyreo;
- hide failed/unrun required tests or call a feature “done” when mandatory verification has not passed.
- hard-code theme/brand color literals in feature UI instead of consuming semantic design tokens;
- create separate copies of shared translation strings when the text belongs in `@lyreo/i18n`;
- force Web and Mobile to share component implementations merely because they share semantic tokens;
- store ordinary locale/theme preferences in SecureStore or another secret store.

---

## 8. Background jobs

PostgreSQL is the authoritative workflow state.

Long-running handlers must:

- claim work through the `platform/jobs` queue/lease protocol;
- check cancellation before and between expensive steps;
- heartbeat the lease according to the protocol;
- persist idempotent step state;
- respect lease ownership/fencing;
- never let a stale worker overwrite a recovered job;
- never use UI progress events as a durability mechanism;
- store raw AI responses as artifact/debug data, not as workflow checkpoints.

Cancellation is durable database state.

If external inference cannot be hard-cancelled, the worker must check cancellation before committing
the output and discard the result if the job is no longer allowed to continue.

---

## 9. AI boundary

Java/Core owns:

- business intent;
- business/product prompts;
- expected schemas;
- orchestration;
- provider/model routing;
- retry/fallback policy;
- persistence/state transition.

FastAPI owns capability execution:

- STT;
- alignment;
- TTS;
- NLP;
- generic LLM generation;
- multimodal judging.

Do not design FastAPI endpoints around business workflows such as `/generate-lesson`.

External provider credentials always remain server-side.

---

## 10. Python project environment

`uv` is the standard tool for Lyreo Python subprojects.

**The host Python version is not the project Python version.**

The host interpreter and the project environments are separate concerns. The supported/recommended
Python baseline is owned by `docs/TECH_CHOICES.md` and enforced by each subproject's
`pyproject.toml`.

Agents/developers must not:

- require changing the system/default Python only because the host version differs from the project version;
- infer runtime compatibility only from `python --version` at repository root;
- install project dependencies into system Python as a quick fix;
- depend on `.venv` already being activated.

Prefer:

```bash
uv sync
uv run ...
```

Supported Python constraints and dependencies must live in the owning subproject's `pyproject.toml`.

If the supported Python policy changes:

- update the relevant `pyproject.toml`;
- update developer documentation when workflow changes;
- rerun relevant tests and local/GPU compatibility checks.

---

## 11. Database and persistence

Flyway owns schema evolution.

Every schema change requires a new migration.

Do not edit a migration after it has been applied in a shared environment.

Hibernate validates mapping/schema; it must not create/update the schema.

Large Lexicon/TOEIC/Grammar/Curriculum content belongs in importer tooling, not Flyway.

JPA/JDBC are infrastructure details. Do not put persistence annotations into domain/application
code just to make an adapter easier.

---

## 12. Configuration

Configuration layers, precedence, persistence, and override semantics are owned by
`docs/CONFIGURATION.md` §1–6.

Business modules consume runtime configuration through appropriate typed policies/ports.

Do not scatter untyped configuration maps through business logic and do not turn domain invariants
into checkboxes.

When adding an environment variable:

- put it in the owning executable/service `.env.example`;
- document purpose/format/security when it is not obvious;
- update `docs/CONFIGURATION.md` when behavior/precedence changes;
- never place server secrets in `VITE_*` or `EXPO_PUBLIC_*`.

Do not create a root `.env` containing all service secrets.

---

## 13. Development topology constraint

The default development model is:

- application source runs locally;
- Docker dev runs dependency infrastructure;
- PostgreSQL + Keycloak are the primary dev infrastructure;
- local filesystem is the default storage adapter;
- an R2 dev bucket is optional;
- AI runtime provides `mock` mode for normal dev/CI.

Step-by-step commands belong in `README.md` / `docs/DEVELOPMENT.md`; do not duplicate the runbook
here.

Do not add Kafka/Redis/MinIO/queue brokers/observability stacks to the dev compose merely to make it
look “complete”. Explain the operational need first.

Frontend foundation follows the same ownership principle: `packages/design-system` owns primitive
and semantic theme contracts, while `packages/i18n` owns intentionally shared translation resources.
Admin Web and Mobile own their platform-specific component implementations.

---

## 14. Comments and documentation ownership

Comments should explain:

- why;
- invariants;
- trade-offs;
- failure modes;
- non-obvious protocols.

Do not narrate obvious syntax.

Documentation ownership:

- setup/run/repository entry point → `README.md`;
- product/feature/architecture intent → `docs/LYREO_PLATFORM_SPEC.md`;
- module/system/topology explanation → `docs/ARCHITECTURE.md`;
- architecture decision changes → `docs/DECISIONS.md`;
- technology rationale → `docs/TECH_CHOICES.md`;
- env/config behavior → owning `.env.example` + `docs/CONFIGURATION.md`;
- developer workflow/troubleshooting → `docs/DEVELOPMENT.md`;
- operational behavior → `docs/OPERATIONS.md`;
- importer/data semantics → `docs/DATA_PIPELINES.md`.

**Do not copy the same normative rule into multiple files.**

If a sentence can be “violated”, it normally belongs in `AGENTS.md` or in an explicitly referenced
architecture decision. README should point to that source rather than maintaining a second copy.

`TESTING_NOTES.md` is a temporary handoff note, not an architecture source of truth.

---

## 15. Security authority

- Keycloak roles: `ADMIN` / `LEARNER`.
- Mobile/Admin: OIDC Authorization Code + PKCE.
- Provider API keys are encrypted with AES-GCM using a master key from env.
- Public frontend env must not contain server/provider secrets.
- Dev-only endpoints must be disabled outside dev.
- The client has no authority over score/reward decisions.
- Learners may access only resources they are authorized to access.
- Sensitive learner recordings/artifacts are private by default.
- Logs must not contain provider credentials/tokens in plaintext.

---

## 16. Testing and completion

Before completing a task, run **the checks applicable to the changed area**:

- Java tests;
- Spring Modulith/architecture verification;
- Flyway migration startup/validation when schema changes;
- Python tests/compile when Python code changes;
- importer tests/dry-run when importer/data code changes;
- TypeScript typecheck/build when frontend/shared TypeScript changes;
- shell/config validation when scripts/config change;
- secret/build-output sanity checks before commit/release.

`make validate` is the minimum offline guardrail, not a replacement for compilation/integration tests.

If a required check cannot be run:

1. do not claim it passed;
2. state which check was not run;
3. state why;
4. provide the command for developer/CI to rerun it.

Temporary artifact limitations may be written in `TESTING_NOTES.md`; long-lived issues belong in the
issue tracker/CI.

---

## 17. Definition of an acceptable change

A change preserves the foundation when:

- module ownership is correct;
- dependencies point in the correct direction;
- public/event contracts are clear;
- authorization/server validation is correct;
- schema changes include migrations;
- config/docs ownership is updated in the correct place;
- no secret leak is introduced;
- required tests ran or limitations are reported honestly;
- no hard prohibition is broken.

Prefer simple, boring, predictable code over abstractions/infrastructure without a proven use case.
