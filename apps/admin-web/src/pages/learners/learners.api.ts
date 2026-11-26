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

export const entitlementFeatureKeys = [
  'lesson.plus-lessons',
  'vocabulary.plus-packs',
  'vocabulary.saved-cards',
  'speech.shadowing',
  'toeic.full-access',
  'grammar.advanced',
  'chat.tutor',
] as const;

export type EntitlementFeatureKey = (typeof entitlementFeatureKeys)[number];

export type EntitlementGrantEntry = {
  id: string;
  userId: string;
  featureKey: string;
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED' | string;
  validFrom: string;
  validUntil: string | null;
  sourceType: string | null;
  sourceReference: string | null;
  createdBy: string;
  createdAt: string;
  revokedAt: string | null;
  revokedBy: string | null;
  revocationReason: string | null;
};

export type CreateEntitlementGrantInput = {
  userId: string;
  featureKey: string;
  validFrom?: string;
  validUntil?: string | null;
  sourceType?: string;
  sourceReference?: string;
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

const entitlementsBase = '/api/v1/admin/entitlements/grants';
const gamificationBase = '/api/v1/admin/gamification/learners';

export function fetchLearnerGrants(userId: string, signal?: AbortSignal) {
  const params = new URLSearchParams({ userId });
  return api<EntitlementGrantEntry[]>(`${entitlementsBase}?${params}`, { signal });
}

export function createLearnerGrant(input: CreateEntitlementGrantInput) {
  return api<EntitlementGrantEntry>(entitlementsBase, {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function revokeLearnerGrant(grantId: string, reason: string) {
  return api<EntitlementGrantEntry>(`${entitlementsBase}/${encodeURIComponent(grantId)}/revoke`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
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
