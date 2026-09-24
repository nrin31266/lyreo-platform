# AGENTS.md — Lyreo engineering contract

Lyreo is in the foundation/scaffold phase. Starter classes, endpoints, UI, fixtures, and tests prove development rails; they do not define product behavior or prove feature completeness. Replace scaffold behavior when implementing an accepted requirement.

## Find the owner

Read [docs/README.md](docs/README.md) and follow the route for the task. Product scope lives in the PRD; feature behavior and acceptance criteria live in requirements; architecture boundaries and decisions live in architecture docs and the decision log. Code describes current behavior, Flyway describes current schema, manifests describe current versions, `.env.example` files describe environment keys, and fresh test runs provide verification evidence. Read only the relevant owners and code paths.

If code differs from an accepted requirement, fix code and tests. If a deliberate request changes a contract, update its owner, code, and tests together. Correct factual drift in secondary docs. Record genuinely unresolved product or architecture choices in `docs/requirements/open-questions.md`; do not turn implementation backlog into product questions. Follow [documentation governance](docs/DOCUMENTATION.md) for details.

## Architecture and authority

Core is a Java/Spring Boot modular monolith. Business modules use `api`, `application`, `domain`, and `infrastructure` concerns with dependencies pointing inward. Cross-module access uses public/named APIs, shared contracts, or Spring Modulith events, never another module's repository or internal adapter. `platform/*` is technical and cannot depend on business modules. [Architecture](docs/ARCHITECTURE.md) owns boundaries and package guidance.

Core Java owns business intent, prompts, orchestration, durable state, routing, and transitions. FastAPI executes AI capabilities. PostgreSQL is authoritative workflow state; Flyway owns schema changes. Append migrations and keep Hibernate in validation mode. Use the [AI](docs/architecture/ai-execution.md), [jobs](docs/architecture/background-jobs.md), and [HTTP](docs/architecture/http-api-contract.md) contracts when affected. Do not introduce Kafka or Redis without a new architecture decision.

The server enforces authorization and owns scores, XP, and rewards. Keep provider credentials encrypted and server-side; never put secrets in `VITE_*` or `EXPO_PUBLIC_*`. Do not expose internal failures or learner-private artifacts. UI uses shared semantic design tokens and shared translations where applicable.

## Change and verification

Keep each fact with its owner. Internal refactors with unchanged contracts need no ceremonial docs edit. Check consumers when changing an API, event, schema, or configuration. Use the applicable tests and validators; `make validate` is the offline minimum and `make check` is the full verification interface. Report any required check that could not run with its exact blocker. Never call a scaffold feature complete based only on starter tests.
