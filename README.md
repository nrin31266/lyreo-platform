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
| `tools/` | Data import utilities and retained research manifests |
| `infra/` | Local/deploy infrastructure assets |
| `docs/` | Product intent, requirements, architecture, and development guidance |

Start task-specific reading at [docs/README.md](docs/README.md). [AGENTS.md](AGENTS.md) is the engineering contract.

## First clone

Use the versions in `.java-version`, `.nvmrc`, and `package.json#packageManager`, plus Docker Compose and `uv` for Python projects. `make doctor` reports local readiness. The Maven Wrapper is included.

```bash
make init-env
# Add the team's R2 endpoint and credentials to apps/core-service/.env and tools/data-import/.env.
make setup
make seed-lessons
make setup-data
```

New environment files select the shared R2 bucket. `make setup` checks the storage settings,
syncs dependencies, starts PostgreSQL and Keycloak, applies Flyway migrations, and creates demo
accounts. `make seed-lessons` adds published sample lessons with audio on R2. `make setup-data`
imports the dictionary, Grammar/TOEIC and Vocabulary catalogs. For isolated development without
R2 credentials, set `STORAGE_MODE=local` in both files and run `make setup` for basic dev data.

See the [Data Import guide](tools/data-import/README.md) for individual catalog imports.
Application source runs on the host. With `make dev-infra` running, start Core, AI and Admin in one
terminal with prefixed logs, or start each process in its own terminal:

```bash
make dev                      # core + ai + admin; APPS="core ai admin mobile" to choose
make core
make ai
make admin
make mobile
```

`make dev` keeps each service's reload behavior. Ctrl+C or closing the terminal stops all of them;
Docker infrastructure keeps running until `make down`. Run `make mobile` separately when you need
Expo's interactive keys.

Local login details: [Development accounts](docs/ACCOUNT.md).

`make core` rebuilds saved Java/resources and requests a DevTools restart, including changes in
business modules. It follows your editor's save behavior; it does not change autosave settings.
See [Core development](apps/core-service/README.md#local-run-and-verification) for setup and reload details.

`make mobile` starts Metro. Install the Expo development build first using the [Mobile guide](apps/mobile/README.md).
The sample lessons and their R2 audio are ready to use after setup. Run local AI models for tasks that need to generate new audio or transcripts locally.

## Common commands

| Command | Purpose |
|---|---|
| `make init-env`, `make doctor`, `make deps` | Initialize and inspect local dependencies |
| `make dev-infra` | Start local PostgreSQL and Keycloak after initial `make setup` |
| `make db-shell`, `make db-reset` | Inspect DB or preview lesson-only reset; [scope and confirmation](docs/DEVELOPMENT.md) |
| `make db-recreate`, `make down-v` | Guarded destructive DB/volume operations; see the same guide |
| `make migrate`, `make seed-dev` | Apply schema and add reference categories without overwriting edits |
| `make seed-lessons` | Add published sample lessons that use shared R2 audio |
| `make setup-data`, `make data-storage-check` | Import frozen catalogs or check media storage alignment |
| `make data-check`, `make data-fetch` | Verify or acquire the clean Grammar/TOEIC release |
| `make setup-media` | Install the Core YouTube downloader; FFmpeg/ffprobe required on the host |
| `make validate`, `make check` | Offline guards or full verification |
| `make validate-docs`, `make validate-repo`, `make test-tooling` | Target repository policy checks |
| `make test-java`, `make verify-java` | Java unit or integration verification |
| `make test-ai`, `make test-data-import` | Python project tests |
| `make typecheck`, `make test-frontend`, `make build-web` | Frontend verification |

The [development guide](docs/DEVELOPMENT.md) explains the local loop; [testing](docs/TESTING.md) explains evidence and test ownership.

## Local guides

[Core](apps/core-service/README.md) · [AI Service](apps/ai-service/README.md) · [Admin Web](apps/admin-web/README.md) · [Mobile](apps/mobile/README.md) · [Data Import](tools/data-import/README.md) · [Docker](infra/docker/README.md) · [Keycloak](infra/keycloak/README.md) · [Mobile UI](apps/mobile/README.md#ui-theme-ownership) · [i18n](packages/i18n/README.md)
