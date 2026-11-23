import asyncio

import pytest

from app.runtime.execution import run_serialized


@pytest.mark.asyncio
async def test_cancellation_keeps_gpu_lock_until_inference_finishes():
    lock = asyncio.Lock()
    started, finish = asyncio.Event(), asyncio.Event()
    calls = []

    async def infer(name):
        calls.append(name)
        if name == 'stt':
            started.set()
            await finish.wait()
        return name

    stt = asyncio.create_task(run_serialized(lock, infer, 'stt'))
    await asyncio.wait_for(started.wait(), timeout=1)
    stt.cancel()
    alignment = asyncio.create_task(run_serialized(lock, infer, 'alignment'))
    try:
        await asyncio.sleep(0)
        stt.cancel()
        assert not stt.done()
        assert calls == ['stt']
    finally:
        finish.set()
        with pytest.raises(asyncio.CancelledError):
            await stt
        assert await alignment == 'alignment'
