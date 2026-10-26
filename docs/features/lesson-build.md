# FEAT-LESSON-BUILD — Lesson authoring

Implements [Lesson requirements](../requirements/lesson.md) and
[D-024](../DECISIONS.md#d-024--integrated-youtube-lesson-authoring-and-free-only-research-2026-10-25).
Core owns lifecycle/prompts/validation, generic jobs own leases/recovery, AI owns route audit, and
FastAPI executes capabilities. PostgreSQL holds authoritative drafts/checkpoints; object storage
holds prepared audio and raw evidence. Mobile playback is a later phase.

## Authoring checkpoints

Inspect a YouTube URL, see existing ranges, choose one contiguous segment and enter the editor directly after metadata inspection.
Local acquisition is operator-enabled for public videos supported by the configured downloader; no channel allowlist or licence-document upload is required. The
current acquisition path downloads the explicitly confirmed source range, then normalizes it as
16 kHz mono PCM WAV. Existing cached full sources can still supply selected ranges. Exact normalized range assets can be reused across lessons.
Output file size alone does not measure network savings; byte measurements must distinguish media payload and transport overhead.

Nguồn do người vận hành lựa chọn để phát triển và kiểm thử nội bộ. Người vận hành chịu trách nhiệm xin phép và bảo đảm quyền sử dụng trước khi khai thác hoặc phát hành.

Metadata inspection does not acquire audio and is independent of the acquisition switch. Both paths
need the configured downloader on the Core host; audio preparation also needs FFmpeg. Expected
source-tool failures return safe `503` Problem Details with stable `YOUTUBE_*` codes.

PREPARE stops at transcript review. Edit/split/merge stable sentences; choose Learn, Watch only or
Skip in bulk. Draft word evidence supports player seeking. Learn receives semantic output; Watch
only contributes context; Skip is excluded from semantic context and skipped in Admin playback.
Review issues are derived from actual coverage/timing evidence, never invented ASR confidence.

Structurally valid provisional Whisper timing needs explicit karaoke preview confirmation. Dedicated
alignment is required when timing is absent/stale or preview is inadequate. Until human-referenced
quality acceptance, deployment policy also requires local alignment for structurally valid draft timing. Coalescing may only
combine adjacent touching/overlapping observed intervals into multi-token timing groups; it may not
invent positive per-word duration, stretch silence or hide unresolved lexical coverage. Qwen alignment
window bounds may reach the next timestamp frame at the audio end; the adapter uses the loaded
model's frame precision and slices only existing samples, preserving raw word timing. Invalid timing
blocks confirmation/publication. Approved lexical edits require bounded anchored realignment; absent
anchors or unsafe long windows fail clearly instead of proportional text/audio splitting.

Transcript approval and karaoke confirmation precede SEMANTIC. The semantic operation sends compact
full context, target Learn IDs and Core-owned schema; output chunks preserve that global context.
Each new semantic chunk contains at most three Learn targets while retaining the full lesson context.
A validation correction preserves translations and practice priorities but requests empty optional
annotation arrays instead of guessing source spans; existing larger validated checkpoints remain reusable.
Provider exact phrases map to unique source spans in Core; thought groups must partition original
text in order. Core rejects missing/duplicate targets, altered/ambiguous surfaces, invalid locale,
enums, bounds and truncation before replacing valid annotations. Candidate eligibility is technical
Core policy; independent model priorities cannot override it. Admin reviews content/candidates and
publishes explicitly. Publication and selected membership are one immutable transaction.


## Admin management and workspace

Admin uses a viewport-based editing workspace with independently scrollable player and editor panes.
On smaller screens, Player/Editor switches preserve state; content lists scroll inside the workspace
and the single workflow action bar stays visible.
Source, transcript, timing and content navigation exposes the authoring checkpoints. Active jobs
have compact progress with expandable details; loading uses skeletons. Poll only while jobs are active,
keep unsaved local edits and candidate revision intact, and confirm navigation away from dirty edits.
Published lessons have a separate read view with metadata and availability controls.

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
| Lexical text | Prepared audio, raw ASR evidence, coarse anchors | Timing, approval, karaoke, semantic, candidates |
| Punctuation/whitespace with same lexical stream | Re-map existing observed timing | Approval/karaoke, semantic surface spans, candidate derivation |
| Split/merge preserving lexical order | Repartition timing groups when boundaries map safely | Approval/karaoke, semantic sentence identities, changed candidate selections |
| Learn/Watch only/Skip | Audio, ASR and timing | Context/membership, approval/karaoke, semantic and candidates |
| Realignment only | Transcript and matching semantic input | Karaoke confirmation and technical eligibility |
| Candidate selection only | Transcript, timing, approval, semantic | Selection/progress membership; no model calls |
| Publish | All approved output | Freeze content and selected membership atomically; no model call |

Whole-draft revision prevents lost updates; transcript revision/hash identifies AI dependencies.
Candidate-only changes must not make approved content stale. Timing result identity separately
invalidates karaoke review. Edits do not trigger inference implicitly; Admin requests stale capabilities.

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
seconds/milliseconds, window offsets, stale results, all invalidation transitions, strict semantic
validation, quota/resume, atomic publish and legacy writer protection. UI tests cover editor actions,
checkpoint controls, dirty polling, candidate selection and player range/Skip behavior. Live benchmark
evidence is separate from deterministic tests; neither mocks nor schema validity prove teaching quality.

Retire Lesson Prep only after important behavior has replacement regression coverage, the integrated
workflow has been verified, and no runtime dependency remains. Remove its source/Gradio/ZIP-only code,
Make/CI/setup/doctor/validator hooks and active documentation together. Preserve local .env, .work,
exports and all existing Lesson/attempt/progress data. Retain explicitly marked historical evidence.

## Current verification checkpoint

The integrated implementation has deterministic coverage for draft persistence/checkpoints,
revision conflicts, expired leases, late results, atomic publication, concurrent legacy writers,
practice membership, token/span validation, provider completion/errors and local window offsets.
Source adapter tests exercise real FFmpeg with local generated audio and a fake metadata/downloader
process, including source/range cache reuse and normalized selected-range WAV output. These do not
acquire YouTube media or establish production acquisition rights.

The technical replacement gate passed on 2026-10-26: actual OIDC, YouTube acquisition,
prepare/edit/approve/alignment, official player seek/Skip/end pause, karaoke confirmation,
semantic generation, candidate selection and local publication/read-only verification. Lesson Prep
source and hooks are retired; local artifacts and user data remain intact. This does not close human
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
| Lesson Prep | Technical replacement gate passed; source/Gradio/ZIP and Make/CI/setup/doctor/validator hooks retired together | Local .env/.work/exports and existing lessons/attempts/progress preserved |

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
Core media setup/doctor checks own the downloader independently of Lesson Prep.


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

Source metadata inspection does not acquire audio. Default selection is full duration at ≤10 minutes,
otherwise the first five minutes; the whole timeline remains available. Explicit confirmation creates
a durable preview job for that exact range. Range preparation and peaks are cached; unprepared areas
stay empty. Preview publication is job-lease/cancellation fenced. YouTube is the sole playback clock;
WaveSurfer renders real prepared peaks and sends seek/region commands without starting another audio
player. Source selection never enables publication rights.

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

Source URL paste inspects metadata and opens the source workspace directly. It does not download
media or start inference automatically. Loading the selected-range waveform remains an explicit
operator action, with no rights checkbox or attestation flag in the API. Workflow submissions for
Transcript, Timing and Content are grouped in the one persistent action bar.

The desktop workflow was rechecked with local Qwen transcription/alignment and Groq semantic
generation on a 20-second source range. Fixture `084bca30-47e7-4242-b125-5bffc808226b` was
created and published through Admin; reload returned PUBLISHED with six sentences and three
selected sentences per activity. This is an automated internal fixture, not human language-quality approval.
