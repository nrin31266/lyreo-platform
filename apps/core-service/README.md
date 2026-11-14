# Lyreo Core Service

Core is the Spring Boot composition root for business modules, HTTP delivery, PostgreSQL persistence, durable jobs, and internal AI calls. [Architecture](../../docs/ARCHITECTURE.md) owns the boundaries; current public endpoints are described by generated OpenAPI and code.

## Local run and verification

Run `make setup` once from the repository root, or prepare the local env and run `make deps-java`
to install internal Maven artifacts. Start PostgreSQL and Keycloak with `make dev-infra`, then:

```bash
make core
```

Core compiles main sources without packaging, installing artifacts, or compiling tests, then runs
with the development-only `core-dev` Maven profile. Sibling modules load from their `target/classes`
directories rather than cached internal JARs. DevTools is absent from the default Maven profile.

The Python-standard-library watcher checks saved `src/main/java` and `src/main/resources` files
every 500 ms and batches saves after one second without another disk change. Unsaved editor
buffers do nothing; manual save and your existing editor autosave both trigger compilation.
Editor focus and typing are not monitored, and autosave settings are left alone. Only a successful,
settled build updates the DevTools trigger. A compile error leaves the current application running;
save a corrected source file to retry. Deleting/renaming a source or resource clears the affected
module's classes before recompilation so removed outputs cannot survive.

`Ctrl+C` stops the watcher and its Maven/Java children. POM, Core `.env`, or Maven launch configuration
changes stop the runner with a restart instruction; rerun `make deps-java` for dependency changes.
If startup reports missing internal Maven artifacts, run `make deps-java` and retry `make core`.
DevTools restarts the Spring context, so requests can briefly fail during a reload. Startup and
compile durations depend on the local cache; the runner prints compile duration for each batch.

Local smoke measurements on Fedora 44 / Java 25 (2026-11-02, dependencies cached): the initial
compile with fresh class outputs took 7.3 s and Spring startup took 9.6 s. Saved code/resources
became visible over HTTP in 8.5–11.2 s, including batching, compilation and context restart.
These are observations on one machine, not latency guarantees.

Run `make test-java` for fast Java tests and `make verify-java` for integration verification from the root. Flyway applies schema migrations at startup and Hibernate validates mappings. Append new migrations for schema changes; use `make db-shell` to inspect and `make db-reset` only for a deliberate local reset. The [HTTP contract](../../docs/architecture/http-api-contract.md) owns response/error conventions.

## YouTube authoring tools

Run `make setup-media` at the repository root to install or upgrade the pinned stable yt-dlp
(`2026.10.26`), its compatible EJS scripts (`default` extra), and Deno (`2.9.8`) using uv.
Deno handles YouTube's JavaScript challenges and is enabled automatically by yt-dlp.
The uv tool bin directory must be on Core's `PATH` so both executables can be found.
FFmpeg and ffprobe must also be installed on the Core host. Set `YOUTUBE_MEDIA_TOOL_ENABLED=true`
in this application's ignored `.env` to enable local audio acquisition, then restart Core.
Metadata inspection needs the downloader but does not require acquisition to be enabled.
`make doctor` checks the configured executable and treats missing media tools as errors when
acquisition is enabled. An absolute executable path can be configured when the service PATH differs.
See the [authoring source policy](../../docs/features/lesson-build.md#authoring-checkpoints).

### YouTube channel metadata

Set optional `YOUTUBE_DATA_API_KEY` only in Core's environment to enable explicit channel name/avatar
synchronization. Admin requests one channel or a batch of at most 50. Core uses durable jobs and
stores channel metadata/URLs, preserving prior good values on errors. This key is never a `VITE_*`
value. Without it, metadata sync returns an actionable conflict; authoring remains available.

## Text and Audio source preparation

FFmpeg and ffprobe are also required for uploaded Audio and generated Text speech, independently
of the YouTube acquisition switch. Uploads accept MP3/WAV/M4A up to 30 MiB; Spring's multipart
request limit is 31 MiB, below the existing 32 MiB proxy limit. The upload endpoint uses a
330-second client/proxy response window; individual probe/decode commands time out after 120 seconds. Core persists source IDs and
serves private media through authenticated Admin endpoints.

Text speech requires an enabled `LOCAL_KOKORO` (or `KOKORO`) TTS route and the AI service's optional
Kokoro runtime/model. Voice discovery uses the internal AI token and configured default voice;
text authoring never falls back to an external TTS provider. Core's bounded AI response buffer
supports the base64 PCM response for the ten-minute lesson limit. See [AI Service](../ai-service/README.md)
for runtime setup and [source requirements](../../docs/requirements/lesson.md#fr-lsn-011--text-and-uploaded-audio-authoring)
for the review/invalidation contract.
