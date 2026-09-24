# Lyreo architecture

Lyreo is an English-learning system with Mobile as the primary learner client, Admin Web for content managers and operators, and a planned, lower-priority Learner Web. Keycloak provides identity. Core owns business behavior and durable data; a separate FastAPI process executes AI capabilities.

## System and containers

```text
Mobile / Admin Web / planned Learner Web
                 | OIDC + PKCE
              Keycloak
                 |
                 v
       Core Service (Spring Boot)
          |       |          |
     PostgreSQL  R2      AI Service (FastAPI)
     state/jobs  artifacts  technical inference
```

Core is one deployable Java modular monolith. PostgreSQL stores normalized product and workflow state. Flyway owns its schema. R2 stores large artifacts behind a storage port; local development uses filesystem storage. The AI Service has no business database or workflow ownership.

## Building blocks and dependency direction

`apps/*` are deployable/composition roots. `modules/*` own business capabilities; `platform/*` owns reusable technical infrastructure; `libs/*` holds intentionally shared Java contracts. Core assembles modules and does not own their domain behavior.

Business capabilities include identity and learner; lesson and speech assessment; lexicon and vocabulary; grammar and TOEIC; curriculum and gamification; analytics, notification, AI administration, and low-priority chat. Detailed behavior belongs in [requirements](README.md), not this building-block view. Each producer owns detailed progress and attempts; analytics projects summaries from published facts.

```text
api / inbound adapter -> application -> domain
infrastructure       -> application ports / domain
```

Domain and application code do not depend on HTTP, persistence adapters, object storage SDKs, model runtimes, or client frameworks. Cross-module collaboration uses named/public Spring Modulith interfaces, shared contracts, or events. Modules do not import each other's repositories, entities, or internal adapters. Platform code does not depend on business modules.

Mobile and Admin Web implement their own components and platform adapters. [`design-system`](../packages/design-system/README.md) owns semantic tokens; [`i18n`](../packages/i18n/README.md) owns shared EN/VI resources. Shared contracts do not require shared Web/Native components.

## Important runtime scenarios

An asynchronous Lesson build accepts an authorized request, persists a job and product state, then workers claim durable PostgreSQL steps. Workers check cancellation, renew leases, and fence writes before committing. [Lesson Build](features/lesson-build.md) owns the workflow; the [jobs protocol](architecture/background-jobs.md) owns claim, recovery, and cancellation semantics.

When business work needs inference, Core selects capability, route, prompt, and expected output. FastAPI executes technical STT, alignment, TTS, NLP, generic LLM, or judging and returns a normalized result. Core audits the invocation and commits business state. The [AI execution contract](architecture/ai-execution.md) owns that boundary.

Public clients authenticate with Keycloak and call Core. Core enforces resource authorization, scoring, and rewards. The [HTTP contract](architecture/http-api-contract.md) owns versioning, successful responses, Problem Details, and correlation IDs.

## Cross-cutting constraints and risks

PostgreSQL is workflow truth; UI events and raw AI artifacts are not checkpoints. Store object keys rather than expiring URLs. Configuration precedence belongs in [configuration](CONFIGURATION.md), import semantics in [data pipelines](DATA_PIPELINES.md), and architecture rationale in [decisions](DECISIONS.md). Unresolved product or deployment choices are tracked in [open questions](requirements/open-questions.md).

<a id="backend-module-package-structure"></a>
## Backend module package structure

Production business modules use `api`, `application`, `domain`, and `infrastructure` as stable top-level concerns where needed. They are roles, not mandatory empty folders. Keep packages flat while cohesive; split deeper by real use case or adapter responsibility. Outbound abstractions belong in `application/port`; JDBC/JPA implementations belong in `infrastructure/persistence`. `api` is inbound transport, not Java cross-module visibility. Named interfaces and shared events define cross-module exposure. Avoid generic `common`, `utils`, `helpers`, `impl`, or `misc` buckets.
