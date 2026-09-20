import io
import shutil
import wave
from pathlib import Path

import pytest

from lesson_prep.package_writer import PackageWriterError, verify_package
from lesson_prep.prep_service import (
    LessonPrepService,
    PrepError,
    build_sentences_with_monotonic_mapping,
    split_into_sentences,
)


def create_dummy_wav(path: Path, duration_seconds: float = 2.0) -> Path:
    sample_rate = 16000
    num_frames = int(sample_rate * duration_seconds)
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(b"\x00\x00" * num_frames)
    return path


class FakeAi:
    def __init__(self):
        self.voices_called = False

    def stt(self, audio_ref, provider="LOCAL_QWEN", model=None, language="English"):
        return {
            "text": "Hello there. How are you?",
            "language": "English",
            "provider": provider,
            "model": model or "Qwen/Qwen3-ASR-0.6B",
        }

    def align(self, audio_ref, text, provider="LOCAL_QWEN", model=None, language="English"):
        return {
            "words": [
                {"index": 0, "word": "Hello", "start_ms": 0, "end_ms": 400},
                {"index": 1, "word": "there.", "start_ms": 400, "end_ms": 700},
                {"index": 2, "word": "How", "start_ms": 800, "end_ms": 1000},
                {"index": 3, "word": "are", "start_ms": 1000, "end_ms": 1200},
                {"index": 4, "word": "you?", "start_ms": 1200, "end_ms": 1500},
            ],
            "provider": provider,
            "model": model or "Qwen/Qwen3-ForcedAligner-0.6B",
        }

    def tts(self, text, voice="af_heart", accent="US", speed=1.0, provider="LOCAL_KOKORO", model=None):
        buf = io.BytesIO()
        with wave.open(buf, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(16000)
            wf.writeframes(b"\x00\x00" * 32000)  # 2 seconds
        return {
            "audio_bytes": buf.getvalue(),
            "mime_type": "audio/wav",
            "provider": provider,
            "model": model or "Kokoro-82M",
            "voice": voice,
            "accent": accent,
        }

    def voices(self):
        self.voices_called = True
        return [{"provider": "LOCAL_KOKORO", "voice_id": "af_heart", "accent": "US"}]


def test_upload_flow_stt_edit_align_export(tmp_path):
    work_dir = tmp_path / "work"
    export_dir = tmp_path / "export"
    service = LessonPrepService(FakeAi(), work_dir)

    src_audio = tmp_path / "source.wav"
    create_dummy_wav(src_audio, duration_seconds=2.0)

    service.select_uploaded_audio(src_audio)
    service.state.title = "Upload Lesson"
    service.run_stt()
    assert service.state.transcript == "Hello there. How are you?"
    assert not service.state.alignment_fresh

    service.run_alignment()
    assert service.state.alignment_fresh
    assert [s.text for s in service.state.sentences] == ["Hello there.", "How are you?"]

    ready, problems = service.export_readiness()
    assert ready, problems

    pkg_path = service.export_package(export_dir)
    assert pkg_path.exists()
    assert pkg_path.name.endswith(".lesson-source.zip")

    # Verify exported package
    source = verify_package(pkg_path)
    assert source.source.kind == "AUDIO"
    assert source.source.origin == "UPLOAD"
    assert source.source.title == "Upload Lesson"
    assert source.media.audio.path == "media/audio.wav"
    assert source.media.audio.duration_ms == 2000
    assert len(source.content.sentences) == 2


def test_package_portability_acceptance(tmp_path):
    """Verifies that an exported package is completely standalone:
    deleting .work and moving the ZIP to an isolated folder leaves it fully verifiable."""
    work_dir = tmp_path / "work"
    export_dir = tmp_path / "export"
    service = LessonPrepService(FakeAi(), work_dir)

    src_audio = tmp_path / "source.wav"
    create_dummy_wav(src_audio, duration_seconds=2.0)

    service.select_uploaded_audio(src_audio)
    service.state.title = "Portable Lesson"
    service.run_stt()
    service.run_alignment()

    pkg_path = service.export_package(export_dir)
    assert pkg_path.is_file()

    # Destroy the working directory completely
    shutil.rmtree(work_dir)
    assert not work_dir.exists()

    # Move package to a completely independent destination
    new_dir = tmp_path / "consumer_service"
    new_dir.mkdir()
    isolated_pkg = new_dir / "imported.lesson-source.zip"
    shutil.move(pkg_path, isolated_pkg)

    # Verify the package in the new location
    verified_source = verify_package(isolated_pkg)
    assert verified_source.source.title == "Portable Lesson"
    assert verified_source.media.audio.size_bytes > 0
    assert len(verified_source.content.sentences) == 2


def test_workspace_isolation_between_runs(tmp_path):
    work_dir = tmp_path / "work"
    service1 = LessonPrepService(FakeAi(), work_dir, run_id="run-1")
    service2 = LessonPrepService(FakeAi(), work_dir, run_id="run-2")

    src_audio = tmp_path / "audio.wav"
    create_dummy_wav(src_audio, 1.0)

    service1.select_uploaded_audio(src_audio)
    service2.select_uploaded_audio(src_audio)

    assert service1.state.local_audio_path.exists()
    assert service2.state.local_audio_path.exists()
    assert service1.run_dir != service2.run_dir

    # Clearing service1 must not delete service2's files
    service1.clear_work_dir()
    assert not service1.state.local_audio_path
    assert service2.state.local_audio_path.exists()


def test_transcript_edit_invalidates_alignment(tmp_path):
    work_dir = tmp_path / "work"
    service = LessonPrepService(FakeAi(), work_dir)
    src_audio = tmp_path / "audio.wav"
    create_dummy_wav(src_audio, 2.0)

    service.select_uploaded_audio(src_audio)
    service.state.title = "Lesson"
    service.run_stt()
    service.run_alignment()
    assert service.state.alignment_fresh

    service.set_transcript("Different transcript.")
    assert not service.state.alignment_fresh
    assert len(service.state.sentences) == 0

    ready, problems = service.export_readiness()
    assert not ready
    assert any("stale" in p for p in problems)


def test_tts_flow_marks_preparation_and_skips_stt(tmp_path):
    work_dir = tmp_path / "work"
    export_dir = tmp_path / "export"
    service = LessonPrepService(FakeAi(), work_dir)

    path = service.generate_audio_from_text(
        "Synthetic speech.", voice="af_heart", accent="US", speed=1.0
    )
    assert path.exists()
    assert service.state.mode == "tts"
    assert service.state.tts.voice == "af_heart"
    assert service.state.tts.accent == "US"
    assert service.state.tts.speed == 1.0

    service.state.title = "TTS lesson"
    service.run_alignment()

    pkg_path = service.export_package(export_dir)
    source = verify_package(pkg_path)

    assert source.source.origin == "TTS_GENERATED"
    assert source.preparation.tts.provider == "LOCAL_KOKORO"
    assert source.preparation.stt is None


def test_export_requires_every_step(tmp_path):
    work_dir = tmp_path / "work"
    export_dir = tmp_path / "export"
    service = LessonPrepService(FakeAi(), work_dir)

    ready, problems = service.export_readiness()
    assert not ready
    assert any("title" in p for p in problems)
    assert any("local audio" in p for p in problems)
    assert any("transcript" in p for p in problems)
    assert any("alignment" in p for p in problems)

    with pytest.raises(PrepError, match="Preparation is not ready to export"):
        service.export_package(export_dir)


def test_audio_duration_exceeding_limit_raises_error(tmp_path):
    work_dir = tmp_path / "work"
    service = LessonPrepService(FakeAi(), work_dir, max_duration_seconds=5)

    long_audio = tmp_path / "long.wav"
    create_dummy_wav(long_audio, duration_seconds=6.0)

    with pytest.raises(PrepError, match="exceeds the maximum supported alignment limit"):
        service.select_uploaded_audio(long_audio)


def test_build_sentences_handles_multi_sentence_compound_word_split():
    """Validates the monotonic token mapping algorithm when compound words are split
    across multiple sentences."""
    text = "She loves ice-cream. The strawberry flavor is delicious."
    raw_aligned = [
        {"word": "she", "start_ms": 0, "end_ms": 200},
        {"word": "loves", "start_ms": 200, "end_ms": 500},
        {"word": "ice", "start_ms": 500, "end_ms": 700},
        {"word": "cream", "start_ms": 700, "end_ms": 900},
        {"word": "the", "start_ms": 1000, "end_ms": 1200},
        {"word": "strawberry", "start_ms": 1200, "end_ms": 1500},
        {"word": "flavor", "start_ms": 1500, "end_ms": 1800},
        {"word": "is", "start_ms": 1800, "end_ms": 1900},
        {"word": "delicious", "start_ms": 1900, "end_ms": 2300},
    ]
    sentences, repaired = build_sentences_with_monotonic_mapping(text, raw_aligned, audio_duration_ms=2500)
    assert len(sentences) == 2
    assert [w.text for w in sentences[0].words] == ["She", "loves", "ice-cream."]
    assert [w.text for w in sentences[1].words] == ["The", "strawberry", "flavor", "is", "delicious."]

    # 'ice-cream.' combines 'ice' (500) and 'cream' (900)
    assert sentences[0].words[2].start_ms == 500
    assert sentences[0].words[2].end_ms == 900

    # Monotonicity check across all words
    all_words = [w for s in sentences for w in s.words]
    for i, w in enumerate(all_words):
        assert w.end_ms > w.start_ms
        if i > 0:
            assert w.start_ms >= all_words[i - 1].end_ms


def test_build_sentences_repairs_zero_duration_word_clusters():
    text = "Today we are going to practice English listening."
    raw_aligned = [
        {"word": "Today", "start_ms": 2720, "end_ms": 3040},
        {"word": "we", "start_ms": 3040, "end_ms": 3200},
        {"word": "are", "start_ms": 3200, "end_ms": 3200},  # zero duration
        {"word": "going", "start_ms": 3200, "end_ms": 3440},
        {"word": "to", "start_ms": 3520, "end_ms": 3520},  # zero duration
        {"word": "practice", "start_ms": 3520, "end_ms": 4000},
        {"word": "English", "start_ms": 4000, "end_ms": 4400},
        {"word": "listening", "start_ms": 4400, "end_ms": 4880},
    ]
    sentences, repaired = build_sentences_with_monotonic_mapping(text, raw_aligned, audio_duration_ms=5000)
    assert len(sentences) == 1
    assert repaired > 0

    w_list = sentences[0].words
    for i, w in enumerate(w_list):
        assert w.end_ms > w.start_ms, f"{w.text} has zero duration"
        if i > 0:
            assert w.start_ms >= w_list[i - 1].end_ms, f"{w.text} overlaps with {w_list[i-1].text}"


def test_build_sentences_preserves_lexical_punctuation_and_numbers():
    text = "Every weekday, Emma wakes up at 6:30 a.m., makes a cup of coffee, and checks her schedule."
    raw_aligned = [
        {"word": "every", "start_ms": 100, "end_ms": 300},
        {"word": "weekday", "start_ms": 300, "end_ms": 600},
        {"word": "emma", "start_ms": 600, "end_ms": 850},
        {"word": "wakes", "start_ms": 850, "end_ms": 1100},
        {"word": "up", "start_ms": 1100, "end_ms": 1250},
        {"word": "at", "start_ms": 1250, "end_ms": 1350},
        {"word": "630", "start_ms": 1350, "end_ms": 1700},
        {"word": "am", "start_ms": 1700, "end_ms": 1900},
        {"word": "makes", "start_ms": 1900, "end_ms": 2150},
        {"word": "a", "start_ms": 2150, "end_ms": 2250},
        {"word": "cup", "start_ms": 2250, "end_ms": 2400},
        {"word": "of", "start_ms": 2400, "end_ms": 2500},
        {"word": "coffee", "start_ms": 2500, "end_ms": 2800},
        {"word": "and", "start_ms": 2800, "end_ms": 2950},
        {"word": "checks", "start_ms": 2950, "end_ms": 3200},
        {"word": "her", "start_ms": 3200, "end_ms": 3350},
        {"word": "schedule", "start_ms": 3350, "end_ms": 3700},
    ]
    sentences, repaired = build_sentences_with_monotonic_mapping(text, raw_aligned, audio_duration_ms=4000)
    assert len(sentences) == 1
    words = sentences[0].words
    assert words[0].text == "Every"
    assert words[1].text == "weekday,"
    assert words[6].text == "6:30"
    assert words[7].text == "a.m.,"
    assert words[12].text == "coffee,"
    assert words[16].text == "schedule."


def test_verify_package_rejects_tampered_audio_bytes(tmp_path):
    import zipfile
    work_dir = tmp_path / "work"
    export_dir = tmp_path / "export"
    service = LessonPrepService(FakeAi(), work_dir)
    src_audio = tmp_path / "source.wav"
    create_dummy_wav(src_audio, duration_seconds=2.0)
    service.select_uploaded_audio(src_audio)
    service.state.title = "Valid Lesson"
    service.run_stt()
    service.run_alignment()
    pkg_path = service.export_package(export_dir)
    assert verify_package(pkg_path)

    # Tamper with audio bytes inside the ZIP
    tampered_pkg = export_dir / "tampered.lesson-source.zip"
    with zipfile.ZipFile(pkg_path, "r") as zin, zipfile.ZipFile(tampered_pkg, "w") as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "media/audio.wav":
                # Alter audio bytes so SHA-256 no longer matches manifest
                data = b"TAMPERED_BYTE_SEQUENCE" + data[22:]
            zout.writestr(item, data)

    with pytest.raises(PackageWriterError, match="Audio SHA-256 mismatch"):
        verify_package(tampered_pkg)


def test_verify_package_rejects_unexpected_extra_members(tmp_path):
    import zipfile
    work_dir = tmp_path / "work"
    export_dir = tmp_path / "export"
    service = LessonPrepService(FakeAi(), work_dir)
    src_audio = tmp_path / "source.wav"
    create_dummy_wav(src_audio, duration_seconds=2.0)
    service.select_uploaded_audio(src_audio)
    service.state.title = "Lesson"
    service.run_stt()
    service.run_alignment()
    pkg_path = service.export_package(export_dir)

    injected_pkg = export_dir / "injected.lesson-source.zip"
    with zipfile.ZipFile(pkg_path, "r") as zin, zipfile.ZipFile(injected_pkg, "w") as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        zout.writestr("extra_payload.sh", b"echo 'malicious'")

    with pytest.raises(PackageWriterError, match="undeclared extra members"):
        verify_package(injected_pkg)


def test_verify_package_rejects_duplicate_member_names(tmp_path):
    import zipfile
    dup_pkg = tmp_path / "duplicate.lesson-source.zip"
    with zipfile.ZipFile(dup_pkg, "w") as zf:
        zf.writestr("lesson-source.json", b"{}")
        zf.writestr("lesson-source.json", b"{}")

    with pytest.raises(PackageWriterError, match="duplicate member names"):
        verify_package(dup_pkg)


def test_verify_package_rejects_symlink_entry(tmp_path):
    import zipfile
    sym_pkg = tmp_path / "symlink.lesson-source.zip"
    with zipfile.ZipFile(sym_pkg, "w") as zf:
        zf.writestr("lesson-source.json", b"{}")
        info = zipfile.ZipInfo("media/symlink.wav")
        # S_IFLNK = 0o120000
        info.external_attr = 0o120777 << 16
        zf.writestr(info, b"/etc/passwd")

    with pytest.raises(PackageWriterError, match="forbidden symlink entry"):
        verify_package(sym_pkg)


def test_sentence_segmentation_handles_quoted_endings_and_dialogue():
    # Exact real TTS test case
    text = (
        'Emma checks her phone, puts on her headphones, and says, "All right, let\'s get started."\n\n'
        'Learning a language takes time, patience, and consistent practice.'
    )
    sentences = split_into_sentences(text)
    assert len(sentences) == 2
    assert sentences[0] == 'Emma checks her phone, puts on her headphones, and says, "All right, let\'s get started."'
    assert sentences[1] == 'Learning a language takes time, patience, and consistent practice.'

    # Inline quoted dialogue
    dialogue = 'She said, "That\'s fine." Then she left.'
    dialogue_sentences = split_into_sentences(dialogue)
    assert len(dialogue_sentences) == 2
    assert dialogue_sentences[0] == 'She said, "That\'s fine."'
    assert dialogue_sentences[1] == 'Then she left.'


def test_sentence_segmentation_preserves_abbreviations_and_splits_boundaries():
    # Split after completed sentence ending with a.m.
    text1 = "before 8:30 a.m. First, she needs to call Daniel."
    s1 = split_into_sentences(text1)
    assert len(s1) == 2
    assert s1[0] == "before 8:30 a.m."
    assert s1[1] == "First, she needs to call Daniel."

    # Preserve abbreviations in mid-sentence
    text2 = "The train leaves at 8:30 a.m. tomorrow morning."
    s2 = split_into_sentences(text2)
    assert len(s2) == 1
    assert s2[0] == text2

    # Preserve honorific titles
    text3 = "Dr. Smith met Mr. Jones and Prof. Davis yesterday."
    s3 = split_into_sentences(text3)
    assert len(s3) == 1
    assert s3[0] == text3

    # Preserve Latin abbreviations
    text4 = "She bought fruit, e.g. apples and oranges, i.e. healthy snacks."
    s4 = split_into_sentences(text4)
    assert len(s4) == 1
    assert s4[0] == text4


def test_user_transcript_editing_state_transition(tmp_path):
    from lesson_prep.ui_app import on_transcript_change

    work_dir = tmp_path / "work"
    service = LessonPrepService(FakeAi(), work_dir)
    src_audio = tmp_path / "audio.wav"
    create_dummy_wav(src_audio, 2.0)
    service.select_uploaded_audio(src_audio)
    service.state.title = "Lesson"
    service.run_stt()
    service.run_alignment()

    # Initially fresh
    assert service.state.alignment_fresh is True
    initial_text = service.state.transcript

    # Programmatic backend set / identical text does NOT mark stale
    on_transcript_change(service, initial_text)
    assert service.state.alignment_fresh is True

    # User edit DOES mark stale
    edited_text = initial_text + " Edited words."
    status_html, realign_update, export_update = on_transcript_change(service, edited_text)
    assert service.state.alignment_fresh is False
    assert service.state.transcript == edited_text
    # Realign enabled, export disabled
    assert realign_update.get("interactive") is True
    assert export_update.get("interactive") is False
    assert "stale" in status_html.lower()


def test_reset_workspace_lifecycle_isolates_sessions(tmp_path):
    from lesson_prep.ui_app import run_clear

    work_dir = tmp_path / "work"
    service_a = LessonPrepService(FakeAi(), work_dir)
    service_b = LessonPrepService(FakeAi(), work_dir)

    src_audio = tmp_path / "audio.wav"
    create_dummy_wav(src_audio, 2.0)
    service_a.select_uploaded_audio(src_audio)
    service_b.select_uploaded_audio(src_audio)

    dir_a = service_a.run_dir
    dir_b = service_b.run_dir
    assert dir_a.exists()
    assert dir_b.exists()
    assert dir_a != dir_b

    # Reset service A
    res = run_clear(service_a)
    fresh_service_c = res[0]
    transcript_box_update = res[13]
    realign_btn_update = res[20]
    export_btn_update = res[21]

    # Workspace A removed
    assert not dir_a.exists()
    # Workspace B untouched
    assert dir_b.exists()
    assert (dir_b / f"uploaded{src_audio.suffix}").exists()

    # Fresh service C returned
    # Fresh service C returned lazily (directory does not exist until preparation begins)
    assert isinstance(fresh_service_c, LessonPrepService)
    assert fresh_service_c.run_id != service_a.run_id
    assert fresh_service_c.run_id != service_b.run_id
    assert not fresh_service_c.run_dir.exists(), "New workspace should be created lazily"

    # UI state properly reset: transcript disabled & cleared, realign & export disabled
    assert transcript_box_update.get("value") == ""
    assert transcript_box_update.get("interactive") is False
    assert realign_btn_update.get("interactive") is False
    assert export_btn_update.get("interactive") is False


def test_prepare_pipeline_yields_interactive_transcript(tmp_path):
    from unittest.mock import patch
    import gradio as gr
    from lesson_prep.ui_app import make_prepare_pipeline

    src_audio = tmp_path / "test.wav"
    create_dummy_wav(src_audio, 2.0)
    fake_ai = FakeAi()
    svc = LessonPrepService(fake_ai, tmp_path / "work")

    pipeline = make_prepare_pipeline(
        gr.Button(), gr.Button(), gr.Button(), gr.Button(),
        gr.HTML(), gr.HTML(), gr.Audio(), gr.Textbox(), gr.Textbox(), gr.HTML(),
        gr.Dataframe(), gr.Code(), gr.JSON(), gr.File(),
        gr.Textbox(), gr.Audio(), gr.Textbox(),
    )

    with patch("lesson_prep.ui_app.ai", fake_ai):
        gen = pipeline(
            svc=svc,
            mode_input="upload",
            upload_path=str(src_audio),
            upload_title="Test Audio",
            tts_text_val="",
            voice="",
            accent="",
            speed=1.0,
            tts_title="",
            youtube_url_val="",
            youtube_title="",
            existing_transcript="",
        )
        results = list(gen)

    assert len(results) > 0
    final_step = results[-1]
    # Index 7 is transcript_box
    t_box_update = final_step[7]
    assert isinstance(t_box_update, dict)
    assert t_box_update.get("interactive") is True
    assert t_box_update.get("value") == "Hello there. How are you?"


def test_export_package_stages_under_current_run_directory(tmp_path):
    work_dir = tmp_path / "work"
    service = LessonPrepService(FakeAi(), work_dir)

    # Lazy directory: does not exist until preparation begins
    assert not service.run_dir.exists()

    src_audio = tmp_path / "audio.wav"
    create_dummy_wav(src_audio, 2.0)
    service.select_uploaded_audio(src_audio)
    assert service.run_dir.exists()

    service.state.title = "Staged Package Test"
    service.run_stt()
    service.run_alignment()

    # Default export: stages inside .work/<run-id>/export/
    pkg_path = service.export_package()
    assert pkg_path.exists()
    assert pkg_path.parent == service.run_dir / "export"
    assert service.run_dir in pkg_path.parents


def test_reset_purges_workspace_and_staged_export(tmp_path):
    from lesson_prep.ui_app import run_clear

    work_dir = tmp_path / "work"
    service_a = LessonPrepService(FakeAi(), work_dir)
    service_b = LessonPrepService(FakeAi(), work_dir)

    src_audio = tmp_path / "audio.wav"
    create_dummy_wav(src_audio, 2.0)
    service_a.select_uploaded_audio(src_audio)
    service_b.select_uploaded_audio(src_audio)

    service_a.state.title = "Session A"
    service_b.state.title = "Session B"
    service_a.run_stt()
    service_b.run_stt()
    service_a.run_alignment()
    service_b.run_alignment()

    pkg_a = service_a.export_package()
    pkg_b = service_b.export_package()

    assert pkg_a.exists()
    assert pkg_b.exists()
    dir_a = service_a.run_dir
    dir_b = service_b.run_dir

    # Reset session A
    res = run_clear(service_a)
    fresh_service_c = res[0]

    # Workspace A and its staged ZIP are completely gone
    assert not dir_a.exists()
    assert not pkg_a.exists()

    # Workspace B and its staged ZIP are untouched
    assert dir_b.exists()
    assert pkg_b.exists()

    # Fresh service C does not create directory until preparation starts
    assert not fresh_service_c.run_dir.exists()
    fresh_service_c.select_uploaded_audio(src_audio)
    assert fresh_service_c.run_dir.exists()


def test_session_disposal_callback_purges_owned_workspace(tmp_path):
    work_dir = tmp_path / "work"
    service = LessonPrepService(FakeAi(), work_dir)

    src_audio = tmp_path / "audio.wav"
    create_dummy_wav(src_audio, 2.0)
    service.select_uploaded_audio(src_audio)
    service.state.title = "Disposal Test"
    service.run_stt()
    service.run_alignment()

    pkg = service.export_package()
    assert pkg.exists()
    assert service.run_dir.exists()

    # Simulating Gradio delete_callback
    deleted_count = service.clear_work_dir()
    assert deleted_count > 0
    assert not service.run_dir.exists()
    assert not pkg.exists()


def test_browser_facing_export_path_validity_lifecycle(tmp_path):
    from lesson_prep.ui_app import run_export, run_clear

    work_dir = tmp_path / "work"
    service = LessonPrepService(FakeAi(), work_dir)

    src_audio = tmp_path / "audio.wav"
    create_dummy_wav(src_audio, 2.0)
    service.select_uploaded_audio(src_audio)
    service.state.title = "Browser Download Lifecycle"
    service.run_stt()
    service.run_alignment()

    # Export via UI handler
    package_path_str, banner_html, log_md = run_export(service)
    assert package_path_str is not None
    downloadable_path = Path(package_path_str)

    # Remains valid for browser download until Reset
    assert downloadable_path.exists()
    assert downloadable_path.parent == service.run_dir / "export"
    assert "ready for download" in banner_html.lower()

    # Reset occurs -> workspace and staged file are deleted
    run_clear(service)
    assert not downloadable_path.exists()
