# Lyreo Core Service

Core is the Spring Boot composition root for business modules, HTTP delivery, PostgreSQL persistence, durable jobs, and internal AI calls. [Architecture](../../docs/ARCHITECTURE.md) owns the boundaries; current public endpoints are described by generated OpenAPI and code.

## Local run and verification

From this directory, copy `.env.example` to `.env` and load it for local execution. Start PostgreSQL and Keycloak with `make dev-infra` from the repository root.

```bash
set -a; source .env; set +a
../../mvnw spring-boot:run
```

Run `make test-java` for fast Java tests and `make verify-java` for integration verification from the root. Flyway applies schema migrations at startup and Hibernate validates mappings. Append new migrations for schema changes; use `make db-shell` to inspect and `make db-reset` only for a deliberate local reset. The [HTTP contract](../../docs/architecture/http-api-contract.md) owns response/error conventions.

## YouTube authoring tools

Run `make setup-media` at the repository root to install the tested yt-dlp version using uv.
FFmpeg and ffprobe must also be installed on the Core host. Set `YOUTUBE_MEDIA_TOOL_ENABLED=true`
in this application's ignored `.env` to enable local audio acquisition, then restart Core.
Metadata inspection needs the downloader but does not require acquisition to be enabled.
`make doctor` checks the configured executable and treats missing media tools as errors when
acquisition is enabled. An absolute executable path can be configured when the service PATH differs.
See the [authoring source policy](../../docs/features/lesson-build.md#authoring-checkpoints).
