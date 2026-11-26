import { api } from '@/lib/api';

export const speakingScenariosKey = ['admin-speaking-scenarios'] as const;

export type SpeakingCefrLevel = 'A1' | 'A2' | 'B1' | 'B2' | 'C1' | 'C2';
export type SpeakingAccessMode = 'PUBLIC' | 'FEATURE';
export type SpeakingScenarioStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export type SpeakingScenario = {
  id: string;
  code: string;
  title: string;
  titleVi: string;
  category: string;
  cefrLevel: SpeakingCefrLevel;
  coverAssetId: string | null;
  aiRoleName: string;
  aiPersona: string;
  learnerRole: string;
  settingDescription: string;
  openingLine: string;
  systemPrompt: string;
  objectives: string[];
  ttsVoice: string;
  targetTurns: number;
  estimatedMinutes: number;
  accessMode: SpeakingAccessMode;
  requiredFeatureKey: string | null;
  status: SpeakingScenarioStatus;
  createdAt?: string;
  updatedAt?: string;
};

export type SaveSpeakingScenarioInput = Omit<SpeakingScenario, 'id' | 'createdAt' | 'updatedAt'>;

export type SpeakingPreviewMessage = {
  role: 'user' | 'assistant';
  content: string;
};

export type SpeakingPreviewTurnResult = {
  assistantReply: string;
  ttsVoice: string;
  aiInvocationId: string;
  provider: string;
  latencyMs: number;
  coachingTips: string[];
};

const base = '/api/v1/admin/speaking/scenarios';

export function fetchSpeakingScenarios(signal?: AbortSignal) {
  return api<SpeakingScenario[]>(base, { signal });
}

export function createSpeakingScenario(body: SaveSpeakingScenarioInput) {
  return api<SpeakingScenario>(base, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateSpeakingScenario(id: string, body: SaveSpeakingScenarioInput) {
  return api<SpeakingScenario>(`${base}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function deleteSpeakingScenario(id: string) {
  return api<void>(`${base}/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export function previewSpeakingTurn(
  id: string,
  history: SpeakingPreviewMessage[],
  userMessage: string,
) {
  return api<SpeakingPreviewTurnResult>(`${base}/${encodeURIComponent(id)}/preview-turn`, {
    method: 'POST',
    body: JSON.stringify({ history, userMessage }),
  });
}
