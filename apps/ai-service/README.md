# Lyreo AI Service

FastAPI executes technical STT, alignment, TTS, NLP, generic LLM, and judging capabilities. Core owns product prompts, routing decisions, workflow, and durable business state. The stable boundary is in [AI execution](../../docs/architecture/ai-execution.md); current endpoints and schemas are in the running OpenAPI document and code.

## Local development

Copy `.env.example` to `.env`, then use the project environment:

```bash
uv sync --locked --extra dev
set -a; source .env; set +a
uv run uvicorn app.main:app --reload --port 8000
uv run --locked --extra dev python -m pytest
```

`mock` is the deterministic development and CI runtime. For real inference, run `make ai-local`
from the repo root. It syncs the WhisperX and Kokoro extras and exposes the installed CUDA libraries
to CTranslate2. NVIDIA CUDA is the default. Set `WHISPERX_DEVICE=cpu` on hosts without it.
Kokoro uses the `espeak-ng` system package and runs on CPU by default.

WhisperX loads `small.en` for STT and English wav2vec2 for alignment on demand. A single worker
serializes the two operations and moves model weights back to CPU between requests. STT uses
batch size 1 on a 4 GB GPU. The first run downloads weights, the voice activity detector and
the English sentence tokenizer. Restarts clear loaded models and retain download caches.

Internal capability calls require the service token. FastAPI does not persist provider credentials or business records. Do not log incoming credentials. Use authorized media references for large audio; storage ownership remains outside this process.

The [Admin pronunciation pilot](../../docs/CONFIGURATION.md#admin-pronunciation-pilot) receives
Azure credentials from Core for each call. Its optional live smoke uses synthetic samples with an
initial pause and pauses across utterances. From this directory, inject the Speech F0 key in the
test process as `AZURE_SPEECH_KEY`, then run
`LYREO_AZURE_SMOKE=1 uv run --locked --extra dev python -m pytest tests/test_azure_speech_live.py`.
The runner also needs `espeak-ng` and `ffmpeg`.

## Lesson authoring execution contract

STT returns `text`, `timestamps` and coarse `segments` with millisecond anchors relative to the
supplied audio. WhisperX uses voice activity detection for STT segment anchors. If VAD finds no
speech, the runtime retries full-audio Whisper in 30-second windows, which covers music and heavily
mixed speech while keeping usable segment boundaries. Core saves the anchors before scheduling
dedicated alignment. Groq can return both segment and word timestamps.

Alignment accepts `input.audio_url`, the approved `input.text` and optional `input.segments`.
Audio longer than 120 seconds requires anchors. The anchors must cover the text in order and fit
120 seconds per window including padding. The runtime returns observed word intervals. Core checks
coverage, order and overlap before using those intervals for karaoke.

LLM options accept concrete `json_schema`, symbolic `response_schema`, `max_output_tokens` and `temperature`. Gemini also accepts `thinking_level`; Groq accepts the existing allowlisted `provider_options` such as `reasoning_effort` and `max_completion_tokens`. Concrete Groq schemas use strict structured output. Missing usage is omitted, not reported as zero. Completion status/finish reason is returned as metadata so Core can reject incomplete generations. Upstream 429 preserves status and `Retry-After` for resumable jobs without exposing provider error bodies.

Measured provider and timing limits are documented in [ASR research](../../docs/research/lesson-authoring-asr.md). Providers still require an explicitly verified free-only configuration in Core; adapter availability is not billing verification.


Local reference IPA is exposed through NLP with `options.operation=sentence_ipa_v1`, sentences input
and US/UK accent. Install `espeak-ng` on the host (included in the Docker image). No Kokoro weights are
needed for IPA. Results include engine version and review flags for ambiguous/special forms; they do
not transcribe the actual speaker's accent or connected speech. Core owns routing and review policy.
