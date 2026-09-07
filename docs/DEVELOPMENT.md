# Lyreo Development Guide

The development topology is intentionally optimized for **containerized dependencies with local
application source** so Java/Python/TypeScript debugging and hot reload stay fast.

## 1. Toolchain

Use the repository-declared toolchain and run `./scripts/doctor.sh` before first setup or when a
machine changes. Exact version baselines and upgrade rationale are owned by
`TECH_CHOICES.md`; local setup commands are owned by `../README.md`.

The repository includes `.java-version`, `.nvmrc`, and `mise.toml` to help the team align tools.
Python subprojects use `uv`-managed project environments rather than relying on the host's default
Python interpreter.

## 2. First run

The first-run workflow is:

1. initialize service-specific environment files;
2. fetch/validate the shared Grammar/TOEIC dataset unless intentionally skipped;
3. sync Python/frontend dependencies;
4. start PostgreSQL + Keycloak development infrastructure;
5. bootstrap the Lyreo realm/clients/roles/dev users;
6. start application processes locally.

Use the exact commands in `../README.md` §5–11. Dev users/passwords are sourced from
`infra/keycloak/.env` and must not be copied into documentation.

## 3. Start executable apps

Run each executable as a separate local process so it can be restarted/debugged independently.
The exact commands, default addresses, and initial dependency-installation steps are owned by
`../README.md` §8–12.

### Core Service

Core can boot before AI Service, but AI-backed lesson work will fail/circuit-break until AI Service
is available.

### AI Service

Use the AI subproject environment managed by `uv`; runtime-mode behavior is described in §5 below.

### Admin Web

Run the Vite dev process separately from Core so frontend reload/debug does not restart the backend.

### Mobile

Mobile uses an Expo Development Build/Prebuild workflow when native capabilities are required.
After adding a native module or changing the Expo SDK, rebuild the Development Build.

## 4. Recommended start order

```text
1. PostgreSQL + Keycloak
2. Keycloak bootstrap
3. AI Service
4. Core Service
5. Admin Web
6. Mobile
```

This order minimizes expected dependency errors while preserving the ability to run/debug each
process independently.

## 5. AI runtime modes

### `mock`

Use for normal UI/backend development and tests. It returns deterministic contract-compatible
responses without GPU/model downloads or provider billing.

### `local`

FastAPI loads the Qwen runtime for local STT/alignment capability execution. Local mode requires the
Qwen optional dependencies and a machine/runtime appropriate for the selected model/device.

Docker GPU is a packaging/deployment option; a developer with a suitable GPU may run the Python
runtime directly on the host.

External SaaS providers such as Groq/Gemini/DeepSeek are selected through Core capability routing.
They are independent from `AI_RUNTIME_MODE` and do not constitute a third runtime mode.

## 6. Storage during development

Use the local filesystem adapter for ordinary development and switch to R2 only when testing R2
integration behavior. Exact variables, defaults, and security rules are owned by
`CONFIGURATION.md` §7.

Use a dedicated development bucket when testing R2. Never point a developer environment at the
production bucket.

## 7. Database workflow

### Schema changes

For a schema change:

1. create a new Flyway migration;
2. run Core/tests so Flyway applies and validates it;
3. verify Hibernate mapping validation;
4. review compatibility/rollback implications for destructive changes.

Mandatory persistence rules are owned by `../AGENTS.md` §10. Large Lexicon/Grammar/TOEIC content
uses importer tooling rather than Flyway seed blobs.

## 8. Data importer development

Importer execution commands are owned by `../README.md` §13. Input semantics, current dataset
shape, dry-run/apply behavior, checksums, and media handling are owned by `DATA_PIPELINES.md`.

Development practice:

- run dry-run/validation before any applied import;
- inspect record counts and integrity output;
- use `--apply` only after the source shape has been reviewed;
- treat R2 media upload as a separate opt-in concern where supported.

## 9. Test/validation loop

Run the smallest relevant checks continuously while developing, then run the required completion
checks for the changed area. Exact repository commands are owned by `../README.md` §15; completion
requirements are owned by `../AGENTS.md` §15.

`TESTING_NOTES.md`, if still present, is only a temporary starter handoff. It is not an architecture
or testing-policy source of truth.

## 10. Common troubleshooting

### Maven cannot download dependencies

Check DNS/network access to Maven Central. The Maven Wrapper and dependencies may need network
access on the first run.

### Core reports schema validation failure

Do not bypass the failure by enabling Hibernate schema mutation. Verify that the required Flyway
migration exists, ran in order, and matches the current mappings. See `../AGENTS.md` §10.

### Keycloak development user cannot log in

Rerun the Keycloak bootstrap/seed workflow described in `../README.md` §7, then verify values in
`infra/keycloak/.env` and the relevant client redirect URI.

### Android emulator cannot reach localhost

The Android emulator uses host alias `10.0.2.2`; the mobile `.env.example` uses this form for local
Core/Keycloak access. A physical device needs a reachable LAN IP or an appropriate development
tunnel.

### Shared TOEIC dataset is missing or Drive download fails

Run `make data-check` first. `make data-fetch` uses the URL/checksum in `tools/data-import/.env`; for
Google Drive it runs `gdown` ephemerally through `uvx`. Check Drive sharing permissions, available
disk space, and the configured archive checksum. The app itself can still be developed without the
dataset by using `SKIP_DATA=1 make setup`; importer/data work cannot.

### NativeWind classes do not update after configuration/native dependency changes

Restart Metro with a clean cache and rebuild the Expo Development Build after native dependency or
Expo plugin changes. Admin Tailwind and Mobile NativeWind intentionally use separate platform
configuration; do not copy one Tailwind config over the other.

### Qwen model download/GPU OOM

Return the AI runtime to `mock` for normal development. Use `local` only on a machine with suitable
model dependencies and hardware; reduce concurrency before changing models as a first response to
memory pressure.

### Job remains RUNNING

Do not edit job rows manually before understanding lease state. Follow the background-job recovery
runbook in `OPERATIONS.md` §5.

## 11. Coding-agent workflow

Coding agents must read `../AGENTS.md` before modifying code. `CLAUDE.md`, `GEMINI.md`, and
`AGENT.md` are aliases to the same contract.

The required completion checks and documentation ownership rules are defined in
`../AGENTS.md` §13–15; do not maintain a second checklist here.
