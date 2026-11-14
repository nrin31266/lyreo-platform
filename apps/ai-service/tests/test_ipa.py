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
