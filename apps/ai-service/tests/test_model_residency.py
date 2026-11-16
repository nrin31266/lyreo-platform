import asyncio
import sys
import threading
from types import SimpleNamespace

import pytest

from app.config import Settings
from app.runtime.qwen import QwenRuntime
from app.schemas import ExecuteRequest


@pytest.fixture
def models(monkeypatch):
    events = []
    weights = []

    class Weights:
        def __init__(self, name):
            self.name = name
            self.device = 'cpu'
            self.dtype = 'bfloat16'
            self.fail_device = None
            self.mixed_devices = False
            weights.append(self)

        def to(self, device):
            events.append((self.name, device))
            if self.fail_device == device:
                self.fail_device = None
                self.mixed_devices = True
                raise RuntimeError('transfer failed')
            self.device = device
            self.mixed_devices = False
            assert sum(w.device.startswith('cuda') for w in weights) <= 1
            return self

    def load(name, **kwargs):
        assert kwargs['device_map'] == 'cpu'
        assert kwargs['dtype'] == 'bfloat16'
        events.append(('load', name))
        return SimpleNamespace(
            model=Weights(name), device='cpu',
            transcribe=lambda **_: [SimpleNamespace(text='Hello')],
            align=lambda **_: [{'word': 'Hello', 'start_ms': 0, 'end_ms': 100}],
        )

    monkeypatch.setitem(sys.modules, 'qwen_asr', SimpleNamespace(
        Qwen3ASRModel=SimpleNamespace(from_pretrained=load),
        Qwen3ForcedAligner=SimpleNamespace(from_pretrained=load),
    ))
    monkeypatch.setattr(QwenRuntime, '_dtype', lambda _: 'bfloat16')
    monkeypatch.setattr('app.runtime.qwen._read_audio', lambda _: [0.0] * 1600)
    monkeypatch.setattr('app.runtime.qwen._empty_cuda_cache', lambda: None)
    return events


def request():
    return ExecuteRequest(invocation_id='1', provider='QWEN', model='qwen',
                          input={'audio': '/tmp/audio.wav', 'text': 'Hello'}, options={})


@pytest.mark.asyncio
@pytest.mark.parametrize('device', ['cuda:0', 'cpu'])
async def test_qwen_reuses_models_across_stt_and_alignment(models, device):
    runtime = QwenRuntime(Settings(_env_file=None, qwen_device=device))
    first = await runtime.stt(request())
    asr = runtime._asr
    assert await runtime.stt(request()) == first
    aligned = await runtime.align(request())
    assert aligned.output['words'][0]['word'] == 'Hello'
    aligner = runtime._aligner
    assert asr.model.device == 'cpu'
    assert aligner.model.device == device
    assert await runtime.stt(request()) == first
    assert runtime._asr is asr
    assert runtime._aligner is aligner
    assert asr.model.device == asr.device == device
    assert aligner.model.device == aligner.device == 'cpu'
    assert asr.model.dtype == aligner.model.dtype == 'bfloat16'
    assert models.count(('load', runtime.cfg.qwen_asr_model)) == 1
    assert models.count(('load', runtime.cfg.qwen_aligner_model)) == 1
    moves = [event for event in models if event[0] != 'load']
    assert moves == ([] if device == 'cpu' else [
        (runtime.cfg.qwen_asr_model, device),
        (runtime.cfg.qwen_asr_model, 'cpu'),
        (runtime.cfg.qwen_aligner_model, device),
        (runtime.cfg.qwen_aligner_model, 'cpu'),
        (runtime.cfg.qwen_asr_model, device),
    ])


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', ['promotion', 'offload'])
async def test_qwen_transfer_failure_recovers_cached_models_on_cpu(models, failure):
    runtime = QwenRuntime(Settings(_env_file=None))
    await runtime.stt(request())
    await runtime.align(request())
    if failure == 'promotion':
        runtime._asr.model.fail_device = 'cuda:0'
    else:
        runtime._aligner.model.fail_device = 'cpu'
    with pytest.raises(RuntimeError, match='transfer failed'):
        await runtime.stt(request())
    assert runtime._asr.model.device == runtime._asr.device == 'cpu'
    assert runtime._aligner.model.device == runtime._aligner.device == 'cpu'
    assert not runtime._asr.model.mixed_devices
    assert not runtime._aligner.model.mixed_devices
    await runtime.stt(request())
    assert sum(event[0] == 'load' for event in models) == 2


@pytest.mark.asyncio
async def test_cancelled_qwen_request_keeps_lock_until_worker_finishes(models):
    runtime = QwenRuntime(Settings(_env_file=None))
    await runtime.stt(request())
    started, finish = threading.Event(), threading.Event()

    def transcribe(**_):
        started.set()
        assert finish.wait(5)
        return [SimpleNamespace(text='Hello')]

    runtime._asr.transcribe = transcribe
    stt = asyncio.create_task(runtime.stt(request()))
    alignment = None
    try:
        assert await asyncio.to_thread(started.wait, 5)
        stt.cancel()
        await asyncio.sleep(0)
        stt.cancel()
        alignment = asyncio.create_task(runtime.align(request()))
        await asyncio.sleep(0.02)
        assert not stt.done()
        assert not alignment.done()
        assert runtime._asr.model.device == 'cuda:0'
        assert runtime._aligner is None
    finally:
        finish.set()
        with pytest.raises(asyncio.CancelledError):
            await stt
        if alignment is not None:
            await alignment


@pytest.mark.asyncio
async def test_concurrent_qwen_calls_load_each_model_once(models):
    runtime = QwenRuntime(Settings(_env_file=None))
    await asyncio.gather(runtime.stt(request()), runtime.align(request()), runtime.stt(request()))
    assert sum(event[0] == 'load' for event in models) == 2
    assert runtime._asr.model.device == 'cuda:0'
    assert runtime._aligner.model.device == 'cpu'


@pytest.mark.asyncio
async def test_cancellation_during_initial_load_preserves_model_cache(models, monkeypatch):
    runtime = QwenRuntime(Settings(_env_file=None))
    loader = sys.modules['qwen_asr'].Qwen3ASRModel
    load = loader.from_pretrained
    started, finish = threading.Event(), threading.Event()

    def blocked_load(*args, **kwargs):
        started.set()
        assert finish.wait(5)
        return load(*args, **kwargs)

    monkeypatch.setattr(loader, 'from_pretrained', blocked_load)
    first = asyncio.create_task(runtime.stt(request()))
    try:
        assert await asyncio.to_thread(started.wait, 5)
        first.cancel()
        await asyncio.sleep(0)
        assert not first.done()
    finally:
        finish.set()
        with pytest.raises(asyncio.CancelledError):
            await first
    assert runtime._asr is not None
    await runtime.stt(request())
    assert models.count(('load', runtime.cfg.qwen_asr_model)) == 1
