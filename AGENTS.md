# AGENTS.md — Lyreo Engineering Contract

This is the single source of truth for mandatory Lyreo engineering rules. Setup and run commands
belong in [README.md](README.md); task-oriented documentation routes live in
[docs/README.md](docs/README.md). If a task conflicts with this contract, surface the conflict and
request an explicit architecture decision instead of silently changing the foundation.

## 1. Selective reading workflow

For normal work:

1. Read this contract and `docs/README.md`. Identify the task intent, affected code paths, and
   owning domain.
2. Select the matching route. Read only the relevant section of the owner document; use `rg` on
   IDs, headings, and symbols before opening larger ranges.
3. Open the code entrypoint, tests, and contracts named by that route. Expand to another module
   only when an event, API, schema, config, or UI consumer is affected.
4. Read Discovery/PRD only for scope, value, or priority changes. Read coursework only when writing
   the report. Load only the skill relevant to the current task.
5. If a route is missing, find the owner from code/docs, add the route, then continue. If sources
   conflict, follow the docs/code divergence workflow below; do not choose the newest or longest
   file by default.
6. After context compaction or a task change, restore the selected owners/IDs and unresolved issues;
   do not rescan all documentation without a reason.

The one-time documentation migration may inventory all project docs. That is not a rule for future
tasks. A vague request to change “progress” must first be resolved to Lesson completion,
Curriculum progress, Analytics projection, Gamification, or UI state.

## 2. Product and architecture boundary

Lyreo is an English-learning platform covering Text/Audio/YouTube Lessons, Dictation, Shadowing,
annotations, Lexicon, Vocabulary SRS, Grammar, TOEIC, Curriculum, Analytics, Level/Diamond/Mission,
notifications, and low-priority Chat. The Lyrebird mascot is presentation/brand only; do not name
business or domain classes after it.

The fixed baseline is:

- Java + Spring Boot modular monolith with Spring Modulith;
- pragmatic Clean/Hexagonal Architecture inside business modules;
- PostgreSQL + Flyway, PostgreSQL-backed jobs, Caffeine, Bucket4j, and Resilience4j;
- FastAPI as an AI capability service;
- Cloudflare R2 behind an S3-compatible storage port, with local filesystem for development;
- Keycloak/OIDC, React/Vite Admin, and Expo/React Native Mobile.

Exact versions and rationale belong in `docs/TECH_CHOICES.md`. Do not add infrastructure merely to
make the system look more enterprise; require measured or operational need.

Project-local skills are procedural guidance. Precedence is: this contract, canonical Lyreo docs,
existing architecture/technology decisions, then third-party skill guidance. Skills must adapt to
Lyreo rather than migrate the project to their preferred stack.

## 3. Dependency and module communication rules

```text
api / adapters-in -> application -> domain
infrastructure -> implements inward-facing ports
```

Domain/application must not depend on JPA/JDBC adapters, PostgreSQL, R2/S3 SDKs, HTTP/FastAPI,
Keycloak SDKs, or frontend frameworks. Persistence annotations do not belong in domain/application.

Cross-module interaction is allowed only through named/public module APIs, intentionally shared
public contracts, or Spring Modulith events. Never import another module's repository, JPA entity,
or internal infrastructure, and never query another module's tables as a shortcut. Architecture
tests and validators must protect these boundaries.

## 4. Domain ownership

- `identity`: Keycloak subject to app-user mapping and JIT provisioning.
- `learner`: onboarding, profile, and persistent learner preferences.
- `ai`: provider/model routing, encrypted credentials, and invocation audit.
- `lesson`: content, annotations, activities, build workflow, and lesson practice.
- `speech-assessment`: recordings, ASR, timing, fluency, and deep-judge results.
- `lexicon`: global dictionary; `vocabulary`: learner SRS referencing Lexicon.
- `grammar`: taxonomy, questions, and practice; `toeic`: tests, attempts, and scoring.
- `curriculum`: paths, enrollment, item progress, and content references.
- `gamification`: level/XP, Diamond ledger, missions, and rewards.
- `analytics`: projections/read models, not detailed domain progress.
- `notification`: realtime/push boundary; `chat`: low-priority tutor boundary.

`platform/*` is technical and must not depend on business modules. Do not create a God `progress`
module. Detailed ownership is in `docs/ARCHITECTURE.md`.

Lesson source selection, selected activities, enrichment, and pronunciation strategy are
independent dimensions. Vocabulary Practice and Grammar Practice are real Lesson activities;
contextual vocabulary/grammar notes attached to another activity are not those practice loops.
A lesson must not be assumed to require generated text.

## 5. Hard prohibitions

Without an approved architecture decision, do not:

- introduce Kafka or Redis, or copy the legacy Kafka/Redis lesson pipeline;
- introduce Expo API Routes, NativeWind v5, or a Next.js server architecture;
- move Lesson/Curriculum/Gamification workflow or product prompts into FastAPI/Python;
- use Hibernate `ddl-auto=update/create/create-drop`;
- use R2 raw JSON as workflow truth or store signed/presigned URLs in the database;
- store provider keys in plaintext or expose server secrets in `VITE_*`/`EXPO_PUBLIC_*`;
- trust client-provided score, XP, or Diamond amounts;
- access another module's repository/entity/infrastructure or create a God `progress` module;
- merge Lexicon with Vocabulary or confuse contextual notes with dedicated practice;
- hard-code provider model names in Java enums;
- commit raw TOEIC/Kaikki datasets, secrets, or generated build artifacts;
- hard-code feature UI brand/theme colors instead of semantic design tokens;
- duplicate shared translation text outside `@lyreo/i18n`;
- force Web and Mobile to share components merely because tokens are shared;
- store ordinary locale/theme preferences in SecureStore;
- hide failed/unrun required checks or call a feature done without required evidence.

## 6. Background jobs

PostgreSQL is authoritative workflow state. Long-running handlers must claim through the
`platform/jobs` lease protocol, check durable cancellation before and between expensive steps,
heartbeat, persist idempotent step state, respect lease ownership/fencing, and prevent stale workers
from overwriting recovered work. UI progress events are never durability. Raw AI responses are
debug/audit artifacts, not checkpoints.

If external inference cannot be hard-cancelled, check cancellation before committing and discard
results that are no longer allowed. Protocol details: `docs/architecture/background-jobs.md`.

## 7. AI boundary

Java/Core owns business intent and prompts, expected schemas, orchestration, routing, retry/fallback,
persistence, and state transitions. FastAPI executes STT, alignment, TTS, NLP, generic LLM
generation, and multimodal judging. Do not create business endpoints such as `/generate-lesson` in
FastAPI. Provider credentials remain server-side. See `docs/architecture/ai-execution.md`.

## 8. Database, storage, and data

Flyway owns schema evolution; every schema change needs a new migration, and an applied shared
migration must not be edited. Hibernate validates mappings/schema and must not mutate schema.
Large Lexicon/TOEIC/Grammar/Curriculum content belongs in importer tooling, not Flyway.

Store durable/queryable normalized state in PostgreSQL and large immutable/debug artifacts in
object storage. Persist object keys, not expiring URLs. Sensitive learner recordings are private by
default. Importer semantics belong in `docs/DATA_PIPELINES.md`.

## 9. Configuration and Python environments

Config precedence and persistence are owned by `docs/CONFIGURATION.md`. Business modules consume
typed policies/ports; do not scatter untyped maps or turn invariants into toggles. New environment
variables go in the owning executable's `.env.example`; update configuration docs when semantics or
precedence changes. Never create a root `.env` containing all service secrets.

`uv` is standard for Python subprojects. The host Python is not the project Python. Do not require a
system Python change, install dependencies into system Python, infer compatibility from root
`python --version`, or depend on an activated `.venv`. Prefer `uv sync` and `uv run`; supported
constraints live in the owning `pyproject.toml`.

## 10. Development and frontend foundation

Application source runs locally; Docker development runs PostgreSQL and Keycloak. Local filesystem
storage and mock AI are defaults; R2 development is optional. Do not add Kafka, Redis, MinIO, queue
brokers, or observability stacks to development compose without an explained operational need.

`packages/design-system` owns primitive/semantic light/dark contracts and
`packages/i18n` owns intentionally shared EN/VI resources. Admin Web and Mobile own their
platform-specific adapters and component implementations. Authentication tokens may use secure
storage; ordinary preferences use appropriate non-secret platform storage.

## 11. Security authority

- Keycloak roles are `ADMIN` and `LEARNER`; Mobile/Admin use Authorization Code + PKCE.
- Provider API keys are AES-GCM encrypted using a master key from environment configuration.
- Clients have no authority over scoring or rewards; authorization is enforced server-side.
- Learners may access only authorized resources; logs must not expose credentials or tokens.
- Dev-only endpoints must be disabled outside development.

## 12. Docs/code divergence and change workflow

Code describes current implementation; owner docs describe applicable intent/contract. Neither
always wins. Timestamp, file length, a passing test, or skill advice does not grant authority.

- Mechanical factual drift (link, path, symbol, typo): update owner and references without asking.
- Code differs from a decided requirement: for coding work, fix code/tests; for docs-only work,
  preserve the requirement and record evidence in `docs/requirements/gaps.md`.
- A clear user instruction changes behavior: update owner requirement/AC/spec, code, tests, and
  traceability in the same change set; do not ask again about what was already decided.
- Conflicting requirements without authority: record both sources, impact, and pending decision;
  complete independent work and ask only if the current action requires choosing.
- Changes to public API, authorization, scoring/rewards, data, scope, or architecture require
  explicit authority when not already granted.
- Internal refactors with unchanged behavior/contracts need no ceremonial doc edit; state why.
- Event/API/schema changes require checking consumers, ownership, migrations, and compatibility.

Workflow: **owner/ID → compare intent with code → classify → decide if needed → update owner + AC →
implementation/tests when in scope → traceability/evidence → link/route checks → handoff**.

Requirement IDs are never reused for a new meaning. Retired requirements are marked superseded.
Architecture decisions retain D-001–D-019 and meaningful new decisions go in `docs/DECISIONS.md`.
Documentation conventions are owned by `docs/documentation.md`.

## 13. Documentation and comments

Comments explain why, invariants, trade-offs, failure modes, or non-obvious protocols—not syntax.
Each normative fact has one owner; other docs may orient and link but must not copy whole rule,
acceptance, endpoint/schema, configuration, or roadmap tables.

- setup/run → `README.md`, `docs/DEVELOPMENT.md`;
- task routing → `docs/README.md`; doc conventions → `docs/documentation.md`;
- product scope/value → `docs/product/prd.md`; requirements/AC → `docs/requirements/`;
- workflow behavior → `docs/features/`; architecture/decisions → `docs/ARCHITECTURE.md`,
  `docs/architecture/`, `docs/DECISIONS.md`;
- configuration/data/operations → their existing canonical docs;
- evidence status → `docs/requirements/traceability.md`; unresolved items → `gaps.md`;
- `docs/LYREO_PLATFORM_SPEC.md` is compatibility navigation, not a competing owner;
- `TESTING_NOTES.md` is a dated historical handoff, never current authority.

## 14. Testing and completion

Run checks applicable to the changed area: Java and Modulith tests, Flyway startup/validation,
Python tests/compile, importer tests/dry-run, TypeScript typecheck/build, shell/config validation, and
secret/build-output sanity checks as appropriate. `make validate` is only the minimum offline
guardrail.

Before claiming completion, use fresh evidence. If a required check cannot run, name it, explain
why, and provide the exact command for developer/CI. An acceptable change preserves ownership and
dependency direction, public/event contracts, authorization/server validation, migration/config
ownership, secrets, and required evidence. Prefer simple, boring, predictable code.
