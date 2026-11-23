# FEAT-LESSON-BUILD — Lesson authoring

Implements [Lesson requirements](../requirements/lesson.md) and
[D-024](../DECISIONS.md#d-024--integrated-youtube-lesson-authoring-and-free-only-research-2026-10-25).
Core owns lifecycle/prompts/validation, generic jobs own leases/recovery, AI owns route audit, and
FastAPI executes capabilities. PostgreSQL holds authoritative drafts/checkpoints; object storage
holds prepared audio and raw evidence. Mobile playback is a later phase.

## Text and uploaded Audio

The source selector offers YouTube (default), Text and Audio. Text saves before inference and uses
an explicit TTS job with the saved Kokoro voice. FastAPI returns one WAV plus sentence bounds
measured from concatenated PCM samples; Core validates duration, normalizes media and fences the
checkpoint. The saved audio checkpoint automatically queues alignment and then CONTENT. The complete chain
is explicit at creation and preserves each checkpoint; no review checkbox gates continuation.
Text/voice edits invalidate audio and approvals. Audio upload is independently retained by source ID;
explicit range preparation normalizes only the selected range and reuses PREPARE/STT.
All three types use the same editor/playback clock, with HTML audio for uploaded/generated media.
Limits and channel availability are owned by [Lesson requirements](../requirements/lesson.md#fr-lsn-011--text-and-uploaded-audio-authoring).

## Authoring checkpoints

Submit a YouTube URL using Search or Enter, see existing ranges and choose one contiguous segment.
Typing/pasting does not inspect; Search also refreshes or retries. Refreshing the same URL keeps
edited metadata and a valid selection. Source selection uses React Timeline Editor for range
movement/resizing, zoom and horizontal scrolling, with full-video and padded-selection fit controls.
Viewing the full source only changes zoom. A separate full-video selection action is available only
up to ten minutes. These videos are selected in full by default; longer videos have empty endpoints
and a selection hint until Admin chooses a valid segment. The Admin build configuration may lower
or disable the automatic threshold; it cannot exceed ten minutes. The timeline never scrolls vertically.
The YouTube clock drives its playhead; the timeline does not run a separate playback clock.
Local acquisition is operator-enabled for public videos supported by the configured downloader; no channel allowlist or licence-document upload is required. The
current acquisition path downloads the explicitly confirmed source range, then normalizes it as
16 kHz mono PCM WAV. Existing cached full sources can still supply selected ranges. Exact normalized range assets can be reused across lessons.
Output file size alone does not measure network savings; byte measurements must distinguish media payload and transport overhead.

Nguồn do người vận hành lựa chọn để phát triển và kiểm thử nội bộ. Người vận hành chịu trách nhiệm xin phép và bảo đảm quyền sử dụng trước khi khai thác hoặc phát hành.

Metadata inspection does not acquire audio and is independent of the acquisition switch. Both paths
need the configured downloader on the Core host; audio preparation also needs FFmpeg. Expected
source-tool failures return safe `503` Problem Details with stable `YOUTUBE_*` codes.

PREPARE saves transcript/audio, then queues ALIGN in the same fenced transaction. Explicit TTS saves
its audio checkpoint and queues ALIGN in the same way, without STT. A completed durable job input
prevents duplicate follow-up jobs during recovery; a newer transcript revision or cancelled/lost lease
rejects late output. Initial alignment does not set transcript or listening approval. Valid provider timing
is reused only under the existing timing-reuse policy. Alignment failure/cancellation preserves the
transcript/audio checkpoint; retry runs alignment only.

The complete transcript is retained and sent for alignment against the unchanged audio. All draft
sentences participate in windows, lexical coverage, overlap/order checks and listening playback.
There is no hide control; exercise selection is independent of alignment. Legacy hidden fields remain
readable, but flagged draft rows display normally and require full alignment before publication.
Explicit save normalizes flags and marks timing stale; successful alignment normalizes flags and
clears prior listening confirmation (and legacy approval). GET does not write. Published read views
retain their stored semantics.
The response exposes nullable `sentences[].previewStartMs`, relative to the selected clip, using
the first observed word or containing segment start. Approximate preview never implies karaoke readiness.
Review issues come from coverage/timing evidence, never invented ASR confidence. Recognition can return different words/punctuation on repeated runs; Core sentence splitting and review hints are deterministic for the returned transcript, not a stable score for a source URL. Groq word timestamps describe its recognized text, not forced alignment of later editorial changes. The timing reuse policy remains explicit (`LESSON_TIMING_REUSE_DRAFT`); invalid/changed timing requires alignment.

The timing-reuse policy still controls whether provisional timing can replace dedicated alignment.
Observed grouping never invents duration or hides missing coverage. Invalid timing blocks publication;
quality warnings do not require a listening checkbox. Anchored realignment retains the existing
coverage/order/overlap/window limits. While the transcript exactly matches STT, Core reuses its original
ordered audio windows directly: a sentence crossing a 115-second seam does not create a 230-second
alignment request. Edited text falls back to validated observed sentence/word anchors. WhisperX
clips the final anchor to decoded audio when source frame rounding leaves up to 50 ms of overrun.

## Content generation and review

The accepted behavior, enums, budgets, IPA conventions and cloze rules are owned by
[FR-LSN-008](../requirements/lesson.md#fr-lsn-008--contextual-generation-and-candidate-review).
Core batches enabled LLM work as numbered transcript text exactly once, no UUID/timing/token tables.
Output uses tr:[[n,translation]], v/g:[[n,phrase,explanation,occurrence?]],
d/s:[[n,reason_code]] and m:[CEFR,type]. Activity arrays contain only clear exceptions to including
spoken lines with valid timing. The server checkpoints explicit sound-cue exclusions locally in
existing drafts. Invalid or missing exclusion arrays leave only the affected activity pending.
A lossless single-object outer-array normalization is accepted; ambiguous roots are rejected.
Lesson content requests low thinking effort on providers that support it. No JSON repair/ranking
request is made. Truncated output recursively splits only the failed range. Explicit provider
rejection does not split the batch.
Translations checkpoint per sentence; invalid notes are warnings, not a failed batch. Missing feature
arrays/profile are incomplete while valid results remain durable. Fingerprints include text/context,
locale, budget, activity-selection policy and IPA display version as relevant. Retry targets only
missing/stale features and preserves manual edits on eligible lines. Explicit reconfirmation makes
stale manual content current; timing-only edits do not re-run text capabilities.

Every concrete route attempt contributes usage, including failed/truncated/fallback attempts. A
job-scoped observer records private redacted request/output evidence; diagnostic persistence failure
must never trigger another provider execution. Artifacts are JSONB records separate from draft
content, at most 256 KiB each and 8 MiB per job, inaccessible after seven days and purged hourly.
Credential/auth fields, signed URLs and media payloads are excluded. Only Admin can read a job's
records scoped to its lesson. Old jobs have no reconstructed output. Diagnostics render as text,
with copy/download JSON. Failed provider attempts retain original HTTP status and sanitized message
behind Debug; see the [AI failure contract](../architecture/ai-execution.md#failure-artifacts-and-security).
Incomplete responses keep their finish reason when available. Debug identifies a split's sentence
range and records media retry stage, attempt number and failure category without raw tool stderr.
History shows saved checkpoint coverage, note/selection counts, grouped
model calls and separate queue/call duration before Debug is opened. LLM API calls/tokens are
separate from audio/IPA calls; missing usage stays unknown. New calls carry capability, invocation
ID, route outcome and an explicit fallback flag. Worker attempts are separate from route calls.
The foundation migration stores versioned per-job result checkpoints in the same fenced transaction as draft writes;
partial checkpoints survive failure, and later edits cannot rewrite old results. Old jobs have no
invented result summary or inferred fallback. The development lesson reset also removes their
private diagnostic records before deleting jobs.

PREPARE/TTS → ALIGN → CONTENT enqueue happens with checkpoint completion under the same lease,
cancellation and revision fence. Completed input recovery is idempotent. Initial generation never
sets human-review revisions. Legacy SEMANTIC/IPA jobs stay retired; explicit writes normalize old
unsupported options. GET does not persist normalization. The foundation schema includes CONTENT/profile metadata, auto-continuation, publisher, expiring diagnostics, metadata versions and job results. Published content guards stay intact.

Admin checks a complete YouTube video URL after 350 ms of inactivity, cancels older requests on URL
changes, and selects English captions by default when inspection finds a publisher-provided track.
Auto-generated captions require an explicit selection. An uploaded caption file takes precedence.
YouTube metadata inspection caches the available English track kind
and language. Core uses
yt-dlp to fetch the selected track during PREPARE. Draft timing evidence retains the normalized cue
anchors and transcript source. An uploaded SRT or WebVTT file is parsed from at most 256 KiB of
UTF-8 text. Use video-relative timestamps and select a range that contains the complete cues you
want to include. Common explicit sound labels, such as `(plane roaring)` or `[applause]`, and
music symbols are removed before making cue windows. Other bracketed speech and lyrics remain
available for review. For automatic YouTube captions, draft sentences follow sentence punctuation
across cues where possible. A track without sentence boundaries keeps its cue rows for review.
The word aligner receives the prepared audio and the original cue windows. Existing drafts without
a caption choice keep their configured STT flow.

Profile estimates use word-weighted CEFR median and a 60% majority for type; otherwise MIXED or
UNKNOWN. Admin overrides are protected. IPA uses eSpeak raw/display fields and versions. Cloze
is deterministic cloze-v2 (including simple modal/function-word exclusions), with nested levels, lesson repetition penalties and manual hash-fenced targets.

### Content smoke — 2026-11-16

Local unpublished fixture `2a4f3073-9a79-4609-89bf-84d83e1d036c` (44 words, four sentences)
produced all translations/IPA, one vocabulary note, one grammar note and editable profile/activity
suggestions. The successful CONTENT job used one Gemini request (431 input / 342 output tokens)
and four local eSpeak calls. Two earlier validation failures consumed another 811 input / 787 output
tokens; strict invalid-span rejection was retained. Saving a manual IPA review flag and confirming
content created no further jobs; persisted content/transcript revisions match and status remains
CONTENT_REVIEW. The 1366×768 browser check exercised the same sentence panel and bottom dock.
This automated workflow fixture does not establish human language, listening or pedagogical quality.

### Three-source smoke and reported URL regression — 2026-11-17

Tests used the existing local AI service; no additional GPU worker was started. These are local test
fixtures, not a measured general English success rate or human approval of translations/IPA.

| Source / fixture | Sentences | Duration | Completed chain / content coverage |
| --- | --- | --- | --- |
| [BBC Learning English, Weather](https://www.youtube.com/watch?v=V7-ChVK1gYM), first 45s; `2ec9ad66-5e87-4394-80dc-5daf0f94b483` | 7 | 45s | PREPARE → ALIGN → CONTENT; 7 translations + IPA |
| Text, Weather practice; `0216d31d-0472-4444-bf15-5305756ec5f5` | 6 | 15.550s | TTS → ALIGN → CONTENT; 6 translations + IPA |
| Uploaded WAV, [Open Speech Repository Harvard sentences](https://www.voiptroubleshooter.com/open_speech/american/OSR_us_000_0010_8k.wav); `e255009b-7ef4-460c-b977-6df3064bc361` | 10 | 33.623s | PREPARE → ALIGN → CONTENT; 10 translations + IPA |
| Text, 24-sentence one-click fixture; `33a8b887-2ff7-4c38-901c-903672df0ba5` | 24 | 50.275s | One primary create request; TTS → ALIGN → CONTENT; all 24 translations + IPA |
| [Listening Time, New Year's Resolutions](https://www.youtube.com/watch?v=98pYyFdHw38); `f5280a31-def2-4461-83d0-c03ddc42f580` | 60 | 250s | Existing failed draft recovered through ALIGN → CONTENT; all 60 translations + IPA, no repeated STT |

The reported video exposed two regressions: a sentence crossed the coarse STT-window seam, and a
provider returned valid JSON as a singleton-object array. Targeted regressions preserve real anchors
and normalize that unambiguous root without another LLM call. A malformed/multiple-object response
still fails explicitly. Live cloze editing also exposed a stale in-memory target after per-row content
reload; batch masks now apply to the final document instance, covered by PostgreSQL integration tests.

Persisted LLM usage (provider attempts, input/output tokens): Text6 Gemini 1, 492/276; Audio10 Gemini 1,
548/477; YouTube7 Gemini 1 failed attempt with unreported tokens, then Groq 1, 584/851; Text24 Gemini 1
failed attempt with unreported tokens, then Groq 1, 726/1411. The reported 60-sentence URL initially spent
928/818 on the rejected wrapper; successful retry used one unreported Gemini attempt, Groq 950/2281 and
Gemini 822/725 across two batches, plus 60 local IPA calls. Unreported usage is unknown, never counted
as zero. STT remained one call (14.072s); the successful alignment call took 2.529s. These are individual
invocation timings, not before/after performance benchmarks.

Live management smoke published the Text24 fixture with 24 sentence/IPA snapshots, duplicated it
as `3816bf51-2d55-441d-b108-500455fc3166` with zero jobs, then soft-deleted and restored the original
hidden. The original content/media remained intact. Publication uses the token subject even when the
principal display name is an email; the real-login regression and preview-local failure message are tested.
Manual translation and cloze masks survived save/reload. Browser checks at 1366×768 and 1280×720
observed one main scroll root, no horizontal overflow, a bottom dock and inline karaoke underlining.
Final `make check` passed, including PostgreSQL integration, frontend tests/typecheck/build and validation.

The original Weather70 draft was already soft-deleted and was not restored for testing. Invalid-note
regression and complete 100-sentence coverage are tested deterministically, not claimed as live Weather70
inference. The dated four-sentence smoke above remains historical evidence; its fixture no longer exists.

## Admin management and workspace

One natural page scroll contains the sentence list, virtualized above 20 rows using the same scroll
root. The laptop player is sticky; playback/actions use a fixed dock with reserved content space.
Translation, activities and short notes are directly visible. IPA has a global display toggle.
Global save/Ctrl+S submits transcript, changed content, options/profile and metadata in one transaction;
revision conflict/network failure preserves local input. Timing remains a focused dialog. Settings
are grouped; processing history and diagnostics use bounded sheets. The processing panel shows live
queue/run/retry/cancellation status, elapsed time and the three real capability stages. Failed stages
expose the backend reason with one-click targeted retry. Completion requires a nonempty transcript
and valid preceding checkpoints; a running earlier stage never marks a later stage done. Completed
chains collapse to one readiness summary. The header's **Jobs for this lesson** action remains visible
in every state and opens only that lesson's history, including its private diagnostics. Admin can read
retained job diagnostics after soft deletion of the owning lesson. Only the main status spinner animates during processing; the active stage uses primary text without a second spinner. Inspection/editor skeletons follow their
loaded layout; pending create/upload disables competing inputs/source changes, and creation keeps
its primary action at the page bottom. A fallback poll reconciles successor jobs if SSE and checkpoint
refetch race. Publication/save menus retain their action label while closing. No mandatory approval checkbox
remains. Creation options are supplied before inference for all three source modes.

Preview offers View, Dictation support levels and Shadowing playback/karaoke/rate/loop. Each selected
Shadowing sentence can open the [Admin pronunciation dialog](../requirements/lesson.md#fr-lsn-006a--admin-pronunciation-pilot)
in the transcript and in Shadowing Preview.
Readiness comes from server validation. Publish takes an immutable content/activity snapshot atomically
and records actor/time; retries are idempotent. Metadata is independently editable with version checks.
Duplicate creates a new editable draft with reused media/results and fresh sentence IDs; no inference
or replacement of the original. Inactive history-free drafts delete permanently after confirmation;
published lessons soft-delete and restore hidden. Shared sources/media and learner history are retained.
Catalog includes Deleted restoration and source/CEFR/activity/AI-failure filters. See
[FR-LSN-009](../requirements/lesson.md#fr-lsn-009--admin-catalog-and-classification).

YouTube channel metadata sync uses `YOUTUBE_DATA_API_KEY` in Core. Selected channel IDs become one
durable job; partial/missing responses retain good values and report per-channel errors. Quota waits
durably, recovery skips completed channel checkpoints, and cancellation/lease fencing protects writes.
The capability stores external image URLs, not downloaded image objects. Empty channels also have a
named delete confirmation; backend guards include deleted lessons, active synchronization and
concurrent draft creation. Shared sources/media remain reusable; see the channel deletion policy in
[FR-LSN-009](../requirements/lesson.md#fr-lsn-009--admin-catalog-and-classification).

## Dependency and invalidation matrix

| Change | Reuse | Invalidate/recompute |
| --- | --- | --- |
| Source/range | Shared source cache | New draft and preparation |
| YouTube/Audio lexical edits | Prepared audio and observed coarse evidence | Timing and review approval |
| Punctuation/split/merge | Safely mapped observed timing | Transcript and listening approval |
| Text/voice edits | Saved text | Generated audio, timing and review approval |
| Manual timing or realignment | Translation, IPA and notes | Revalidate timing/activity readiness |
| Presentation metadata | Audio, transcript and timing | No review invalidation |

Whole-draft revision prevents lost updates; transcript revision identifies inference dependencies.
Text/split/merge edits pause karaoke and mark timing for updating. Save and realign saves first and
explicitly queues alignment without STT; changed Text still requires explicit speech regeneration.
No edit implicitly invokes inference. Readiness uses current valid data; no fabricated human-review revision is recorded.

## Failure, quota and publication safety

External calls run outside database transactions. Short commits lock/recheck draft revision and
job lease owner/expiry/cancellation. Late/stale workers discard output. Durable jobs can retry or
resume; no job waits in RUNNING for an Admin checkpoint. Job success never implicitly publishes.
A failed retry preserves previously validated output. API and database guards protect published
content and selected membership, including writes through old scaffold endpoints. Practice validates
selected membership and uses that same set as its progress denominator.

See [jobs protocol](../architecture/background-jobs.md) for fencing. Free-only routes reject
unconfirmed projects, paid/unknown model paths and oversized Groq fallback requests before calling
a provider. Respect Retry-After using durable wait. Authentication/schema failures do not retry
blindly; exhausted quota never enables billing or paid fallback.

## Verification and retirement

Require tests for sentence splitting, compound/Unicode token mapping, coverage, zero/overlap groups,
seconds/milliseconds, window offsets, stale results, all invalidation transitions, quota/resume, removed-operation fencing and legacy published-reader/writer protection. UI tests cover editor actions,
checkpoint controls, dirty polling, full-transcript playback and player range behavior. Live benchmark
evidence is separate from deterministic tests; neither mocks nor schema validity prove teaching quality.

The standalone workstation was retired after replacement regression coverage, integrated workflow
verification and removal of runtime dependencies. Source/Gradio/ZIP-only code and Make/CI/setup/doctor/
validator hooks were removed together. The owner authorized purging the remaining local environment,
cache and audio artifacts on 2026-10-31. Existing Lesson/attempt/progress data remains protected.
The former workflow is recorded in D-022, not an active tool contract.

## Current verification checkpoint

The integrated implementation has deterministic coverage for draft persistence/checkpoints,
revision conflicts, expired leases, late results, atomic publication, concurrent legacy writers,
practice membership, token/span validation, provider completion/errors and local window offsets.
Source adapter tests exercise real FFmpeg with local generated audio and a fake metadata/downloader
process, including source/range cache reuse and normalized selected-range WAV output. These do not
acquire YouTube media or establish production acquisition rights.

The technical replacement gate passed on 2026-10-26: actual OIDC, YouTube acquisition,
prepare/edit/approve/alignment, official player seek/Skip/end pause, karaoke confirmation,
semantic generation, candidate selection and local publication/read-only verification. The standalone
source and hooks are retired; local workstation artifacts were subsequently purged on 2026-10-31,
while persisted business data remains intact. This does not close human
transcript, word-boundary or pedagogical approval, broad natural-accent scoring, or independent
account billing verification. See the research evidence and the live checkpoint below.

<a id="plan-completion-audit--2026-10-25"></a>
## Plan completion audit — 2026-10-26

**The complete research/release plan is not closed.** Code verification and the measured research
are distinct from human content/timing approval and account billing verification.

| Plan area | Verified implementation/evidence | Remaining work |
| --- | --- | --- |
| Inspect/range/cache/normalization | Actual Listening Time inspection and repeated-source ranges; real acquisition; safe source error mapping; source/range cache reuse | Media-payload bytes measured separately from output bytes; full socket/TLS overhead remains unmeasured |
| Persistent editor and recovery | Actual split/merge, stable identity, refresh persistence, Learn/Watch only/Skip and revision conflict; 120s edit/save/reload/split regression | Older stale drafts without retained word evidence require a new draft |
| ASR/alignment research | Actual 2/5/10/20min paired runs, animation and UK material, synthetic noise/speed/silence; both ASR transcripts aligned on the existing 4GB GPU | Independent human reference/boundary and broad natural-accent scoring |
| Semantic research | Full Gemini 2/5/10min outputs; GPT-OSS 2min and explicit 5min429/10min413 limits; failed spans retained; one bounded correction implemented | Human pedagogical scoring; corrected prompt's live validation is reported below, not substituted into the original benchmark |
| Official player | Actual iframe seek, Skip, observed-group highlighting and selected-range end pause passed on the measured clip | Human listening review across representative sources |
| Free-only/quota/audit | Credentials encrypted through Core; allowlisted routes; actual provider429/413 observed; PostgreSQL invocation timestamp regression and simulated 429 durable worker/resume regression | Independently verified account billing state; no zero-charge assertion |
| Publication/recovery | PostgreSQL cancellation/late-result/lease/revision/atomic publication/membership regressions | Live local publication200, refresh locked and mutation409; human release approval remains open |
| Standalone workstation retirement | Replacement gate passed; source/export and runtime hooks removed | Local artifacts purged by owner request on 2026-10-31; existing lessons/attempts/progress preserved |

The original 2026-10-25 audit reproduced a long-anchor recovery defect; the implementation update
below records its fix. Deterministic tests do not certify human teaching quality.

## Implementation updates — 2026-10-26

The anchor recovery defect above is fixed for drafts retaining observed word evidence. Regression
coverage includes lexical edit plus split in one save and edit/save/reload/split on PostgreSQL.
Long descendants use distinct, uniquely observed boundaries; repeated, inserted or internal
multi-token group boundaries fail without synthetic timings. Older already-edited drafts whose
evidence was erased need a new shorter-range draft or splitting before lexical edits.

Metadata inspection is independent of the audio acquisition switch. Source failures now preserve
safe `YOUTUBE_*` codes as HTTP 503 Problem Details with correlation IDs, rather than a generic 500.
Core media setup/doctor checks own the downloader on the Core host.


## Live local checkpoint — 2026-10-26

A real OIDC session used Listening Time 0–120s, unrestricted local source acknowledgement,
Whisper Turbo (2190ms), dedicated WhisperX alignment, and Gemini semantic generation.
Split/merge retained identity, edits survived refresh, a stale revision returned 409, and Learn,
Watch-only and Skip persisted. The official YouTube iframe played real media; sentence seek,
Skip, end pause and observed-group karaoke highlighting passed. A second real 60–90s draft verified nonzero source offset: expected seek 60.44s, observed 60.994s;
playback paused at 90.006s.

The first semantic job correctly rejected an ambiguous annotation in a 130+word ASR sentence,
including its single correction attempt. Four manual test splits at existing observed timing
boundaries preserved valid timing; a fresh approval and karaoke checkpoint preceded regeneration.
All 20 Learn targets then passed Core validation across 6 successful Gemini calls (29267ms summed
provider latency). The original benchmark results are unchanged. This is one corrected-prompt
E2E success after transcript curation, not proof that correction alone fixes every transcript.

Candidate selection saved 200; browser publish returned 200; refresh showed PUBLISHED with a
disabled editor; an attempted API transcript write returned 409. This publication is an internal
local fixture. The preview and raw snapshot are retained under ignored
`data/cache/lesson-authoring-research/2026-10-26/e2e/`, referenced by the credential-free
[manifest](../../tools/research/lesson-authoring-2026-10-26.json).

Agent content inspection still found an inaccurate explanation of “video where I speak” as a
place, an overly broad grammar span, and fragmentary sentences after mechanical timing-boundary
splits. These are visible in `e2e/preview.html`; technical publication is not teaching-quality
acceptance. Human review remains required before actual release. Groq 5/10min comparisons remain
incomplete due to observed free-route 429/413 limits; no billing or paid fallback was enabled.

Final verification: `make check` passed with 236 Java unit tests, 30 PostgreSQL integration tests,
42 AI tests, 83 importer tests, 24 Admin tests and 1 Mobile test, plus typecheck/build.
After the final quoted-env Doctor fix, `make validate test-tooling` passed 37 tooling tests.
Real browser checks covered 40 route/theme/viewport cases plus 6 published-draft cases, 320–1440px,
and 10 additional cases using Chromium's actual 200% page zoom (verified DPR 2 and 720px layout in
a 1440px window). No horizontal overflow or page JavaScript exceptions were observed; mobile
Sheet Escape restored focus. Screenshots are retained locally, not committed.

## Catalog, waveform and timing policy update

The Admin catalog exposes lessons, flat many-to-many categories and metadata-derived source channels.
Classification lives outside published content guards; title edits remain draft-only. API additions are
`GET /api/v1/admin/lessons`, `PUT /api/v1/admin/lessons/{id}/metadata`, category CRUD, channel listing,
and asynchronous `/api/v1/admin/lesson-source-previews` creation/read/audio endpoints. Pagination is
zero-based with a default size of 20 and maximum 100. Missing categories or channels do not prevent
reading legacy lessons.

Source metadata inspection does not acquire audio. The source editor preselects up to two minutes
from the URL offset, positions playback there, and exposes the whole source via one timeline. Admin
can mark start/end at the YouTube clock, drag/resize or enter timecodes. The creation grid keeps source input/player/selection together beside an always-visible metadata
and learning-options panel in all three source modes. Balanced columns and a 520px video width cap
keep source playback secondary to configuration; below 900px the panels stack without nested scrolling. Short desktop screens allow page scrolling
with a sticky submission row and one page scroll for the source and settings.
A pending start appears as a marker; complete selections support drag/resize.
a labelled time ruler and dot playhead allow seeking without moving the range. Hovering shows pointer time.
Waveform UI and automatic preview preparation are deferred by owner request; the existing preview API
remains available with its lease/cancellation fencing. Transcript preparation acquires the selected range,
reusing normalized cached audio. YouTube is the sole playback clock. Source selection never enables publication rights.

Core retains raw STT timing evidence and reports coverage/interval issues separately from missing
output. Development defaults to reusing validated STT word timing for lexically unchanged text.
Other profiles require opt-in with `LESSON_TIMING_REUSE_DRAFT=true`. Invalid or stale timing still requires alignment.
Both routes require karaoke confirmation. Existing immutable atomic publication and candidate
membership guards remain in force.

The reproducible scorer is `tools/research/benchmark_lesson_timing.py`; the credential-free
[quality report](../../tools/research/lesson-timing-quality-2026-10-26.json) compares retained Groq and
WhisperX runs using the same transcript at 2/5/10 minutes. Human word references and listening approval
are absent, so the report explicitly cannot certify karaoke quality. Historical evidence above is
preserved and is not verification of this subsequent implementation.

The [range measurement](../../tools/research/lesson-source-range-2026-10-26.json) downloaded 11:00–11:30
from a 20-minute source in 2.444 seconds: FFmpeg reported 589,824 bytes read versus 316,837 output
bytes. Full socket/TLS/metadata overhead is unmeasured. A separate 60–180-second sample returned
Groq timestamps in 2.461 seconds and WhisperX alignment through the existing local service in 1.217
seconds; model loading state was not independently measured. The quality report includes this
offset sample, but no human boundary reference or listening approval is claimed.

### Admin refactor verification checkpoint

Browser checks exercised 54 route/theme/viewport cases at 375/1024/1440px plus six draft/published
editor layouts, actual region drag/resize, bidirectional timecodes, zoom, seek to source time
663.243 seconds, loop and play/pause. No page overflow or JavaScript errors were observed in those
cases. A long-source default and an edited range caused zero preview calls before explicit
confirmation, and one call after confirmation. These are bounded technical checks, not all-device
or accessibility certification.

A new local fixture at source 60–90 seconds preserved transcript edits across reload, approved the
transcript, ran local alignment and confirmed the karaoke checkpoint. Semantic validation rejected
ambiguous annotation output and left the draft unpublished. Curation at observed clause boundaries
reduced the Learn selection to one short unique clause, with other material Watch-only; generation
then succeeded. Browser publication returned 200, reload showed PUBLISHED/read-only, and transcript
mutation returned 409. Fixture ID: `74abbdc8-b0cd-4285-b7e6-b29d51531da1`. This is an automated
internal workflow fixture, not human listening/pedagogical approval or authorization for release.

Source URL Search or Enter inspects metadata and opens the source workspace. Paste and blur do
not inspect. Inspection and range selection do not download audio or start inference. Waveform UI
is deferred; there is no rights checkbox or attestation flag in the API. Workflow submissions for
Transcript, Timing and Content are grouped in the one persistent action bar.

The desktop workflow was rechecked with local WhisperX transcription/alignment and Groq semantic
generation on a 20-second source range. Fixture `084bca30-47e7-4242-b125-5bffc808226b` was
created and published through Admin; reload returned PUBLISHED with six sentences and three
selected sentences per activity. This is an automated internal fixture, not human language-quality approval.


## Authoring review API extensions

The [HTTP contract](../architecture/http-api-contract.md#lesson-authoring-and-publication) owns content
endpoints, revision checks and response fields. Existing transcript/timing operations remain;
content editing never invokes inference implicitly, and published snapshots remain immutable.


## Superseded four-step workspace

The earlier Source → Words & timing → Content → Publish workspace is superseded by the two-step
workflow above. Historical measured reports in this document describe their dated implementation;
they are not the current authoring contract. Current content behavior follows the contract above; publication still requires a product decision.

### Reusable voice previews and metadata creation

`VOICE_SAMPLE_PREPARE` is a reference-media job, not a lesson build. Core requests only
`LOCAL_KOKORO` TTS at speed 1.0, saves each successful PCM WAV and hash under a versioned object
prefix, and updates the manifest under the job lease fence. Retry skips valid objects; partial
failure retains successes. The manifest records sample text, library/model version and speed.
See the [HTTP contract](../architecture/http-api-contract.md) for Admin endpoints.

The foundation schema includes `lesson_metadata.title_automatic`, category `translations` and Audio `original_filename`.
Creation computes a non-null title server-side. Audio PREPARE commits the first STT sentence only
while the marker still holds, then clears it; manual changes clear it atomically. The metadata
form is shared across source modes. Development seed and safe reset procedures are owned by
[development workflow](../DEVELOPMENT.md).

### Bear/Bee diagnostic — 2026-11-15

A same-audio diagnostic used the existing 218 s canonical clip, without overwriting Admin drafts or
changing global routing. Saved Groq outputs already contained a repeated `his elbow.` with
word intervals overlapping the preceding phrase; changing punctuation also changed Core sentence
counts and review hints across runs. This was present in provider output, not inserted by Core.
[Groq supports word and segment timestamps](https://console.groq.com/docs/speech-to-text), but these
describe recognized text, not forced alignment of subsequent editorial changes.

Local WhisperX STT took 19.65 s and did not repeat this fragment. Its subsequent alignment took 10.88 s;
all 58 sentence timings passed the production mapping/validation code, including 8 sentences with
observed word groups. The 53-sentence Groq snapshot, with only the confirmed repeat hidden in the
diagnostic copy, aligned in 1.08 s plus a single 0.08 s anchored recovery for `Bye.`; all 52 visible
sentences then passed validation, also with 8 grouped sentences. Sequential runs include different model load/warm states, so these times are diagnostic, not a
speed comparison. Neither result constitutes human karaoke approval or a cross-corpus accuracy comparison. Provider selection is explicit in [AI routing configuration](../CONFIGURATION.md#development-ai-defaults); this one clip is not a cross-corpus accuracy claim.
