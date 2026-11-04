import { act, cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, expect, it, vi } from 'vitest';
import { JobRealtimeProvider, useJobConnection } from './JobRealtimeProvider';
import type { JobEvent, ConnectionStatus } from './job-stream';

const stream = vi.hoisted(() => ({ connect: vi.fn(), add: vi.fn(), remove: vi.fn() }));
vi.mock('./job-stream', () => ({ connectJobs: stream.connect }));
vi.mock('@/lib/oidc/client', () => ({ userManager: { events: { addUserUnloaded: stream.add, removeUserUnloaded: stream.remove } } }));
afterEach(() => { cleanup(); vi.useRealTimers(); vi.clearAllMocks(); });
function Status() { return <span>{useJobConnection()}</span>; }

it('coalesces notifications for matching drafts and resynchronizes authoritative queries after reconnect', async () => {
  vi.useFakeTimers();
  let notify!: (job: JobEvent) => void, resync!: () => void;
  stream.connect.mockImplementation((_signal: AbortSignal, status: (value: ConnectionStatus) => void, job: typeof notify, sync: typeof resync) => {
    notify=job; resync=sync; status('connected'); return Promise.resolve();
  });
  const client=new QueryClient();
  client.setQueryData(['authoring','a'], { jobs:[{jobId:'job-a'}] });
  client.setQueryData(['authoring','b'], { jobs:[{jobId:'job-b'}] });
  const invalidate=vi.spyOn(client,'invalidateQueries');
  render(<QueryClientProvider client={client}><JobRealtimeProvider><Status /></JobRealtimeProvider></QueryClientProvider>);
  expect(screen.getByText('connected')).toBeInTheDocument();
  act(() => { notify({jobId:'job-a',status:'RUNNING'}); notify({jobId:'job-a',status:'SUCCEEDED'}); });
  await act(async () => { await vi.advanceTimersByTimeAsync(100); });
  expect(invalidate.mock.calls.map(([args])=>args?.queryKey)).toEqual([['authoring','a'],['job','job-a']]);
  invalidate.mockClear();
  act(() => resync());
  expect(invalidate.mock.calls.map(([args])=>args?.queryKey)).toEqual([['authoring'],['job']]);
});

it('aborts on logout and cancels queued refreshes when the layout unmounts', async () => {
  vi.useFakeTimers();
  let signal!: AbortSignal, notify!: (job: JobEvent) => void;
  stream.connect.mockImplementation((value: AbortSignal, _status: unknown, job: typeof notify) => { signal=value; notify=job; return Promise.resolve(); });
  const client=new QueryClient(), invalidate=vi.spyOn(client,'invalidateQueries');
  const view=render(<QueryClientProvider client={client}><JobRealtimeProvider><Status /></JobRealtimeProvider></QueryClientProvider>);
  act(() => notify({jobId:'job-a',status:'SUCCEEDED'}));
  act(() => stream.add.mock.calls[0][0]());
  expect(signal.aborted).toBe(true);
  view.unmount();
  await vi.advanceTimersByTimeAsync(100);
  expect(invalidate).not.toHaveBeenCalled();
  expect(stream.remove).toHaveBeenCalledWith(stream.add.mock.calls[0][0]);
});
