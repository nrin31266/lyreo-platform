import asyncio


async def run_serialized(lock, operation, *args):
    async with lock:
        task = asyncio.create_task(operation(*args))
        try:
            return await asyncio.shield(task)
        except asyncio.CancelledError:
            # Cancelling a request cannot stop a model's worker thread or release its lock.
            while not task.done():
                try:
                    await asyncio.shield(task)
                except asyncio.CancelledError:
                    continue
                except Exception:
                    break
            if not task.cancelled():
                task.exception()
            raise
