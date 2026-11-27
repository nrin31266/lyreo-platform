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
  toeicTargetScore: number | null;
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

export type LearnerPlanTier = 'FREE' | 'PLUS';

export type LearnerPlan = {
  tier: LearnerPlanTier;
  validUntil: string | null;
  history: { tier: LearnerPlanTier; changedAt: string }[];
};

export type DiamondTransactionEntry = {
  id: string;
  amount: number;
  balanceAfter?: number;
  reason: string;
  sourceType?: string | null;
  sourceReference?: string | null;
  createdAt: string;
};

export type LearnerGamificationSummary = {
  learnerId: string;
  xp: number;
  level: number;
  xpIntoLevel: number;
  xpForNextLevel: number;
  diamondBalance: number;
  currentStreak: number;
  longestStreak: number;
  lastActiveDate: string | null;
  transactions: DiamondTransactionEntry[];
};

export type AdjustLearnerDiamondsInput = {
  amount: number;
  reason: string;
  idempotencyKey: string;
};

export type AdjustDiamondsResponse = {
  transactionId: string;
  learnerId: string;
  amount: number;
  balance: number;
  reason: string;
  idempotencyKey: string;
  ruleVersion: string;
  createdAt: string;
};

const gamificationBase = '/api/v1/admin/gamification/learners';

function planUrl(learnerId: string) {
  return `/api/v1/admin/entitlements/learners/${encodeURIComponent(learnerId)}/plan`;
}

export function fetchLearnerPlan(learnerId: string, signal?: AbortSignal) {
  return api<LearnerPlan>(planUrl(learnerId), { signal });
}

export function updateLearnerPlan(learnerId: string, tier: LearnerPlanTier, durationDays?: number) {
  return api<LearnerPlan>(planUrl(learnerId), {
    method: 'PUT',
    body: JSON.stringify({ tier, durationDays }),
  });
}

export function fetchLearnerGamification(learnerId: string, signal?: AbortSignal) {
  return api<LearnerGamificationSummary>(`${gamificationBase}/${encodeURIComponent(learnerId)}`, { signal });
}

export function adjustLearnerDiamonds(learnerId: string, input: AdjustLearnerDiamondsInput) {
  return api<AdjustDiamondsResponse>(`${gamificationBase}/${encodeURIComponent(learnerId)}/diamonds`, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export type LearnerAccountStatus = {
  management: 'AVAILABLE' | 'UNCONFIGURED' | 'UNMANAGED';
  locked: boolean;
  syncPending: boolean;
};

function accountUrl(learnerId: string) {
  return `${base}/${encodeURIComponent(learnerId)}/account`;
}

export function fetchLearnerAccount(learnerId: string, signal?: AbortSignal) {
  return api<LearnerAccountStatus>(accountUrl(learnerId), { signal });
}

export function resetLearnerPassword(learnerId: string, password: string, changeOnNextLogin: boolean) {
  return api<void>(`${accountUrl(learnerId)}/password`, {
    method: 'PUT',
    body: JSON.stringify({ password, changeOnNextLogin }),
  });
}

export function setLearnerAccountLock(learnerId: string, locked: boolean, reason?: string) {
  return api<LearnerAccountStatus>(`${accountUrl(learnerId)}/lock`, {
    method: 'PUT',
    body: JSON.stringify({ locked, reason }),
  });
}
