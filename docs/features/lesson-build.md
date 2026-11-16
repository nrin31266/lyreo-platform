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
checkpoint. The saved audio checkpoint automatically queues alignment; transcript and listening approval remain explicit and end at a reviewed unpublished draft.
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
There is no hide control; exercise selection is outside this workflow. Legacy hidden fields remain
readable, but flagged draft rows display normally and require full alignment and renewed review.
Explicit save normalizes flags and marks timing stale; successful alignment normalizes flags and
clears prior listening confirmation (and legacy approval). GET does not write. Published read views
retain their stored semantics.
The response exposes nullable `sentences[].previewStartMs`, relative to the selected clip, using
the first observed word or containing segment start. Approximate preview never implies karaoke readiness.
Review issues come from coverage/timing evidence, never invented ASR confidence. Recognition can return different words/punctuation on repeated runs; Core sentence splitting and review hints are deterministic for the returned transcript, not a stable score for a source URL. Groq word timestamps describe its recognized text, not forced alignment of later editorial changes. The timing reuse policy remains explicit (`LESSON_TIMING_REUSE_DRAFT`); invalid/changed timing requires alignment.

Structurally valid provisional Whisper timing needs explicit karaoke preview confirmation. Dedicated
alignment is required when timing is absent/stale or preview is inadequate. Until human-referenced
quality acceptance, deployment policy also requires local alignment for structurally valid draft timing. Coalescing may only
combine adjacent touching/overlapping observed intervals into multi-token timing groups. Dedicated alignment additionally retains isolated zero-duration tokens with an adjacent positive observed interval in the same sentence, flagged GROUPED_TIMING for human review. All-zero sentences remain invalid. Neither path may
invent positive per-word duration, stretch silence or hide unresolved lexical coverage. Qwen alignment
window bounds may reach the next timestamp frame at the audio end; the adapter uses the loaded
model's frame precision and slices only existing samples, without extending audio samples. A positive final word ending in the single model frame that straddles the actual audio boundary is clipped to the sample boundary in the Qwen adapter; negative, reversed, zero-length and more distant intervals remain invalid. Invalid timing
blocks review confirmation. Lexical edits require bounded anchored realignment; absent
anchors or unsafe long windows fail clearly instead of proportional text/audio splitting.

The workflow ends when Admin explicitly saves the reviewed transcript/timing revision. It does not
publish. Content generation, IPA authoring, candidate selection and publication UI/write APIs have
been removed. Pending SEMANTIC/IPA jobs fail permanently with `AUTHORING_OPERATION_REMOVED`; commit
fences reject obsolete output. Existing schema, JSON compatibility fields, published content and
learner history remain intact; no migration or data reset is required.

## Admin management and workspace

One continuous workspace changes source preparation into a single words-and-timing sentence list,
without numbered navigation or separate review modes. The saved draft ID is retained in the creation URL for refresh.
It uses resizable/collapsible player and editor panes. Source metadata, controls and action
placement are shared across YouTube/Text/Audio. The full source summary can collapse beside the player.
The sentence list virtualizes long documents; focus, unsaved text and revision fences survive polling.
The transcript uses direct input fields in a bounded scrolling region. Sentence playback shows karaoke
in that same row; its edit action pauses playback and restores the input, without a duplicate text list.
Each row exposes observed time bounds, playback, split/merge and the existing timing dialog. Possible repeated suffixes of
the previous sentence are review hints; they never delete words or prove that the audio is wrong.
Grouped timing uses a compact, neutral label with an explanation on hover or focus; it is not a validation failure.
Word suggestions share the list header. Timing failure messages identify the affected sentence when available, and Admin can jump to it.
The active timing sentence uses foreground emphasis and a short underline beneath only the currently spoken observed word/group.
Text retains its selected voice and shows the original source until speech generation succeeds; then alignment runs before authoring controls become available. Saving alone never invokes AI, while the primary Generate speech action
saves first and enqueues TTS for that exact revision. It never runs STT on generated audio.
Word timecodes remain in the manual timing dialog. Both players support sentence stop, uninterrupted continuous
playback, mutually exclusive looping, seek and speed through the shared playback clock. Continuous
mode only disables sentence-end stopping: it never seeks over gaps or speech, and emphasis
follows the currently observed sentence/word. Toggling it never starts playback. Preview and active
word layouts share type metrics; native text underlines fit the active word/group at the text baseline.
Processing has a prominent operation/status panel above the workspace, real progress or an indeterminate
indicator, operation-specific retry/cancel controls and durable duration. On laptops the bottom dock stays within the viewport, with playback, history, listening confirmation and
one contextual primary action. Save and realign appears only for changed words or timing that needs updating;
restoring the saved text clears the unsaved state. Valid unchanged timing exposes the explicit review/save action.
Player/source details and the sentence list scroll above the dock without being covered by it. Published lessons keep a separate read view.

Presentation metadata lives outside the immutable content snapshot. It includes display title,
description, image URL and classification. A missing custom image falls back to source metadata.
Soft-delete is limited to inactive drafts; unlisting published lessons blocks new practice while
preserving learner history. Metadata changes do not invoke inference or invalidate approval.

YouTube channel metadata sync uses `YOUTUBE_DATA_API_KEY` in Core. Selected channel IDs become one
durable job; partial/missing responses retain good values and report per-channel errors. Quota waits
durably, recovery skips completed channel checkpoints, and cancellation/lease fencing protects writes.
The capability stores external image URLs, not downloaded image objects.

## Dependency and invalidation matrix

| Change | Reuse | Invalidate/recompute |
| --- | --- | --- |
| Source/range | Shared source cache | New draft and preparation |
| YouTube/Audio lexical edits | Prepared audio and observed coarse evidence | Timing and review approval |
| Punctuation/split/merge | Safely mapped observed timing | Transcript and listening approval |
| Text/voice edits | Saved text | Generated audio, timing and review approval |
| Manual timing or realignment | Transcript | Listening confirmation |
| Presentation metadata | Audio, transcript and timing | No review invalidation |

Whole-draft revision prevents lost updates; transcript revision identifies inference dependencies.
Text/split/merge edits pause karaoke and mark timing for updating. Save and realign saves first and
explicitly queues alignment without STT; changed Text still requires explicit speech regeneration.
No edit implicitly invokes inference. Listening confirmation plus Save reviewed draft records approval
and the reviewed revision; initial preparation/alignment never does.

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
Whisper Turbo (2190ms), dedicated Qwen alignment (15202ms), and Gemini semantic generation.
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
can mark start/end at the YouTube clock, drag/resize or enter timecodes. The desktop grid groups
video, transport and timeline beside lesson metadata. Short desktop screens allow page scrolling
with a sticky submission row; longer metadata scrolls inside its own column.
A pending start appears as a marker; complete selections support drag/resize.
a labelled time ruler and dot playhead allow seeking without moving the range. Hovering shows pointer time.
Waveform UI and automatic preview preparation are deferred by owner request; the existing preview API
remains available with its lease/cancellation fencing. Transcript preparation acquires the selected range,
reusing normalized cached audio. YouTube is the sole playback clock. Source selection never enables publication rights.

Core retains raw STT timing evidence and reports coverage/interval issues separately from missing
output. `LESSON_TIMING_REUSE_DRAFT` defaults to false pending human quality acceptance. Enabling it
permits validated, lexically unchanged timing reuse; invalid/stale timing still requires alignment.
Both routes require karaoke confirmation. Existing immutable atomic publication and candidate
membership guards remain in force.

The reproducible scorer is `tools/research/benchmark_lesson_timing.py`; the credential-free
[quality report](../../tools/research/lesson-timing-quality-2026-10-26.json) compares retained Groq and
Qwen runs using the same transcript at 2/5/10 minutes. Human word references and listening approval
are absent, so the report explicitly cannot certify karaoke quality. Historical evidence above is
preserved and is not verification of this subsequent implementation.

The [range measurement](../../tools/research/lesson-source-range-2026-10-26.json) downloaded 11:00–11:30
from a 20-minute source in 2.444 seconds: FFmpeg reported 589,824 bytes read versus 316,837 output
bytes. Full socket/TLS/metadata overhead is unmeasured. A separate 60–180-second sample returned
Groq timestamps in 2.461 seconds and Qwen alignment through the existing local service in 1.217
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

The desktop workflow was rechecked with local Qwen transcription/alignment and Groq semantic
generation on a 20-second source range. Fixture `084bca30-47e7-4242-b125-5bffc808226b` was
created and published through Admin; reload returned PUBLISHED with six sentences and three
selected sentences per activity. This is an automated internal fixture, not human language-quality approval.


## Authoring review API extensions

Draft responses include generation options, manual field provenance, stale-content markers, timing
source and optional IPA metadata. Revision-checked Admin operations are `PUT .../{id}/options`,
`PUT .../{id}/sentences/{sentenceId}/content`, `PUT .../{id}/sentences/{sentenceId}/timing`,
`POST .../{id}/replace-generated` and `POST .../{id}/ipa`, under `/api/v1/admin/lesson-authoring`.
The IPA endpoint returns 202 and a durable job location. Content editing never implicitly invokes AI;
published content rejects all of these mutations. Existing published snapshots remain readable.


## Superseded four-step workspace

The earlier Source → Words & timing → Content → Publish workspace is superseded by the two-step
workflow above. Historical measured reports in this document describe their dated implementation;
they are not the current authoring contract. Content generation and publication require a new product decision.

### Reusable voice previews and metadata creation

`VOICE_SAMPLE_PREPARE` is a reference-media job, not a lesson build. Core requests only
`LOCAL_KOKORO` TTS at speed 1.0, saves each successful PCM WAV and hash under a versioned object
prefix, and updates the manifest under the job lease fence. Retry skips valid objects; partial
failure retains successes. The manifest records sample text, library/model version and speed.
See the [HTTP contract](../architecture/http-api-contract.md) for Admin endpoints.

V011 adds `lesson_metadata.title_automatic`, category `translations` and Audio `original_filename`.
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

Local Qwen STT took 19.65 s and did not repeat this fragment. Its subsequent alignment took 10.88 s;
all 58 sentence timings passed the production mapping/validation code, including 8 sentences with
observed word groups. The 53-sentence Groq snapshot, with only the confirmed repeat hidden in the
diagnostic copy, aligned in 1.08 s plus a single 0.08 s anchored recovery for `Bye.`; all 52 visible
sentences then passed validation, also with 8 grouped sentences. Sequential runs include different model load/warm states, so these times are diagnostic, not a
speed comparison. Neither result constitutes human karaoke approval or a cross-corpus accuracy comparison. Provider selection is explicit in [AI routing configuration](../CONFIGURATION.md#local-speech-recognition-for-authoring); this one clip is not a cross-corpus accuracy claim.
