# Lyreo Lesson Prep Tool

Operator/content-authoring tool: acquire, transcribe, align and export prepared Lyreo
lesson sources as one versioned `*.lesson-source.json` artifact. That JSON file is the
final deliverable of the current phase — importing it into the Lesson database is a
separate, later phase. Business orchestration lives in `lesson_prep/prep_service.py`;
UI callbacks only translate events.

## Responsibility boundary

```text
Lesson Prep Tool  -> calls AI Service over HTTP (STT/align/TTS/voices)
                  -> calls Core over HTTP (OIDC ADMIN login, canonical media upload)

AI Service        -> inference only; never receives YouTube URLs or Lesson DTOs
Core / Lesson     -> authentication, canonical media ownership (media upload for
                     prepared assets); no import persistence in this phase
```

The tool never touches PostgreSQL, R2/S3 or business repositories directly.

## Setup

Prerequisites:

- `uv` (project Python is managed per-subproject, never system Python)
- `ffmpeg` on PATH for YouTube audio normalization:
  - Fedora: `sudo dnf install ffmpeg-free`
  - Ubuntu/Debian: `sudo apt-get install ffmpeg`
- Local AI service with `LOCAL_QWEN` (STT/alignment) and optionally `LOCAL_KOKORO` (TTS)
- Local Core + Keycloak dev infra (see root `DEVELOPMENT.md`)

```bash
cp .env.example .env
# set AI_SERVICE_INTERNAL_TOKEN to the same value as apps/ai-service/.env
uv sync --locked
uv run python -m lesson_prep.ui_app
```

Open http://localhost:7860. Click **Login to Core (ADMIN via PKCE)**: a browser tab
opens against the `lyreo-lesson-prep` public Keycloak client, the tool exchanges the
authorization code with PKCE and Core validates the normal ADMIN JWT. No static admin
secret, no embedded client secret, no stored credentials.

## Workflows

```text
AUDIO (Upload)      select audio -> upload ONCE to Core -> Qwen STT -> edit transcript
                    -> Qwen alignment -> validate -> export JSON

AUDIO (From text)   text -> LOCAL_KOKORO (accent/voice/speed from discovery API)
                    -> preview -> upload ONCE -> Qwen alignment -> export JSON

VIDEO (YouTube)     URL -> validate -> metadata + thumbnail -> yt-dlp + ffmpeg audio
                    -> upload audio+thumbnail ONCE -> STT -> edit -> alignment -> export JSON
```

Transcript edits invalidate alignment; export stays blocked until alignment is rerun.
The exported JSON contains no base64 media, signed URLs, local absolute paths or secrets.
Assets are uploaded to Core storage ONCE before export; the operator only needs to keep
the resulting JSON file.

## Tests

```bash
uv run --locked --extra dev python -m pytest
```
