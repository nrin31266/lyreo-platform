"""Gradio operator UI for the Lesson Prep Tool.

A modern, clean developer workbench for media ingestion, speech synthesis,
word-level alignment, and schema-compliant package export.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import gradio as gr
from fastapi import FastAPI
from fastapi.responses import HTMLResponse

from .ai_service_client import AiServiceClient, AiServiceError
from .config import settings
from .core_api_client import CoreApiClient, CoreApiError, OidcClient
from .prep_service import LessonPrepService, PrepError
from .youtube import YoutubeError

cfg = settings()

ai = AiServiceClient(
    cfg.ai_service_url,
    cfg.ai_service_internal_token,
    timeout=cfg.ai_timeout_seconds,
)
oidc = OidcClient(
    cfg.keycloak_issuer_uri,
    cfg.lesson_prep_client_id,
    cfg.lesson_prep_redirect_uri,
)
core = CoreApiClient(cfg.core_api_url, oidc, timeout=cfg.ai_timeout_seconds)

fastapi_app = FastAPI(title="Lyreo Lesson Prep Studio")

MODE_KEYS = {
    "tts": "tts",
    "upload": "upload",
    "youtube": "youtube",
    "Generate from Text (TTS)": "tts",
    "Upload Audio File": "upload",
    "YouTube Video": "youtube",
}

MODE_DISPLAY_LABELS = {
    "tts": "Text to Speech (TTS)",
    "upload": "Upload Audio File",
    "youtube": "YouTube Video",
}

DEFAULT_US_VOICE = "af_heart"
DEFAULT_UK_VOICE = "bf_emma"

CUSTOM_CSS = """
/* Administrative Enterprise Square Theme — Clean, Flat, Functional */
*, *::before, *::after {
    border-radius: 2px !important;
}

.gradio-container {
    max-width: 98vw !important;
    width: 98% !important;
    margin: 0 auto !important;
    padding: 8px 14px !important;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif !important;
    background-color: #f8fafc !important;
}

/* Flat Top Navigation Bar */
.admin-header-row {
    background: #ffffff !important;
    border: 1px solid #cbd5e1 !important;
    padding: 6px 12px !important;
    margin-bottom: 10px !important;
    align-items: center !important;
}
.brand-block {
    padding: 2px 0 6px 0;
}
.brand-title {
    font-size: 0.95rem;
    font-weight: 800;
    color: #0f172a;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    white-space: nowrap;
    margin: 0;
    line-height: 1.2;
}
.brand-subtitle {
    font-size: 0.72rem;
    color: #64748b;
    margin: 2px 0 0 0;
    white-space: nowrap;
}

/* Compact Service Status Table in Header */
.service-status-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.73rem;
    background: #ffffff;
    border: 1px solid #cbd5e1;
}
.service-status-table th {
    background: #f1f5f9;
    color: #475569;
    padding: 3px 7px;
    font-weight: 700;
    border: 1px solid #cbd5e1;
    text-align: left;
    font-size: 0.69rem;
    text-transform: uppercase;
    letter-spacing: 0.02em;
}
.service-status-table td {
    padding: 3px 7px;
    border: 1px solid #e2e8f0;
    color: #1e293b;
    line-height: 1.2;
}
.service-status-table code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 0.70rem;
    background: #f8fafc;
    padding: 1px 4px;
    border: 1px solid #e2e8f0;
}

.status-indicator {
    display: inline-flex;
    align-items: center;
    font-weight: 700;
    font-size: 0.72rem;
}
.status-indicator.online {
    color: #15803d;
}
.status-indicator.offline {
    color: #b91c1c;
}
.status-indicator.warning {
    color: #b45309;
}

.static-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50% !important;
    margin-right: 5px;
}
.static-dot.green { background-color: #16a34a; }
.static-dot.red { background-color: #dc2626; }
.static-dot.amber { background-color: #d97706; }

/* Status Banners / Alerts */
.admin-alert {
    padding: 6px 12px;
    font-size: 0.82rem;
    font-weight: 500;
    border: 1px solid #cbd5e1;
    margin-bottom: 8px;
    border-radius: 2px !important;
}
.admin-alert.running {
    background: #eff6ff;
    border-color: #93c5fd;
    color: #1e40af;
}
.admin-alert.success {
    background: #f0fdf4;
    border-color: #86efac;
    color: #166534;
}
.admin-alert.error {
    background: #fef2f2;
    border-color: #fca5a5;
    color: #991b1b;
}
.admin-alert.idle {
    background: #f8fafc;
    border-color: #cbd5e1;
    color: #475569;
}

/* Data Tables */
.admin-data-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.80rem;
    background: #ffffff;
    border: 1px solid #cbd5e1;
    margin-bottom: 6px;
}
.admin-data-table th {
    background: #f1f5f9;
    color: #475569;
    padding: 5px 8px;
    font-weight: 700;
    border: 1px solid #cbd5e1;
    text-align: left;
    font-size: 0.72rem;
    text-transform: uppercase;
    letter-spacing: 0.02em;
}
.admin-data-table td {
    padding: 5px 8px;
    border: 1px solid #e2e8f0;
    color: #0f172a;
}

/* Suppress Gradio event loading progress overlays on components (never touch audio/media players) */
.progress-level,
.meta-text,
.eta-bar,
div.status-tracker,
div.progress-text {
    display: none !important;
    visibility: hidden !important;
    opacity: 0 !important;
    pointer-events: none !important;
    height: 0 !important;
}

/* Prevent inputs/components from fading or graying out when running */
.wrap.translucent, .translucent {
    opacity: 1 !important;
    filter: none !important;
}
"""


def extract_youtube_id(url: str) -> str | None:
    if not url:
        return None
    url = url.strip()
    patterns = [
        r"(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|embed/|v/|shorts/))([a-zA-Z0-9_-]{11})",
        r"^([a-zA-Z0-9_-]{11})$",
    ]
    for p in patterns:
        m = re.search(p, url)
        if m:
            return m.group(1)
    return None


def new_service() -> LessonPrepService:
    return LessonPrepService(ai, core, cfg.tool_work_dir)


@fastapi_app.get("/oidc/callback")
def oidc_callback(code: str | None = None, state: str | None = None, error: str | None = None):
    if error:
        return HTMLResponse(
            f"<h3>Login was cancelled</h3><p>error={error}</p>"
            "<p>Close this tab and return to the Lesson Prep Workspace.</p>"
        )
    if not code or not state:
        return HTMLResponse(
            "<h3>Callback is missing parameters</h3><p>Close this tab and try again.</p>"
        )
    try:
        oidc.complete(code, state)
        message = "Authentication successful"
    except CoreApiError as exc:
        message = f"Authentication failed: {exc}"
    return HTMLResponse(
        f"<h3>{message}</h3><p>You may close this tab and return to the Lesson Prep Workspace.</p>"
        "<script>setTimeout(function(){ window.close(); }, 800);</script>"
    )


# ------------------------------------------------------------------- dynamic voice discovery

def _fetch_voices() -> list[dict[str, Any]]:
    """Dynamically fetches supported voices from AI service API (GET /v1/tts/voices)."""
    try:
        remote = ai.voices()
        if remote and isinstance(remote, list):
            return remote
    except Exception:
        pass
    return []


def _get_voice_choices(accent: str = "US") -> tuple[list[str], str]:
    """Filters dynamically retrieved voices by accent."""
    voices = _fetch_voices()
    target_accent = (accent or "US").upper()
    filtered = [
        str(v["voice_id"])
        for v in voices
        if str(v.get("accent", "")).upper() == target_accent and v.get("voice_id")
    ]
    if not filtered:
        filtered = [str(v["voice_id"]) for v in voices if v.get("voice_id")]
    filtered = sorted(set(filtered))
    default_voice = DEFAULT_US_VOICE if target_accent == "US" else DEFAULT_UK_VOICE
    value = default_voice if default_voice in filtered else (filtered[0] if filtered else "af_heart")
    return filtered, value


# ------------------------------------------------------------------- service status

def connection_status() -> str:
    ai_ok = False
    ai_detail = "ASR, Aligner & TTS"
    try:
        voices = ai.voices()
        ai_ok = True
        ai_detail = f"Online ({len(voices)} Kokoro voices)"
    except Exception as exc:
        ai_ok = False
        s = str(exc)
        ai_detail = f"Offline ({s[:28]}...)" if len(s) > 28 else f"Offline ({s})"

    core_ok = False
    core_detail = "Storage & Lesson API"
    try:
        core_ok = core.health()
        if not core_ok:
            core_detail = "Core API unreachable"
        else:
            core_detail = "Storage & Lesson API ready"
    except Exception as exc:
        core_ok = False
        s = str(exc)
        core_detail = f"Offline ({s[:28]}...)" if len(s) > 28 else f"Offline ({s})"

    auth_ok = bool(oidc._access_token)
    auth_detail = "Token Active (Role: ADMIN)" if auth_ok else "Not authenticated (Click 'Login (PKCE)')"

    ai_dot = "green" if ai_ok else "red"
    ai_status = "ONLINE" if ai_ok else "OFFLINE"
    ai_cls = "online" if ai_ok else "offline"

    core_dot = "green" if core_ok else "red"
    core_status = "ONLINE" if core_ok else "OFFLINE"
    core_cls = "online" if core_ok else "offline"

    auth_dot = "green" if auth_ok else "amber"
    auth_status = "ADMIN" if auth_ok else "UNAUTHENTICATED"
    auth_cls = "online" if auth_ok else "warning"

    ai_url = cfg.ai_service_url
    core_url = cfg.core_api_url
    auth_url = cfg.keycloak_issuer_uri

    return f"""
    <table class="service-status-table">
      <thead>
        <tr>
          <th style="width: 20%;">Component</th>
          <th style="width: 32%;">Target Endpoint URL</th>
          <th style="width: 18%;">Status</th>
          <th style="width: 30%;">Details</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><strong>AI Service</strong></td>
          <td><code>{ai_url}</code></td>
          <td><span class="status-indicator {ai_cls}"><span class="static-dot {ai_dot}"></span>{ai_status}</span></td>
          <td style="color: #64748b; font-size: 0.70rem;">{ai_detail}</td>
        </tr>
        <tr>
          <td><strong>Core API</strong></td>
          <td><code>{core_url}</code></td>
          <td><span class="status-indicator {core_cls}"><span class="static-dot {core_dot}"></span>{core_status}</span></td>
          <td style="color: #64748b; font-size: 0.70rem;">{core_detail}</td>
        </tr>
        <tr>
          <td><strong>Keycloak OIDC</strong></td>
          <td><code>{auth_url}</code></td>
          <td><span class="status-indicator {auth_cls}"><span class="static-dot {auth_dot}"></span>{auth_status}</span></td>
          <td style="color: #64748b; font-size: 0.70rem;">{auth_detail}</td>
        </tr>
      </tbody>
    </table>
    """


def do_login() -> str:
    try:
        if oidc.login():
            return "Logged in to Core as ADMIN (JWT token acquired via PKCE)"
        return "Login did not complete within timeout (tab closed or cancelled)"
    except CoreApiError as exc:
        return str(exc)


def on_login(svc: LessonPrepService) -> tuple[str, str, Any]:
    msg = do_login()
    auth_ok = bool(oidc._access_token)
    return f"```text\n{msg}\n```", connection_status(), gr.update(interactive=auth_ok)


def on_status_refresh(svc: LessonPrepService) -> tuple[str, Any]:
    auth_ok = bool(oidc._access_token)
    return connection_status(), gr.update(interactive=auth_ok)


def on_accent_change(selected_accent: str):
    choices, value = _get_voice_choices(selected_accent)
    return gr.update(choices=choices, value=value)


def on_refresh_voices(svc: LessonPrepService, accent: str):
    choices, value = _get_voice_choices(accent)
    return gr.update(choices=choices, value=value)




def on_youtube_url_change(url: str) -> str:
    vid = extract_youtube_id(url)
    if vid:
        return f"""
        <div style="border: 1px solid #cbd5e1; padding: 8px 10px; background: #ffffff; margin-top: 4px; border-radius: 2px;">
          <div style="font-size: 0.72rem; font-weight: 700; color: #2563eb; margin-bottom: 4px; text-transform: uppercase;">
            YouTube Preview
          </div>
          <div style="display: flex; gap: 10px; align-items: center;">
            <img src="https://img.youtube.com/vi/{vid}/hqdefault.jpg" style="height: 60px; width: 80px; object-fit: cover; border-radius: 2px; border: 1px solid #cbd5e1;" alt="Thumbnail" />
            <div style="font-size: 0.78rem; color: #1e293b;">
              <div><strong>Video ID:</strong> <code style="font-family:monospace; background:#f1f5f9; padding:1px 5px; border:1px solid #cbd5e1;">{vid}</code></div>
              <div style="margin-top: 2px; color: #64748b; font-size: 0.74rem;">Audio and thumbnail will be extracted upon processing.</div>
            </div>
          </div>
        </div>
        """
    return """
    <div style="border: 1px dashed #cbd5e1; padding: 6px 10px; background: #ffffff; margin-top: 4px; font-size: 0.76rem; color: #64748b; border-radius: 2px;">
      <em>Paste a valid YouTube link above to preview video thumbnail.</em>
    </div>
    """


# ---------------------------------------------------------------- status banner

def _render_status_banner(state: str, text: str) -> str:
    if state == "running":
        return f"""
        <div class="admin-alert running">
          ⏳ <strong>PROCESSING:</strong> {text}
        </div>
        """
    elif state == "success":
        return f"""
        <div class="admin-alert success">
          ✓ <strong>COMPLETED:</strong> {text}
        </div>
        """
    elif state == "error":
        return f"""
        <div class="admin-alert error">
          ✕ <strong>ERROR:</strong> {text}
        </div>
        """
    else:  # idle
        return f"""
        <div class="admin-alert idle">
          ⚡ <strong>READY:</strong> {text}
        </div>
        """


# ---------------------------------------------------------------- review helpers

def _render_metrics_empty() -> str:
    return """
    <div style="border: 1px dashed #cbd5e1; padding: 10px; text-align: center; color: #64748b; background: #ffffff; font-size: 0.80rem;">
      No processed data yet. Select a source on the left and click <strong>"⚡ Process Source Audio"</strong>.
    </div>
    """


def _render_metrics_table(svc: LessonPrepService) -> str:
    if not svc.state.sentences:
        return _render_metrics_empty()

    total_words = sum(len(s.words) for s in svc.state.sentences)
    total_sentences = len(svc.state.sentences)
    first_start = svc.state.sentences[0].start_ms or 0
    last_end = svc.state.sentences[-1].end_ms or 0
    duration_s = max(0.0, (last_end - first_start) / 1000.0)
    wpm = round((total_words / (duration_s / 60.0))) if duration_s > 0.5 else 0
    ready, problems = svc.export_readiness()

    status_tag = '<span style="color:#16a34a; font-weight:700;">● VALID (PASSED)</span>' if ready else f'<span style="color:#dc2626; font-weight:700;">● ERROR ({len(problems)} issues)</span>'
    audio_key = svc.state.audio_object_key or "not_uploaded"
    sha = svc.state.audio_sha256 or "none"
    mode_str = (svc.state.mode or "unknown").upper()

    return f"""
    <div>
      <table class="admin-data-table">
        <thead>
          <tr>
            <th style="width: 16%;">Duration</th>
            <th style="width: 16%;">Total Words</th>
            <th style="width: 16%;">Sentences</th>
            <th style="width: 16%;">Reading Speed</th>
            <th style="width: 18%;">Source Mode</th>
            <th style="width: 18%;">Schema Status</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td><strong>{duration_s:.2f}s</strong></td>
            <td><strong>{total_words} words</strong></td>
            <td><strong>{total_sentences} sentences</strong></td>
            <td><strong>{wpm} WPM</strong></td>
            <td><code>{mode_str}</code></td>
            <td>{status_tag}</td>
          </tr>
        </tbody>
      </table>
      <table class="admin-data-table" style="margin-top:-2px;">
        <tbody>
          <tr>
            <td style="width: 20%; background:#f8fafc; font-weight:600;">Object Key:</td>
            <td style="width: 30%;"><code>{audio_key}</code></td>
            <td style="width: 18%; background:#f8fafc; font-weight:600;">SHA-256 Digest:</td>
            <td style="width: 32%;"><code>{sha}</code></td>
          </tr>
        </tbody>
      </table>
    </div>
    """


def _sentence_rows(svc: LessonPrepService) -> list[list[object]]:
    def fmt_time(ms: int | None) -> str:
        if ms is None:
            return "--"
        s = ms / 1000.0
        return f"{s:.2f}s"

    rows = []
    for s in svc.state.sentences:
        dur = (
            ((s.end_ms - s.start_ms) / 1000.0)
            if s.start_ms is not None and s.end_ms is not None
            else 0.0
        )
        rows.append([
            s.position + 1,
            s.text,
            fmt_time(s.start_ms),
            fmt_time(s.end_ms),
            f"{dur:.2f}s",
            len(s.words),
        ])
    return rows


def _export_preview(svc: LessonPrepService) -> dict | None:
    try:
        return svc.build_export().export_dict()
    except PrepError:
        return None


def _render_log(lines: list[str]) -> str:
    body = "\n".join(lines) if lines else "[Ready. Configure source on the left and click 'Process Source Audio']"
    return f"```text\n{body}\n```"


# ---------------------------------------------------------------- unified pipeline

def make_prepare_pipeline(
    prepare_btn,
    clear_btn,
    realign_btn,
    export_btn,
    status_banner,
    log,
    audio_preview,
    transcript_box,
    title_resolved,
    metrics_table,
    review_rows,
    review_code,
    review_json,
    export_file,
    tts_text,
    audio_file,
    youtube_url,
):
    def prepare_pipeline(
        svc: LessonPrepService,
        mode_input: str,
        upload_path: str | None,
        upload_title: str,
        tts_text_val: str,
        voice: str,
        accent: str,
        speed: float,
        tts_title: str,
        youtube_url_val: str,
        youtube_title: str,
        existing_transcript: str,
        export_dir_str: str,
    ):
        mode = MODE_KEYS.get(mode_input, "tts")
        mode_label = MODE_DISPLAY_LABELS.get(mode, "Text to Speech (TTS)")
        lines: list[str] = []
        audio_file_path: str | None = None
        transcript_value: str = ""
        resolved_title: str = ""

        # Step 0: Lock controls and disable form inputs immediately
        yield (
            gr.update(value="⏳ Processing...", interactive=False),
            gr.update(interactive=False),
            gr.update(interactive=False),
            gr.update(interactive=False),
            _render_status_banner("running", "Initializing pipeline and validating service connections..."),
            _render_log(lines),
            gr.skip(),
            gr.update(interactive=False),
            gr.skip(),
            gr.skip(),
            gr.skip(),
            gr.skip(),
            gr.skip(),
            gr.skip(),
            gr.update(interactive=False),
            gr.update(interactive=False),
            gr.update(interactive=False),
        )

        # -------------------------------------------------- Precondition Checks
        if not oidc._access_token:
            lines.append("[ERROR] Not logged in to Core. Click 'Login (PKCE)' above.")
            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=False),
                gr.update(interactive=False),
                _render_status_banner("error", "Not logged in to Core backend. Click 'Login (PKCE)' above."),
                _render_log(lines),
                gr.skip(),
                gr.update(interactive=False),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
            )
            return

        if not core.health():
            lines.append(f"[ERROR] Core backend unreachable at {cfg.core_api_url}. Start with 'make core'.")
            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=False),
                gr.update(interactive=False),
                _render_status_banner("error", f"Core backend unreachable at {cfg.core_api_url}."),
                _render_log(lines),
                gr.skip(),
                gr.update(interactive=False),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
            )
            return

        ai_live = False
        try:
            ai.voices()
            ai_live = True
        except Exception:
            ai_live = False

        if not ai_live:
            lines.append(f"[ERROR] AI service unreachable at {cfg.ai_service_url}. Start with 'make ai'.")
            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=False),
                gr.update(interactive=False),
                _render_status_banner("error", f"AI service unreachable at {cfg.ai_service_url}."),
                _render_log(lines),
                gr.skip(),
                gr.update(interactive=False),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.skip(),
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
            )
            return

        # Resolve title preference
        if mode == "tts":
            resolved_title = (tts_title or "").strip()
            if not resolved_title and tts_text_val.strip():
                first_line = tts_text_val.strip().split("\n")[0].strip()
                resolved_title = first_line[:50]
        elif mode == "upload":
            resolved_title = (upload_title or "").strip()
            if not resolved_title and upload_path:
                resolved_title = Path(upload_path).stem.replace("-", " ").replace("_", " ").title()
        else:
            resolved_title = (youtube_title or "").strip()

        try:
            # -------------------------------------------------- Step 1: Acquire source
            lines.append(f"[Step 1/5] Acquiring source audio ({mode_label})...")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", f"Step 1/5: Acquiring source audio ({mode_label})..."),
                _render_log(lines),
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            if mode == "tts":
                text = (tts_text_val or "").strip()
                if not text:
                    raise PrepError("Please enter English dialogue or text to synthesize.")
                local_audio = svc.generate_audio_from_text(
                    text,
                    voice=voice or DEFAULT_US_VOICE,
                    accent=accent or "US",
                    speed=float(speed or 1.0),
                )
                transcript_value = svc.state.transcript
                lines.append(f"  -> Synthesized speech with Kokoro TTS ({voice}, {speed}x)")
            elif mode == "upload":
                if not upload_path:
                    raise PrepError("Please select an audio file to upload.")
                local_audio = svc.select_uploaded_audio(upload_path)
                lines.append(f"  -> Loaded audio file: {local_audio.name}")
            else:
                yt_url = (youtube_url_val or "").strip()
                if not yt_url:
                    raise PrepError("Please enter a valid YouTube URL.")
                meta = svc.prepare_youtube(yt_url)
                if not resolved_title:
                    resolved_title = meta.title
                local_audio = svc.state.local_audio_path
                lines.append(f"  -> Extracted YouTube audio & thumbnail: '{meta.title}'")

            audio_file_path = str(Path(local_audio).resolve())
            svc.state.title = resolved_title or "Lesson"

            # AUDIO READY -> update audio preview immediately so user can listen right away!
            lines.append("  [OK] Audio acquisition complete.")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 1/5 complete. Audio acquired. Proceeding to Step 2/5 (Storage Upload)..."),
                _render_log(lines),
                audio_file_path,  # audio_preview updates here
                transcript_value if transcript_value else gr.skip(),
                resolved_title,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            # -------------------------------------------------- Step 2: Upload canonical assets
            lines.append("[Step 2/5] Uploading canonical media to Core storage...")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 2/5: Uploading canonical media to Core storage..."),
                _render_log(lines),
                audio_file_path,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            audio_status = svc.upload_canonical_audio()
            lines.append(f"  -> Object Key: {audio_status['objectKey']}")
            lines.append(f"  -> SHA-256: {audio_status['sha256']}")
            if svc.state.thumbnail_path:
                thumb = svc.upload_thumbnail()
                if thumb:
                    lines.append(f"  -> Thumbnail Key: {thumb['objectKey']}")
            lines.append("  [OK] Canonical storage upload complete.")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 2/5 complete. Proceeding to Step 3/5 (Speech Transcription)..."),
                _render_log(lines),
                audio_file_path,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            # -------------------------------------------------- Step 3: Transcription (STT)
            lines.append("[Step 3/5] Loading / transcribing speech...")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 3/5: Loading / transcribing speech with Qwen ASR..."),
                _render_log(lines),
                audio_file_path,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            if mode == "tts":
                lines.append("  -> Transcript ready from synthesized dialogue.")
            else:
                stt_res = svc.run_stt()
                transcript_value = stt_res["text"]
                lines.append(f"  -> Transcribed with Qwen ASR ({len(transcript_value.split())} words).")

            # TRANSCRIPT READY -> update transcript_box & title_resolved
            lines.append("  [OK] Transcript ready.")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 3/5 complete. Proceeding to Step 4/5 (Forced Alignment)..."),
                _render_log(lines),
                audio_file_path,
                transcript_value,  # transcript_box updates here
                resolved_title,    # title_resolved updates here
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            # -------------------------------------------------- Step 4: Forced Alignment
            lines.append("[Step 4/5] Running forced alignment for word timestamps with Qwen...")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 4/5: Running Qwen forced alignment for word-level timestamps..."),
                _render_log(lines),
                audio_file_path,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            svc.set_transcript(transcript_value)
            words = svc.run_alignment()
            lines.append(f"  -> Aligned {len(words)} words across {len(svc.state.sentences)} sentences.")

            # TIMELINE READY -> update metrics & rows, enable realign_btn
            lines.append("  [OK] Alignment complete.")
            yield (
                gr.skip(),
                gr.skip(),
                gr.update(interactive=True),  # realign_btn enabled
                gr.skip(),
                _render_status_banner("running", "Step 4/5 complete. Proceeding to Step 5/5 (Validation)..."),
                _render_log(lines),
                audio_file_path,
                transcript_value,
                resolved_title,
                _render_metrics_table(svc),  # metrics_table updates here
                _sentence_rows(svc),         # review_rows updates here
                gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            # -------------------------------------------------- Step 5: Validate (No auto-export)
            lines.append("[Step 5/5] Validating schema compliance...")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 5/5: Validating package schema compliance..."),
                _render_log(lines),
                audio_file_path,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            ready, problems = svc.export_readiness()
            if not ready:
                raise PrepError("Export validation failed: " + "; ".join(problems))

            preview_dict = _export_preview(svc)
            preview_code_str = (
                json.dumps(preview_dict, indent=2, ensure_ascii=False)
                if preview_dict
                else ""
            )

            lines.append("  [OK] Schema validation PASSED.")
            lines.append("")
            lines.append("[COMPLETE] Source prepared and verified.")
            lines.append("-> Review audio player, sentence breakdown, and JSON preview on the right.")
            lines.append("-> Click 'Export Package' below to write package to disk.")

            target_folder = export_dir_str.strip() or str(cfg.tool_export_dir)
            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),  # export_btn enabled!
                _render_status_banner("success", f"Source prepared and validated successfully! Ready to export to {target_folder}."),
                _render_log(lines),
                audio_file_path,
                gr.update(value=transcript_value, interactive=True),  # transcript_box editable
                resolved_title,
                _render_metrics_table(svc),
                _sentence_rows(svc),
                preview_code_str,
                preview_dict,
                None,  # export_file
                gr.update(interactive=True),  # tts_text
                gr.update(interactive=True),  # audio_file
                gr.update(interactive=True),  # youtube_url
            )

        except (PrepError, AiServiceError, CoreApiError, YoutubeError, ValueError) as exc:
            err_msg = str(exc) or exc.__class__.__name__
            lines.append(f"[ERROR] {err_msg}")
            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=bool(svc.state.sentences)),
                gr.update(interactive=False),
                _render_status_banner("error", f"Processing Error: {err_msg}"),
                _render_log(lines),
                audio_file_path if audio_file_path else gr.skip(),
                gr.update(interactive=bool(transcript_value)),
                resolved_title if resolved_title else gr.skip(),
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                None,
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
            )
            return
        except Exception as exc:
            err_msg = f"Unexpected error ({type(exc).__name__}): {exc}"
            lines.append(f"[ERROR] {err_msg}")
            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=bool(svc.state.sentences)),
                gr.update(interactive=False),
                _render_status_banner("error", f"Unexpected System Error: {err_msg}"),
                _render_log(lines),
                audio_file_path if audio_file_path else gr.skip(),
                gr.update(interactive=bool(transcript_value)),
                resolved_title if resolved_title else gr.skip(),
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                None,
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
            )
            return

    return prepare_pipeline


def run_realign(
    svc: LessonPrepService,
    editor_text: str,
    title: str,
    export_dir_str: str,
):
    """Re-runs alignment with user's edited transcript (does not re-TTS, audio stays intact)."""
    lines: list[str] = []
    target_folder = export_dir_str.strip() or str(cfg.tool_export_dir)

    cleaned_text = (editor_text or "").strip()
    if not cleaned_text:
        lines.append("[ERROR] Re-alignment blocked: Transcript text cannot be empty.")
        yield (
            gr.update(value="🔄 Sync Timestamps with Transcript", interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=False),
            _render_status_banner("error", "Transcript text cannot be empty."),
            _render_log(lines),
            gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
            gr.update(interactive=True),
        )
        return

    if not svc.state.local_audio_path or not svc.state.audio_object_key:
        lines.append("[ERROR] Re-alignment blocked: No canonical audio loaded. Process source first.")
        yield (
            gr.update(value="🔄 Sync Timestamps with Transcript", interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=False),
            _render_status_banner("error", "No canonical audio loaded yet. Please process a source first."),
            _render_log(lines),
            gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
            gr.update(interactive=True),
        )
        return

    try:
        # Lock buttons
        yield (
            gr.update(value="⏳ Syncing...", interactive=False),
            gr.update(interactive=False),
            gr.update(interactive=False),
            gr.update(interactive=False),
            _render_status_banner("running", "Computing updated timestamps for edited transcript..."),
            _render_log(lines),
            gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
            gr.update(interactive=False),
        )

        lines.append(f"[Re-align] Reading updated transcript ({len(cleaned_text.split())} words)...")
        cleaned_title = (title or "").strip()
        if cleaned_title:
            svc.state.title = cleaned_title

        lines.append("[Re-align] Running Qwen acoustic alignment (Audio preserved 100% intact, NO TTS)...")
        svc.set_transcript(cleaned_text)
        words = svc.run_alignment()
        lines.append(f"  -> Computed timestamps for {len(words)} words across {len(svc.state.sentences)} sentences.")

        ready, problems = svc.export_readiness()
        if not ready:
            err = "; ".join(problems)
            lines.append(f"[ERROR] Validation failed: {err}")
            yield (
                gr.update(value="🔄 Sync Timestamps with Transcript", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=False),
                _render_status_banner("error", f"Validation failed: {err}"),
                _render_log(lines),
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.update(interactive=True),
            )
            return

        preview_dict = _export_preview(svc)
        preview_code = (
            json.dumps(preview_dict, indent=2, ensure_ascii=False)
            if preview_dict
            else ""
        )

        lines.append("  [OK] Word timestamps synchronized successfully with edited text.")
        lines.append("  [OK] Schema compliance PASS. Ready for export.")

        yield (
            gr.update(value="🔄 Sync Timestamps with Transcript", interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=True),  # export_btn is enabled
            _render_status_banner("success", f"Timestamps synchronized successfully with edited text! Ready to export to {target_folder}."),
            _render_log(lines),
            _render_metrics_table(svc),  # metrics_table updates here
            _sentence_rows(svc),         # review_rows updates here
            preview_code,                # review_code updates here
            preview_dict,                # review_json updates here
            None,                        # export_file
            gr.update(interactive=True), # transcript_box
        )

    except (PrepError, AiServiceError, CoreApiError, ValueError) as exc:
        err_msg = str(exc) or exc.__class__.__name__
        lines.append(f"[ERROR] {err_msg}")
        yield (
            gr.update(value="🔄 Sync Timestamps with Transcript", interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=False),
            _render_status_banner("error", f"Sync Error: {err_msg}"),
            _render_log(lines),
            gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
            gr.update(interactive=True),
        )
    except Exception as exc:
        err_msg = f"Unexpected error: {exc}"
        lines.append(f"[ERROR] {err_msg}")
        yield (
            gr.update(value="🔄 Sync Timestamps with Transcript", interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=False),
            _render_status_banner("error", f"System Error: {err_msg}"),
            _render_log(lines),
            gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
            gr.update(interactive=True),
        )


def run_export(
    svc: LessonPrepService,
    export_dir_str: str,
):
    """Explicit export button to write the artifact to disk on demand."""
    ready, problems = svc.export_readiness()
    if not ready:
        msg = f"[ERROR] Export blocked: {'; '.join(problems)}"
        return None, _render_status_banner("error", f"Export failed: {'; '.join(problems)}"), _render_log([msg])

    try:
        raw_dir = export_dir_str.strip() or str(cfg.tool_export_dir)
        dest_dir = Path(raw_dir).expanduser().resolve()
        dest_dir.mkdir(parents=True, exist_ok=True)

        target = svc.write_export_file(dest_dir)

        msg = (
            f"[EXPORT SUCCESS]\n"
            f"File: {target.name}\n"
            f"Destination: {target.parent}\n"
            f"Size: {target.stat().st_size} bytes"
        )
        return str(target), _render_status_banner("success", f"Package exported successfully to {target.name}!"), _render_log([msg])
    except (PrepError, OSError) as exc:
        msg = f"[EXPORT ERROR] {exc}"
        return None, _render_status_banner("error", f"Export error: {exc}"), _render_log([msg])


def run_clear():
    """Resets workspace state, purges temporary files on disk, and resets outputs."""
    fresh_svc = new_service()
    deleted = fresh_svc.clear_work_dir()
    msg = f"[RESET] Workspace cleared. Purged {deleted} temporary file(s) from disk."
    return (
        fresh_svc,
        gr.update(selected="tts"),  # source_tabs
        "tts",                      # mode_state
        None,                       # audio_file
        "",                         # upload_title
        "",                         # tts_text
        "",                         # tts_title
        "",                         # youtube_url
        "",                         # youtube_title
        on_youtube_url_change(""),  # youtube_preview_html
        _render_status_banner("idle", "Workspace reset. Select a source on the left to begin."),
        _render_log([msg]),         # log
        None,                       # audio_preview
        gr.update(value="", interactive=False),  # transcript_box
        "",                         # title_resolved
        _render_metrics_empty(),    # metrics_table
        [],                         # review_rows
        "",                         # review_code
        None,                       # review_json
        None,                       # export_file
        gr.update(interactive=False),  # realign_btn
        gr.update(interactive=False),  # export_btn
    )


# ---------------------------------------------------------------- UI Builder

def build_ui() -> gr.Blocks:
    us_choices, us_default = _get_voice_choices("US")
    detected_downloads_path = str(cfg.tool_export_dir)
    auth_initial = bool(oidc._access_token)

    with gr.Blocks(title="Lyreo Lesson Studio", css=CUSTOM_CSS, fill_width=True) as demo:
        # Flat Top App Bar with Branding and Services Status Table
        with gr.Row(elem_classes=["admin-header-row"]):
            with gr.Column(scale=3, min_width=240):
                gr.HTML("""
                <div class="brand-block">
                  <div class="brand-title">LYREO PREP WORKBENCH</div>
                  <div class="brand-subtitle">Media Ingestion, TTS & Alignment</div>
                </div>
                """)
                with gr.Row():
                    login_button = gr.Button("🔑 Login (PKCE)", variant="primary", size="sm", scale=2)
                    status_refresh = gr.Button("⟳ Check", variant="secondary", size="sm", scale=1)
            with gr.Column(scale=7, min_width=520):
                status_box = gr.HTML(connection_status())

        # Main 2-Column Studio Layout (Equal 50/50 split)
        with gr.Row(equal_height=False):
            # ------------------------------------------------ Left Column: Ingestion & Controls (50%)
            with gr.Column(scale=1, min_width=420):
                gr.Markdown("### 1. Source & Configuration")
                mode_state = gr.Textbox(value="tts", visible=False)
                with gr.Tabs() as source_tabs:
                    with gr.Tab("🎙️ Text to Speech (TTS)", id="tts") as tab_tts:
                        tts_text = gr.Textbox(
                            lines=6,
                            label="English Text *",
                            placeholder="Enter or paste English dialogue or passage to synthesize...",
                        )
                        with gr.Row():
                            accent_dd = gr.Dropdown(["US", "UK"], value="US", label="Accent", scale=1)
                            voice_dd = gr.Dropdown(
                                choices=us_choices,
                                value=us_default,
                                allow_custom_value=True,
                                label="Kokoro Voice",
                                scale=2,
                            )
                            speed_sl = gr.Slider(0.5, 2.0, value=1.0, step=0.1, label="Speed", scale=2)
                            refresh_voices_btn = gr.Button("⟳ Voice", scale=0, size="sm")
                        tts_title = gr.Textbox(
                            label="Lesson Title (Optional)",
                            placeholder="Default: First line of text if left blank",
                        )

                    with gr.Tab("📁 Upload Audio File", id="upload") as tab_upload:
                        audio_file = gr.Audio(
                            type="filepath",
                            sources=["upload"],
                            label="Audio File * (WAV, MP3, M4A, OGG, FLAC)",
                        )
                        upload_title = gr.Textbox(
                            label="Lesson Title (Optional)",
                            placeholder="Default: File name if left blank",
                        )

                    with gr.Tab("▶️ YouTube Video", id="youtube") as tab_youtube:
                        youtube_url = gr.Textbox(
                            label="YouTube URL *",
                            placeholder="https://www.youtube.com/watch?v=...",
                        )
                        youtube_preview_html = gr.HTML(on_youtube_url_change(""))
                        youtube_title = gr.Textbox(
                            label="Lesson Title (Optional)",
                            placeholder="Default: YouTube video title if left blank",
                        )

                gr.Markdown("### 2. Pipeline Execution")
                status_banner = gr.HTML(_render_status_banner("idle", "Ready. Select a source above and click Process."))
                with gr.Row():
                    prepare_button = gr.Button("⚡ Process Source Audio", variant="primary", size="lg", scale=3, interactive=auth_initial)
                    clear_button = gr.Button("🗑️ Reset Workspace", variant="secondary", size="lg", scale=2)

                with gr.Accordion("Execution Details Log", open=True):
                    log = gr.Markdown(_render_log([]))

            # ------------------------------------------------ Right Column: Inspection & Package (50%)
            with gr.Column(scale=1, min_width=420):
                gr.Markdown("### 3. Review & Alignment")

                # Metrics summary row (Table format)
                metrics_table = gr.HTML(_render_metrics_empty())

                # Canonical audio player
                audio_preview = gr.Audio(
                    type="filepath",
                    interactive=False,
                    label="Canonical Audio Player",
                    show_download_button=True,
                )

                # Lesson Title
                title_resolved = gr.Textbox(
                    label="Lesson Title",
                    lines=1,
                    placeholder="Lesson title will appear here after processing...",
                )

                # Editable transcript (initially disabled until transcript is loaded)
                transcript_box = gr.Textbox(
                    label="Transcript (Editable)",
                    lines=5,
                    interactive=False,
                    placeholder="Transcript text will appear here after processing. You can correct words and click Sync below.",
                )

                # Re-align Button & Helper Note (initially disabled until alignment finishes)
                realign_btn = gr.Button(
                    "🔄 Sync Timestamps with Transcript",
                    variant="secondary",
                    interactive=False,
                )
                gr.HTML(
                    '<div style="font-size:0.76rem; color:#64748b; margin-top:1px;">'
                    '💡 <em>Audio is preserved 100% (NO TTS re-synthesis). Only word timestamps are re-aligned.</em>'
                    '</div>'
                )

                # Inspection Tabs: Timeline, Tree View, Formatted JSON
                with gr.Tabs():
                    with gr.TabItem("📋 Sentence Timeline"):
                        review_rows = gr.Dataframe(
                            headers=["No.", "Sentence Text", "Start", "End", "Duration", "Words"],
                            datatype=["number", "str", "str", "str", "str", "number"],
                            interactive=False,
                            wrap=True,
                        )
                    with gr.TabItem("🌲 JSON Tree View"):
                        review_json = gr.JSON(label="Interactive Tree View")
                    with gr.TabItem("📄 Formatted JSON"):
                        review_code = gr.Code(
                            language="json",
                            label="Formatted *.lesson-source.json",
                            lines=16,
                        )

                gr.Markdown("### 4. Package Export")
                with gr.Row():
                    export_dir_input = gr.Textbox(
                        label="Export Directory",
                        value=detected_downloads_path,
                        scale=8,
                    )
                    export_btn = gr.Button("💾 Export Package (.json)", variant="primary", scale=4, interactive=False)

                export_file = gr.File(label="Download Package (.json)", interactive=False)

        state = gr.State(new_service)

        # Event Handlers with show_progress="hidden" to suppress all loading overlays
        tab_tts.select(lambda: "tts", outputs=mode_state, show_progress="hidden")
        tab_upload.select(lambda: "upload", outputs=mode_state, show_progress="hidden")
        tab_youtube.select(lambda: "youtube", outputs=mode_state, show_progress="hidden")

        youtube_url.change(
            on_youtube_url_change,
            inputs=[youtube_url],
            outputs=[youtube_preview_html],
            show_progress="hidden",
        )
        accent_dd.change(
            on_accent_change,
            inputs=[accent_dd],
            outputs=[voice_dd],
            show_progress="hidden",
        )
        login_button.click(
            on_login,
            inputs=[state],
            outputs=[log, status_box, prepare_button],
            show_progress="hidden",
        )
        status_refresh.click(
            on_status_refresh,
            inputs=[state],
            outputs=[status_box, prepare_button],
            show_progress="hidden",
        )
        refresh_voices_btn.click(
            on_refresh_voices,
            inputs=[state, accent_dd],
            outputs=[voice_dd],
            show_progress="hidden",
        )

        prepare_button.click(
            make_prepare_pipeline(
                prepare_button,
                clear_button,
                realign_btn,
                export_btn,
                status_banner,
                log,
                audio_preview,
                transcript_box,
                title_resolved,
                metrics_table,
                review_rows,
                review_code,
                review_json,
                export_file,
                tts_text,
                audio_file,
                youtube_url,
            ),
            inputs=[
                state,
                mode_state,
                audio_file,
                upload_title,
                tts_text,
                voice_dd,
                accent_dd,
                speed_sl,
                tts_title,
                youtube_url,
                youtube_title,
                transcript_box,
                export_dir_input,
            ],
            outputs=[
                prepare_button,
                clear_button,
                realign_btn,
                export_btn,
                status_banner,
                log,
                audio_preview,
                transcript_box,
                title_resolved,
                metrics_table,
                review_rows,
                review_code,
                review_json,
                export_file,
                tts_text,
                audio_file,
                youtube_url,
            ],
            show_progress="hidden",
        )

        realign_btn.click(
            run_realign,
            inputs=[state, transcript_box, title_resolved, export_dir_input],
            outputs=[
                realign_btn,
                prepare_button,
                clear_button,
                export_btn,
                status_banner,
                log,
                metrics_table,
                review_rows,
                review_code,
                review_json,
                export_file,
                transcript_box,
            ],
            show_progress="hidden",
        )

        export_btn.click(
            run_export,
            inputs=[state, export_dir_input],
            outputs=[export_file, status_banner, log],
            show_progress="hidden",
        )

        clear_button.click(
            run_clear,
            inputs=[],
            outputs=[
                state,
                source_tabs,
                mode_state,
                audio_file,
                upload_title,
                tts_text,
                tts_title,
                youtube_url,
                youtube_title,
                youtube_preview_html,
                status_banner,
                log,
                audio_preview,
                transcript_box,
                title_resolved,
                metrics_table,
                review_rows,
                review_code,
                review_json,
                export_file,
                realign_btn,
                export_btn,
            ],
            show_progress="hidden",
        )

    return demo


repo_root = Path(__file__).resolve().parents[2]
demo = build_ui()
app = gr.mount_gradio_app(
    fastapi_app,
    demo,
    path="/",
    allowed_paths=[
        str(Path.home()),
        "/tmp",
        str(cfg.tool_work_dir.resolve()),
        str(cfg.tool_export_dir.resolve()),
        str(repo_root),
    ],
)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=7860, timeout_graceful_shutdown=1)
