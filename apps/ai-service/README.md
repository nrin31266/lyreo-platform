# Lyreo AI Service

FastAPI là **thin AI capability runtime**. Nó không sở hữu Lesson state machine, Curriculum,
Diamond, progress, SRS hay business policy.

## Boundary

Core gửi:

```text
capability + provider + model + business-built prompt/input/options
```

AI Service:

1. verify internal service token;
2. dispatch đúng runtime/provider adapter;
3. execute model/API;
4. normalize output contract;
5. trả result về Core.

Business prompt/orchestration vẫn ở Java.

## Endpoints

```text
GET  /health
POST /v1/stt
POST /v1/align
POST /v1/tts
GET  /v1/tts/voices
POST /v1/nlp/analyze
POST /v1/multimodal/judge
POST /v1/llm/generate
```

Các endpoint `/v1/*` yêu cầu `X-Lyreo-Internal-Token`.

## Runtime modes

### `mock`

Default cho dev/CI. Không GPU, không tải model, không gọi paid provider nếu test dùng mock path.

### `local`

Import package Python `qwen-asr` trực tiếp và lazy-load:

- Qwen3-ASR;
- Qwen3-ForcedAligner.

Qwen **không bắt buộc Docker**. Developer GPU có thể chạy Python local. `Dockerfile.gpu` chỉ đóng gói CUDA/runtime reproducibly.

### Kokoro TTS (provider `LOCAL_KOKORO`)

Local TTS runtime, lazy-loaded on first `/v1/tts` call. Long text is chunked deterministically
and concatenated with a fixed silence gap; output is a normalized mono WAV.

```bash
uv sync --extra kokoro
# Phonemization also needs the 'espeak-ng' system package (see .env.example for per-OS commands)
```

Voice discovery `GET /v1/tts/voices` returns the supported voice set (static metadata, no model
load) so the Lesson Prep Tool never hard-codes voices.

## Local development

```bash
cp .env.example .env
uv sync --locked --extra dev
set -a; source .env; set +a
uv run uvicorn app.main:app --reload --port 8000
```

Tests:

```bash
uv run --extra dev pytest
```

## Local Qwen/GPU

```bash
uv sync --locked --extra qwen --extra dev
AI_RUNTIME_MODE=local uv run uvicorn app.main:app --port 8000
```

Model weights được cache bởi Hugging Face/qwen runtime trên máy, không commit Git.

## Provider credentials

FastAPI không persist API key. Core encrypt credential trong PostgreSQL và forward credential đã chọn trên internal request. Không log header credential.

## Media input

Core nên gửi signed R2 URL/file URL dev thay vì base64 audio lớn. Raw media ownership vẫn ở object storage.

## Adding a provider/capability

Được phép:

- thêm adapter model/provider;
- normalize protocol;
- audio/NLP preprocessing kỹ thuật.

Không được phép:

- quyết định learner nên học gì;
- quản Lesson/Curriculum progress;
- award Diamond;
- đưa product prompt/state machine vào Python.

Xem root `AGENTS.md`.
