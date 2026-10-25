# Lesson authoring semantic benchmark — 2026-10-25

## Decision

Use Gemini `gemini-3.5-flash-lite` with minimal thinking as the V1 semantic primary. Keep Groq `openai/gpt-oss-120b`, low reasoning, as an explicitly configured alternative only when the request fits its free token budget and the same business validation passes. Do not silently use Groq after a quality failure: its Vietnamese explanations and thought groups were weaker on this sample. Neither model output may publish without Admin review.

The decisive change is the provider contract: ask for exact source phrases, not numeric token offsets. Core resolves unique exact annotation phrases and sequential, complete thought-group phrases to canonical `[start,end)` token spans. Reject missing, ambiguous, changed, or incomplete text; never guess offsets. This keeps canonical spans while avoiding model token counting.

## Corpus and method

Official [LibriSpeech test-clean](https://www.openslr.org/12/), CC BY 4.0, by Vassil Panayotov, Guoguo Chen, Daniel Povey and Sanjeev Khudanpur. Source archive: `https://www.openslr.org/resources/12/test-clean.tar.gz`. References and audio prepared together under `/tmp/lyreo-asr-research`; no dataset or credentials committed. Complete utterances concatenated, not a natural contiguous conversation. Actual lengths: 114.005 s / 352 words; 299.840 s / 943 words; 598.005 s / 1,778 words. Literary English contains long sentences and proper names; this is a useful structural stress case, not broad conversational quality coverage.

Each comparison used the same approved reference context, six Learn targets, Watch-only context and an excluded Skip utterance. Output includes Vietnamese translation, sparse vocabulary/grammar, independent Dictation/Shadowing priorities, hints, pronunciation notes and thought groups. Only six targets were generated per context: these are full-context output-chunk tests, not full-lesson completion latency measurements. Temperature 0.2; sequential calls; no grounding, tools or paid models.

Transient evidence: `/tmp/lyreo-semantic-research/results.json`, `cases.json`, `surface-cases.json`, `surface-request-contract.json`. These contain public reference text and model output, not credentials. Model output was inspected and checked against the exact reference tokens.

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
