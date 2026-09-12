from pathlib import Path

import pytest

from lesson_prep.prep_service import (
    LessonPrepService,
    PrepError,
    build_sentences,
)


class FakeAi:
    def __init__(self):
        self.voices_called = False

    def stt(self, audio_ref, provider="LOCAL_QWEN", model=None, language="English"):
        return {"text": "Hello there. How are you?", "language": "English",
                "provider": provider, "model": model or "qwen-asr"}

    def align(self, audio_ref, text, provider="LOCAL_QWEN", model=None, language="English"):
        return {"words": [
            {"index": 0, "word": "Hello", "start_ms": 0, "end_ms": 400},
            {"index": 1, "word": "there.", "start_ms": 400, "end_ms": 700},
            {"index": 2, "word": "How", "start_ms": 800, "end_ms": 1000},
            {"index": 3, "word": "are", "start_ms": 1000, "end_ms": 1200},
            {"index": 4, "word": "you?", "start_ms": 1200, "end_ms": 1500},
        ], "provider": provider, "model": model or "qwen-aligner"}

    def tts(self, text, voice, accent, speed=1.0, provider="LOCAL_KOKORO", model=None):
        return {"audio_bytes": b"RIFFMOCKWAV", "mime_type": "audio/wav",
                "provider": provider, "model": model or "Kokoro-82M",
                "voice": voice, "accent": accent}

    def voices(self):
        return [{"provider": "LOCAL_KOKORO", "voice_id": "af_heart", "accent": "US"}]


class FakeCore:
    def __init__(self):
        self.uploads = []
        self.imports = []

    def upload_media(self, kind, path):
        key = f"lessons/media/{kind.lower()}/fake-{len(self.uploads)}.bin"
        self.uploads.append((kind, str(path)))
        return {"objectKey": key, "contentType": "audio/wav", "size": 12,
                "sha256": "abc", "downloadUrl": f"https://storage.invalid/{key}"}

    def import_prepared(self, source):
        self.imports.append(source)
        return {"lessonId": "00000000-0000-0000-0000-000000000001"}


@pytest.fixture
def service(tmp_path):
    core = FakeCore()
    return LessonPrepService(FakeAi(), core, tmp_path), core


def test_upload_flow_stt_edit_align_export(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"RIFFfake")

    service.select_uploaded_audio(audio)
    service.state.title = "Upload lesson"
    service.upload_canonical_audio()
    service.run_stt()
    assert service.state.transcript == "Hello there. How are you?"
    assert not service.state.alignment_fresh

    service.run_alignment()
    assert service.state.alignment_fresh
    assert [s.text for s in service.state.sentences] == ["Hello there.", "How are you?"]

    ready, problems = service.export_readiness()
    assert ready, problems
    source = service.build_export()
    assert source.source.kind == "AUDIO"
    assert source.source.origin == "UPLOAD"
    assert source.media.canonical_audio_object_key.startswith("lessons/media/audio/")
    assert source.content.sentences[0].words[0].text == "Hello"
    assert source.preparation.stt.provider == "LOCAL_QWEN"
    assert source.preparation.alignment.provider == "LOCAL_QWEN"


def test_transcript_edit_invalidates_alignment(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"RIFFfake")

    service.select_uploaded_audio(audio)
    service.upload_canonical_audio()
    service.run_stt()
    service.run_alignment()
    assert service.state.alignment_fresh

    service.set_transcript("Edited transcript.")
    assert not service.state.alignment_fresh
    assert service.state.alignment is None
    ready, problems = service.export_readiness()
    assert not ready
    assert any("stale" in p for p in problems)

    service.run_alignment()
    assert service.state.alignment_fresh


def test_tts_flow_marks_preparation_and_skips_stt(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)

    path = service.generate_audio_from_text("Synthetic speech.", voice="af_heart",
                                            accent="US", speed=1.0)
    assert path.exists()
    assert service.state.mode == "tts"
    assert service.state.tts.voice == "af_heart"
    assert service.state.tts.accent == "US"
    assert service.state.tts.speed == 1.0

    service.state.title = "TTS lesson"
    service.upload_canonical_audio()
    service.run_alignment()

    source = service.build_export()
    assert source.source.origin == "TTS_GENERATED"
    assert source.preparation.tts.provider == "LOCAL_KOKORO"
    assert source.preparation.stt is None


def test_export_requires_every_step(tmp_path):
    service = LessonPrepService(FakeAi(), FakeCore(), tmp_path)
    ready, problems = service.export_readiness()
    assert not ready
    assert any("title" in p for p in problems)
    assert any("canonical audio" in p for p in problems)
    assert any("transcript" in p for p in problems)
    assert any("alignment" in p for p in problems)
    with pytest.raises(PrepError):
        service.build_export()


def test_build_sentences_distributes_words_deterministically():
    words = [
        {"index": i, "word": w, "start_ms": i * 100, "end_ms": i * 100 + 80}
        for i, w in enumerate(["One", "two.", "Three", "four."])
    ]
    sentences = build_sentences("One two. Three four.", words)
    assert [s.text for s in sentences] == ["One two.", "Three four."]
    assert [w.text for w in sentences[0].words] == ["One", "two."]
    assert [w.text for w in sentences[1].words] == ["Three", "four."]
    assert sentences[0].start_ms == 0
    assert sentences[1].start_ms == 200


def test_upload_canonical_audio_only_once(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"RIFFfake")
    service.select_uploaded_audio(audio)

    first = service.upload_canonical_audio()
    second = service.upload_canonical_audio()
    assert first["objectKey"] == second["objectKey"]
    assert len(core.uploads) == 1


def test_upload_thumbnail_reports_existing_key_without_reupload(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"RIFFfake")
    service.select_uploaded_audio(audio)
    service.state.thumbnail_path = tmp_path / "thumb.jpg"
    (tmp_path / "thumb.jpg").write_bytes(b"img")

    first = service.upload_thumbnail()
    second = service.upload_thumbnail()
    assert first is not None and second is not None
    assert second["objectKey"] == first["objectKey"]
    # audio (1) + thumbnail (1) only
    assert len([u for u in core.uploads if u[0] == "IMAGE"]) == 1


def test_build_sentences_clamps_cross_sentence_overlaps():
    words = [
        {"index": 0, "word": "One", "start_ms": 0, "end_ms": 500},
        {"index": 1, "word": "two.", "start_ms": 500, "end_ms": 900},
        {"index": 2, "word": "Three", "start_ms": 850, "end_ms": 1200},
        {"index": 3, "word": "four.", "start_ms": 1200, "end_ms": 1500},
    ]
    sentences = build_sentences("One two. Three four.", words)
    assert sentences[0].start_ms == 0 and sentences[0].end_ms == 900
    # overlapping 850 gets clamped to the previous sentence end (900)
    assert sentences[1].start_ms == 900
    assert sentences[1].end_ms == 1500


def test_validate_uses_full_export_validation(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"RIFFfake")
    service.select_uploaded_audio(audio)
    service.state.title = "Lesson"
    service.upload_canonical_audio()
    service.run_stt()
    service.run_alignment()
    ready, problems = service.export_readiness()
    assert ready, problems


def test_build_sentences_gives_zero_duration_words_minimum_width():
    words = [
        {"index": 0, "word": "We", "start_ms": 0, "end_ms": 300},
        {"index": 1, "word": "are", "start_ms": 300, "end_ms": 300},
        {"index": 2, "word": "here.", "start_ms": 300, "end_ms": 800},
    ]
    sentences = build_sentences("We are here.", words)
    assert sentences[0].words[1].end_ms > sentences[0].words[1].start_ms
    assert sentences[0].words[1].end_ms - sentences[0].words[1].start_ms >= 50
    assert sentences[0].words[2].start_ms >= sentences[0].words[1].end_ms


def test_build_sentences_handles_zero_duration_word_clusters():
    words = [
        {"word": "Today", "start_ms": 2720, "end_ms": 3040},
        {"word": "we", "start_ms": 3040, "end_ms": 3200},
        {"word": "are", "start_ms": 3200, "end_ms": 3200},
        {"word": "going", "start_ms": 3200, "end_ms": 3440},
        {"word": "to", "start_ms": 3520, "end_ms": 3520},
        {"word": "practice", "start_ms": 3520, "end_ms": 4000},
        {"word": "English", "start_ms": 4000, "end_ms": 4400},
        {"word": "listening.", "start_ms": 4400, "end_ms": 4880},
    ]
    sentences = build_sentences("Today we are going to practice English listening.", words)
    assert len(sentences) == 1
    w_list = sentences[0].words
    for i, w in enumerate(w_list):
        assert w.end_ms > w.start_ms, f"{w.text} has zero duration"
        if i > 0:
            assert w.start_ms >= w_list[i - 1].end_ms, f"{w.text} overlaps with {w_list[i-1].text}"



def test_export_file_naming_uses_title_timestamp_and_never_collides(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)
    audio = tmp_path / "audio.wav"
    audio.write_bytes(b"RIFFfake")
    service.select_uploaded_audio(audio)
    service.state.title = "My Lesson: Unit 1!"
    service.upload_canonical_audio()
    service.run_stt()
    service.run_alignment()

    exports = tmp_path / "exports"
    first = service.write_export_file(exports)
    second = service.write_export_file(exports)
    assert first.exists() and second.exists()
    assert first != second
    assert first.name.startswith("My-Lesson-Unit-1-")
    assert first.name.endswith(".lesson-source.json")
    assert "!" not in first.name and ":" not in first.name


def test_export_includes_tts_speed(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)
    service.generate_audio_from_text("Synthetic speech.", voice="af_heart",
                                     accent="US", speed=1.25)
    service.state.title = "TTS"
    service.upload_canonical_audio()
    service.run_alignment()
    exported = service.build_export().export_dict()
    assert exported["preparation"]["tts"]["speed"] == 1.25


def test_build_sentences_preserves_lexical_punctuation():
    text = "Every weekday, Emma wakes up at 6:30 a.m., makes a cup of coffee, and checks her schedule before going to work."
    # Simulated CTC forced aligner output which strips punctuation and numbers
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
        {"word": "before", "start_ms": 3700, "end_ms": 3950},
        {"word": "going", "start_ms": 3950, "end_ms": 4200},
        {"word": "to", "start_ms": 4200, "end_ms": 4350},
        {"word": "work", "start_ms": 4350, "end_ms": 4700},
    ]
    sentences = build_sentences(text, raw_aligned)
    assert len(sentences) == 1
    words = sentences[0].words
    assert len(words) == len(text.split())
    # Crucial assertions: lexical punctuation, colons, commas, dots, numbers are preserved
    assert words[0].text == "Every"
    assert words[1].text == "weekday,"
    assert words[2].text == "Emma"
    assert words[6].text == "6:30"
    assert words[7].text == "a.m.,"
    assert words[12].text == "coffee,"
    assert words[20].text == "work."

    # Timestamps are strictly monotonic and positive
    for i, w in enumerate(words):
        assert w.end_ms > w.start_ms
        if i > 0:
            assert w.start_ms >= words[i - 1].end_ms


def test_build_sentences_handles_compound_words_with_split_alignment():
    text = "She loves ice-cream very much."
    # Aligner split 'ice-cream' into two words: 'ice' and 'cream'
    raw_aligned = [
        {"word": "she", "start_ms": 0, "end_ms": 200},
        {"word": "loves", "start_ms": 200, "end_ms": 500},
        {"word": "ice", "start_ms": 500, "end_ms": 700},
        {"word": "cream", "start_ms": 700, "end_ms": 900},
        {"word": "very", "start_ms": 900, "end_ms": 1100},
        {"word": "much", "start_ms": 1100, "end_ms": 1400},
    ]
    sentences = build_sentences(text, raw_aligned)
    words = sentences[0].words
    assert [w.text for w in words] == ["She", "loves", "ice-cream", "very", "much."]
    # ice-cream spans from 'ice' start (500) to 'cream' end (900)
    assert words[2].start_ms == 500
    assert words[2].end_ms == 900


def test_clear_work_dir_purges_files(tmp_path):
    core = FakeCore()
    service = LessonPrepService(FakeAi(), core, tmp_path)
    (tmp_path / "temp1.wav").write_bytes(b"123")
    (tmp_path / "temp2.m4a").write_bytes(b"456")
    service.state.title = "Temporary Lesson"
    deleted = service.clear_work_dir()
    assert deleted == 2
    assert not (tmp_path / "temp1.wav").exists()
    assert not (tmp_path / "temp2.m4a").exists()
    assert service.state.title == ""


