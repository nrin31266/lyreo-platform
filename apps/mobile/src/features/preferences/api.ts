import type { ApiClient } from '@/api/client';

export type DisplayTiming = 'OFF' | 'TAP_TO_SHOW' | 'AFTER_ATTEMPT' | 'ALWAYS';

export type LearnerPreferences = {
  preferredAccent: string;
  translation: DisplayTiming;
  sentenceIpa: DisplayTiming;
  vocabularyNotes: DisplayTiming;
  grammarNotes: DisplayTiming;
  thoughtGroups: boolean;
  karaokeHighlighting: boolean;
  properNounHints: boolean;
  defaultPlaybackSpeed: number;
};

export const defaultLearnerPreferences: LearnerPreferences = {
  preferredAccent: 'US',
  translation: 'AFTER_ATTEMPT',
  sentenceIpa: 'TAP_TO_SHOW',
  vocabularyNotes: 'AFTER_ATTEMPT',
  grammarNotes: 'AFTER_ATTEMPT',
  thoughtGroups: true,
  karaokeHighlighting: true,
  properNounHints: true,
  defaultPlaybackSpeed: 1,
};

export function getLearnerPreferences(
  client: ApiClient,
  signal?: AbortSignal,
): Promise<LearnerPreferences> {
  return client.request<LearnerPreferences>('/api/v1/learner/preferences', { signal });
}

export function updateLearnerPreferences(
  client: ApiClient,
  preferences: LearnerPreferences,
): Promise<LearnerPreferences> {
  return client.request<LearnerPreferences>('/api/v1/learner/preferences', {
    method: 'PUT',
    body: JSON.stringify(preferences),
  });
}
