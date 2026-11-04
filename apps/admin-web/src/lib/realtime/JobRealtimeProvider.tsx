import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { userManager } from '@/lib/oidc/client';
import { connectJobs, type ConnectionStatus } from './job-stream';
import type { LessonDraft } from '@/pages/lesson-builder/lesson-builder.types';

const Realtime = createContext<ConnectionStatus>('polling');
export const useJobConnection = () => useContext(Realtime);

export function JobRealtimeProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const [status, setStatus] = useState<ConnectionStatus>('connecting');
  useEffect(() => {
    const abort = new AbortController();
    const jobs = new Set<string>();
    let pending: ReturnType<typeof setTimeout> | undefined;
    function refresh() {
      const ids = new Set(jobs); jobs.clear(); pending = undefined;
      for (const [key, draft] of client.getQueriesData<LessonDraft>({ queryKey: ['authoring'] })) {
        if (draft?.jobs.some(job => ids.has(job.jobId))) void client.invalidateQueries({ queryKey: key, exact: true });
      }
      for (const id of ids) void client.invalidateQueries({ queryKey: ['job', id] });
    }
    const logout = () => abort.abort();
    userManager.events.addUserUnloaded(logout);
    void connectJobs(abort.signal, setStatus, job => {
      jobs.add(job.jobId);
      pending ??= setTimeout(refresh, 100);
    }, () => {
      void client.invalidateQueries({ queryKey: ['authoring'] });
      void client.invalidateQueries({ queryKey: ['job'] });
    });
    return () => { abort.abort(); clearTimeout(pending); userManager.events.removeUserUnloaded(logout); };
  }, [client]);
  return <Realtime.Provider value={status}>{children}</Realtime.Provider>;
}
