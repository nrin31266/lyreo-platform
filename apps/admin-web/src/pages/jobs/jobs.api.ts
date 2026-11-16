import { api } from '@/lib/api';
import type { Job, JobPage } from './jobs.types';

export async function fetchJob(id: string, signal?: AbortSignal): Promise<Job> {
  return api<Job>(`/api/v1/jobs/${id}`, { signal });
}

export async function cancelJob(id: string): Promise<void> {
  return api<void>(`/api/v1/jobs/${id}/cancel`, { method: 'POST' });
}

export function fetchJobs(params: URLSearchParams, signal?: AbortSignal): Promise<JobPage> {
  return api<JobPage>(`/api/v1/jobs?${params}`, { signal });
}
