# Lyreo

Lyreo is an English-learning platform for Lessons, listening and speaking practice, vocabulary, grammar, TOEIC, and structured learning paths. **Project phase: foundation/scaffold.** Starter behavior and tests establish development rails; they do not certify product features.

Mobile is the primary learner client, Admin Web serves content managers/operators, and Learner Web is planned for later. Core is a Java/Spring Boot modular monolith with PostgreSQL/Flyway; FastAPI executes AI capabilities. Keycloak supplies OIDC identity, and object storage holds large artifacts.

## Repository map

| Path | Owner |
|---|---|
| `apps/` | Deployable Core, AI, Admin Web, and Mobile applications |
| `modules/` | Java business capabilities |
| `platform/`, `libs/` | Technical building blocks and shared Java contracts |
| `packages/` | Shared design tokens and translations |
| `tools/` | Data import and Lesson Prep utilities |
| `infra/` | Local/deploy infrastructure assets |
| `docs/` | Product intent, requirements, architecture, and development guidance |

Start task-specific reading at [docs/README.md](docs/README.md). [AGENTS.md](AGENTS.md) is the engineering contract.

## First clone

Use the versions in `.java-version`, `.nvmrc`, and `package.json#packageManager`, plus Docker Compose and `uv` for Python projects. `make doctor` reports local readiness. The Maven Wrapper is included.

```bash
make setup
```

Setup creates owner-specific local `.env` files, syncs dependencies, starts PostgreSQL/Keycloak, and seeds the development realm. The versioned Grammar/TOEIC clean release is optional: run `make data-fetch` when needed, or use `WITH_DATA=1 make setup` to fetch it during setup after dependencies are ready. Application source runs on the host; start each process in its own terminal:

```bash
make core
make ai
make admin
make mobile
```

`make mobile` starts Metro; install the Expo development build first using the [Mobile guide](apps/mobile/README.md). Mock AI and local storage are the normal development defaults.

## Common commands

| Command | Purpose |
|---|---|
| `make init-env`, `make doctor`, `make deps` | Initialize and inspect local dependencies |
| `make dev-infra`, `make keycloak-seed` | Run local PostgreSQL/Keycloak and seed the realm |
| `make db-shell`, `make db-reset` | Inspect or deliberately reset the local app database |
| `make data-check`, `make data-fetch` | Verify or acquire the optional clean Grammar/TOEIC release |
| `make lesson-prep` | Run the local Lesson source workstation |
| `make validate`, `make check` | Offline guards or full verification |
| `make validate-docs`, `make validate-repo`, `make test-tooling` | Target repository policy checks |
| `make test-java`, `make verify-java` | Java unit or integration verification |
| `make test-ai`, `make test-data-import`, `make test-lesson-prep` | Python project tests |
| `make typecheck`, `make test-frontend`, `make build-web` | Frontend verification |

The [development guide](docs/DEVELOPMENT.md) explains the local loop; [testing](docs/TESTING.md) explains evidence and test ownership.

## Local guides

[Core](apps/core-service/README.md) · [AI Service](apps/ai-service/README.md) · [Admin Web](apps/admin-web/README.md) · [Mobile](apps/mobile/README.md) · [Data Import](tools/data-import/README.md) · [Lesson Prep](tools/lesson-prep/README.md) · [Docker](infra/docker/README.md) · [Keycloak](infra/keycloak/README.md) · [Design System](packages/design-system/README.md) · [i18n](packages/i18n/README.md)
