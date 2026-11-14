# Core HTTP API contract

Core public APIs use versioned `/api/v1/**` paths. Current endpoints and DTO schemas are owned by code and generated OpenAPI. Breaking wire changes require a new path version or an explicit compatibility plan.

## Successful responses

Return typed resources or DTOs directly, without a global success envelope. Use `200` for a successful query/update with body, `201` for synchronous creation, `202` for accepted asynchronous work, and `204` for successful work without body. Async acceptance includes a `Location` pointing to the durable status resource; clients may query that resource even when realtime updates are unavailable.

## Errors

Public failures use RFC 9457 Problem Details with `application/problem+json`. Include safe `type`, `title`, `status`, `detail`, `instance`, stable uppercase `code`, and `correlationId`. Validation failures may include field violations. Never expose stack traces, provider raw errors, credentials, or private storage references. Unexpected infrastructure failures return a safe `500` with `INTERNAL_ERROR`; generic Java exception classes are not globally mapped to client 4xx responses.

Authentication failures return `401` with `AUTHENTICATION_REQUIRED` and the appropriate Bearer challenge. Authenticated requests lacking authority return `403` with `ACCESS_DENIED`. Rate limits return `429` with `RATE_LIMITED` and `Retry-After`. Intentional not-found and state-conflict outcomes use explicit application errors. Bean Validation checks transport shape; domain and application layers enforce business invariants.

## Correlation and DTO boundaries

Accept a bounded, sanitized `X-Correlation-Id` or generate one. Return the same value in the response header and Problem Details and propagate it through logs and downstream work. Clear request-local logging context after each request.

Domain/application models remain HTTP-agnostic. Public transport uses explicit API DTOs and does not expose persistence entities or internal workflow plans. Authorization checks stay server-side.

## OpenAPI ownership

Core generates OpenAPI from the implemented HTTP boundary. Development/test may expose the descriptor and Swagger UI; production disables them by default unless deliberately enabled in a controlled environment. The generated descriptor is the source for current endpoint and schema inventory.


## Job observation

`GET /api/v1/jobs/{id}` and authoring job summaries expose additive `createdAt`, nullable `startedAt`
and nullable `finishedAt` timestamps from persisted state. Elapsed time semantics and the
Admin-only SSE event/snapshot boundary are owned by the [jobs protocol](background-jobs.md).

## Admin classification and source media

All endpoints below require ADMIN. `/api/v1/admin/lesson-channels/sync-configuration` returns
`{configured: boolean}` only; no credential is exposed. Channel listing includes `visible`.
`PUT /api/v1/admin/lesson-channels/{id}/visibility` accepts `{visible: boolean}` independently of
lesson visibility. Catalog items retain `visible` for the individual setting and add `channelVisible`
and `effectiveVisible`; the latter also requires publication. Categories remain CRUD only.

Under `/api/v1/admin/lesson-authoring`:

- `GET /voices`: `{defaultVoice, voices: [{id, name, accent, gender, sampleAvailable}],
  sampleText, sampleJobId, sampleStatus, sampleProgress, sampleError}` from internal Kokoro discovery
  and the stored sample manifest. Gender is `FEMALE|MALE`, accent `US|UK`.
- `POST /voices/samples`: `202 {jobId}` for one idempotently active `VOICE_SAMPLE_PREPARE` job;
  retries reuse valid samples. No credentials or object keys are returned.
- `GET /voices/{voice}/sample`: stored `audio/wav`, Admin only, no-store; never invokes TTS.
  Missing/damaged samples return 404. The picker can request regeneration and surface job errors.
- `POST /text-drafts`: `{title?, text, voice, categoryIds, description?, imageUrl?}` → `201` review document, no job.
- `PUT /{id}/voice`: `{revision, voice}` → updated review document with stale audio invalidated.
- `POST /{id}/synthesize`: `{revision}` → `202 {lessonId, jobId}` plus job Location.
- `POST /audio-sources`: multipart `file` → `201 {id, durationMs, contentType}`. Maximum file
  size is 30 MiB; malformed, unsupported or over-duration audio fails validation.
- `GET /audio-sources/{id}` returns the same source metadata for refresh/resume.
- `POST /audio-drafts`: `{title?, sourceId, startMs, endMs, categoryIds, description?, imageUrl?}` → `202 {lessonId, jobId}`.
- `GET /audio-sources/{id}/audio` and `GET /{id}/audio` serve private source/canonical audio to
  authenticated Admin, with `Cache-Control: no-store`. Storage keys are never API input/output.

Existing YouTube creation requests remain unchanged. Review documents add `sourceType`, nullable
`audioSourceId`, `ttsVoice`, `audioReady` and opaque `audioVersion`; `videoId` is nullable for non-YouTube drafts and `endMs`
is nullable until Text synthesis succeeds. Existing revision/job fences cover new operations too.
The browser uses the authenticated HTTP client to fetch media and revokes temporary object URLs.

Creation titles are optional for all source modes; Core supplies source-derived non-empty titles.
Category responses retain `name` and add `translations: {locale: name}`. Category POST/PUT accepts
`{name, translations?}`; omission preserves existing translations for legacy clients. UI falls back
to `name` when its locale has no translation. Invalid locale/name entries return validation errors.

## Two-step lesson authoring

The authoring workflow ends at an unpublished reviewed draft. `PUT /{id}/transcript` accepts
`{revision, sentences: [{id?, text}]}`; legacy `usage` is optional and existing stored flags are
preserved rather than edited by this workflow. Approval requires a non-empty transcript and current
Text audio, not a LEARN sentence. `/approve`, `/align`, `/prepare`, `/karaoke-review` and observed
word `/sentences/{sentenceId}/timing` remain revision checked and Admin only.
`POST /{id}/karaoke-review` is the final save-reviewed action, never publication. Readiness is derived
from current transcript approval, valid timing and `karaokeReviewedRevision`; no new workflow status is added.
Transcript `sentences[]` accepts optional boolean `hidden` (default false for new sentences; omitted
values preserve saved visibility). Responses expose `hidden`; legacy `usage` remains readable and
is independent of visibility. Hidden sentences stay stored/editable but do not participate in alignment,
timing validation or listening review text. Continuous preview audio retains the entire source,
including hidden sentence audio and silence; hidden rows receive no highlight. Hiding every sentence is rejected. Visibility edits use the same
revision fence and invalidate review checkpoints while preserving source audio/word evidence.
No schema migration is needed: visibility lives in the existing authoring JSON document.
Sentence `issues` may include `POSSIBLE_REPEAT`, a review hint that preserves the complete transcript.
For sentence-local timing failures, the job `errorMessage` begins with `sentence=N,` (one-based saved
transcript order), followed by numeric timing diagnostics. Admin uses this prefix only for `TIMING_*`
errors to offer a jump to the sentence; old jobs without the prefix retain generic recovery actions.

The former authoring `generate`, `ipa`, `options`, sentence `content`, `replace-generated`,
`candidates` and `publish` writes are no longer mapped. Historical response fields and stored
published content remain readable. Removed operations cannot run or commit results; a pending
SEMANTIC/IPA job terminates with `AUTHORING_OPERATION_REMOVED` through the job failure protocol.

`PUT /{id}/text` accepts `{revision, text}` for an existing TEXT draft. Core validates the same
10,000-character/1,000-sentence limits, splits the source consistently and preserves unchanged
sentence identities. Source edits invalidate generated audio/timing/review; saving never invokes AI.
The source action may save and then explicitly enqueue `/synthesize` with the returned revision.
