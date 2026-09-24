# Lyreo Testing Strategy

This document is the canonical reference for Lyreo's testing philosophy, structure, and tooling.
Completion requires fresh evidence for the affected area.
Run commands and local setup are owned by [`README.md`](../README.md). Test/validation loop
practices are in [`DEVELOPMENT.md`](DEVELOPMENT.md#verification-loop).

## 1. Testing philosophy

Tests are executable evidence of behavior, not product requirement authority. A passing test proves
the code does what the test says — it does not prove the product requirement is correct or complete.
Requirements are owned by `docs/requirements/`; tests verify them, not define them.

Prefer testing observable behavior over implementation detail. A test should break when the system
stops doing the right thing, not when internal structure is refactored. This means asserting on
outputs, state transitions, published events, and HTTP responses rather than on which internal
method was called or how many times.

## 2. Scaffold tests vs product tests

Lyreo is in its foundation/scaffold phase. Existing starter tests verify that architectural
patterns, module boundaries, database baselines, and infrastructure rails work correctly. They do
not represent product completeness and must not be cited as evidence that a feature is done.

As product features are built, tests transition from scaffold verification to behavioral coverage
of real business rules. The scaffold tests remain as guardrails but are not sufficient for claiming
a feature is complete.

## 3. Java testing

### Unit tests

- File suffix: `*Test.java`, colocated in `src/test/java` of the owning Maven module.
- Runner: Maven Surefire — `make test-java`.
- Characteristics: fast, no Spring context, no external dependencies.
- Tools: JUnit Jupiter, AssertJ, Mockito (for meaningful boundaries only).
- Use for: domain logic, application services with injected ports, value objects, policies.

### Module tests

- Test business logic within a single module that requires Spring bean wiring.
- Use `@ApplicationModuleTest` only when the test genuinely needs the module's Spring context.
- Still colocated in the owning module's `src/test/java`.
- Avoid starting more context than necessary; an `@ApplicationModuleTest` bootstraps only the
  target module and its dependencies, not the full application.

### Integration tests

- File suffix: `*IT.java`, colocated in `src/test/java` of the owning Maven module.
- Runner: Maven Failsafe — `make verify-java`.
- Use PostgreSQL Testcontainers (`@ServiceConnection`) for tests that depend on real SQL semantics,
  constraint behavior, or Flyway migration correctness.
- Docker must be running locally or in CI.

### Application-level tests

- Location: `apps/core-service/src/test/java`.
- Purpose: cross-module composition, Flyway baseline verification, HTTP wire contracts, Spring
  Modulith boundary verification, and global exception handling.
- These are the only tests that belong in the Core Service test root. Ordinary business module
  tests belong with their owning module.

### Architecture tests

- Architecture tests verify module dependency boundaries, package structure invariants, and
  Spring Modulith rules.
- These are structural guardrails, not behavioral tests. They prevent accidental coupling between
  modules.

## 4. Python testing

All Python subprojects use `pytest` through `uv`-managed environments. Do not install test
dependencies into system Python.

| Subproject | Test location | Make target |
|---|---|---|
| AI Service | `apps/ai-service/tests/` | `make test-ai` |
| Data Import | `tools/data-import/tests/` | `make test-data-import` |
| Lesson Prep | `tools/lesson-prep/tests/` | `make test-lesson-prep` |

Each subproject's `pyproject.toml` declares its test dependencies under a `dev` extra.

## 5. Admin Web testing

- Framework: Vitest + React Testing Library + jsdom.
- Run via: `pnpm --filter @lyreo/admin-web test` (or `make test-frontend` for all frontend tests).
- Component tests focus on user-observable behavior: what renders, what happens on interaction,
  what gets submitted. Do not assert on component internals or snapshot structure.

## 6. Mobile testing

- Framework: Jest + jest-expo + React Native Testing Library.
- Run via: `pnpm --filter @lyreo/mobile test` (or `make test-frontend`). The current command
  runs Jest component tests and retains the existing pure TypeScript foundation suite.
- Component tests for native components follow the same behavioral principle as Admin Web.
- Platform-specific behavior may require conditional test setup but should still test outcomes,
  not implementation.

## 7. Shared TypeScript packages

`packages/design-system` and `packages/i18n` are pure data/token packages. Type correctness is
verified by `pnpm typecheck`. These packages do not require runtime test suites unless they gain
logic beyond type exports.

## 8. Learner Web

The Learner Web application is not yet scaffolded. When created, it will inherit the Web testing
profile (Vitest + React Testing Library) and follow the same behavioral testing principles as
Admin Web.

## 9. Naming and location

Tests live with their owning module or application in the standard test directory for that
platform. Do not create a separate top-level `tests/` directory or centralize tests outside their
owning module.

- Java: `<module>/src/test/java`, mirroring the production package hierarchy.
- Python: `<subproject>/tests/`.
- TypeScript: colocated with source or in a `__tests__/` directory within the app/package.

## 10. Test doubles guidance

- Prefer fakes and stubs over mocks. A fake port implementation that behaves like the real thing
  is more resilient to refactoring than a mock that asserts on call sequences.
- Never mock domain models. Domain objects are the system under test or direct collaborators;
  replacing them with mocks removes the behavior being tested.
- Mock at meaningful architectural boundaries: ports, gateways, external service clients. Do not
  mock internal collaborators within the same module unless there is a compelling reason.
- For database-dependent behavior, prefer Testcontainers over mocking repositories.

## 11. Coverage philosophy

- Meaningful business logic needs tests that exercise its observable behavior and failure paths.
- Coverage is a diagnostic for missed paths, not a global numeric quota. Any future threshold needs
  an explicit decision.
- Do not test: records, simple DTO accessors, constants, simple enums, `package-info.java`,
  configuration holders, or builder boilerplate.
- JaCoCo is configured across all Maven modules for local feedback. Reports are generated at
  `<module>/target/site/jacoco/index.html`.
- Do not add trivial tests solely to raise a coverage metric.

## 12. CI mapping

Make targets are the stable interface between developer workflow and CI pipelines.

| CI job | Make targets / commands | What it covers |
|---|---|---|
| Java | `make verify-java` | Unit tests (Surefire), integration tests (Failsafe) |
| Python | `make test-ai`, `make test-data-import`, `make test-lesson-prep` | All Python subproject test suites |
| Frontend | `make typecheck`, `make test-frontend`, `make build-web` | Type checking, component tests, build verification |
| Repository policy | `make validate`, `make test-tooling` | Doc validation, syntax checks, repo structure rules |
| Full check | `make check` | All of the above in sequence |

`make validate` is the minimum offline guardrail. `make check` provides full verification and is
the standard pre-merge gate.

## 13. End-to-end testing

E2E testing is intentionally deferred. Do not set up Playwright, Detox, Maestro, or any mobile E2E
framework until a meaningful vertical slice — from mobile/web UI through Core Service to database
and back — exists and is stable enough to make E2E tests a net positive rather than a maintenance
burden.

When the time comes, E2E scope and tooling will be decided as an architecture decision and
documented in [`DECISIONS.md`](DECISIONS.md).
