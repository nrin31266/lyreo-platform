# Lyreo

**Lyreo** is an English-learning platform focused on Listening, Dictation, Shadowing,
Vocabulary, Grammar, TOEIC, and structured Curriculum.

- Product: **Lyreo**
- Mascot: **Lyrebird**
- Java namespace: `com.lyreo`
- Repository: `lyreo-platform`

This README is the **operational entry point for new developers**: what the repository contains,
which tools are required, how to initialize the local environment, and how to run each part.

> Mandatory engineering rules live only in `AGENTS.md`.  
> Start task-oriented reading from `docs/README.md`; the old master path is compatibility-only.

---

## 1. System at a glance

Lyreo currently uses this development topology:

```text
Admin Web ─┐
           ├──→ Core Service ───→ PostgreSQL
Mobile ────┘         │
                     ├──→ Object Storage
                     └──→ AI Service

Admin/Mobile ─── OIDC/PKCE ───→ Keycloak
```

Core is a Spring Boot modular monolith. The AI runtime is a separate FastAPI/Python service.

For architecture boundaries and technology rationale, read:

- `AGENTS.md`
- `docs/README.md`
- `docs/product/prd.md`
- `docs/ARCHITECTURE.md`
- `docs/TECH_CHOICES.md`

---

## 2. Read this first

1. `README.md` — setup, run commands, and repository map.
2. `AGENTS.md` — mandatory engineering contract and selective-reading workflow.
3. `docs/README.md` — the single task/domain/code routing table.
4. Open only the owner docs, code and tests selected by that route.

`docs/LYREO_PLATFORM_SPEC.md` preserves old paths/anchors; it is no longer a normative master.

`CLAUDE.md`, `GEMINI.md`, and `AGENT.md` are symlinks to `AGENTS.md`; maintain only one
engineering contract.

`TESTING_NOTES.md` is a temporary handoff note from the starter artifact, not canonical
documentation. After the team completes the first full verification pass, move remaining issues
into the issue tracker/CI and remove this file if it is no longer useful.

---

## 3. Repository map

```text
lyreo-platform/
├── apps/
│   ├── core-service/          # Spring Boot deployable
│   ├── admin-web/             # React + Vite admin
│   └── mobile/                # Expo/React Native learner app
│
├── modules/                   # business modules
│   ├── identity/
│   ├── learner/
│   ├── ai/
│   ├── lesson/
│   ├── speech-assessment/
│   ├── lexicon/
│   ├── vocabulary/
│   ├── grammar/
│   ├── toeic/
│   ├── curriculum/
│   ├── gamification/
│   ├── analytics/
│   ├── notification/
│   └── chat/
│
├── platform/                  # technical building blocks
│   ├── cache/
│   ├── config/
│   ├── jobs/
│   ├── storage/
│   ├── security/
│   └── observability/
│
├── services/
│   └── ai-service/            # FastAPI/Python capability runtime
│
├── tools/
│   └── data-import/           # Lexicon/Grammar/TOEIC importers
│
├── packages/
│   ├── design-system/         # shared primitive/semantic design tokens
│   └── i18n/                  # shared common/admin/mobile translation resources
│
├── infra/
│   ├── docker/
│   ├── keycloak/
│   ├── nginx/
│   └── postgres/
│
├── docs/
├── tooling/
├── compose.dev.yml
├── compose.prod.yml
├── compose.gpu.yml
├── Makefile
└── AGENTS.md
```

---

## 4. Toolchain

The development host needs Java, the repository Maven Wrapper (`./mvnw`), Node/pnpm, `uv`,
Docker + Docker Compose, and Android Studio/Xcode when working on native mobile builds.

Use the versions declared by repository toolchain files (`.java-version`, `.nvmrc`,
`package.json#packageManager`, and `mise.toml`). The dated baseline and version rationale live in
`docs/TECH_CHOICES.md`.

Quick host check:

```bash
./scripts/doctor.sh
```

### Python environments

`doctor.sh` reports the Python interpreter available on the current host shell. That is only a
host/tooling sanity check; it does **not** define the interpreter used by Lyreo Python subprojects.

The authoritative host-vs-project Python policy, supported project baseline, and agent rules live
in [`AGENTS.md`](AGENTS.md#9-configuration-and-python-environments).

For normal project work, use `uv` from inside the owning Python subproject:

```bash
uv sync
uv run ...
```

You do not need to activate `.venv` manually when using `uv`.

---

## 5. Initialize the local environment

Lyreo does not use one root `.env`.

Each executable/tool owns its own environment file:

```text
infra/docker/.env
infra/keycloak/.env
apps/core-service/.env
services/ai-service/.env
apps/admin-web/.env
apps/mobile/.env
tools/data-import/.env
```

Create local files:

```bash
make init-env
```

or:

```bash
./scripts/init-dev-env.sh
```

The script copies from `.env.example` when a file does not already exist and generates/synchronizes
the local development values that need to be shared between services.

Do not commit real `.env` files.

See:

```text
docs/CONFIGURATION.md
```

### First-clone setup

For a new clone, the convenience target first runs the non-strict environment doctor, then
initializes env files, installs the shared Grammar/TOEIC dataset when it is missing, syncs
Python/frontend dependencies, starts PostgreSQL + Keycloak, and bootstraps Keycloak:

```bash
make setup
```

The shared TOEIC archive is large. To prepare the codebase without downloading it yet:

```bash
SKIP_DATA=1 make setup
```

After setup, run `make core`, `make ai`, `make admin`, and `make mobile` in separate terminals.
Dataset bootstrap behavior and source provenance are owned by `docs/DATA_PIPELINES.md`.

---

## 6. Start development infrastructure

By default, PostgreSQL and Keycloak run in Docker while application source runs on the host.

```bash
make dev-infra
```

Equivalent command:

```bash
docker compose   --env-file infra/docker/.env   -f compose.dev.yml   up -d
```

Inspect containers:

```bash
docker compose   --env-file infra/docker/.env   -f compose.dev.yml   ps
```

---

## 7. Bootstrap Keycloak

After Keycloak is healthy:

```bash
make keycloak-seed
```

The bootstrap scripts create/update the realm, clients, roles, and development users from local
configuration.

If bootstrap fails, check:

```text
infra/keycloak/README.md
docs/DEVELOPMENT.md
```

---

## 8. Run Core Service

Open a dedicated terminal:

```bash
make core
```

Default address:

```text
http://localhost:8080
```

Core startup runs Flyway before the application becomes ready, and Hibernate checks mappings
according to the project configuration.

Migrations live at:

```text
apps/core-service/src/main/resources/db/migration/
```

If startup fails because of database/migration/schema problems, inspect the Core logs and read:

```text
docs/DEVELOPMENT.md
docs/OPERATIONS.md
```

Schema ownership and migration rules live in `AGENTS.md`; README does not duplicate them.

---

## 9. Run AI Service

First run, or after Python dependencies change:

```bash
cd services/ai-service
uv sync --extra dev
```

Run directly:

```bash
uv run uvicorn app.main:app --reload
```

Or from the repository root:

```bash
make ai
```

The development configuration defaults to mock AI runtime, so the project can boot without a GPU
or downloading local Qwen models.

Run AI tests:

```bash
cd services/ai-service
uv run pytest
```

Core/FastAPI responsibility boundaries live in `AGENTS.md` and `docs/ARCHITECTURE.md`.

---

## 10. Run Admin Web

First install:

```bash
pnpm install
```

Run:

```bash
make admin
```

Default Vite development address:

```text
http://localhost:5173
```

If OIDC/API URLs differ from local defaults, update `apps/admin-web/.env` using
`apps/admin-web/.env.example` as the reference.

---

## 11. Run Mobile

After:

```bash
pnpm install
```

run:

```bash
make mobile
```

This starts the Expo Metro bundler. **Expo Go is not supported.** You must have the Lyreo Dev Build
installed on your device or emulator before Metro is useful. `make mobile` never triggers a build.

For first-time Android setup (Linux CLI emulator without Android Studio) or iOS setup:

```text
apps/mobile/README.md
```

For cross-project mobile workflow context, see:

```text
docs/DEVELOPMENT.md#3-start-executable-apps
```

Public runtime variables live in:

```text
apps/mobile/.env
```

---

## 12. Verify the local setup

After the components are running, the default development addresses are:

```text
Core Service    http://localhost:8080
AI Service      http://localhost:8000
Admin Web       http://localhost:5173
Keycloak        see compose.dev.yml / infra env
```

Use the health endpoints currently configured in code/config. If an endpoint or port changes,
update `README.md`, the owning `.env.example`, and `docs/DEVELOPMENT.md` in the same change.

Development account/bootstrap values come from `infra/keycloak/.env`; do not place real passwords
or tokens in README.

---

## 13. Data import

Large datasets remain outside Git. The importer env points to the local dataset root.

Check whether the shared Grammar/TOEIC dataset is already installed:

```bash
make data-check
```

Download and safely extract the configured shared archive when it is missing:

```bash
make data-fetch
```

Prepare importer Python dependencies and load the importer env:

```bash
cd tools/data-import
uv sync --extra dev
set -a; source .env; set +a
```

Run using the project environment:

```bash
uv run python import_grammar.py --data-dir "$DAUTOEIC_DATA_DIR"
uv run python import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR"
```

Lexicon import is separate and becomes runnable after the intended Kaikki/Wiktextract JSONL sources
have been obtained. Current source availability, exact expected files, Drive bootstrap behavior, and
import semantics are owned by:

```text
tools/data-import/README.md
docs/DATA_PIPELINES.md
```

---

## 14. Docker full topology

README owns the local development setup commands above. Production/self-host deployment commands,
GPU overrides, backups, storage operations, and recovery procedures are owned by:

```text
docs/OPERATIONS.md
infra/docker/README.md
```

---

## 15. Validation

Minimum repository guardrail:

```bash
make validate
```

Main checks can also be run separately:

```bash
make validate-docs
make test-java
make test-ai
pnpm typecheck
pnpm build
```

Python AI tests:

```bash
cd services/ai-service
uv run pytest
```

Importer tests:

```bash
cd tools/data-import
uv run pytest
```

`AGENTS.md` defines which checks are mandatory for each type of change.

The workspace lockfile must describe every pnpm importer (`apps/admin-web`, `apps/mobile`,
`packages/design-system`, and `packages/i18n`). If `pnpm-lock.yaml` is absent after a fresh source
artifact, run `pnpm install` on a networked machine, review the generated lockfile, and commit it
before enabling frozen-lockfile CI.

---

## 16. Starter implementation status

This repository is a foundation with real implementation patterns. It does not claim every product
feature is production-complete.

Areas with starter implementation/patterns include:

```text
identity
jobs
lesson
ai
speech-assessment
lexicon
vocabulary
grammar
toeic
curriculum
gamification
analytics
notification
admin-web
mobile
data-import
```

For intended priorities and current evidence status, read:

```text
docs/product/prd.md
docs/requirements/traceability.md
```

---

## 17. Architecture rules

All mandatory architecture boundaries, module ownership rules, dependency rules, security
authority, and hard prohibitions live in:

```text
AGENTS.md
```

**`AGENTS.md` is the single source of truth for engineering rules. README does not maintain a
second copy of those rules.**
