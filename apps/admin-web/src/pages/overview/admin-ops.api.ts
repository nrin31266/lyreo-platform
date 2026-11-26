import { api, http } from '@/lib/api';

export type AnalyticsDailyAggregate = {
  activityDate: string;
  activeLearners: number;
  studySeconds: number;
  lessonCount: number;
  vocabularyReviewCount: number;
  grammarAnswerCount: number;
  toeicQuestionCount: number;
};

export type AnalyticsWeaknessHotspot = {
  weaknessType: string;
  weaknessKey: string;
  affectedLearners: number;
  averageSeverity: number;
  totalSamples: number;
  lastUpdatedAt: string | null;
};

export type AdminAnalyticsSummary = {
  activeLearnersToday: number;
  activeLearners7d: number;
  studySeconds7d: number;
  lessonsCompleted7d: number;
  vocabularyReviews7d: number;
  grammarAnswers7d: number;
  toeicQuestions7d: number;
  dailySeries: AnalyticsDailyAggregate[];
  topWeaknesses: AnalyticsWeaknessHotspot[];
};

export type LearnerActivityWindow = {
  fromDate: string;
  toDate: string;
  activeDays: number;
  studySeconds: number;
  lessonCount: number;
  vocabularyReviewCount: number;
  grammarAnswerCount: number;
  toeicQuestionCount: number;
};

export type LearnerSkillSummary = {
  skillCode: string;
  score: number;
  sampleCount: number;
  updatedAt: string | null;
};

export type LearnerWeaknessSummary = {
  weaknessType: string;
  weaknessKey: string;
  severity: number;
  sampleCount: number;
  updatedAt: string | null;
};

export type LearnerAnalyticsOverview = {
  hasHistory: boolean;
  today: LearnerActivityWindow;
  week: LearnerActivityWindow;
  skills: LearnerSkillSummary[];
  weaknesses: LearnerWeaknessSummary[];
};

export const missionMetrics = [
  'LESSON_COMPLETED',
  'SHADOWING_SENTENCE',
  'VOCABULARY_REVIEW',
  'TOEIC_DRILL_COMPLETED',
  'GRAMMAR_ANSWER',
] as const;

export type MissionMetric = (typeof missionMetrics)[number] | string;

export const missionRecurrences = ['DAILY', 'WEEKLY', 'ONE_TIME'] as const;
export type MissionRecurrence = (typeof missionRecurrences)[number] | string;

export const achievementMetrics = [
  'DISTINCT_LESSON_COMPLETED',
  'STREAK_DAYS',
  'VOCABULARY_REVIEW',
  'TOEIC_DRILL_COMPLETED',
] as const;

export const badgeTiers = ['BRONZE', 'SILVER', 'GOLD', 'PLATINUM', 'LEGENDARY'] as const;
export const missionDifficulties = ['EASY', 'NORMAL', 'HARD'] as const;

export type AdminMissionDefinition = {
  id: string;
  code: string;
  title: string;
  metric: MissionMetric;
  target: number;
  xpReward: number;
  diamondReward: number;
  recurrence: MissionRecurrence;
  ruleVersion: string;
  enabled: boolean;
  category?: string;
  difficulty?: string;
};

export type SaveMissionInput = {
  code: string;
  title: string;
  metric: MissionMetric;
  target: number;
  xpReward: number;
  diamondReward: number;
  recurrence: MissionRecurrence;
  ruleVersion?: string;
  enabled: boolean;
  category?: string;
  difficulty?: string;
};

export type AdminAchievementDefinition = {
  id: string;
  code: string;
  title: string;
  description: string;
  metric: string;
  target: number;
  xpReward: number;
  diamondReward: number;
  ruleVersion: string;
  enabled: boolean;
  badgeAssetId?: string | null;
  badgeTier?: string;
  category?: string;
};

export type SaveAchievementInput = {
  code: string;
  title: string;
  description: string;
  metric: string;
  target: number;
  xpReward: number;
  diamondReward: number;
  ruleVersion?: string;
  enabled: boolean;
  badgeAssetId?: string | null;
  badgeTier?: string;
  category?: string;
};

export type RewardPolicyConfig = {
  ruleVersion: string;
  directLessonActivityDiamondsEnabled: boolean;
  cutoverEffectiveAt: string | null;
  xpPerLevel: number;
};

export type FeatureDefinition = {
  featureKey: string;
  displayName: string;
  description: string;
  status: string;
};

export const quotaTiers = ['FREE', 'PLUS'] as const;
export const quotaLimitTypes = ['PERIOD_USAGE', 'CAPACITY'] as const;
export const quotaPeriodUnits = ['DAY', 'NONE'] as const;

export type QuotaTier = (typeof quotaTiers)[number] | string;
export type QuotaLimitType = (typeof quotaLimitTypes)[number] | string;
export type QuotaPeriodUnit = (typeof quotaPeriodUnits)[number] | string;

export type QuotaPolicy = {
  featureKey: string;
  tier: QuotaTier;
  limitType: QuotaLimitType;
  periodUnit: QuotaPeriodUnit;
  maxAmount: number;
  policyVersion: string;
};

export type UpdateQuotaPolicyInput = {
  featureKey: string;
  tier: QuotaTier;
  limitType: QuotaLimitType;
  periodUnit: QuotaPeriodUnit;
  maxAmount: number;
  policyVersion?: string;
};

export type EntitlementAuditEntry = {
  id: string;
  userId: string;
  featureKey: string;
  operationId: string | null;
  decision: string;
  policyVersion: string;
  detail: Record<string, unknown>;
  occurredAt: string;
};

export type ChatConversationSummary = {
  conversationId: string;
  learnerId: string;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
};

export type AdminChatSummary = {
  totalConversations: number;
  activeLearners: number;
  userTurnsToday: number;
  userTurns7d: number;
  recentConversations: ChatConversationSummary[];
};

export type ContentMediaItem = {
  id: string;
  mimeType: string;
  sizeBytes: number;
  filename: string | null;
  synthetic: boolean;
  createdBy: string | null;
  createdAt: string;
};

export type ContentMediaPage = {
  items: ContentMediaItem[];
  total: number;
  page: number;
  size: number;
};

export function fetchAdminAnalyticsSummary(signal?: AbortSignal) {
  return api<AdminAnalyticsSummary>('/api/v1/admin/analytics/summary', { signal });
}

export function fetchLearnerAnalytics(learnerId: string, signal?: AbortSignal) {
  return api<LearnerAnalyticsOverview>(
    `/api/v1/admin/analytics/learners/${encodeURIComponent(learnerId)}`,
    { signal },
  );
}

export function fetchAdminMissions(signal?: AbortSignal) {
  return api<AdminMissionDefinition[]>('/api/v1/admin/gamification/missions', { signal });
}

export function createAdminMission(input: SaveMissionInput) {
  return api<AdminMissionDefinition>('/api/v1/admin/gamification/missions', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateAdminMission(id: string, input: SaveMissionInput) {
  return api<AdminMissionDefinition>(
    `/api/v1/admin/gamification/missions/${encodeURIComponent(id)}`,
    {
      method: 'PUT',
      body: JSON.stringify(input),
    },
  );
}

export function fetchAdminAchievements(signal?: AbortSignal) {
  return api<AdminAchievementDefinition[]>('/api/v1/admin/gamification/achievements', { signal });
}

export function createAdminAchievement(input: SaveAchievementInput) {
  return api<AdminAchievementDefinition>('/api/v1/admin/gamification/achievements', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

export function updateAdminAchievement(id: string, input: SaveAchievementInput) {
  return api<AdminAchievementDefinition>(
    `/api/v1/admin/gamification/achievements/${encodeURIComponent(id)}`,
    {
      method: 'PUT',
      body: JSON.stringify(input),
    },
  );
}

export function fetchRewardPolicy(signal?: AbortSignal) {
  return api<RewardPolicyConfig>('/api/v1/admin/gamification/reward-policy', { signal });
}

export function updateRewardPolicy(input: RewardPolicyConfig) {
  return api<RewardPolicyConfig>('/api/v1/admin/gamification/reward-policy', {
    method: 'PUT',
    body: JSON.stringify(input),
  });
}

export function fetchEntitlementFeatures(signal?: AbortSignal) {
  return api<FeatureDefinition[]>('/api/v1/admin/entitlements/features', { signal });
}

export function fetchEntitlementQuotas(signal?: AbortSignal) {
  return api<QuotaPolicy[]>('/api/v1/admin/entitlements/quotas', { signal });
}

export function updateEntitlementQuota(input: UpdateQuotaPolicyInput) {
  return api<QuotaPolicy>('/api/v1/admin/entitlements/quotas', {
    method: 'PUT',
    body: JSON.stringify(input),
  });
}

export function fetchEntitlementAudit(
  options: { userId?: string; featureKey?: string; limit?: number } = {},
  signal?: AbortSignal,
) {
  const params = new URLSearchParams();
  if (options.userId) params.set('userId', options.userId);
  if (options.featureKey) params.set('featureKey', options.featureKey);
  if (options.limit != null) params.set('limit', String(options.limit));
  const query = params.toString();
  return api<EntitlementAuditEntry[]>(
    `/api/v1/admin/entitlements/audit${query ? `?${query}` : ''}`,
    { signal },
  );
}

export function fetchAdminChatSummary(signal?: AbortSignal) {
  return api<AdminChatSummary>('/api/v1/admin/chat/summary', { signal });
}

export function fetchContentMediaList(
  options: { page?: number; size?: number; query?: string; kind?: string } = {},
  signal?: AbortSignal,
) {
  const params = new URLSearchParams();
  params.set('page', String(options.page ?? 0));
  params.set('size', String(options.size ?? 24));
  if (options.query) params.set('query', options.query);
  if (options.kind && options.kind !== 'all') params.set('kind', options.kind);
  return api<ContentMediaPage>(`/api/v1/admin/content-media?${params}`, { signal });
}

export async function uploadContentMedia(file: File) {
  const body = new FormData();
  body.append('file', file);
  const response = await http.post<{
    id: string;
    mimeType: string;
    sizeBytes: number;
    filename: string | null;
    synthetic: boolean;
  }>('/api/v1/admin/content-media', body);
  return response.data;
}

export async function fetchContentMediaBlob(id: string, signal?: AbortSignal) {
  const response = await http.get<Blob>(
    `/api/v1/admin/content-media/${encodeURIComponent(id)}/file`,
    { responseType: 'blob', signal },
  );
  return response.data;
}
