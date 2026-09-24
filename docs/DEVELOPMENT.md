# Lyreo development workflow

Lyreo runs application source locally for debugging and hot reload. Docker development provides PostgreSQL and Keycloak. [Root setup](../README.md) owns first-clone and common Make commands; its [Local Guides](../README.md#local-guides) link platform-specific app and tool READMEs.

## First development loop

Run `make init-env`, `make doctor`, and the needed dependency/setup targets from the root README. Start `make dev-infra` before Core. Run `make core`, `make ai`, `make admin`, or `make mobile` in separate terminals as needed. Mobile needs an installed Expo development build; see [Mobile](../apps/mobile/README.md). Lesson Prep needs AI Service and media tools; see [Lesson Prep](../tools/lesson-prep/README.md). Optional data acquisition lives in [Data Import](../tools/data-import/README.md).

Mock AI and local filesystem storage are the normal development defaults. Live model runtimes and R2 are optional and require owner-specific configuration. Do not infer model quality or production behavior from mock responses.

## Database workflow

Core startup applies Flyway migrations and validates Hibernate mappings. Append a new versioned migration for every schema change; do not edit established migrations or let Hibernate mutate schema. Test PostgreSQL-specific behavior with Testcontainers. `make db-shell` inspects the local database; `make db-reset` deliberately resets only the local Lyreo application database while preserving Keycloak. Review the reset prompt and target before using it.

Large content datasets are imported through the [data-import tool](../tools/data-import/README.md), not Flyway seed blobs. Store raw datasets outside Git.

## Verification loop

Run targeted tests for the changed owner, then `make validate` for offline repository guards. Use `make validate-docs` for documentation changes, `make test-tooling` for validator changes, `make verify-java` for Java changes, the relevant Python test target for each Python project, and `make typecheck`, `make test-frontend`, and `make build-web` for frontend changes. `make check` aggregates full verification. [Testing strategy](TESTING.md) owns test types, naming, and evidence policy.

If Docker, external models, datasets, or a device is unavailable, report the exact check that could not run. Do not treat an unrun check as a pass.

## Troubleshooting routes

Use [Docker](../infra/docker/README.md) for local services and compose, [Keycloak](../infra/keycloak/README.md) for realm/bootstrap, [Core](../apps/core-service/README.md) for database startup, [AI Service](../apps/ai-service/README.md) for model runtime, and [Mobile](../apps/mobile/README.md) for emulator/device issues. The [jobs protocol](architecture/background-jobs.md) explains claim, recovery, and cancellation behavior.
