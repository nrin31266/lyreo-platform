import shutil
import pytest
from app.runtime.ipa import IpaRuntime, _transcribe
from app.schemas import ExecuteRequest


def request(sentences, accent="US"):
    return ExecuteRequest(invocation_id="ipa-test", provider="LOCAL_NLP", model="espeak-ng", input={"sentences": sentences}, options={"accent": accent})


@pytest.mark.asyncio
async def test_rejects_oversized_or_malformed_input_before_execution():
    for sentences in ([], [None], ["x" * 10001]):
        with pytest.raises(ValueError):
            await IpaRuntime().analyze(request(sentences))
    with pytest.raises(ValueError):
        await IpaRuntime().analyze(request(["Hello."], "unsupported"))


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which("espeak-ng"), reason="espeak-ng is not installed")
async def test_real_local_ipa_preserves_identity_flags_hard_cases_and_caches():
    result = await IpaRuntime().analyze(request(["We read a book.", "Call Dr. Nguyen at 9.", "Hello there."]))
    items = result.output["items"]
    assert [item["i"] for item in items] == [1, 2, 3]
    assert items[0]["needs_review"] and items[1]["needs_review"]
    assert not items[2]["needs_review"]
    assert all(item["ipa"] and "mock" not in item["ipa"] for item in items)
    before = _transcribe.cache_info().hits
    await IpaRuntime().analyze(request(["Hello there."]))
    assert _transcribe.cache_info().hits > before
    assert result.metadata["reference_only"]


@pytest.mark.asyncio
@pytest.mark.parametrize("accent", ["US", "UK"])
async def test_display_preserves_raw_stress_flap_accent_and_weak_vowels(monkeypatch, accent):
    monkeypatch.setattr("app.runtime.ipa._version", lambda: "espeak:test")
    raw = "ɹˈoʊzᵻz ɑːɹ bjˈuːɾifəl"
    monkeypatch.setattr("app.runtime.ipa._transcribe", lambda text, voice, version: raw)
    result = await IpaRuntime().analyze(request(["Roses are beautiful."], accent))
    item = result.output["items"][0]
    assert item["ipa_raw"] == raw
    assert item["ipa"] == "rˈoʊzɪz ɑːr bjˈuːtifəl"
    assert result.output["accent"] == accent
    assert item["display_version"] == "learner-ipa-v2"


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which("espeak-ng"), reason="espeak-ng is not installed")
async def test_real_ipa_handles_quotes_final_words_and_connected_speech():
    sentences = ["I shall be late!’", "book", "room", "with the book in the room", "a book", "water", "ladder", "written"]
    for accent in ("US", "UK"):
        items = (await IpaRuntime().analyze(request(sentences, accent))).output["items"]
        assert items[0]["ipa"] == (await IpaRuntime().analyze(request(["I shall be late!"], accent))).output["items"][0]["ipa"]
        assert items[1]["ipa"] == "bˈʊk"
        assert items[2]["ipa"].endswith("m")
        assert "ð ð" in items[3]["ipa"] and "n ð" in items[3]["ipa"]
        assert items[4]["ipa"].startswith("ə ")
        assert "t" in items[5]["ipa"] and "ɾ" not in items[5]["ipa"]
        assert "d" in items[6]["ipa"]
        assert items[7]["ipa"] == "rˈɪtən"


@pytest.mark.asyncio
async def test_prepares_numbers_and_reports_original_review_spans(monkeypatch):
    spoken = []
    monkeypatch.setattr("app.runtime.ipa._version", lambda: "espeak:test")
    monkeypatch.setattr("app.runtime.ipa._transcribe", lambda text, voice, version: spoken.append(text) or "ə")
    text = "😀 In 1066, 1,066 people used B12 at 7:05. $45.50 and 15%. Chad read Chaucer on 03/04/2026. sk at ted.com"
    item = (await IpaRuntime().analyze(request([text]))).output["items"][0]
    assert "In 10 66, 1066 people used B 1 2 at 7 oh 5" in spoken[0]
    assert "45 dollars and 50 cents" in spoken[0] and "15 percent" in spoken[0]
    issues = {(issue["surface"], issue["code"]) for issue in item["review_issues"]}
    assert {("Chaucer", "NAME"), ("read", "MULTIPLE_READINGS"), ("03/04/2026", "DATE_FORMAT"),
            ("sk", "LETTER_OR_SOUND"), ("ted.com", "URL")} <= issues
    original = text.encode("utf-16-le")
    for issue in item["review_issues"]:
        assert original[issue["start"] * 2:issue["end"] * 2].decode("utf-16-le") == issue["surface"]


@pytest.mark.asyncio
@pytest.mark.skipif(not shutil.which("espeak-ng"), reason="espeak-ng is not installed")
@pytest.mark.parametrize("accent,nurse", [("US", "ɜr"), ("UK", "ɜː")])
async def test_keeps_phonemic_contrasts_and_flags_ambiguous_readings(accent, nurse):
    sentences = ["ship sheep", "thin then", "nurse", "We read and lead.", "I don’t read English’s history."]
    items = (await IpaRuntime().analyze(request(sentences, accent))).output["items"]
    assert "ɪ" in items[0]["ipa"].split()[0] and "iː" in items[0]["ipa"].split()[1]
    assert items[1]["ipa"].startswith("θ") and " ð" in items[1]["ipa"]
    assert nurse in items[2]["ipa"]
    assert [(issue["surface"], issue["code"]) for issue in items[3]["review_issues"]] == [
        ("read", "MULTIPLE_READINGS"), ("lead", "MULTIPLE_READINGS"),
    ]
    assert "rr" not in items[4]["ipa"]
    ascii_quotes = (await IpaRuntime().analyze(request(["I don't read English's history."], accent))).output["items"]
    assert items[4]["ipa"] == ascii_quotes[0]["ipa"]
