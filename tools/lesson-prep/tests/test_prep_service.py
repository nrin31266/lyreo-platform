import io
import shutil
import wave
from pathlib import Path

import pytest

from lesson_prep.package_writer import verify_package
from lesson_prep.prep_service import (
    LessonPrepService,
    PrepError,
    build_sentences_with_monotonic_mapping,
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
