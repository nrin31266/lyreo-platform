# Lesson authoring semantic benchmark — 2026-10-25

Latest: the [2026-10-26 full-lesson extension](#full-lesson-youtube-extension--2026-10-26) records all Learn targets, exact-contract failures and real free-route limits. Earlier sections remain historical six-target results.

## Decision

Use Gemini `gemini-3.5-flash-lite` with minimal thinking as the V1 semantic primary. Keep Groq `openai/gpt-oss-120b`, low reasoning, as an explicitly configured alternative only when the request fits its free token budget and the same business validation passes. Do not silently use Groq after a quality failure: its Vietnamese explanations and thought groups were weaker on this sample. Neither model output may publish without Admin review.

The decisive change is the provider contract: ask for exact source phrases, not numeric token offsets. Core resolves unique exact annotation phrases and sequential, complete thought-group phrases to canonical `[start,end)` token spans. Reject missing, ambiguous, changed, or incomplete text; never guess offsets. This keeps canonical spans while avoiding model token counting.

## Corpus and method

Official [LibriSpeech test-clean](https://www.openslr.org/12/), CC BY 4.0, by Vassil Panayotov, Guoguo Chen, Daniel Povey and Sanjeev Khudanpur. Source archive: `https://www.openslr.org/resources/12/test-clean.tar.gz`. References and audio were prepared together in historical transient scratch; those inputs are not retained in this environment. No dataset or credentials committed. Complete utterances concatenated, not a natural contiguous conversation. Actual lengths: 114.005 s / 352 words; 299.840 s / 943 words; 598.005 s / 1,778 words. Literary English contains long sentences and proper names; this is a useful structural stress case, not broad conversational quality coverage.

Each comparison used the same approved reference context, six Learn targets, Watch-only context and an excluded Skip utterance. Output includes Vietnamese translation, sparse vocabulary/grammar, independent Dictation/Shadowing priorities, hints, pronunciation notes and thought groups. Only six targets were generated per context: these are full-context output-chunk tests, not full-lesson completion latency measurements. Temperature 0.2; sequential calls; no grounding, tools or paid models.

Historical transient evidence is not retained in this environment. The earlier report records model-output inspection against reference tokens, but its raw files cannot currently be replayed.

## Measured results

Initial numeric-span contract, maximum output 3,000–3,600 tokens:

| Context | Provider | Wall time | Input / output tokens | Result |
| --- | --- | ---: | ---: | --- |
| ~2 min | Gemini | 5.77 s | 1,476 / 1,706 | Completed JSON; 3 bad annotation spans, 4 incomplete thought-group partitions |
| ~2 min | Groq | 4.72 s | 1,802 / 1,836 | Stop; 11 bad annotation spans, 5 bad partitions, English answer leakage in 6 hints |
| ~5 min | Gemini | 6.52 s | 3,572 / 1,816 | Completed; 18 bad spans, 3 bad partitions; overannotation despite prompt |
| ~5 min | Groq | 4.97 s | 3,827 / 1,653 | Stop; 7 bad spans, 5 bad partitions |
| ~10 min | Gemini | 5.78 s | 6,896 / 1,589 | Completed; 12 bad spans, 5 bad partitions |

Explicit indexed-token input improved some spans but did not establish correctness. Compact text input with the old numeric output contract also failed; neither is an acceptable repair. Structured JSON and successful finish status are insufficient evidence of business validity.

Final exact-source-phrase contract, full ~10-minute context and six targets:

| Provider | Wall time | Input / output tokens | Validation and qualitative assessment |
| --- | ---: | ---: | --- |
| Gemini | 9.53 s | 5,003 / 1,853 | All 6 IDs; all annotation phrases uniquely resolve; complete thought-group partitions; sparse caps; no answer leakage. Groups generally follow phrases. Vietnamese fairly natural. |
| Groq | 5.13 s | 4,824 / 2,022 | All 6 IDs and complete groups; one annotation changes source casing and fails exact mapping. Groups often split by word count rather than syntax; generic hints. |

Groq first returned a real TPM 429 with `Retry-After: 4`; one later retry succeeded. Observed headers: 8,000 token limit and 1,000 request/day limit. Successful Groq final completion includes 345 reasoning tokens. No truncation occurred in successful runs.

Examples reviewed: Gemini rendered “implicit faith” naturally as “hoàn toàn tin tưởng”, while Groq earlier used “tin tưởng ngầm”. Groq later explained contrastive `WHILE` as simultaneity, an instructional error. Gemini still produced occasional awkward wording; no claim of human-reviewed teaching quality or universal model superiority follows from this small literary sample.

## Implementation contract and limits

- Core owns the JSON schema and prompt. Provider wire returns `sentenceId`, `translationVi`, vocabulary/grammar `{text,noteVi}`, independent priorities, Vietnamese hints/notes, and `thoughtGroups: string[]`. Exact phrases resolve to canonical tokens in Core. Reject ambiguity rather than fuzzy matching.
- Include compact full approved context and target IDs; chunk output by a small bounded set of Learn sentences. Six targets proved sufficient here; size chunks by input plus reserved output budget, not audio minutes alone.
- Prefer Gemini full-context generation. The numeric/token-array ~10-minute representation already consumed 6,896 input tokens before output and cannot be mechanically sent to an 8K-TPM Groq route. Compact final representation fit one Groq call (4,824 input plus 3,000 reserved output = 7,824), with little headroom. Preserve full context only when request fits; otherwise remain on Gemini or wait. Do not silently discard context to force fallback.
- Groq observed quota admission accounts for a requested token estimate, not only final generated tokens. Respect response headers and Retry-After with bounded durable retries; do not hardcode the observed limits as permanent.
- Gemini body: `response_format={type:"text",mime_type:"application/json",schema:...}`, `generation_config={temperature:0.2,thinking_level:"minimal",max_output_tokens:3000}`, `store:false`; Interactions revision `2026-05-20`. Require `status=completed`, nonempty parsed output and all Core invariants. Usage is `total_input_tokens` / `total_output_tokens`.
- Groq body: strict `response_format={type:"json_schema",json_schema:{name:"lesson_semantic",strict:true,schema:...}}`, `reasoning_effort:"low"`, `max_completion_tokens:3000`. Require `finish_reason=stop`, nonempty output and the same Core invariants. Failed quality output remains unpublished; allow at most one corrective attempt before surfacing actionable failure.
- Tests must cover exact unique resolution, repeated/ambiguous phrases, case/text changes, complete thought-group coverage, membership, sparse constraints, truncation, 429/resume and stale revisions. Technical eligibility overrides model priorities.

## Free-only and stop criteria

Official docs list [Gemini free input/output for Flash-Lite](https://ai.google.dev/gemini-api/docs/pricing#gemini-3.5-flash-lite), [Groq free limits](https://console.groq.com/docs/rate-limits), and [strict structured outputs](https://console.groq.com/docs/structured-outputs). They do not prove the billing configuration of these particular accounts; successful requests and headers cannot prove zero charge. No billing was enabled or changed. Production routing must require an operator-confirmed free project/path, an allowlisted provider/model and fail closed when absent.

Research stops here: the test identifies a working V1 provider/wire representation and concrete fallback limits. No more models or dependencies are justified by this evidence. Remaining limits: six-target chunks, one literary corpus, no human pedagogical scoring, no full-lesson total latency measurement and no independently verified account billing state.

## Full-lesson YouTube extension — 2026-10-26

This experiment replaces the earlier six-target extrapolation with all Learn sentences for each measured context. It uses the **original production prompt before the new bounded-correction change**. The raw outputs below must not be relabelled as results of the corrected prompt or a successful Core job.

### Frozen context and contract

Source: [Listening Time — Why You Didn't Learn English in School](https://www.youtube.com/watch?v=RlA-Nhr8XVU), ranges 0–120/300/600 seconds. Each context is a frozen Qwen transcript from the paired ASR experiment: 32/61/113 sentences, all marked Learn. This is a fixed research reference, **not a human-approved production transcript or evidence of Core checkpoint approval**. For example, an apparent ASR error in the lawn-related phrase remains in the frozen input; translation evaluation cannot certify transcript correctness.

Both providers receive the same context, deterministic sentence IDs, six-target chunks, exact `AuthoringSemantic` prompt and equivalent production JSON schema. Maximum output is 3500 tokens, temperature 0.2, Gemini minimal thinking / GPT-OSS low reasoning. Eligibility is held true for this isolated semantic comparison; no result overrides real Core eligibility. No Watch/Skip behavior or durable-job success is inferred from this test.

The current wire names are `translation`, annotation `surface`/`explanation`, `canonicalForm`, hints/notes and exact thought-group strings. Earlier `translationVi`/`text` descriptions above document a historical experiment and are not the current production DTO. The offline validator mirrors Core's exact case-sensitive unique substring, lexical-token boundary, target membership and sequential complete group checks; it does not replace running Core's validator or human pedagogical review.

### Complete totals and failures

| Context | Provider | Generated items | Successful chunks | Wall time including bounded quota waits | Input / output tokens | Chunks rejected by exact-span/group checks |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 2min / 32 Learn | Gemini | 32/32 | 6 | 22.94s | 16,416 / 7,973 | 3/6 |
| 2min / 32 Learn | GPT-OSS | 32/32 | 6 | 157.69s | 16,979 / 10,146 | 3/6 |
| 5min / 61 Learn | Gemini | 61/61 | 11 | 50.23s | 54,037 / 17,573 | 4/11 |
| 5min / 61 Learn | GPT-OSS | 12/61 | 2 | 107.78s including the failed next chunk | 9,289 / 3,401 on successful calls | 2/2 generated chunks |
| 10min / 113 Learn | Gemini | 113/113 | 19 | 124.69s | 164,787 / 33,885 | 6/19 |
| 10min / 113 Learn | GPT-OSS | 0/113 | 0 | 0.27s | No usage returned | Request rejected before generation |

These are **generation completion** totals. No full lesson passed first-pass business validation. Gemini generated 206 items across 36 successful chunks; 13 chunks had structural business errors. GPT-OSS generated 44 items across 8 successful chunks; 5 chunks had errors. Successful outputs ended with completed/stop and were not truncated. The supplied keys authenticated with model-list HTTP 200, but authentication says nothing about account billing.

Concrete first-pass failures, preserved in the original responses:

- Gemini 2min chunk 1 references a repeated filler without a unique surface; chunk 3 invents a noncontiguous annotation with an ellipsis; chunk 5 changes source casing. These are three different reasons for rejection, despite otherwise plausible Vietnamese.
- Gemini 5min also repeats ambiguous surfaces and produces one thought-group partition that omits/changes source material. The 10min run has ambiguous notes and additional ellipsis-based grammar surfaces.
- GPT-OSS 2min chunk 0 copies an annotation from another sentence into the first target. Other chunks choose repeated subphrases, which cannot uniquely map to tokens.
- Qualitatively, Gemini is generally sparser and its Vietnamese more useful; GPT-OSS often annotates generic words/conjunctions and commonly returns the whole sentence as one thought group. A phrase about being ahead in the recording schedule is mistranslated by GPT-OSS as personal progress. This is agent inspection of examples, not a blinded human teaching-quality score.

Core must reject these cases rather than use fuzzy matching, alter casing or guess spans. The newer implementation adds explicit target-local contiguous/case-exact instructions and at most one corrective attempt with the validation error while preserving context and budgets. **That changed behavior is not benchmarked in this table**; its live E2E evidence must be reported separately. No claim that one retry guarantees a valid lesson is supported here.

### Real free-route limits

GPT-OSS reported an 8000 TPM limit. Several 2min chunks resumed after real 429 responses and bounded `Retry-After` waits; the 157.69s total includes those waits. For the 5min lesson, the next chunk still returned 429 after three bounded attempts, so the run stopped at 12/61 items. This is a partial result, not a completed full-lesson latency. The experiment did not persist/recover through Core and therefore cannot prove durable quota-resume behavior.

The 10min request returned **HTTP 413 `rate_limit_exceeded`**, requested 9309 tokens versus an 8000 TPM allowance. Retrying the same oversized message cannot make it fit. Preserve the complete approved context and use the configured eligible Gemini route; do not silently drop context, enable billing or treat this as a transient YouTube failure. Gemini's measured 10min input was approximately 8.6K tokens per chunk before output. Full-context repetition makes total cost/quota consumption much larger than a single six-target call.

Keep Gemini as the provisional semantic primary with strict validation and Admin review. The evidence supports the provider choice and bounded correction work, **not unattended publication or a fully validated pipeline**. The observed limits are account/time-specific and must not become permanent hardcoded quotas.

### Reproduction and remaining gates

See [lesson-authoring-2026-10-26.json](../../tools/research/lesson-authoring-2026-10-26.json). Frozen inputs, raw original prompt responses, JSON usage and the per-item validation report are retained only in ignored `data/cache/lesson-authoring-research/2026-10-26/`. `semantic-validation.json` records exact target IDs, sources and rejection reasons. The manifest pins the original prompt revision/hash and the retained `original-semantic-prompt.txt`; replay must use it rather than the subsequently changed Java prompt. Retained active scripts now resolve their artifact directory from `__file__`; from repository root, the documented offline `validate_semantic.py` command reproduces the rejection totals without provider calls. Original execution scripts remain as `*.executed.py`. Live replay must be intentional and sequential in a copied artifact directory because it invokes providers and overwrites matching outputs. No raw transcript, media, credential or organization identifier is committed.

Nguồn do người vận hành lựa chọn để phát triển và kiểm thử nội bộ. Người vận hành chịu trách nhiệm xin phép và bảo đảm quyền sử dụng trước khi khai thác hoặc phát hành.

Remaining gates: human transcript, timing and pedagogical approval; independently verified free
billing/account state; and real release approval. There is no completed GPT-OSS 5/10min comparison
to report. No paid fallback or billing mutation was attempted. The experiment above does not close
these gates. Separately, the live Core checkpoint below satisfies the technical retirement gate.

### Corrected-prompt Core workflow — separate local evidence

A real OIDC session completed the integrated Listening Time 0–120s workflow using Whisper Turbo,
dedicated Qwen alignment and Gemini. The first semantic job exhausted its one correction and
correctly rejected an ambiguous surface in a 130+word ASR sentence. Four test splits used existing
observed timing boundaries, preserving timing rather than generating timestamps; a new transcript
approval and karaoke confirmation preceded the next generation.

The resulting 22 sentences contain 20 Learn, 1 Watch-only and 1 Skip. All 20 Learn targets passed Core
validation in 6 successful calls, totaling 29267ms provider latency,13734 input and 9283 output tokens.
Candidate save and browser publication returned 200; refresh was locked and an API edit returned 409.
This curated success is not part of the original paired benchmark and does not establish a 100%
first-pass or correction success rate. Durable quota waiting/resume, cancellation during provider
execution, precommit cancellation and late results have independent worker/PostgreSQL regressions;
quota response there is simulated 429, not a live account billing test.

The ignored `e2e/preview.html` in the retained artifact directory exposes all translations and
annotations for review. Agent inspection found an inaccurate “video where I speak” explanation,
a broad grammar span, and fragments introduced by timing-boundary test splits. Schema correctness
and a successful local publish therefore do not establish teaching quality. This local fixture
closes the functional replacement workflow; human release approval remains open.
