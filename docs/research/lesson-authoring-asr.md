# Lesson authoring ASR and timing evidence

Latest: the [2026-10-26 live extension](#live-youtube-extension--2026-10-26) adds continuous YouTube, 20-minute ASR, paired alignment and acquisition evidence. Earlier sections preserve the separate 2026-10-25 experiment.

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

The installed package has `MAX_ASR_INPUT_SECONDS = 1200` and `MAX_FORCE_ALIGN_INPUT_SECONDS = 180`; timestamp-enabled ASR orchestrates 180 s chunks. Dedicated `Qwen3ForcedAligner.align()` has no equivalent chunk orchestration. The former workstation assertion that a 300 s limit matches aligner capability is not evidence that 300 s is safe on this hardware.

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

The original transient inputs and scripts were not retained in the current environment; the historical hashes below do not by themselves make that experiment reproducible. No media is committed. Exact sample SHA-256 values:

| Target | FLAC SHA-256 |
| --- | --- |
| 120 s | `118572142aa357c2c4017deb4137e5ada51ed56ee3387d417cbef33b7364947e` |
| 300 s | `1aa1f884061764dc6fbef34397fd458b928701802c7b3425555e215731b40d93` |
| 600 s | `298a1c73ba85277d73a2a827e1d8df19933234e8d50ff85f77a3eebb71bb07ed` |

## Implementation verification

The implemented runtime was exercised on the real 299.840 s sample with `timestamps=true`: three fixed windows with anchors 0 / 120,000 / 240,000 ms, sequential ASR followed by alignment after unloading ASR. It completed in 37.61 s including loading, with peak allocated 2,661 MiB. The last word ended at 299,520 ms, confirming global offset restoration beyond the first window. Raw output contained 28 zero-duration words, intentionally preserved for Core's quality gate. This check confirms execution and memory bounds, not approval of those timestamps. Earlier WER measurements used reference-utterance window boundaries and are not re-labelled as the fixed-window implementation's accuracy.

## Live YouTube extension — 2026-10-26

This extension adds continuous YouTube speech, a 20-minute input, animation, a UK conversation and controlled stress cases. Earlier LibriSpeech measurements above remain historical results on a different corpus. No new model or inference dependency was installed. `yt-dlp==2026.10.21` was installed as a user tool; existing FFmpeg, the AI virtual environment and cached Qwen weights were used.

### Sources and reference limits

- [Listening Time](https://www.youtube.com/watch?v=RlA-Nhr8XVU), **Why You Didn't Learn English in School**: 0–120/300/600/1200 seconds. This is continuous outdoor American English at normal speed; the transcript itself mentions ambient activity. Reference: the video's automatic English caption track, not an independently checked transcript.
- [English Fairy Tales](https://www.youtube.com/watch?v=Jsfli3pyk5s), **PRINCESS FOR A DAY**: 0–120/300/600 seconds for paired measurements; Whisper also 0–900 seconds. An additional Qwen request for 1500 seconds was limited by the source: decoded input was **926.26725 seconds**, not a 25-minute experiment. Its 75.765s latency must not be reported as a 25-minute result. Reference: automatic English captions.
- [BBC Learning English](https://www.youtube.com/watch?v=pjFlVc-EDzc), **What should you eat for a better night’s sleep?**: 0–120 seconds, an additional UK dialogue/proper-name case. A creator English (United Kingdom) caption track is available. No independent human transcript or boundary annotation was performed; no name-recognition accuracy percentage is claimed.

Nguồn do người vận hành lựa chọn để phát triển và kiểm thử nội bộ. Người vận hành chịu trách nhiệm xin phép và bảo đảm quyền sử dụng trước khi khai thác hoặc phát hành.

Automatic-caption comparison below uses normalized word Levenshtein edits against extracted timed caption tokens. It measures **disagreement between two machine outputs**, not human-reference WER. Caption errors, rolling-caption extraction and clip boundaries can all contribute; these scores do not prove accuracy or establish that one model is correct whenever the other differs.

### Paired ASR measurements

| Source / audio | Whisper upload + response | Qwen ASR wall time | Whisper / auto-caption disagreement | Qwen / auto-caption disagreement |
| --- | ---: | ---: | ---: | ---: |
| listening / 120s | 1.34s | 38.91s | 1.56% | 4.17% |
| listening / 300s | 14.55s | 58.70s | 2.69% | 3.55% |
| listening / 600s | 3.76s | 75.75s | 2.87% | 3.68% |
| listening / 1200s | 13.91s | 161.07s | 3.44% | 4.52% |
| story / 120s | 10.29s | 16.66s | 8.03% | 12.04% |
| story / 300s | 14.15s | 44.49s | 6.83% | 9.76% |
| story / 600s | 3.75s | 59.19s | 5.76% | 7.28% |

All eight main Whisper requests returned HTTP 200. Uploads were mono 16 kHz, 16-bit FLAC; the largest 20-minute upload was 24,032,940 bytes. Qwen used the same source/ranges at 16 kHz from 24-bit FLAC exports, so compressed bytes and quantization are not identical. The first 38.907s Qwen run includes initial model loading; later runs reuse that model. Local times do not include source download or FLAC export. There is no separate upload-only, model-loading-only or network-only measurement.

Qwen ran sequential fixed windows of at most 120 seconds, batch size 1, bfloat16 on the existing RTX A2000 Laptop 4 GB GPU. Peak allocated ASR memory was 2,399 MiB; dedicated alignment peaked at 2,669 MiB. All measured long inputs completed without OOM. These are single wall-time observations, not percentile estimates. The nine 120-second seams of the 20-minute aligned result retained global offsets, with observed gaps of 80–640ms and no overlapping adjacent boundary words; this checks offset restoration, not whether a clipped/repeated word is linguistically correct. Human listening at seams remains required.

### Qwen alignment with both ASR transcripts

Each transcript was aligned to its own model-provided coarse anchors. Qwen anchors are fixed 120-second windows; Whisper anchors are its much shorter observed segments. Both use the same bounded alignment runtime and actual clip audio. Different anchor sizes therefore confound a direct accuracy comparison. Raw timing is preserved rather than clamped or repaired.

Each cell is **wall time / zero-or-negative words / overlaps / out-of-bounds words**:

| Listening Time input | Qwen transcript → Qwen aligner | Whisper transcript → Qwen aligner |
| --- | --- | --- |
| 120s | 22.06s / 22 / 0 / 0 | 2.24s / 20 / 2 / 0 |
| 300s | 1.91s / 45 / 0 / 0 | 3.23s / 48 / 7 / 1 |
| 600s | 4.11s / 81 / 0 / 0 | 6.24s / 84 / 7 / 0 |
| 1200s | 7.33s / 152 / 0 / 0 | 12.10s / 185 / 16 / 0 |

The first 22.059s alignment includes loading; later calls reuse the aligner. Additional paired animation alignment completed for 120/300/600 seconds. Its raw Qwen-transcript results contained 6/13/28 zero-duration words; Whisper-transcript results contained 8/42/35. Small aligner overshoots were preserved: up to 80ms beyond the 120-second animation clip. Thus a provider-success response still requires Core's finite/bounds/positive-duration/ordering/coverage gates.

Applying the existing evidence-union grouping rule to the 20-minute results leaves **6 isolated zero groups** from the Qwen transcript and **13** from the Whisper transcript, with maximum groups of 3 and 4 words respectively. Grouping does not remove every invalid interval. No model has passed unattended karaoke approval.

### Accent, noise, speed and silence checks

The BBC UK two-speaker case completed in 1.469s for Whisper. Qwen ASR plus alignment and cold model swaps took 26.251s, peaked at 2,661 MiB and returned 338 word intervals including 11 zeros. This expands voice/accent coverage but is not a scored accent benchmark.

A reproducible 60-second excerpt of the Listening Time source was transformed with seeded white noise at nominal 20dB SNR, FFmpeg `atempo=1.25`, and 10 seconds of leading/trailing digital silence. The rows below compare each model with **its own unchanged-audio output**, not with human truth:

| Transformation | Whisper lexical disagreement | Qwen lexical disagreement |
| --- | ---: | ---: |
| White noise, 20dB | 0.51% | 1.03% |
| Leading/trailing silence | 5.08% | 0.51% |
| 1.25× speech | 5.58% | 3.59% |

Whisper returned no timed words wholly inside the added silence with 200ms tolerance. Qwen was run without word timestamps for these stress cases, so that timing claim is not made for Qwen. Synthetic noise/speed do not substitute for diverse natural recordings, accents or independent teaching-quality review.

### Acquisition: selected remote range versus full-source cache

For the same 1206.821s Listening Time source:

| Method | Wall time | Saved bytes | Range evidence |
| --- | ---: | ---: | --- |
| Full best-audio cache | 9.20s | 17,100,266 | Local exact crop provides a reusable source |
| Remote 0–120s, force keyframe/re-encode | 63.77s | 1,323,750 | 120.000s decoded; zero measured lag; waveform correlation 0.998829 with cached crop |
| Remote 60–180s, stream copy | 82.71s | 1,889,438 | **129.9935s** decoded; approximately **9.979s early** against the accurate cached crop |

Both remote runs used yt-dlp download sections; the first explicitly requested re-encoding, the second used stream copy. These settings differ and network conditions are uncontrolled. Saved output size is **not measured transferred network bytes**; no bandwidth-consumption claim is made. A follow-up socket-byte measurement was blocked because `command -v strace` returned exit 1 (tool unavailable). No OS tooling was installed and no additional download was run without instrumentation. Actual received TLS socket bytes therefore remain unmeasured; they must not be inferred from saved file sizes. The offset test nevertheless demonstrates a concrete range error with stream copy. Retain full-source caching and accurate local cropping for V1; do not change acquisition strategy based only on smaller saved files.

### Verified media-payload read bytes

Two fresh, sequential runs of the same source additionally measured payload reads with existing tools. Native yt-dlp emitted `progress.downloaded_bytes` through `--newline --progress-template 'download:LYREO_RECEIVED:%(progress.downloaded_bytes)s'`. Its final counter matched the completed full file. The remote 0–120s run added `--downloader-args 'ffmpeg:-loglevel verbose'` and retained `--force-keyframes-at-cuts`; its log contains exactly one remote HTTPS input and one local output. Only the input AVIOContext's **bytes read** is counted; the separate 1,324,207 **bytes written** counter is explicitly excluded.

| Fresh run | Wall time | Received media-payload bytes | Saved file bytes |
| --- | ---: | ---: | ---: |
| Native full source | 8.268s | 17,100,266 | 17,100,266 |
| Remote 0–120s, re-encoded | 64.730s | 1,753,088 | 1,323,750 |

This is a verified downloader/demuxer **media-payload-read** measurement, including input read-ahead. It excludes metadata requests, HTTP/TLS overhead and unread network/OS buffers; it is not a socket-byte or L2 bandwidth measurement. The remote run read less media payload but took longer in these observations. This closes the missing media-payload quantity without replacing the separate unavailable socket-level metric or changing the V1 cache decision. Raw counters, sanitized command arguments and settings are recorded in the manifest and retained `byte-measurement/` evidence.

### Reproduction and remaining gates

The credential-free manifest is [lesson-authoring-2026-10-26.json](../../tools/research/lesson-authoring-2026-10-26.json). It records source URLs/ranges, provider models, upload hashes and evidence filenames. Raw media, original responses and scripts are retained only in ignored `data/cache/lesson-authoring-research/2026-10-26/`; credentials are excluded. Active retained scripts resolve inputs beside their own files. From the repository root, run `apps/ai-service/.venv/bin/python data/cache/lesson-authoring-research/2026-10-26/validate_semantic.py` and the equivalent `final_metrics.py` command for offline validation; `prepare_reference.py`, `score_reference.py` and `score_additional.py` recompute the derived caption/stress metrics without API or GPU calls. Syntax compilation and offline validation/grouping were rerun after making the scripts portable. Original scripts are preserved as `*.executed.py`; the historical document-writer is archived only. Live `run_*` scripts invoke providers/GPU and overwrite matching artifacts: copy the artifact directory before an intentional sequential replay, and keep the working directory at repository root. Semantic replay loads retained `original-semantic-prompt.txt`, pinned by revision/hash, rather than the changed Java prompt. No production helper or dependency was added.

Completed: 13 Qwen ASR executions and 13 Whisper executions (main corpus, UK case and four stress variants per model), 14 dedicated paired alignment runs plus the UK integrated ASR/alignment run, and three original acquisition measurements plus two instrumented media-payload runs. No further API/GPU calls are required to reproduce the recorded tables from retained JSON.

Still unverified: independently checked WER, human word boundaries, listening/teaching-quality approval, natural broad-accent coverage, 25–30-minute continuous speech, account billing state and real quota exhaustion/recovery through Core. Provider success and rate-limit headers do not prove a free account. No billing was enabled or changed, no paid fallback was used, and no licence verification is asserted. The supplementary 20-minute completion narrows the earlier long-input gap; it does not close these remaining gates or authorize retiring the old workflow by itself.
