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

`GET /api/v1/jobs` (Admin only) returns `{items,total,page,size}`, ordered by creation descending,
with optional `status` (state enum or `ACTIVE`) and exact `type` filter. Page is zero-based; size
is 1–100, default 20. Detail/list add nullable `nextRetryAt`, `errorCode`, safe `errorMessage`,
`ownerModule` and `ownerReferenceId`. Config snapshots, lease ownership and credentials remain private.

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
- `POST /text-drafts`: `{title?, text, voice, categoryIds, description?, imageUrl?, options?, startProcessing?}` → `201` review document; `startProcessing=true` atomically enqueues TTS and the chain, otherwise saves only.
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

## Lesson authoring and publication

Admin endpoints use `/api/v1/admin/lesson-authoring/{id}`. Source creation accepts optional options
before starting the explicit full chain. POST `/run` selects missing source preparation, alignment
or CONTENT and enables continuation. PREPARE/TTS/ALIGN checkpoints and successor enqueue share a
fenced transaction. Cancelled/lost leases/new revisions cannot commit or enqueue; completed inputs
prevent duplicate successors. Text never runs STT. No AI runs on GET, editing or save.

| Method/path | Request and result |
| --- | --- |
| `PUT /transcript` | `{revision,sentences:[{id?,text}]}` saves full transcript, no AI |
| `POST /cancel` | `{revision}` stops chain continuation and cancels its active job under the draft lock |
| `POST /run` | `{revision}` starts/continues affected chain; 202 `{lessonId,jobId}` |
| `PUT /changes` | `{changes:{revision,voice?,options?,profile?,sentences:[{id?,text,content?,cloze?,clozeManual}]},metadata?}` atomic batch save; returns draft |
| `PUT /content-options` | `{revision,options}` saves normalized capabilities/budget/accent, no AI |
| `POST /content` | `{revision}` CONTENT only, valid current timing required; 202 with job Location |
| `PUT /sentences/{sentenceId}/content` | `{revision,content}` manual translation/IPA/annotations/activity selections |
| `PUT /content-profile` | `{revision,cefr,contentType}` manual enum-validated profile |
| `GET /readiness` | `{ready,blockers:[code or sentence-specific detail]}` no writes |
| `POST /publish` | `{revision}` server-validated atomic immutable snapshot, publisher subject/time; idempotent after publication |
| `POST /duplicate` | `{revision}` new editable lesson/sentence IDs, shared media/current content, no AI |
| `GET /jobs/{jobId}/diagnostics` | scoped Admin-only expiring private artifacts and invocation IDs |

Draft job entries additionally expose `attemptCount` and nullable `result` (version 1: checkpoint
recording time/revision, sentence/word/audio totals, timed sentence count, feature coverage,
notes, selections and warnings). These are historical saved checkpoints, including reused results,
not counts of newly generated items. `usage` adds optional `invocationId`, `capability`, `status`
and `fallback`; absent legacy values remain unknown. Results are durable; raw diagnostics expire.

All mutations reject stale revisions and active conflicts; published learning content is immutable.
`content` contains translation/IPA/review-warning, vocabulary/grammar spans and independent activity
booleans. `cloze` contains easy/medium token indexes, version, original text and SHA-256 textHash;
manual targets are validated and supersede automatic ones until stale. No learner attempt is created.
Metadata inside `/changes` includes its separate `version`; the whole transaction rolls back on conflict.

Responses expose contentComplete/contentNeeded, budget caps, enum profile/stale markers, read-only
statistics, sentence raw/display IPA/version, exclusions/reasons, contentWarnings and cloze targets.
GET computes readiness/staleness without persisting normalization. Legacy hidden draft rows remain
visible and participate in full alignment. Explicit saves/alignment normalize old hidden flags.
Generated output never sets human-review revisions. Legacy `/approve`, `/karaoke-review` and
`/content-review` remain compatibility writes and are not publication prerequisites; content-review
validates data without pretending to record listening or pronunciation checks.

Missing translations/IPA block readiness at the affected sentence; invalid generated notes are
excluded with visible warnings, retaining other validated features. Malformed JSON and truncation
remain distinct failures. Retry only missing/stale data. Retired `generate`, `ipa`, `options`,
`replace-generated` and `candidates` writes remain unmapped; SEMANTIC/IPA jobs cannot execute/commit.
Timing errors retain sentence=N prefixes where available and all existing window/coverage checks.

Diagnostics return real recorded job/invocation evidence, sanitized and size/retention bounded.
Expired/old jobs return an empty list, not fabricated server logs. There is no arbitrary log-file API.

Catalog `/api/v1/admin/lessons` accepts status/source/category/channel/cefr/activity filters.
`status=DELETED` includes only soft-deleted lessons; AI_FAILED identifies current failed processing.
Items include metadata version, draft revision, deleted/processing/aiFailed flags and profile/activities.
Metadata/visibility writes carry version, DELETE uses `?version=N`, and POST `/{id}/restore` takes
`{version}`. Draft deletion is permanent only without active jobs/history; published deletion is soft
and restoration is hidden. Shared source/media and learning history are retained.

`PUT /{id}/text` accepts `{revision, text}` for an existing TEXT draft. Core validates the same
10,000-character/1,000-sentence limits, splits the source consistently and preserves unchanged
sentence identities. Source edits invalidate generated audio/timing/review; saving never invokes AI.
The source action may save and then explicitly enqueue `/synthesize` with the returned revision.
