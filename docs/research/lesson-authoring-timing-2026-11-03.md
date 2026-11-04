# Groq / Qwen: measured authoring timing — 2026-11-03

This is a live local comparison on the same cached source audio, not a fabricated response or a
human quality benchmark. The [credential-free JSON summary](../../tools/research/lesson-authoring-timing-2026-11-03.json)
records timing, actual sample objects and Core validation results. Full raw/normalized JSON is handed
to the operator separately; media, credentials, request headers and verbose logs are not committed.

## Fixture and method

The selected source interval is 0–45,837 ms. The cached PCM WAV contains 733,056 samples at 16 kHz
(45,816 ms); SHA-256 is `8e78af6a1a40e3690c2811988d57dd8c63c49ea0cd35b466d4e784f001f5307a`.
Source duration and decoded sample duration are distinct. Groq's native response reports 45.816000512 s.

The local GPU endpoint ran Groq STT, Qwen ASR, forced alignment of each transcript and the edited
variant “Hello everybody” in place of “Hello everyone”. Both pipelines returned 75 words; Core split
Groq text into 9 sentences and Qwen text into 7 due to punctuation/number wording. A separate CPU
float32 harness captured actual native Qwen ASR/aligner objects, loaded-model precision and the
adapter output; its timings must not be compared to the GPU endpoint as equivalent hardware.
Actual Core mapping/coverage/interval validators were run on the captured response files.

## What the models actually returned

Groq native STT supplies transcript, word/segment timestamps and metadata. Its last native word:

```json
{"word":"all?","start":45.44,"end":46.3}
```

The adapter changes units to `{index:74,word:"all?",start_ms:45440,end_ms:46300}`. It does not
invent valid timing. This run also contained three raw overlapping word pairs. Core coalesces only
observed touching/overlapping intervals; the final 46,300 ms remains outside the selected range,
so it rejects with `TIMING_OUT_OF_BOUNDS`. Transcript preparation can still succeed with missing
karaoke timing: the transcript and timing checkpoints represent different guarantees.

Qwen ASR alone returns language/text and `time_stamps:null` natively. Its text can include different
punctuation/casing and “four” instead of “4”. Asking the local STT wrapper for timestamps also calls
Qwen ForcedAligner; ASR itself does not generate those word boundaries. Native final alignment:

```json
{"text":"all","start_time":45.44,"end_time":45.84}
```

The loaded aligner reports 80 ms timestamp resolution. Its 45,840 ms end is the frame covering the
45,816 ms audio end: 24 ms beyond actual samples, and 3 ms beyond the selected source duration.
Before the fix, both original and edited pipelines failed Core interval validation at that word.
The adapter now clips only a positive interval ending inside that single covering frame:

```json
{"word":"all","start_ms":45440,"end_ms":45816,"index":74}
```

The diagnostic probe captures native evidence separately. The adapter does not fix negative, reversed, zero-duration, excessive
overshoot or lexical-coverage errors. All four corrected align cases passed actual Core validation
with 75 observed word groups. This proves structural compatibility, not listening accuracy.

## Measured timings

| Call | Wall time | Interpretation |
| --- | ---: | --- |
| Groq STT | 1.407 s | HTTP plus provider transcription; native word/segment output |
| Qwen ASR, no alignment | 19.740 s | First GPU service call, includes model loading |
| Qwen STT with timestamps | 10.202 s | ASR plus forced alignment; warm/cache state differs |
| First forced alignment after code reload | 14.832 s | Includes aligner load |
| Next three corrected align calls | 0.181–0.193 s | Warm aligner only; not ASR/end-to-end latency |
| Native CPU ASR capture | 13.148 s load + 22.754 s inference | float32 capture harness |
| Native CPU aligner capture | 9.017 s load + 2.848 s inference | float32 capture harness |

The original failed UI job showed about 23 s; this historical duration is not a new benchmark.
No conclusion about 2/5/10-minute throughput or human boundary accuracy follows from this short clip.

## Routing and fixes

Groq timestamps can be reused only when unchanged text, structural checks and the deployment quality
policy allow it. This exact Groq response needs realignment because its final interval is invalid.
Current `LESSON_TIMING_REUSE_DRAFT=false` additionally requires dedicated local alignment pending
human quality evidence. Aligner receives approved text and audio; it does not decide what was said.
Editing lexical text requires re-alignment even when the ASR originally returned valid timestamps.

Using Qwen for both words and timing is supported: ASR first, review words, then ForcedAligner on
approved text. Core now requests `timestamps=true,allow_alignment=false` during preparation:
Groq retains native timestamps, while Qwen avoids an implicit alignment that would be repeated after
approval. No provider credentials or workflow decisions move to the browser/FastAPI.
ASR windows are bounded to 115 s so retained coarse anchors leave padding room for later alignment.
Dedicated error codes distinguish bounds, non-monotonic intervals, coverage, zero duration,
overlap and missing timing; the UI no longer claims every alignment error means text mismatch.
Human listening confirmation remains explicit before semantic generation/publication.

## References and limits

### Follow-up: failing 10-minute source, 2026-11-04

The reported draft uses the 0–599,629 ms source interval. Comparison used its retained Groq
`whisper-large-v3-turbo` preparation and fresh Qwen calls on the same cached PCM audio
(SHA-256 `71c246dfa0aef74e565711c131fd104b64d4e6fd01fe3481cc16af912d4d54b2`).
The original draft was not changed. Raw transcripts/audio and verbose diagnostics stay outside Git.

| Pipeline | Observed result |
| --- | --- |
| Groq native | 1,045 words; lexical coverage differs, 14 raw overlaps, one out-of-bounds interval |
| Groq text + Qwen aligner | Exact coverage, 10 raw zero intervals; three remain isolated after observed-interval coalescing |
| Qwen ASR + Qwen aligner | 1,049 words, exact coverage, 17 raw zero intervals; six remain isolated after coalescing |

The reported Core failure is `TIMING_ZERO_DURATION` at 216,200 ms. Repacking approved text into
shorter 30-second windows still leaves four isolated zero groups on this sample; that experiment
does not justify changing the production window size. Zero intervals are not extended or dropped
to make validation pass. This identified a missing explicit phrase-group fallback in dedicated alignment, described below.

Retained Groq preparation took 11.306 s. Fresh Qwen ASR took 54.243 s; subsequent alignment took
10.452 s. Groq-text alignment took 57.832 s including model loading. These calls have different
cache/load states and are not a controlled speed comparison. There is no human timing reference.
Neither raw complete pipeline qualifies for individual-word timing acceptance on this fixture. Groq-only reuse remains
available under the existing validated, unchanged-text policy; this evidence does not support
enabling it by default. Edited words still need dedicated alignment because Groq transcription
timestamps do not align an independently edited transcript.

Steps 1–2 now group source/range/metadata and player/word-review controls, show the actual last
transcription/alignment models, and expose translated processing errors with retry in the current
workspace. Steps 3–4 retain their layout and checkpoint behavior.

[Groq STT documentation](https://console.groq.com/docs/speech-to-text) describes verbose word/segment
responses. [Qwen ASR](https://huggingface.co/Qwen/Qwen3-ASR-0.6B) and
[ForcedAligner](https://huggingface.co/Qwen/Qwen3-ForcedAligner-0.6B) document their separate roles.
The precision/bounds diagnosis above comes from actual loaded-model output and sample counts.
Offset playback and multi-window boundary behavior have deterministic regression coverage; these
fresh model calls cover only this zero-offset clip. There is no human ground truth or pedagogical
acceptance, and no publication was performed for the new smoke fixture.


## Implementation verification

`make check` ran against an isolated source mirror to avoid the live Core development compiler.
Java `verify` passed 236 unit and 36 PostgreSQL/Testcontainers integration tests; tooling passed 50,
AI 47 and importer 83. The combined command then stopped at pnpm's refusal to use a symlinked
node_modules task-state directory. Frontend gates were completed on the original checkout with
`make typecheck test-frontend build-web`: 103 Admin and 29 Mobile tests passed, all workspace
TypeScript checks and Admin production build passed. `make validate` and `git diff --check` passed.
The authoring route chunk is 155.79 kB (45.28 kB gzip); no dependency was added in this change.

A fresh unpublished smoke fixture completed Groq preparation and Qwen alignment through the actual
Admin UI; the original failed draft was untouched. Browser checks covered 1280×720 and 1440×900,
light/dark, 16:9 frame geometry, bounded page layout, split caret via keyboard, timing menu/dialog,
selected-sentence sharing, history and persisted 0.4 s terminal duration after reload. No captured
page JavaScript errors were observed. SSE connected and delivered the job transition; auth/parser,
retry/reconnect/resync, burst invalidation and cleanup have regression coverage. Anonymous SSE
returned 401; the Admin/Learner boundary passed controller tests. These are bounded development
checks, not human listening approval or a claim of multi-instance realtime delivery.

## Original draft recovery — 2026-11-04

The dedicated alignment mapper now retains isolated zero-duration tokens in an observed phrase
with a neighboring positive interval inside the same sentence. It preserves exact text/token spans
and flags GROUPED_TIMING. It does not assign individual word durations or borrow across sentences;
an all-zero sentence still fails. Bounds, ordering and lexical coverage checks remain active.

Retrying the original 100-sentence draft through Admin completed in 3.0 seconds with the warm
Qwen aligner. Persisted output has 1,035 timing entries, including 8 multi-token groups. All approved
sentence text is unchanged; interval bounds, positive duration and ordering checks passed. This is
structural recovery, not a human accuracy benchmark. Karaoke approval remains unchecked.
Targeted mapper and job-handler regressions cover grouped success and unresolved failure.

The review dock now combines processing status/history, playback controls and the primary action.
Sentence split/merge/timing and history retry/cancel/inspect use direct tooltip icons. Steps 3–4 keep
their existing layout and logic.

Current follow-up verification passed the complete `make check` gate: Java verification ran against
an isolated source mirror to avoid the live development compiler; Python, workspace typechecks,
frontend tests and production build ran in the working checkout. Admin passed 112 tests. Browser
checks covered 1440×900 and 1280×720 in light/dark; no captured JavaScript errors were observed.
This retry modified the original draft's alignment checkpoint, preserving transcript text.
