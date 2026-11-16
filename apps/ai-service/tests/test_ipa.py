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
    raw = "ɹˈoʊzᵻz bˈjuːɾɪfəl"
    monkeypatch.setattr("app.runtime.ipa._transcribe", lambda text, voice, version: raw)
    result = await IpaRuntime().analyze(request(["Roses are beautiful."], accent))
    item = result.output["items"][0]
    assert item["ipa_raw"] == raw
    assert item["ipa"] == "rˈoʊz(ɪ/ə)z bˈjuːɾɪfəl"
    assert result.output["accent"] == accent
    assert item["display_version"] == "learner-ipa-v1"
