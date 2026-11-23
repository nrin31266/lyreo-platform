import { api, http } from '@/lib/api';
import type { AvatarPreset } from './learner-avatar-presets';
export type { AvatarPreset } from './learner-avatar-presets';

export type OnboardingStatus = 'PENDING' | 'COMPLETED';

export type LearnerEntry = {
  learnerId: string;
  displayName: string | null;
  email: string | null;
  firstSeenAt: string;
  onboardingStatus: OnboardingStatus;
  onboardingCompletedAt: string | null;
  goal: string | null;
  currentLevel: string | null;
  dailyMinutes: number | null;
  focusArea: string | null;
  avatarPreset: AvatarPreset | null;
  avatarUploadId: string | null;
};

type LearnerPage = {
  items: LearnerEntry[];
  total: number;
  page: number;
  size: number;
};

const base = '/api/v1/admin/learners';

export function fetchLearners(page: number, query: string, status: OnboardingStatus | '', signal?: AbortSignal) {
  const params = new URLSearchParams({ page: String(page) });
  if (query) params.set('query', query);
  if (status) params.set('status', status);
  return api<LearnerPage>(`${base}?${params}`, { signal });
}

export function fetchLearner(id: string, signal?: AbortSignal) {
  return api<LearnerEntry>(`${base}/${encodeURIComponent(id)}`, { signal });
}

export function updateLearnerAppearance(id: string, fields: { displayName?: string | null; avatarPreset?: AvatarPreset | null }) {
  return api<LearnerEntry>(`${base}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify(fields),
  });
}

export function uploadLearnerAvatar(id: string, file: File) {
  const body = new FormData();
  body.append('file', file);
  return api<LearnerEntry>(`${base}/${encodeURIComponent(id)}/avatar`, { method: 'PUT', body });
}

export async function fetchLearnerAvatar(id: string, signal?: AbortSignal) {
  const response = await http.get<Blob>(`${base}/${encodeURIComponent(id)}/avatar`, { signal, responseType: 'blob' });
  return response.data;
}
