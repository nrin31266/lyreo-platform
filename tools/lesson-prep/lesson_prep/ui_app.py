"""Gradio operator UI for the Lesson Prep Tool.

A clean developer workbench for media ingestion, speech synthesis,
word-level alignment, and portable lesson package export (*.lesson-source.zip).
"""

from __future__ import annotations

import json
import re
import shutil
from pathlib import Path
from typing import Any

import gradio as gr

from .ai_service_client import AiServiceClient, AiServiceError
from .config import settings
from .prep_service import LessonPrepService, PrepError
from .youtube import YoutubeError

cfg = settings()

ai = AiServiceClient(
    cfg.ai_service_url,
    cfg.ai_service_internal_token,
    timeout=cfg.ai_timeout_seconds,
)

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

/* Status Table */
.service-status-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.75rem;
    margin: 0;
    background: #ffffff;
}
.service-status-table th {
    background: #f1f5f9;
    color: #475569;
    font-weight: 700;
    text-transform: uppercase;
    font-size: 0.68rem;
    padding: 3px 8px;
    border: 1px solid #e2e8f0;
    text-align: left;
}
.service-status-table td {
    padding: 4px 8px;
    border: 1px solid #e2e8f0;
    vertical-align: middle;
}
.status-indicator {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-weight: 700;
    font-size: 0.72rem;
    padding: 1px 6px;
    border-radius: 2px !important;
}
.status-indicator.online {
    background-color: #dcfce7;
    color: #15803d;
    border: 1px solid #86efac;
}
.status-indicator.offline {
    background-color: #fee2e2;
    color: #b91c1c;
    border: 1px solid #fca5a5;
}
.status-indicator.warning {
    background-color: #fef3c7;
    color: #b45309;
    border: 1px solid #fde68a;
}
.static-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50% !important;
}
.static-dot.green { background-color: #22c55e; }
.static-dot.red { background-color: #ef4444; }
.static-dot.amber { background-color: #f59e0b; }

/* Status Banner */
.admin-alert {
    padding: 8px 12px;
    font-size: 0.82rem;
    border-left: 4px solid;
    margin-bottom: 8px;
    background: #ffffff;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}
.admin-alert.running {
    border-color: #0284c7;
    background-color: #f0f9ff;
    color: #0369a1;
}
.admin-alert.success {
    border-color: #16a34a;
    background-color: #f0fdf4;
    color: #15803d;
}
.admin-alert.error {
    border-color: #dc2626;
    background-color: #fef2f2;
    color: #b91c1c;
}
.admin-alert.idle {
    border-color: #64748b;
    background-color: #f8fafc;
    color: #334155;
}

/* Metric / Summary Data Table */
.admin-data-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.78rem;
    background: #ffffff;
    border: 1px solid #cbd5e1;
    margin-bottom: 6px;
}
.admin-data-table th {
    background: #f8fafc;
    border: 1px solid #cbd5e1;
    padding: 4px 8px;
    font-weight: 700;
    font-size: 0.70rem;
    text-transform: uppercase;
    color: #475569;
}
.admin-data-table td {
    border: 1px solid #cbd5e1;
    padding: 4px 8px;
}

/* Review Tabs */
.review-subtabs .tab-nav button {
    font-size: 0.76rem !important;
    padding: 4px 10px !important;
    font-weight: 600 !important;
}

/* Buttons */
.btn-primary-action {
    background-color: #0f172a !important;
    color: #ffffff !important;
    font-weight: 700 !important;
    border: none !important;
}
.btn-primary-action:hover {
    background-color: #1e293b !important;
}
"""


def _extract_youtube_id(url: str) -> str | None:
    patterns = [
        r"(?:v=|/v/|youtu\.be/|/embed/|/shorts/|/live/)([a-zA-Z0-9_-]{11})",
    ]
    for p in patterns:
        m = re.search(p, url)
        if m:
            return m.group(1)
    return None


def new_service() -> LessonPrepService:
    return LessonPrepService(
        ai=ai,
        work_dir=cfg.tool_work_dir,
        max_duration_seconds=cfg.max_audio_duration_seconds,
    )


# ------------------------------------------------------------------- dynamic voice discovery


_FALLBACK_KOKORO_VOICES: dict[str, str] = {
    "af_heart": "US",
    "af_alloy": "US",
    "af_aoede": "US",
    "af_bella": "US",
    "af_jessica": "US",
    "af_kore": "US",
    "af_nicole": "US",
    "af_nova": "US",
    "af_river": "US",
    "af_sarah": "US",
    "af_sky": "US",
    "am_adam": "US",
    "am_echo": "US",
    "am_eric": "US",
    "am_fenrir": "US",
    "am_liam": "US",
    "am_michael": "US",
    "am_onyx": "US",
    "am_puck": "US",
    "am_santa": "US",
    "bf_alice": "UK",
    "bf_emma": "UK",
    "bf_isabella": "UK",
    "bf_lily": "UK",
    "bm_daniel": "UK",
    "bm_fable": "UK",
    "bm_george": "UK",
    "bm_lewis": "UK",
}


def _fetch_voice_list() -> list[dict[str, Any]]:
    try:
        return ai.voices()
    except Exception:
        return [
            {
                "voice_id": vid,
                "accent": acc,
                "display": {"name": vid.replace("_", " ").title(), "accent": acc},
            }
            for vid, acc in _FALLBACK_KOKORO_VOICES.items()
        ]


def _get_voice_choices(accent: str) -> tuple[list[tuple[str, str]], str]:
    all_voices = _fetch_voice_list()
    filtered = [v for v in all_voices if v.get("accent", "US") == accent]
    if not filtered:
        filtered = all_voices

    choices = [
        (
            f"{v.get('display', {}).get('name', v['voice_id'])} ({v['voice_id']})",
            v["voice_id"],
        )
        for v in filtered
    ]
    default_voice = (
        DEFAULT_UK_VOICE
        if accent == "UK"
        else DEFAULT_US_VOICE
    )
    valid_keys = [c[1] for c in choices]
    if default_voice not in valid_keys and valid_keys:
        default_voice = valid_keys[0]

    return choices, default_voice


# ---------------------------------------------------------------- status bar


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

    ffmpeg_available = bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))
    ffmpeg_detail = "Installed on system PATH" if ffmpeg_available else "ffmpeg/ffprobe not found"

    ai_dot = "green" if ai_ok else "red"
    ai_status = "ONLINE" if ai_ok else "OFFLINE"
    ai_cls = "online" if ai_ok else "offline"

    ffmpeg_dot = "green" if ffmpeg_available else "amber"
    ffmpeg_status = "READY" if ffmpeg_available else "MISSING"
    ffmpeg_cls = "online" if ffmpeg_available else "warning"

    ai_url = cfg.ai_service_url

    return f"""
    <table class="service-status-table">
      <thead>
        <tr>
          <th style="width: 25%;">Component</th>
          <th style="width: 35%;">Endpoint / Command</th>
          <th style="width: 15%;">Status</th>
          <th style="width: 25%;">Details</th>
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
          <td><strong>Media Tools</strong></td>
          <td><code>ffmpeg / ffprobe</code></td>
          <td><span class="status-indicator {ffmpeg_cls}"><span class="static-dot {ffmpeg_dot}"></span>{ffmpeg_status}</span></td>
          <td style="color: #64748b; font-size: 0.70rem;">{ffmpeg_detail}</td>
        </tr>
      </tbody>
    </table>
    """


def on_status_refresh(svc: LessonPrepService) -> str:
    return connection_status()


def on_accent_change(selected_accent: str):
    choices, value = _get_voice_choices(selected_accent)
    return gr.update(choices=choices, value=value)


# ---------------------------------------------------------------- youtube live preview


def on_youtube_url_change(url: str) -> str:
    cleaned = (url or "").strip()
    if not cleaned:
        return """
        <div style="border: 1px dashed #cbd5e1; padding: 12px; text-align: center; color: #64748b; background: #ffffff; font-size: 0.75rem;">
          <em>Enter a YouTube URL above to preview video metadata.</em>
        </div>
        """

    video_id = _extract_youtube_id(cleaned)
    if not video_id:
        return """
        <div style="border: 1px dashed #f87171; padding: 10px; color: #dc2626; background: #fef2f2; font-size: 0.75rem;">
          Invalid YouTube URL format. Expected standard URL, shortlink or embed.
        </div>
        """

    thumb_url = f"https://img.youtube.com/vi/{video_id}/hqdefault.jpg"
    return f"""
    <div style="border: 1px solid #cbd5e1; padding: 8px; background: #ffffff; font-size: 0.75rem;">
      <div style="display: flex; gap: 10px; align-items: flex-start;">
        <img src="{thumb_url}" style="width: 140px; height: 80px; object-fit: cover; border: 1px solid #e2e8f0;" />
        <div>
          <div style="font-weight: 700; color: #0f172a;">YouTube Video ID: <code>{video_id}</code></div>
          <div style="color: #64748b; margin-top: 4px; font-size: 0.70rem;">Thumbnail: {thumb_url}</div>
        </div>
      </div>
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

    status_tag = (
        '<span style="color:#16a34a; font-weight:700;">● VALID & READY</span>'
        if ready
        else f'<span style="color:#dc2626; font-weight:700;">● BLOCKED ({len(problems)} issues)</span>'
    )

    audio_meta = svc.state.audio_meta
    audio_dur_s = (audio_meta.duration_ms / 1000.0) if audio_meta else duration_s
    sha = audio_meta.sha256[:16] + "..." if audio_meta else "none"
    mode_str = (svc.state.mode or "unknown").upper()

    cov = svc.state.coverage
    cov_str = f"{cov.coverage_pct}%" if cov else "N/A"
    gap_str = f"{cov.trailing_unaligned_ms / 1000.0:.1f}s" if cov else "N/A"
    repaired_str = str(svc.state.alignment.repaired_word_count) if (svc.state.alignment and svc.state.alignment.repaired_word_count is not None) else "0"

    return f"""
    <div>
      <table class="admin-data-table">
        <thead>
          <tr>
            <th style="width: 16%;">Audio Length</th>
            <th style="width: 16%;">Total Words</th>
            <th style="width: 16%;">Sentences</th>
            <th style="width: 16%;">Coverage</th>
            <th style="width: 18%;">Trailing Gap</th>
            <th style="width: 18%;">Schema Status</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td><strong>{audio_dur_s:.2f}s</strong></td>
            <td><strong>{total_words} words</strong></td>
            <td><strong>{total_sentences} sentences</strong></td>
            <td><strong>{cov_str}</strong></td>
            <td><strong>{gap_str}</strong></td>
            <td>{status_tag}</td>
          </tr>
        </tbody>
      </table>
      <table class="admin-data-table" style="margin-top:-2px;">
        <tbody>
          <tr>
            <td style="width: 18%; background:#f8fafc; font-weight:600;">SHA-256 Digest:</td>
            <td style="width: 32%;"><code>{sha}</code></td>
            <td style="width: 18%; background:#f8fafc; font-weight:600;">Repaired Words:</td>
            <td style="width: 32%;"><code>{repaired_str}</code></td>
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
    ):
        mode = MODE_KEYS.get(mode_input, "tts")
        mode_label = MODE_DISPLAY_LABELS.get(mode, "Text to Speech (TTS)")
        lines: list[str] = []
        audio_file_path: str | None = None
        transcript_value: str = ""
        resolved_title: str = ""

        # Step 0: Lock controls
        yield (
            gr.update(value="⏳ Processing...", interactive=False),
            gr.update(interactive=False),
            gr.update(interactive=False),
            gr.update(interactive=False),
            _render_status_banner("running", "Initializing preparation pipeline..."),
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

        # Precondition: Check AI Service
        ai_live = False
        try:
            ai.voices()
            ai_live = True
        except Exception:
            ai_live = False

        if not ai_live:
            lines.append(f"[ERROR] AI service unreachable at {cfg.ai_service_url}. Start with 'make ai' or 'make ai-local'.")
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
            lines.append(f"[Step 1/3] Acquiring source audio ({mode_label})...")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", f"Step 1/3: Acquiring source audio ({mode_label})..."),
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

            lines.append(f"  [OK] Audio ready: {audio_file_path}")
            lines.append(f"  -> Duration: {svc.state.audio_meta.duration_ms / 1000.0:.2f}s")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 1/3 complete. Audio acquired. Proceeding to Step 2/3 (Speech Transcription)..."),
                _render_log(lines),
                audio_file_path,
                transcript_value if transcript_value else gr.skip(),
                resolved_title,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            # -------------------------------------------------- Step 2: Transcription (STT)
            lines.append("[Step 2/3] Transcribing speech with Qwen ASR...")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 2/3: Transcribing speech with Qwen ASR..."),
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

            lines.append("  [OK] Transcript ready.")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 2/3 complete. Proceeding to Step 3/3 (Forced Alignment)..."),
                _render_log(lines),
                audio_file_path,
                transcript_value,
                resolved_title,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            # -------------------------------------------------- Step 3: Forced Alignment
            lines.append("[Step 3/3] Running forced alignment with Qwen ForcedAligner...")
            yield (
                gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                _render_status_banner("running", "Step 3/3: Running Qwen forced alignment for word timestamps..."),
                _render_log(lines),
                audio_file_path,
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.skip(), gr.skip(), gr.skip(),
            )

            aligned_words = svc.run_alignment()
            lines.append(f"  -> Received {len(aligned_words)} word timestamps from Qwen aligner.")
            lines.append(f"  -> Distributed across {len(svc.state.sentences)} sentence(s) with monotonic mapping.")
            if svc.state.alignment and svc.state.alignment.repaired_word_count:
                lines.append(f"  -> Repaired {svc.state.alignment.repaired_word_count} sub-minimum duration word(s).")
            lines.append("  [OK] Forced alignment complete.")

            # Check coverage
            if svc.state.coverage and svc.state.coverage.warnings:
                for w in svc.state.coverage.warnings:
                    lines.append(f"  [WARN] {w}")

            # Verification
            lines.append("[Validation] Validating portable manifest...")
            ready, problems = svc.export_readiness()

            if ready:
                lines.append("  [OK] Portable manifest validation PASSED.")
            else:
                lines.append(f"  [WARN] Verification issues: {'; '.join(problems)}")

            preview_dict = _export_preview(svc)
            preview_code = (
                json.dumps(preview_dict, indent=2, ensure_ascii=False)
                if preview_dict
                else ""
            )

            # Determine banner status
            if ready:
                banner_status = "success"
                banner_text = "Source preparation complete! Ready to export package."
            else:
                banner_status = "error"
                banner_text = f"Preparation complete with warnings: {'; '.join(problems)}"

            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=ready),  # export_btn enabled if valid
                _render_status_banner(banner_status, banner_text),
                _render_log(lines),
                audio_file_path,
                gr.update(value=transcript_value, interactive=True),
                resolved_title,
                _render_metrics_table(svc),
                _sentence_rows(svc),
                preview_code,
                preview_dict,
                None,
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
            )

        except (PrepError, AiServiceError, YoutubeError, ValueError) as exc:
            err_msg = str(exc) or exc.__class__.__name__
            lines.append(f"[ERROR] {err_msg}")
            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=False),
                gr.update(interactive=False),
                _render_status_banner("error", f"Pipeline Error: {err_msg}"),
                _render_log(lines),
                audio_file_path if audio_file_path else gr.skip(),
                gr.update(value=transcript_value, interactive=True) if transcript_value else gr.skip(),
                resolved_title if resolved_title else gr.skip(),
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
            )
        except Exception as exc:
            err_msg = f"Unexpected error: {exc}"
            lines.append(f"[ERROR] {err_msg}")
            yield (
                gr.update(value="⚡ Process Source Audio", interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=False),
                gr.update(interactive=False),
                _render_status_banner("error", f"System Error: {err_msg}"),
                _render_log(lines),
                audio_file_path if audio_file_path else gr.skip(),
                gr.update(value=transcript_value, interactive=True) if transcript_value else gr.skip(),
                resolved_title if resolved_title else gr.skip(),
                gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
                gr.update(interactive=True),
                gr.update(interactive=True),
                gr.update(interactive=True),
            )

    return prepare_pipeline


# ---------------------------------------------------------------- transcript edit & realign


def on_transcript_change(svc: LessonPrepService, current_text: str):
    """When the user edits the transcript, mark alignment stale and disable export."""
    text = (current_text or "").strip()
    if text != (svc.state.transcript or "").strip():
        svc.set_transcript(text)
        status_html = _render_status_banner(
            "idle",
            "Transcript modified. Alignment is now stale. Click '🔄 Re-align Transcript' to synchronize.",
        )
        return status_html, gr.update(interactive=True), gr.update(interactive=False)
    return gr.skip(), gr.skip(), gr.skip()


def run_realign(
    svc: LessonPrepService,
    edited_text: str,
    title_text: str,
):
    lines: list[str] = []
    lines.append("[Realign] Synchronizing word timestamps with edited transcript...")

    yield (
        gr.update(value="⏳ Aligning...", interactive=False),
        gr.update(interactive=False),
        gr.update(interactive=False),
        gr.update(interactive=False),
        _render_status_banner("running", "Re-running Qwen forced alignment with edited text..."),
        _render_log(lines),
        gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
        gr.update(interactive=False),
    )

    try:
        svc.set_transcript(edited_text)
        if title_text.strip():
            svc.state.title = title_text.strip()

        aligned_words = svc.run_alignment()
        lines.append(f"  -> Aligned {len(aligned_words)} word timestamps.")
        lines.append(f"  -> Distributed across {len(svc.state.sentences)} sentence(s).")
        if svc.state.alignment and svc.state.alignment.repaired_word_count:
            lines.append(f"  -> Repaired {svc.state.alignment.repaired_word_count} sub-minimum duration word(s).")

        ready, problems = svc.export_readiness()
        if ready:
            lines.append("  [OK] Portable manifest validation PASSED.")
            banner_status = "success"
            banner_text = "Timestamps synchronized successfully! Ready to export package."
        else:
            lines.append(f"  [WARN] Verification issues: {'; '.join(problems)}")
            banner_status = "error"
            banner_text = f"Alignment updated with issues: {'; '.join(problems)}"

        preview_dict = _export_preview(svc)
        preview_code = (
            json.dumps(preview_dict, indent=2, ensure_ascii=False)
            if preview_dict
            else ""
        )

        yield (
            gr.update(value="🔄 Re-align Transcript", interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=ready),
            _render_status_banner(banner_status, banner_text),
            _render_log(lines),
            _render_metrics_table(svc),
            _sentence_rows(svc),
            preview_code,
            preview_dict,
            None,
            gr.update(interactive=True),
        )

    except (PrepError, AiServiceError, ValueError) as exc:
        err_msg = str(exc) or exc.__class__.__name__
        lines.append(f"[ERROR] {err_msg}")
        yield (
            gr.update(value="🔄 Re-align Transcript", interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=True),
            gr.update(interactive=False),
            _render_status_banner("error", f"Realign Error: {err_msg}"),
            _render_log(lines),
            gr.skip(), gr.skip(), gr.skip(), gr.skip(), gr.skip(),
            gr.update(interactive=True),
        )


# ---------------------------------------------------------------- package export


def run_export(
    svc: LessonPrepService,
):
    """Exports the primary portable package into the session workspace for browser download."""
    ready, problems = svc.export_readiness()
    if not ready:
        msg = f"[ERROR] Export blocked: {'; '.join(problems)}"
        return None, _render_status_banner("error", f"Export failed: {'; '.join(problems)}"), _render_log([msg])

    try:
        package_path = svc.export_package()

        msg = (
            f"[EXPORT SUCCESS]\n"
            f"Package Archive: {package_path.name}\n"
            f"Staged in Workspace: {package_path.parent}\n"
            f"Size: {package_path.stat().st_size} bytes\n"
            f"Click 'Download Package' to save to your local Downloads folder."
        )
        return (
            str(package_path),
            _render_status_banner("success", f"Package ready for download: {package_path.name}"),
            _render_log([msg]),
        )
    except (PrepError, OSError) as exc:
        msg = f"[EXPORT ERROR] {exc}"
        return None, _render_status_banner("error", f"Export error: {exc}"), _render_log([msg])


def run_clear(svc: LessonPrepService | None = None):
    """Resets workspace state, purges temporary files on disk, and resets outputs."""
    deleted = 0
    if isinstance(svc, LessonPrepService):
        deleted = svc.clear_work_dir()
    fresh_svc = new_service()
    msg = f"[RESET] Workspace cleared. Purged {deleted} temporary file(s) from disk."
    return (
        fresh_svc,
        gr.update(selected="tts"),
        "tts",
        None,
        "",
        "",
        "",
        "",
        "",
        on_youtube_url_change(""),
        _render_status_banner("idle", "Workspace reset. Select a source on the left to begin."),
        _render_log([msg]),
        None,
        gr.update(value="", interactive=False),
        "",
        _render_metrics_empty(),
        [],
        "",
        None,
        None,
        gr.update(interactive=False),
        gr.update(interactive=False),
    )


# ---------------------------------------------------------------- UI Builder


def build_ui() -> gr.Blocks:
    us_choices, us_default = _get_voice_choices("US")

    with gr.Blocks(title="Lyreo Lesson Studio", css=CUSTOM_CSS, fill_width=True) as demo:
        # Header Row
        with gr.Row(elem_classes=["admin-header-row"]):
            with gr.Column(scale=3, min_width=240):
                gr.HTML("""
                <div class="brand-block">
                  <div class="brand-title">LYREO PREP WORKBENCH</div>
                  <div class="brand-subtitle">Source Acquisition & Portable Packaging</div>
                </div>
                """)
                with gr.Row():
                    status_refresh = gr.Button("⟳ Refresh Status", variant="secondary", size="sm")
            with gr.Column(scale=7, min_width=520):
                status_box = gr.HTML(connection_status())

        # Main 2-Column Layout
        with gr.Row(equal_height=False):
            # Left Column: Ingestion & Controls
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
                                label="Kokoro Voice",
                                scale=2,
                            )
                        with gr.Row():
                            speed_slider = gr.Slider(0.5, 2.0, value=1.0, step=0.05, label="Speed")
                        tts_title = gr.Textbox(label="Title (Optional)", placeholder="Auto-generated if blank")

                    with gr.Tab("📁 Upload Audio", id="upload") as tab_upload:
                        audio_file = gr.File(
                            label="Audio File (.wav, .mp3, .m4a)",
                            file_types=["audio"],
                            type="filepath",
                        )
                        upload_title = gr.Textbox(label="Title (Optional)", placeholder="Inferred from filename if blank")

                    with gr.Tab("▶️ YouTube Video", id="youtube") as tab_yt:
                        youtube_url = gr.Textbox(
                            label="YouTube URL *",
                            placeholder="https://www.youtube.com/watch?v=...",
                        )
                        youtube_preview_html = gr.HTML(on_youtube_url_change(""))
                        youtube_title = gr.Textbox(label="Title (Optional)", placeholder="Inferred from YouTube if blank")

                with gr.Row():
                    prepare_button = gr.Button(
                        "⚡ Process Source Audio",
                        variant="primary",
                        elem_classes=["btn-primary-action"],
                        scale=3,
                    )
                    clear_button = gr.Button("🗑️ Reset", variant="secondary", scale=1)

                gr.Markdown("### Execution Log")
                log = gr.Markdown(_render_log([]))

            # Right Column: Review & Export
            with gr.Column(scale=1, min_width=420):
                gr.Markdown("### 2. Audio Preview & Transcript")
                audio_preview = gr.Audio(label="Acquired Audio Preview", interactive=False, type="filepath")

                with gr.Row():
                    title_resolved = gr.Textbox(label="Source Title", interactive=True, scale=3)
                    realign_btn = gr.Button("🔄 Re-align Transcript", variant="secondary", interactive=False, scale=2)

                transcript_box = gr.Textbox(
                    label="Editable Transcript (Editing marks alignment stale)",
                    lines=5,
                    interactive=False,
                )

                status_banner = gr.HTML(_render_status_banner("idle", "Configure source on the left to begin."))

                gr.Markdown("### Alignment Metrics")
                metrics_table = gr.HTML(_render_metrics_empty())

                with gr.Tabs(elem_classes=["review-subtabs"]):
                    with gr.Tab("Sentence Breakdown"):
                        review_rows = gr.Dataframe(
                            headers=["#", "Sentence Text", "Start", "End", "Duration", "Words"],
                            datatype=["number", "str", "str", "str", "str", "number"],
                            interactive=False,
                        )
                    with gr.Tab("Manifest Code Preview"):
                        review_code = gr.Code(label="lesson-source.json", language="json", interactive=False)
                    with gr.Tab("JSON Tree"):
                        review_json = gr.JSON(label="Parsed Manifest")

                with gr.Row():
                    export_btn = gr.Button(
                        "📦 Export Lesson Source Package (*.zip)",
                        variant="primary",
                        elem_classes=["btn-primary-action"],
                        interactive=False,
                        scale=2,
                    )
                    export_file = gr.File(label="Download Package", interactive=False, scale=2)

        def _dispose_service_state(svc: Any) -> None:
            if isinstance(svc, LessonPrepService):
                try:
                    svc.clear_work_dir()
                except Exception:
                    pass

        state = gr.State(new_service, delete_callback=_dispose_service_state)

        # Tab Selection State
        tab_tts.select(lambda: "tts", outputs=[mode_state])
        tab_upload.select(lambda: "upload", outputs=[mode_state])
        tab_yt.select(lambda: "youtube", outputs=[mode_state])

        accent_dd.change(on_accent_change, inputs=[accent_dd], outputs=[voice_dd])
        youtube_url.change(on_youtube_url_change, inputs=[youtube_url], outputs=[youtube_preview_html])
        status_refresh.click(on_status_refresh, inputs=[state], outputs=[status_box])

        # Transcript editing invalidates alignment (user input event only)
        transcript_box.input(
            on_transcript_change,
            inputs=[state, transcript_box],
            outputs=[status_banner, realign_btn, export_btn],
        )

        pipeline_fn = make_prepare_pipeline(
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
        )

        prepare_button.click(
            pipeline_fn,
            inputs=[
                state,
                mode_state,
                audio_file,
                upload_title,
                tts_text,
                voice_dd,
                accent_dd,
                speed_slider,
                tts_title,
                youtube_url,
                youtube_title,
                transcript_box,
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
            inputs=[state, transcript_box, title_resolved],
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
            inputs=[state],
            outputs=[export_file, status_banner, log],
            show_progress="hidden",
        )

        clear_button.click(
            run_clear,
            inputs=[state],
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


demo = build_ui()

if __name__ == "__main__":
    cfg.tool_work_dir.resolve().mkdir(parents=True, exist_ok=True)
    demo.launch(
        server_name="127.0.0.1",
        server_port=7860,
        allowed_paths=[
            str(cfg.tool_work_dir.resolve()),
        ],
        show_error=True,
    )
