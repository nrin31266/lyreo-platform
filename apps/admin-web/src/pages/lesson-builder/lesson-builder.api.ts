import { api, http } from "@/lib/api";
import type {
  BuildAccepted,
  LessonDraft,
  SourceInspection,
  TranscriptSentence,
} from "./lesson-builder.types";

const base = "/api/v1/admin/lesson-authoring";
const body = (value: unknown) => ({
  method: "POST",
  body: JSON.stringify(value),
});
export const inspectSource = (sourceReference: string) =>
  api<SourceInspection>(`${base}/inspect`, body({ sourceReference }));
export const createDraft = (payload: {
  title: string;
  sourceReference: string;
  startMs: number;
  endMs: number;
  description?: string;
  imageUrl?: string;
  categoryIds?: string[];
}) => api<BuildAccepted>(`${base}/drafts`, body(payload));
export const fetchDraft = (id: string, signal?: AbortSignal) =>
  api<LessonDraft>(`${base}/${encodeURIComponent(id)}`, { signal }).then(draft => ({ ...draft, endMs: draft.endMs ?? 0 }));
export const saveTranscript = (
  id: string,
  revision: number,
  sentences: TranscriptSentence[],
) =>
  api<LessonDraft>(`${base}/${encodeURIComponent(id)}/transcript`, {
    method: "PUT",
    body: JSON.stringify({ revision, sentences: sentences.map(({ id, text }) => ({ id, text })) }),
  }).then(draft => ({ ...draft, endMs: draft.endMs ?? 0 }));
export const approveTranscript = (id: string, revision: number) =>
  api<LessonDraft>(
    `${base}/${encodeURIComponent(id)}/approve`,
    body({ revision }),
  );
export const alignTranscript = (id: string, revision: number) =>
  api<BuildAccepted>(
    `${base}/${encodeURIComponent(id)}/align`,
    body({ revision }),
  );
export const reviewKaraoke = (id: string, revision: number) =>
  api<LessonDraft>(
    `${base}/${encodeURIComponent(id)}/karaoke-review`,
    body({ revision }),
  );
export const prepareTranscript = (id: string, revision: number) =>
  api<BuildAccepted>(
    `${base}/${encodeURIComponent(id)}/prepare`,
    body({ revision }),
  );

export const saveTiming = (id: string, revision: number, sentenceId: string, words: import('./lesson-builder.types').AuthoringWord[]) =>
  api<LessonDraft>(`${base}/${encodeURIComponent(id)}/sentences/${encodeURIComponent(sentenceId)}/timing`, { method: 'PUT', body: JSON.stringify({ revision, words }) });
export type Voice = { id: string; accent: string; name: string; gender: 'FEMALE' | 'MALE'; sampleAvailable: boolean };
export type VoiceCatalog = { defaultVoice: string; voices: Voice[]; sampleText: string; sampleJobId: string | null; sampleStatus: string | null; sampleProgress: number; sampleError: string | null };
export const prepareVoiceSamples = () => api<{ jobId: string }>(`${base}/voices/samples`, { method: 'POST' });
export const fetchVoiceSample = (id: string, signal?: AbortSignal) => http.get<Blob>(`${base}/voices/${encodeURIComponent(id)}/sample`, { responseType: 'blob', signal }).then(response => response.data);
export const fetchVoices = (signal?: AbortSignal) => api<VoiceCatalog>(`${base}/voices`, { signal });
export const createTextDraft = (payload: { title?: string; text: string; voice: string; categoryIds: string[]; description?: string; imageUrl?: string }) => api<LessonDraft>(`${base}/text-drafts`, body(payload));
export type AudioSource = { id: string; durationMs: number; contentType: string; originalFilename?: string | null };
export const fetchAudioSource = (id: string, signal?: AbortSignal) => api<AudioSource>(`${base}/audio-sources/${encodeURIComponent(id)}`, { signal });
export const uploadAudio = (file: File) => { const data = new FormData(); data.append('file', file); return http.post<AudioSource>(`${base}/audio-sources`, data, { timeout: 330_000 }).then(response => response.data); };
export const createAudioDraft = (payload: { title?: string; sourceId: string; startMs: number; endMs: number; categoryIds: string[]; description?: string; imageUrl?: string }) => api<BuildAccepted>(`${base}/audio-drafts`, body(payload));
export const synthesizeText = (id: string, revision: number) => api<BuildAccepted>(`${base}/${id}/synthesize`, body({ revision }));
export const saveVoice = (id: string, revision: number, voice: string) => api<LessonDraft>(`${base}/${id}/voice`, { method: 'PUT', body: JSON.stringify({ revision, voice }) }).then(draft => ({ ...draft, endMs: draft.endMs ?? 0 }));

export const saveTextSource = (id: string, revision: number, text: string) => api<LessonDraft>(`${base}/${encodeURIComponent(id)}/text`, { method: 'PUT', body: JSON.stringify({ revision, text }) }).then(draft => ({ ...draft, endMs: draft.endMs ?? 0 }));
