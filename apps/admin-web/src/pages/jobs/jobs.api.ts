import { api } from '@/lib/api';
import type { Job } from './jobs.types';

export async function fetchJob(id: string): Promise<Job> {
  return api<Job>(`/api/v1/jobs/${id}`);
}

export async function cancelJob(id: string): Promise<void> {
  return api<void>(`/api/v1/jobs/${id}/cancel`, { method: 'POST' });
}
