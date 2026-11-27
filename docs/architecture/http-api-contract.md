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

## Learner announcements

`GET /api/v1/notifications` requires `LEARNER` and accepts zero-based pagination and filter
parameters (`page`, `size`, `unreadOnly`), returning `{items, total, page, size, hasMore, unreadCount}`.
Items are filtered to active published announcements matching the learner's current Free/Plus tier,
global `ALL` announcements, or `SELECTED` announcements addressed to their ID. Each item contains
`id`, `title`, `body`, `category`, nullable `publishedAt`, `read`, and nullable `readAt`.
`POST /api/v1/notifications/{id}/read` and `POST /api/v1/notifications/read-all` return `204 No Content`
and record multi-device read state in `learner_notification_read`.

Admin authoring uses `/api/v1/admin/notifications`. `targetAudience` accepts `ALL`, `FREE`,
`PLUS` or `SELECTED`. `SELECTED` requires `recipientIds` with 1–100 directory learners.
Creating without `status` saves a draft. Editing without `status` preserves it.
Creating or transitioning to `PUBLISHED` requires an explicit `targetAudience`.
Admin responses also include `recipientLabels` in the same order as `recipientIds`.
Publishing `STUDY_REMINDER` with `ALL` is rejected. Once the publication time has arrived,
the audience, selected recipients and original publication time are fixed.

`GET /api/v1/admin/notifications/activity` returns `{items,page,hasNext}` with 30 entries
per zero-based page. An optional `category` filters `MEMBERSHIP`, `ACCOUNT`,
`NOTIFICATION`, `REWARD` or `OTHER`. The feed presents membership transitions,
account changes, announcement edits, manual Diamond adjustments, global reward policy updates (`REWARD_POLICY_UPDATED`), and quota configuration changes.
`NOTIFICATION_SCHEDULED` entries include the planned timestamp in `scheduledFor`.
For accounts awaiting Keycloak sync, the feed includes the most recent lock or unlock request.
## Admin classification and source media

All endpoints below require ADMIN. `/api/v1/admin/lesson-channels/sync-configuration` returns
`{configured: boolean}` only; no credential is exposed. Channel listing includes `visible`, `lessonCount` (excluding deleted lessons) and
`linkedLessonCount` (including them). `DELETE /api/v1/admin/lesson-channels/{id}` returns 204 after
removing an empty channel, 404 if absent, or 409 if lessons or active synchronization prevent deletion.
It preserves source/media caches and job history; see [FR-LSN-009](../requirements/lesson.md#fr-lsn-009--admin-catalog-and-classification).
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

### Admin Shadowing pronunciation pilot

Under `/api/v1/admin/lesson-authoring/{lessonId}/sentences/{sentenceId}/pronunciation-attempts`,
`GET ?revision=N` returns the saved reference range plus `maxDurationSeconds` and
`maxUploadBytes`. `POST` accepts multipart `revision` and `audio` with a UUID
`Idempotency-Key`. It returns `202 {attemptId,jobId,expiresAt}` and an attempt Location.
The same owner, key and payload replay the accepted attempt. A stale revision on a new submission
or reuse of a key with a different payload returns 409.

Owners can read `GET /api/v1/admin/pronunciation-attempts/{id}`, fetch its private WAV via `/audio`,
and request cancellation via `POST /cancel`. Status comes from the background job. A successful job
returns a v1 result with `SCORED` or `NO_SPEECH`, nullable scores, and optional word and phoneme
intervals. A reference word carries a zero-based `referenceIndex` into the tokenized
`referenceText`. Insertions carry a null index and appear in alignment order. Reads require the
Admin role and the creating JWT subject. Unknown, expired and other
Admins' attempts return 404. An accepted attempt keeps its reference text and revision through
later edits. The UI can play the reference while its saved revision remains current.

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

## Admin vocabulary catalog

Admin routes under `/api/v1/admin/vocabulary` operate on the imported study catalog.

| Method and route | Contract |
| --- | --- |
| `GET /packs` | Ordered packs, bilingual titles, unit and entry counts, visibility and revision. |
| `GET /packs/{id}/units` | Ordered units, CEFR level, entry count, visibility and revision. |
| `GET /entries` | Optional `packId`, `unitId`, and literal, case-insensitive `q` of at most 120 characters for word or meaning. Zero-based `page` defaults to 0. Returns `{items,total}` with 30 rows per page. |
| `GET /entries/{id}` | Bilingual definitions, examples and collocations, optional TOEIC context notes, opaque media IDs, visibility and revision. Load media through the authorized content-media file endpoint. |
| `PUT /packs/{id}`, `PUT /units/{id}`, `PUT /entries/{id}` | Replace editable fields with the read revision. Returns 204 when saved and 409 if the revision conflicts. After editing an example, its prior recording is detached. |

The catalog endpoints require Admin authorization. The Learner SRS API keeps its existing contract.

The entry list accepts `review` values `ALL`, `NEEDS_REVIEW`, `MISSING_VI`,
`MISSING_SYNONYMS`, `MISSING_ANTONYMS`, and `HIDDEN`. Entries are ready for study by
default; `NEEDS_REVIEW` filters items explicitly flagged by an administrator for
follow-up. The entry summary reports pronunciation preview, sense count, and effective
visibility, including its parent pack and unit. Unit edits accept an optional CEFR level
and preserve the existing level when omitted.


## Library administration and learner cutover

Admin library roots are `/admin/lexicon`, `/admin/grammar/{topic|subtopic|bank}`,
`/admin/assessment-items` and `/admin/toeic` under `/api/v1`. They support list/detail and corrections to existing content,
`PUT /{id}/draft {revision,content}`, publish `{revision}` (optional `reason`, at most 2,000 characters), status `{revision,status}`,
history/audit and restore `{revision,publicationId}`. Stale mutations return 409. Restore creates a
new immutable publication. `DELETE /{id}?revision=N` permits unpublished unreferenced drafts only.

Grammar/assessment/TOEIC `/page` returns `{items,total,page,size}` (zero-based page, size 1–100);
array list routes remain available for existing callers. Lexicon `/count` returns the exact filtered
total and `/browse` uses opaque `after`/`before` cursors. `tail=true` reads the final page, with
bounded `skip` (0–90) for its neighbouring pages; no deep offset is used. Admin uses one numbered
paginator with first/last pages and ellipses across catalogs. Search debounces 300 ms and resets
pagination; filters and page anchors are encoded in the URL.
Admin document DTOs include `hasUnpublishedChanges`; unchanged publication requests return the
current document without allocating another revision. Visibility changes do not count as content edits.
TOEIC accepts only FULL_TEST content with 200 placements in the standard Part ranges.
Restoring a TOEIC publication also validates its structure and current publication blockers.
`GET /admin/toeic/source-review` returns active-source passage issue locations for review deep links.
`GET /admin/toeic/{id}/preview` returns the saved test and its published question content keyed
by placement ID. The Admin can filter this read-only preview by section or Part without
opening a learner session. An unavailable question is omitted from the question map.
`GET /admin/grammar/review` lists current structural issues (`id,title,kind,code,parentId`), separate
from immutable import reports. `GET /admin/grammar/{kind}/{id}/check` validates saved metadata and
pinned question revisions, returning `{code,questionId,number}` issues without changing content.

Lexicon filters include type/POS/translation/state, Grammar access/state, TOEIC access/year/state
and current quality-report status. `POST /bulk-preview` accepts `{items:[{id,revision}]}` and reports current
revisions/usages. `/bulk-status` applies `{items,status}` atomically for 1–100 unique targets.

Content enters these catalogs through the offline dataset pipeline. Export is `GET /{id}/export?format=json|csv`; CSV is a lossy
simple-field interchange, rejected for known complex annotation/media cases. Formula-leading CSV
cells are escaped. TOEIC export uses versioned JSON; it retains complete related groups.

Question/Lexicon `/{id}/ai` list/start durable suggestions. Async responses include job Location.
Accept checks base/accepted revision and only selected fields, with per-suggestion idempotency.
Dictionary requests include `{revision,senseIds,brief,operation}` with MATCH, TRANSLATE or EXAMPLE
(legacy SUGGEST remains readable). Accept is `{revision,index,mode}`; translation mode must be
REPLACE or APPEND. Suggestions include senseId/field/value and nullable candidateId plus reason.
Admin translation DTOs retain sourceContext/origin for review; learner translations omit them. Learner senses expose only the source name, language-keyed definitions and stable sense ID, without raw source locators.
TTS requests include operation TTS, supported voice, optional itemId scope and `reviewed=true`; output is a synthetic
asset requiring preview/accept. AI never changes a supplied answer or publishes content.

`/admin/content-media` uploads decoded bounded audio/images, returning opaque metadata. Authorized
`/{assetId}/file` serves a Resource (including framework byte-range responses), no-store/nosniff.
Dataset package installation is operator-only. See the [import guide](../../tools/data-import/README.md).

Learners use `/grammar/catalogs/{kind}` and `/toeic/catalog` plus detail/start-session endpoints.
Grammar catalog summaries expose the Vietnamese title and `titleEn` for locale-aware display.
The Lexicon detail's `readingModes` lists available `en-vi` and `en-en` views.
`/assessment-sessions` provides private history/detail, optimistic answer saves and idempotent submit.
Open DTOs omit answer keys and transcripts; submitted results carry pinned grading evidence.
Media authorization requires membership in that learner's session. Sessions remain resumable for
24 hours despite later publication/hiding. Obsolete source scaffold discovery/direct-submit controllers
and service stacks are removed; old stored attempts and schema remain preserved.


### Current library quality and TOEIC context

`GET /admin/toeic/{id}/quality` and `GET /admin/grammar/{kind}/{id}/quality` return the latest
report (nullable). POST starts a durable check and returns 202 with jobId/Location. Reports include
status, revision/fingerprint, rulesVersion, checkedAt and issues `{code,severity,location,message,href}`.
Changed dependencies or rules invalidate older reports. Cancelled/failed jobs never imply pass;
publication validates current saved content on the server.

`/admin/toeic/{id}/questions/{placement}` reads/edits a question in test context. Draft mutations
carry test/question revisions; AI uses the current saved Part, placement, transcript and documents.
Saving pins the question publication into that test draft; publishing the whole test is separate.
Learner sessions keep the publication/question/media evidence captured at start.

<a id="learner-profile-onboarding-and-admin-directory"></a>
## Learner profile, onboarding and Admin directory

Admin can read the directory field `toeicTargetScore`. The Admin profile has no write route for this field.

Account routes under `/api/v1/admin/learners/{learnerId}/account` require a Lyreo Admin.
`GET` returns `management`, `locked` and `syncPending`.
`PUT /password` accepts `password` and `changeOnNextLogin` and returns 204.
`PUT /lock` requires `locked` and accepts an optional `reason` of up to 500 characters.
A response with `syncPending: true`
means Core has blocked access and the Keycloak operation requires a retry.
Locked accounts and tokens issued before the account was reopened receive `401`.

Self-service routes require `LEARNER` and derive ownership from the authenticated
JWT subject. Admin directory routes require `ADMIN`.

| Operation | Contract |
|---|---|
| `GET /api/v1/learner/profile` | Returns `{userId,onboarded,profile}`. Before completion, `profile` is null. |
| `PUT /api/v1/learner/onboarding` | Accepts `{goal,currentLevel,dailyMinutes}`. Returns the saved profile DTO directly. Repeat requests return the current DTO and keep later edits. |
| `PATCH /api/v1/learner/profile` | Returns the updated profile DTO directly. Omitted fields keep their values. Null clears `displayName` or `focusArea`. Before completion, returns `409`. |
| `GET /api/v1/admin/learners` | Accepts `query` (name/email, case-insensitive literal substring, max 120 characters), optional `status=PENDING\|COMPLETED`, zero-based `page` (default 0), and `size` (default 20, 1–100). Returns `{items,total,page,size}` ordered by first-seen time descending and then learner ID descending. |
| `GET /api/v1/admin/learners/{learnerId}` | Returns a directory entry or `404`. Directory entries contain `learnerId`, `displayName`, `email`, `firstSeenAt`, `onboardingStatus`, `onboardingCompletedAt`, `goal`, `currentLevel`, `dailyMinutes`, `focusArea`, `avatarPreset`, and `avatarUploadId`. Unset values are null. |
| `PATCH /api/v1/admin/learners/{learnerId}` | Changes `displayName` and/or `avatarPreset` and returns the directory entry. Only learners already in the directory are editable. |
| `GET /api/v1/learner/profile/avatar` and `GET /api/v1/admin/learners/{learnerId}/avatar` | Return the uploaded PNG for the authorized viewer, or `404` when the profile uses initials or a preset. |
| `PUT /api/v1/learner/profile/avatar` and `PUT /api/v1/admin/learners/{learnerId}/avatar` | Accept a multipart `file` and return the updated profile DTO or directory entry, respectively. Learner uploads require a completed onboarding. |

Writes accept `goal` values `CONVERSATION`, `WORK`, `TOEIC`, and `UNDECIDED`.
`currentLevel` accepts `A1`, `A2`, `B1`, `B2`, `C1`, `C2`, and `UNKNOWN`.
`dailyMinutes` is an integer from 5 to 240. Profile edits can also set `displayName`
(trimmed, at most 80 characters, blank clears it) and `focusArea` (`LISTENING`,
`SPEAKING`, `VOCABULARY`, or `GRAMMAR`). Existing stored values remain readable.
The existing preferences endpoints own preferences. Editing a profile and completing
onboarding leave saved preferences intact.

Profile DTOs and directory entries expose nullable `avatarPreset` and
`avatarUploadId`. Clients render initials when both are null. Admin chooses a stable
background color from the learner ID. The picker groups preset IDs by collection:

| Collection | Preset IDs |
|---|---|
| Animals | `BEAR`, `PANDA`, `RED_PANDA`, `KOALA`, `LION`, `PENGUIN`, `GIRAFFE`, `ORCA`, `TOUCAN` |
| Flowers | `FLOWER_HEATHER`, `FLOWER_CLIMBING_LILY`, `FLOWER_CHRYSANTHEMUMS`, `FLOWER_ROSES_LILACS`, `FLOWER_IRISES`, `FLOWER_POTTED_PANSIES` |
| Characters | `PEEP_CURLS`, `PEEP_BEANIE`, `PEEP_BUN`, `PEEP_HEADSCARF`, `PEEP_LONG_HAIR`, `PEEP_QUIFF` |
| Comics | `COMIC_MILO`, `COMIC_LUNA`, `COMIC_NOVA`, `COMIC_KYO`, `COMIC_MIKA`, `COMIC_ARI` |

The earlier `CAT`, `DOG`, `FOX`, and `RABBIT` presets remain valid in the API.
Admin displays initials for these saved presets.
Setting a preset or clearing it with null replaces the current avatar. Uploads
accept JPEG and PNG up to 2 MiB and dimensions up to 4096 pixels per side. Core
stores one metadata-free 256 × 256 PNG per learner. Use `avatarUploadId` as the
avatar revision when fetching authorized image bytes.

First successful use of a Learner API records `firstSeenAt` once. A learner is
listed even before completion. Keycloak remains the role authority and Identity
provides the email snapshot through the `identity_user_directory` read view.

### Mobile examples

A first profile read returns:

```json
{"userId":"6eca0ef7-d80d-42e5-b694-d010a45ac233","onboarded":false,"profile":null}
```

Complete onboarding with:

```json
{"goal":"CONVERSATION","currentLevel":"A2","dailyMinutes":10}
```

The completion response is a profile DTO. A later GET wraps this same DTO in
`{userId,onboarded:true,profile}`.

```json
{
  "learnerId":"6eca0ef7-d80d-42e5-b694-d010a45ac233",
  "displayName":null,
  "currentLevel":"A2",
  "goal":"CONVERSATION",
  "dailyMinutes":10,
  "focusArea":null,
  "avatarPreset":null,
  "avatarUploadId":null,
  "preferences":{
    "preferredAccent":"US",
    "translation":"AFTER_ATTEMPT",
    "sentenceIpa":"TAP_TO_SHOW",
    "vocabularyNotes":"AFTER_ATTEMPT",
    "grammarNotes":"AFTER_ATTEMPT",
    "thoughtGroups":true,
    "karaokeHighlighting":true,
    "properNounHints":true,
    "defaultPlaybackSpeed":1.0
  },
  "starterSuggestion":{"topic":"CONVERSATION","difficulty":"BEGINNER","dailyMinutes":10}
}
```

To edit, send `{"displayName":"Mai","focusArea":"LISTENING","dailyMinutes":15}`.
The response has the same DTO shape with the new fields and
`"starterSuggestion":{"topic":"CONVERSATION","difficulty":"BEGINNER","dailyMinutes":15}`.
A following GET returns the updated DTO in its wrapper.

## Learning, Entitlement, Curriculum, Gamification, Analytics & Chat Extensions

All protected endpoints use `BearerAuth` (`Authorization: Bearer <JWT>`) and return RFC 9457 Problem Details (`LyreoProblemDetail`) with explicit machine-readable error codes and structured extension fields:

| HTTP Status | `code` | Extra Problem Details fields | Client action |
|---|---|---|---|
| `401` | `AUTHENTICATION_REQUIRED` | — | Refresh OIDC token once or re-authenticate |
| `403` | `ACCESS_DENIED` | — | Wrong role (e.g., `LEARNER` calling `/api/v1/admin/**`) |
| `403` | `FEATURE_ENTITLEMENT_REQUIRED` | `featureKey` | Show feature upgrade / locked state |
| `403` | `QUOTA_EXCEEDED` | `featureKey`, `limitType`, `limit`, `remaining`, `resetAt` | Display quota limit & UTC ISO-8601 `resetAt` |
| `409` | `STALE_REVISION` | `currentRevision` | Reload latest server state before retrying edit/save |
| `409` | `SESSION_EXPIRED` | `expiredAt` | Offer starting a new study/assessment session |
| `409` | `IDEMPOTENCY_CONFLICT` | — | Reject mutation replay with changed payload under same `Idempotency-Key` |
| `413` | `PAYLOAD_TOO_LARGE` | — | Reject oversized uploads above server multipart limits |
| `429` | `RATE_LIMITED` | `Retry-After` header | Respect per-client API rate limit backoff |
| `503` | `SERVICE_UNAVAILABLE` | — | Upstream execution / transient dependency unavailable |

All paginated collection endpoints standardize on `{items, total, page, size, hasMore}` (with `balance` also included on `/api/v1/gamification/ledger`, and `unreadCount` on `/api/v1/notifications`).

### Endpoint Summary by Module Owner

- **Identity & Learner (`modules/identity`, `modules/learner`)**:
  - `GET /api/v1/me`: returns `id`, `subject`, `email`, and `roles` (`List<String>`).
  - `GET/PATCH /api/v1/learner/profile`: includes `toeicTargetScore` (10–990 in increments of 5, nullable), `avatarUrl`, plus `PUT /api/v1/learner/onboarding`.
  - `GET/PUT /api/v1/learner/profile/avatar`: `GET` supports `If-None-Match` -> `304 Not Modified` before reading bytes.
- **Entitlement (`modules/entitlement`)**:
  - Learner: `GET /api/v1/entitlements` (and alias `GET /api/v1/learner/entitlements`) include top-level `tier` (`FREE` | `PLUS`), nullable `validUntil`, and per-feature access, remaining period/capacity quotas, and `resetAt`.
  - Admin: `GET/PUT /api/v1/admin/entitlements/learners/{learnerId}/plan` returns the current `tier`, `validUntil` and up to 30 recent Free/Plus changes. PUT accepts `tier` (`FREE` or `PLUS`) and an optional Plus `durationDays` (30, 90 or 365). Omit the duration for open-ended Plus. Expiration returns the learner to Free automatically. Repeating the active tier leaves its expiry and history intact. A manual plan choice supersedes any scheduled legacy changes.
  - Admin policy and audit: `GET /api/v1/admin/entitlements/features`, `GET/PUT /api/v1/admin/entitlements/quotas`, and `GET /api/v1/admin/entitlements/audit`. `GET /api/v1/admin/entitlements/grants` reads archived per-feature records. Effective grant intervals from before plan membership are combined into Plus periods.
- **Vocabulary (`modules/vocabulary`)**:
  - Study Catalog & Media: `GET /api/v1/vocabulary/packs`, `GET /api/v1/vocabulary/packs/{packId}/cover` (`If-None-Match` `304`), `GET /api/v1/vocabulary/packs/{packId}/units`, `GET /api/v1/vocabulary/units/{unitId}`, `GET /api/v1/vocabulary/entries/{entryId}/media/{assetId}` (supports HTTP `Range` / `206 Partial Content`).
  - Flashcard SRS Study (`Học`): `GET /api/v1/vocabulary/flashcards` (`unitId`/`packId`, `scope`: `DUE_AND_NEW` | `ALL`, `limit` 1..200 with counts and 4-rating `intervalPreviews`), `POST /api/v1/vocabulary/entries/{entryId}/flashcard-review` (idempotent + `expectedReviewCount` optimistic guard, updates curated-entry SRS without touching personal `vocabulary_card`).
  - Practice & Guess Sessions (`CHOOSE_MEANING`, `CHOOSE_WORD`, `LETTER_SCRAMBLE`, `MISSING_LETTERS`, `LISTEN_WRITE`, `CONTEXT_CLOZE`, `GUESS_WORD`, `MIXED`): `POST /api/v1/vocabulary/units/{unitId}/sessions` and `POST /api/v1/vocabulary/packs/{packId}/sessions` (supports `mode` or `modes[]`, `limit` 1..100), `GET /api/v1/vocabulary/sessions` (optional `packId`, `unitId`, `status`, paginated `{items, total, page, size, hasMore}` with `unitTitle`), `GET /api/v1/vocabulary/sessions/{id}`, `POST /api/v1/vocabulary/sessions/{id}/questions/{questionId}/hint`, `POST /api/v1/vocabulary/sessions/{id}/questions/{questionId}/check`, `PUT /api/v1/vocabulary/sessions/{id}/answers`, `POST /api/v1/vocabulary/sessions/{id}/submit`.
  - Saved Vocabulary & Personal Collections: `POST /api/v1/vocabulary/cards`, `DELETE /api/v1/vocabulary/cards/{cardId}` (`204`), `POST /api/v1/vocabulary/saved-items`, `GET /api/v1/vocabulary/due` (paginated `{items, total, page, size, hasMore}` with `term`, `meaningVi`, `partOfSpeech`, `audioUrl`, `imageUrl`), `POST /api/v1/vocabulary/cards/{cardId}/review`, `GET/POST /api/v1/vocabulary/collections`, `PATCH/DELETE /api/v1/vocabulary/collections/{id}`, `GET/POST /api/v1/vocabulary/collections/{id}/items`, `DELETE /api/v1/vocabulary/collections/{id}/items/{cardId}`, `POST /api/v1/vocabulary/collections/{id}/move`, `GET /api/v1/vocabulary/saved-items/{cardId}/media/{assetId}`.
  - Admin Vocabulary Pack & Conflict metadata: `GET /api/v1/admin/vocabulary/packs`, `PUT /api/v1/admin/vocabulary/packs/{id}` (`accessMode`: `PUBLIC` | `FEATURE`, `requiredFeatureKey`), `GET /api/v1/admin/vocabulary/entries` (`hasConflict`).
- **Lesson, Speech Assessment & Lexicon (`modules/lesson`, `modules/speech-assessment`, `modules/lexicon`)**:
  - Learner Lessons: `GET /api/v1/lessons` (paginated with `hasMore`), `GET /api/v1/lessons/categories`, `GET /api/v1/lessons/{lessonId}`, `GET /api/v1/lessons/{lessonId}/activities/{activityId}`, `GET /api/v1/lessons/{lessonId}/audio` (supports HTTP `Range` / `206`), `GET /api/v1/lessons/{lessonId}/attempts`, `POST /api/v1/lessons/{lessonId}/activities/{activityId}/dictation/attempts`, `POST /api/v1/lessons/{lessonId}/activities/{activityId}/complete`.
  - Learner Shadowing: `GET /api/v1/lessons/{lessonId}/activities/{activityId}/shadowing/sentences/{sentenceId}/constraints`, `POST .../attempts` (multipart `audio`, requires `Idempotency-Key` UUID + `revision`, `202` + `Location`), `GET /api/v1/shadowing-attempts/{id}`, `GET /api/v1/shadowing-attempts/{id}/audio` (supports HTTP `Range` / `206`), `POST /api/v1/shadowing-attempts/{id}/cancel`.
  - Lexicon (`ROLE_LEARNER` or `ROLE_ADMIN`): `GET /api/v1/lexicon/search`, `GET /api/v1/lexicon/{id}`, `GET /api/v1/lexicon/by-headword/{headwordId}`, `GET /api/v1/lexicon/{id}/pronunciations/{pronunciationId}/audio` (supports HTTP `Range` / `206`).
- **Grammar, TOEIC & Assessment (`modules/grammar`, `modules/toeic`, `modules/assessment`)**:
  - Grammar: `GET /api/v1/grammar/catalogs/{kind}` (+ `parentId`), `GET /api/v1/grammar/catalogs/{kind}/page`, `GET /api/v1/grammar/catalogs/{kind}/{id}`, `POST /api/v1/grammar/catalogs/{kind}/{id}/sessions`.
  - TOEIC: `GET /api/v1/toeic/catalog`, `GET /api/v1/toeic/catalog/page`, `GET /api/v1/toeic/catalog/{id}` (includes `parts` breakdown), `POST /api/v1/toeic/catalog/{id}/sessions` (supports `parts: [1..7]` for `DRILL`). `FULL_TEST` submissions compute ETS-approximated `listeningScaledScore`, `readingScaledScore` (5–495), and `totalScaledScore` (10–990); `DRILL` keeps scaled fields `null` and provides `accuracyPercent`.
  - Admin question context: `GET /api/v1/admin/assessment-items/{id}/context` returns imported TOEIC placement metadata and available passage/transcript.
  - Sessions: `GET /api/v1/assessment-sessions?domain=...&status=...&catalogId=...&page=0&size=20` (paginated `{items, total, page, size, hasMore}` using lightweight `ScoreSummary`), `GET /api/v1/assessment-sessions/{id}`, `PUT /api/v1/assessment-sessions/{id}/answers`, `POST /api/v1/assessment-sessions/{id}/submit`, `GET /api/v1/assessment-sessions/{id}/media/{assetId}` (supports HTTP `Range` / `206`).
- **Curriculum (`modules/curriculum`)**:
  - Learner: `GET /api/v1/curriculum/paths` (includes `coverUrl`), `GET /api/v1/curriculum/paths/{id}/cover` (`If-None-Match` `304`), `GET /api/v1/curriculum/paths/{id}`, `POST /api/v1/curriculum/paths/{id}/enroll`, `GET /api/v1/curriculum/continue` (includes `contentTitle`, `config`, `coverUrl`).
  - Admin: `GET/POST /api/v1/admin/curriculum/paths`, `GET/PUT/DELETE /api/v1/admin/curriculum/paths/{id}`, `PUT /api/v1/admin/curriculum/paths/{id}/status`, `POST /api/v1/admin/curriculum/paths/{id}/sections`, `POST /api/v1/admin/curriculum/paths/{id}/sections/{sectionId}/items`.
- **Gamification, Analytics & Chat (`modules/gamification`, `modules/analytics`, `modules/chat`, `modules/assessment`)**:
  - Learner Gamification: `GET /api/v1/gamification/summary`, `GET /api/v1/gamification/missions`, `POST /api/v1/gamification/missions/{missionId}/claim` (manual claim with optional `Idempotency-Key`), `GET /api/v1/gamification/achievements`, `GET /api/v1/gamification/achievements/{id}/badge` (`If-None-Match` `304`), `GET /api/v1/gamification/ledger` (`{balance, items, total, page, size, hasMore}`).
  - Admin Gamification: `GET /api/v1/admin/gamification/learners/{learnerId}`, `POST /api/v1/admin/gamification/learners/{learnerId}/diamonds`, `GET/POST /api/v1/admin/gamification/missions`, `PUT /api/v1/admin/gamification/missions/{id}`, `GET/POST /api/v1/admin/gamification/achievements`, `PUT /api/v1/admin/gamification/achievements/{id}`, `GET/PUT /api/v1/admin/gamification/reward-policy` (`ruleVersion` optional; changes audited to `reward_policy_audit`).
    In the Admin learner summary, `currentStreak` is zero after a full UTC day without study or when there is no history.
  - Learner Analytics: `GET /api/v1/analytics/overview`.
  - Admin Analytics: `GET /api/v1/admin/analytics/summary`, `GET /api/v1/admin/analytics/dashboard?days=7|14|30` (unified operational dashboard KPIs with prior-period deltas, continuous daily series, jobs/AI/account health, content inventory, top curriculum paths, and weakness hotspots), `GET /api/v1/admin/analytics/learners/{learnerId}`, `GET /api/v1/admin/analytics/learners/{learnerId}/study-profile`.
  - Learner Chat: `POST /api/v1/chat/ask` (supports `Idempotency-Key` replay / `409` conflict), `GET /api/v1/chat/conversations` (paginated `{items, total, page, size, hasMore}` with `lastMessagePreview` & `messageCount`), `GET/PATCH/DELETE /api/v1/chat/conversations/{id}`.
  - Admin Chat: `GET /api/v1/admin/chat/summary` (privacy-preserving aggregate telemetry & thread metadata only).
  - Admin Content Media: `GET /api/v1/admin/content-media` (`page`, `size`, `query`, `kind`), `POST /api/v1/admin/content-media`, `GET /api/v1/admin/content-media/{id}`, `DELETE /api/v1/admin/content-media/{id}`, `GET /api/v1/admin/content-media/{id}/file`.
