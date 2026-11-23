import { api, http } from '@/lib/api';

export type PronunciationLimits = {
  revision: number;
  maxDurationSeconds: number;
  maxUploadBytes: number;
  startMs: number;
  endMs: number;
};

export type AcceptedPronunciation = { attemptId: string; jobId: string; expiresAt: string };
export type PronunciationSpan = { startMs: number | null; endMs: number | null };
export type PronunciationWord = PronunciationSpan & {
  text: string;
  referenceIndex?: number | null;
  error: 'NONE' | 'OMISSION' | 'INSERTION' | 'MISPRONUNCIATION';
  accuracy: number | null;
  phonemes: (PronunciationSpan & { ipa: string; accuracy: number | null })[];
};
export type PronunciationSummary = { revision: number; issueCount: number };
export type PronunciationAttempt = AcceptedPronunciation & {
  revision: number;
  referenceText: string;
  status: 'QUEUED' | 'RUNNING' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'CANCEL_REQUESTED' | 'CANCELLED';
  errorCode: string | null;
  result: {
    outcome: 'SCORED' | 'NO_SPEECH';
    durationMs: number;
    transcript: string | null;
    scores: { overall: number | null; accuracy: number | null; fluency: number | null; completeness: number | null; prosody?: number | null };
    words: PronunciationWord[];
    providerRaw?: Record<string, unknown> | null;
  } | null;
};

const sentencePath = (lesson: string, sentence: string) =>
  `/api/v1/admin/lesson-authoring/${lesson}/sentences/${sentence}/pronunciation-attempts`;
const attemptPath = (id: string) => `/api/v1/admin/pronunciation-attempts/${id}`;

export function fetchPronunciationLimits(lesson: string, sentence: string, revision: number, signal: AbortSignal) {
  return api<PronunciationLimits>(`${sentencePath(lesson, sentence)}?revision=${revision}`, { signal });
}

export async function submitPronunciation(lesson: string, sentence: string, revision: number, audio: File, key: string) {
  const body = new FormData();
  body.set('revision', String(revision));
  body.set('audio', audio);
  const response = await http.post<AcceptedPronunciation>(sentencePath(lesson, sentence), body, {
    headers: { 'Idempotency-Key': key },
  });
  return response.data;
}

export const fetchPronunciation = (id: string, signal: AbortSignal) => api<PronunciationAttempt>(attemptPath(id), { signal });
export const cancelPronunciation = (id: string) => api<void>(`${attemptPath(id)}/cancel`, { method: 'POST' });
export const fetchPronunciationAudio = (id: string, signal: AbortSignal) =>
  http.get<Blob>(`${attemptPath(id)}/audio`, { signal, responseType: 'blob' }).then(response => response.data);
