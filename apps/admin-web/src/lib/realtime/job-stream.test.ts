import { afterEach, expect, it, vi } from 'vitest';
import { connectJobs, eventFrames } from './job-stream';
const auth = vi.hoisted(() => ({getUser:vi.fn(),signinSilent:vi.fn()}));
vi.mock('@/lib/oidc/client', () => ({userManager:auth}));
afterEach(() => {vi.restoreAllMocks();vi.unstubAllGlobals();});
it('parses split CRLF and multi-line data, ignores heartbeat/malformed messages', () => {
  const event=vi.fn(), parse=eventFrames(event);
  parse(':heartbeat\r\n\r\nevent: job-progress\r\ndata: {"jobId":\r\n');
  parse('data: "a"}\r\n\r\nevent: connected\ndata: {}\n\ndata: invalid\n\n');
  expect(event.mock.calls).toEqual([['job-progress',{jobId:'a'}],['connected',{}]]);
});
it('authenticates with a header and cancels the reader on logout', async () => {
  auth.getUser.mockResolvedValue({access_token:'test-only-token'});
  const abort=new AbortController(),states:string[]=[],resync=vi.fn(),job=vi.fn();
  const stream=new ReadableStream({start(controller){controller.enqueue(new TextEncoder().encode('event: connected\ndata: {}\n\nevent: job-progress\ndata: {"jobId":"a"}\n\n'));}});
  const fetch=vi.fn().mockResolvedValue(new Response(stream,{headers:{'content-type':'text/event-stream'}}));vi.stubGlobal('fetch',fetch);
  const run=connectJobs(abort.signal,s=>states.push(s),value=>{job(value);abort.abort();},resync);
  await run;
  expect(fetch).toHaveBeenCalledOnce();
  expect(fetch.mock.calls[0][0]).not.toContain('test-only-token');
  expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer test-only-token');
  expect(states).toContain('connected');expect(resync).toHaveBeenCalledOnce();expect(job).toHaveBeenCalledWith({jobId:'a'});
});
it('refreshes expired authentication and does not reconnect on forbidden access', async () => {
  auth.getUser.mockResolvedValue({expired:true});auth.signinSilent.mockResolvedValue({access_token:'renewed'});
  const fetch=vi.fn().mockResolvedValue(new Response(null,{status:403}));vi.stubGlobal('fetch',fetch);
  const status=vi.fn();await connectJobs(new AbortController().signal,status,vi.fn(),vi.fn());
  expect(auth.signinSilent).toHaveBeenCalled();expect(status).toHaveBeenLastCalledWith('polling');expect(fetch).toHaveBeenCalledOnce();
});
it('backs off after failure, resynchronizes on reconnect and aborts without another request', async () => {
  vi.useFakeTimers();vi.spyOn(Math,'random').mockReturnValue(0.5);
  auth.getUser.mockResolvedValue({access_token:'test-token'});
  const abort=new AbortController(), resync=vi.fn(()=>abort.abort());
  const stream=new ReadableStream({start(c){c.enqueue(new TextEncoder().encode('event: connected\ndata: {}\n\n'));}});
  const fetch=vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(new Response(stream,{headers:{'content-type':'text/event-stream'}}));vi.stubGlobal('fetch',fetch);
  const states=vi.fn(),run=connectJobs(abort.signal,states,vi.fn(),resync);
  await vi.advanceTimersByTimeAsync(0);expect(fetch).toHaveBeenCalledTimes(1);expect(states).toHaveBeenLastCalledWith('polling');
  await vi.advanceTimersByTimeAsync(999);expect(fetch).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1);await run;
  expect(fetch).toHaveBeenCalledTimes(2);expect(resync).toHaveBeenCalledOnce();
  vi.useRealTimers();
});
it('cancels a pending stream read when the caller leaves the page', async () => {
  auth.getUser.mockResolvedValue({access_token:'test-token'});
  const cancel=vi.fn(),abort=new AbortController();
  const fetch=vi.fn().mockResolvedValue(new Response(new ReadableStream({cancel}),{headers:{'content-type':'text/event-stream'}}));vi.stubGlobal('fetch',fetch);
  const run=connectJobs(abort.signal,vi.fn(),vi.fn(),vi.fn());
  await Promise.resolve();await Promise.resolve();await Promise.resolve();
  abort.abort();await run;
  expect(cancel).toHaveBeenCalledOnce();expect(fetch).toHaveBeenCalledOnce();
});
