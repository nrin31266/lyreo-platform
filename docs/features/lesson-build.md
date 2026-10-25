# FEAT-LESSON-BUILD — Lesson authoring

Implements [Lesson requirements](../requirements/lesson.md) and
[D-024](../DECISIONS.md#d-024--integrated-youtube-lesson-authoring-and-free-only-research-2026-10-25).
Core owns lifecycle/prompts/validation, generic jobs own leases/recovery, AI owns route audit, and
FastAPI executes capabilities. PostgreSQL holds authoritative drafts/checkpoints; object storage
holds prepared audio and raw evidence. Mobile playback is a later phase.

## Authoring checkpoints

Inspect a YouTube URL, see existing ranges, choose one contiguous segment and confirm media rights.
Acquisition is operator-enabled only after the applicable rights/path have been checked. The
conservative acquisition baseline caches full source audio once, then normalizes the selected
segment as 16 kHz mono PCM WAV. Exact normalized range assets can be reused across lessons.
Range downloading has not been benchmarked; no network savings claim follows from selected-range inference.

PREPARE stops at transcript review. Edit/split/merge stable sentences; choose Learn, Watch only or
Skip in bulk. Draft word evidence supports player seeking. Learn receives semantic output; Watch
only contributes context; Skip is excluded from semantic context and skipped in Admin playback.
Review issues are derived from actual coverage/timing evidence, never invented ASR confidence.

Structurally valid provisional Whisper timing needs explicit karaoke preview confirmation. Dedicated
alignment is available when timing is absent/stale or preview is inadequate. Coalescing may only
combine adjacent touching/overlapping observed intervals into multi-token timing groups; it may not
invent positive per-word duration, stretch silence or hide unresolved lexical coverage. Invalid timing
blocks confirmation/publication. Approved lexical edits require bounded anchored realignment; absent
anchors or unsafe long windows fail clearly instead of proportional text/audio splitting.

Transcript approval and karaoke confirmation precede SEMANTIC. The semantic operation sends compact
full context, target Learn IDs and Core-owned schema; output chunks preserve that global context.
Provider exact phrases map to unique source spans in Core; thought groups must partition original
text in order. Core rejects missing/duplicate targets, altered/ambiguous surfaces, invalid locale,
enums, bounds and truncation before replacing valid annotations. Candidate eligibility is technical
Core policy; independent model priorities cannot override it. Admin reviews content/candidates and
publishes explicitly. Publication and selected membership are one immutable transaction.

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

Lesson Prep retirement remains pending the replacement's live end-to-end check with authorized
YouTube material and official player review. The research limits in the [ASR evidence](../research/lesson-authoring-asr.md)
and [semantic evidence](../research/lesson-authoring-semantic.md) remain explicit: conversational/noisy
and 20–30-minute quality, human timing/pedagogical scoring and account billing state are unverified.
Keep the old tool and user artifacts until that gate passes; its tests do not prove the new pipeline.
