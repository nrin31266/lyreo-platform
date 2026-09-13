# Lyreo Lesson Prep Tool

Operator/content-authoring tool: acquire, synthesize, transcribe, align, and export prepared Lyreo
lesson sources as one self-contained, portable `*.lesson-source.zip` package. That zip package is the
single primary deliverable of the preparation phase — importing it into the Lesson database is a
separate, future phase.

Business orchestration lives in `lesson_prep/prep_service.py`; UI callbacks in `lesson_prep/ui_app.py`
only translate Gradio events.

## Responsibility boundary

```text
Lesson Prep Tool  -> Standalone local operator workstation
                  -> Calls AI Service over HTTP (STT, alignment, TTS, voices)
                  -> Passes local file references (file:///...) for deterministic audio processing
                  -> Exports verified *.lesson-source.zip containing manifest + media bytes

Core / Keycloak / R2 -> NOT required to run or export from the Lesson Prep Tool.
```

The tool never connects to PostgreSQL, Keycloak, or Cloudflare R2 directly. It produces a completely
portable artifact that can be stored offline, shared, or imported anywhere.

## Package format (`*.lesson-source.zip`)

The exported zip package contains:
- `lesson-source.json` — Versioned manifest (`schemaVersion: 1`) with source metadata, audio duration,
  SHA-256 hashes, sentence boundaries, and word-level alignment timestamps.
- `media/audio.<ext>` — Exact prepared canonical audio bytes (e.g. `media/audio.wav`).
- `media/thumbnail.<ext>` (optional) — Thumbnail image bytes for video sources (e.g. `media/thumbnail.webp`).

The package never leaks:
- Object storage keys (e.g. `canonicalAudioObjectKey`)
- Signed or presigned URLs
- Host absolute paths or `file://` URIs
- Server credentials or tokens

## Setup & Running

Prerequisites:

- `uv` (managed subproject environment)
- `ffmpeg` on PATH for YouTube audio normalization (16 kHz mono WAV)
- Local AI service running (e.g. `make ai` or `make ai-local` on `http://localhost:8000`)

### Run

```bash
# From repo root:
make lesson-prep

# Or directly in tools/lesson-prep:
uv run python -m lesson_prep.ui_app
```

Open http://127.0.0.1:7860 in your browser.

## Workflows

```text
AUDIO (Upload)      select audio -> inspect/validate -> Qwen STT -> edit transcript
                    -> Qwen alignment -> validate -> export *.lesson-source.zip

AUDIO (From text)   text -> LOCAL_KOKORO (voice/accent/speed) -> generate audio
                    -> Qwen alignment -> validate -> export *.lesson-source.zip

VIDEO (YouTube)     URL -> validate -> fetch metadata -> download thumbnail (WebP/JPEG)
                    -> yt-dlp + ffmpeg audio (16kHz mono WAV) -> Qwen STT -> edit
                    -> Qwen alignment -> validate -> export *.lesson-source.zip
```

### Safety & Invariants
- **Sentence & Word Alignment**: Uses monotonic lexical token stream mapping across all sentences,
  preventing word misalignment when punctuation or hyphenated words (`ice-cream`) are processed.
- **Timestamp Repair**: Zero-duration word intervals are repaired monotonically, and repair counts
  are audited in `preparation.alignment.repairedWordCount`.
- **Max Duration Limit**: Audio length is validated against a 5-minute (300 seconds) ceiling,
  matching the official capability boundary of `Qwen3-ForcedAligner-0.6B`.
- **Stale State Invalidation**: Any edit to the transcript immediately marks alignment stale and
  blocks export until re-aligned.
- **Workspace Isolation**: Each run executes in an isolated directory (`.work/<run-id>/`), and
  clearing a run will never delete another operator's files.
- **Filesystem Security**: Gradio `allowed_paths` is strictly constrained to the tool's own working
  and export directories.

## Tests

```bash
# Run tests via root Makefile:
make test-lesson-prep

# Or from tools/lesson-prep:
uv run --locked --extra dev python -m pytest
```
