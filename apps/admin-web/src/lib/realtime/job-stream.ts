import { env } from '@/config/env';
import { userManager } from '@/lib/oidc/client';

export type ConnectionStatus = 'connecting' | 'connected' | 'polling';
export type JobEvent = { jobId: string; status: string; currentStep?: string; progressPercent?: number };

/** Incremental SSE framing also accepts comments, CRLF and multi-line data. */
export function eventFrames(onEvent: (name: string, value: unknown) => void) {
  let buffer = '';
  return (chunk: string) => {
    buffer += chunk;
    if (buffer.length > 262144) throw new Error('Realtime event exceeds size limit');
    let match: RegExpExecArray | null;
    while ((match = /\r?\n\r?\n/.exec(buffer))) {
      const frame = buffer.slice(0, match.index); buffer = buffer.slice(match.index + match[0].length);
      const lines = frame.split(/\r?\n/);
      const name = lines.find(line => line.startsWith('event:'))?.slice(6).trim() ?? 'message';
      const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n');
      if (!data) continue;
      try { onEvent(name, JSON.parse(data)); } catch { /* A malformed notification cannot replace a server snapshot. */ }
    }
  };
}

export async function connectJobs(signal: AbortSignal, onStatus: (status: ConnectionStatus) => void,
  onJob: (job: JobEvent) => void, resync: () => void) {
  let attempt = 0;
  while (!signal.aborted) {
    const connection = new AbortController();
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    const abort = () => { connection.abort(); void reader?.cancel().catch(() => {}); };
    signal.addEventListener('abort', abort, { once: true });
    let expiry: ReturnType<typeof setTimeout> | undefined;
    let idle: ReturnType<typeof setTimeout> | undefined;
    try {
      onStatus(attempt ? 'polling' : 'connecting');
      let user = await userManager.getUser();
      if (user?.expired) user = await userManager.signinSilent();
      if (!user?.access_token || signal.aborted) { onStatus('polling');return; }
      if (user.expires_at) expiry = setTimeout(abort, Math.max(1000, user.expires_at * 1000 - Date.now()));
      const response = await fetch(`${env.apiBaseUrl}/api/v1/admin/realtime/jobs`, {
        headers: { Authorization: `Bearer ${user.access_token}`, Accept: 'text/event-stream' }, signal: connection.signal,
      });
      if (response.status === 401) { await userManager.signinSilent(); throw new Error('Renew stream authentication'); }
      if (response.status === 403) { onStatus('polling'); return; }
      if (!response.ok || !response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('Realtime unavailable');
      reader = response.body.getReader();
      const decode = new TextDecoder();
      const parse = eventFrames((name, data) => {
        if (name === 'connected') { attempt = 0; onStatus('connected'); resync(); }
        else if (name === 'job-progress' && data && typeof data === 'object' && 'jobId' in data && typeof data.jobId === 'string') onJob(data as JobEvent);
      });
      while (!connection.signal.aborted) {
        idle = setTimeout(abort, 45000);
        const { value, done } = await reader.read();
        clearTimeout(idle);
        if (done) break;
        parse(decode.decode(value, { stream: true }));
      }
    } catch { /* Persisted state remains available through HTTP polling. */ }
    finally {
      clearTimeout(expiry); clearTimeout(idle); connection.abort();
      try { await reader?.cancel(); } catch { /* The transport may already be closed. */ }
      signal.removeEventListener('abort', abort);
    }
    if (signal.aborted) break;
    onStatus('polling');
    const delay = Math.min(30000, 1000 * 2 ** Math.min(attempt++, 5) * (0.8 + Math.random() * 0.4));
    await new Promise<void>(resolve => {
      const complete = () => { clearTimeout(timer); signal.removeEventListener('abort', complete); resolve(); };
      const timer = setTimeout(complete, delay);
      signal.addEventListener('abort', complete, { once: true });
    });
  }
}
