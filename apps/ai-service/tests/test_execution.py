import asyncio

import pytest

from app.runtime.execution import run_serialized


@pytest.mark.asyncio
@pytest.mark.parametrize('cancel', [False, True])
async def test_worker_failure_releases_lock_and_preserves_cancellation(cancel):
    lock = asyncio.Lock()
    started, finish = asyncio.Event(), asyncio.Event()

    async def fail():
        started.set()
        await finish.wait()
        raise ValueError('worker failed')

    task = asyncio.create_task(run_serialized(lock, fail))
    await started.wait()
    if cancel:
        task.cancel()
        await asyncio.sleep(0)
        assert lock.locked()
    finish.set()
    with pytest.raises(asyncio.CancelledError if cancel else ValueError):
        await task
    assert not lock.locked()


@pytest.mark.asyncio
async def test_cancelled_queued_request_never_starts_operation():
    lock = asyncio.Lock()
    started = False

    async def operation():
        nonlocal started
        started = True

    async with lock:
        task = asyncio.create_task(run_serialized(lock, operation))
        await asyncio.sleep(0)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    assert not started
