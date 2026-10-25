# Lesson authoring ASR and timing evidence

Measured 2026-10-25. This is a bounded V1 experiment, not a general ASR ranking or proof of karaoke accuracy.

## Corpus and method

Actual recorded English speech and checked reference transcripts came from the official [OpenSLR LibriSpeech test-clean archive](https://www.openslr.org/12/), licensed **CC BY 4.0**. Attribution: Vassil Panayotov, Guoguo Chen, Daniel Povey, Sanjeev Khudanpur, *LibriSpeech: an ASR corpus based on public domain audio books* (2015). No YouTube media was acquired. This license establishes rights for this experiment, not for arbitrary production acquisition.

The archive was streamed only until sufficient complete utterances and their transcript files were available. Utterances were sorted by ID and concatenated without artificial silence. Samples include speakers 1320 and 6930, predominantly clean read audiobook speech. They are **assembled excerpts**, not a natural continuous 2/5/10-minute conversation. Samples are nested, not independent. Whole utterance boundaries were retained; target durations therefore differ slightly from nominal lengths.

Audio is native 16 kHz mono FLAC. Word error rate is Levenshtein distance divided by reference words, after lowercasing and removing ASCII punctuation. Numbers are not semantically normalized. Latency is one run, not a percentile. Groq wall time includes upload and response, not an independently measured network breakdown. Local inference excludes separately reported model loading. No new dependency or model weight was downloaded; installed `qwen-asr==0.0.6` and cached weights were used.

## ASR results

| Actual audio | Reference words | Groq Whisper V3 Turbo WER | Groq wall time | Qwen 0.6B WER | Qwen inference |
| --- | ---: | ---: | ---: | ---: | ---: |
| 114.005 s | 352 | 0.85% (3 edits) | 1.21 s | 1.99% (7 edits) | 10.70 s |
| 299.840 s | 943 | 1.17% (11 edits) | 1.80 s | 2.23% (21 edits), windowed | 25.16 s |
| 598.005 s | 1,778 | 2.08% (37 edits) | 2.75 s | 2.92% (52 edits), windowed | 45.50 s |

Groq requests used `whisper-large-v3-turbo`, English, temperature 0, verbose JSON and word timestamps. All three returned HTTP 200. Uploaded FLAC sizes were 2,036,900 / 5,311,902 / 11,042,611 bytes. Observed request-limit headers reported 2,000 requests, but this is not a guarantee of future quota or proof of billing configuration. No billing/account changes were made.

Local hardware: NVIDIA RTX A2000 Laptop GPU, 4 GB, CUDA, bfloat16, batch size 1, max new tokens 2,048. Initial ASR load took 2.41 s. Peak allocated memory was 2,347 MiB for 114 s and at most 2,400 MiB for the successful windowed runs. **An unchunked 300 s input failed with CUDA OOM.** Longer local samples succeeded when processed sequentially at existing utterance boundaries in windows of at most 120 s. This tests bounded windows, not an automatic production boundary detector. The failed unchunked path was not retried with larger inputs.

The installed package has `MAX_ASR_INPUT_SECONDS = 1200` and `MAX_FORCE_ALIGN_INPUT_SECONDS = 180`; timestamp-enabled ASR orchestrates 180 s chunks. Dedicated `Qwen3ForcedAligner.align()` has no equivalent chunk orchestration. The former Lesson Prep assertion that a 300 s limit matches aligner capability is not evidence that 300 s is safe on this hardware.

## Timing evidence and quality gate

| Groq sample | Timestamp words | Zero/negative durations | Overlapping intervals | Largest overlap |
| --- | ---: | ---: | ---: | ---: |
| 114 s | 351 | 0 | 5 | 440 ms |
| 300 s | 941 | 0 | 27 | 600 ms |
| 598 s | 1,772 | 0 | 24 | 480 ms |

No Groq interval extended outside audio bounds. Exact transcript-word matches had centers inside the originating reference utterance with 200 ms tolerance. That is a **coarse coverage check**, not a measurement against human word boundaries, and cannot establish karaoke quality. Overlaps are raw model output; no monotonic repair was applied.

Dedicated Qwen alignment of the exact 352-word reference on 114 s took 0.59 s inference plus 2.59 s loading, with peak allocated 2,616 MiB. It returned every word but **50 zero-duration intervals**; two exact-word centers were outside their reference utterance plus 200 ms. Repeating alignment one reference utterance at a time, with up to 0.5 s padding on each side, reduced this to **27 zero-duration words** and no overlaps. The 12-window run took 1.52 s plus 1.35 s load and peak allocated 1,918 MiB. Dedicated alignment therefore also needs validation and review; it is not an automatic quality guarantee.

V1 timing gate: preserve model evidence, validate lexical coverage, finite in-bounds times, positive word duration, ordering and overlap; show invalid results for correction/review and block timing approval/publication. Do not allocate synthetic 50 ms durations, redistribute silence or silently clamp bad output to make it pass. Even structurally valid timing needs actual karaoke preview approval. Automatic Whisper reuse remains disabled until reviewed real audio has established a satisfactory policy.

For approved lexical edits, align explicit anchored sentence segments sequentially, each bounded to 120 s including padding, then restore the window's offset. Preserve existing coarse source anchors; require Admin correction when anchors are unavailable or unsuitable. Do not proportionally split edited text over audio. Long alignment without safe anchors should fail with an actionable message. The reference-anchored experiment supports feasibility of this strategy, not its accuracy on noisy speech or imperfect ASR sentence anchors.

## Evidence-preserving grouped karaoke experiment

A separate self-checked interval-union experiment merged observed overlapping intervals, or a zero-duration token whose instant touches/falls inside an adjacent observed interval. A stack rechecks earlier neighbors after a merge. Combined text retains token order and uses the actual union of touching/overlapping evidence; no positive duration is invented and isolated zeros remain invalid.

| Raw output | Resulting groups | Unresolved zero groups | Maximum words/group | Words displayed in groups |
| --- | ---: | ---: | ---: | ---: |
| Whisper 114 s | 342 | 0 | 4 | 14 |
| Whisper 300 s | 886 | 0 | 4 | 83 |
| Whisper 598 s | 1,735 | 0 | 4 | 61 |
| Qwen full 114 s | 309 | 7 | 4 | 77 |
| Qwen sentence-window 114 s | 328 | 3 | 3 | 44 |

No resulting groups overlapped. This supports an explicit grouped-preview option for Whisper with mandatory Admin approval; it does **not** establish separate word accuracy or justify automatically passing karaoke. UI must highlight the whole observed group rather than split its duration into fake word times. Qwen's remaining isolated zero groups still require correction/re-alignment and block approval. Self-checks confirm an isolated zero separated by a gap stays zero, while a zero touching a positive neighbor may join that evidence.

## V1 decision, fallback and stopping point

- Prefer Groq Whisper V3 Turbo for the initial free-only ASR route, subject to verified free account configuration. On these samples it was faster and had lower WER than the installed local model. Use Qwen sequential bounded windows as the tested local transcription fallback; retain transcript review for both.
- Preserve Whisper timing as provisional evidence; neither Whisper nor Qwen has passed unattended karaoke approval. Alignment must not fabricate word times. Invalid timing requires correction or another explicit alignment attempt, not automatic publication.
- Quota exhaustion must leave a resumable job waiting, or use the configured tested local fallback. HTTP success and a request-limit header do not establish billing guarantees.
- Stop adding models/dependencies: current evidence distinguishes ASR options and exposes the necessary timing gate. Remaining work is implementation and review of actual lesson audio. Noise, conversational accents, 20–30-minute inputs, human word-boundary accuracy, automatic boundary selection and production acquisition rights remain unverified.

## Reproduction evidence

Transient inputs, transcripts, clip manifests, scripts, raw responses and metric calculations are in `/tmp/lyreo-asr-research/`; they contain no credentials. No media is committed. Exact sample SHA-256 values:

| Target | FLAC SHA-256 |
| --- | --- |
| 120 s | `118572142aa357c2c4017deb4137e5ada51ed56ee3387d417cbef33b7364947e` |
| 300 s | `1aa1f884061764dc6fbef34397fd458b928701802c7b3425555e215731b40d93` |
| 600 s | `298a1c73ba85277d73a2a827e1d8df19933234e8d50ff85f77a3eebb71bb07ed` |

## Implementation verification

The implemented runtime was exercised on the real 299.840 s sample with `timestamps=true`: three fixed windows with anchors 0 / 120,000 / 240,000 ms, sequential ASR followed by alignment after unloading ASR. It completed in 37.61 s including loading, with peak allocated 2,661 MiB. The last word ended at 299,520 ms, confirming global offset restoration beyond the first window. Raw output contained 28 zero-duration words, intentionally preserved for Core's quality gate. This check confirms execution and memory bounds, not approval of those timestamps. Earlier WER measurements used reference-utterance window boundaries and are not re-labelled as the fixed-window implementation's accuracy.
