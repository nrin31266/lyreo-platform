import { api, http } from "@/lib/api";
import type {
  BuildAccepted,
  CaptionFile,
  LessonDraft,
  SourceInspection,
  TranscriptSentence,
} from "./lesson-builder.types";

const base = "/api/v1/admin/lesson-authoring";
const body = (value: unknown) => ({
  method: "POST",
  body: JSON.stringify(value),
});
export const inspectSource = (sourceReference: string, signal?: AbortSignal) =>
  api<SourceInspection>(`${base}/inspect`, { ...body({ sourceReference }), signal });
export const createDraft = (payload: {
  title: string;
  sourceReference: string;
  preferEnglishCaptions?: boolean;
  captionFile?: CaptionFile;
  startMs: number;
  endMs: number;
  description?: string;
  imageUrl?: string;
  categoryIds?: string[];
  options?: import("./lesson-builder.types").AuthoringOptions;
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
export const prepareTranscript = (id: string, revision: number, useWhisperX?: boolean) =>
  api<BuildAccepted>(
    `${base}/${encodeURIComponent(id)}/prepare`,
    body({ revision, useWhisperX }),
  );

export const saveContentOptions = (id: string, revision: number, options: import('./lesson-builder.types').AuthoringOptions) =>
  api<LessonDraft>(`${base}/${id}/content-options`, { method: 'PUT', body: JSON.stringify({ revision, options }) });
export const generateContent = (id: string, revision: number) => api<BuildAccepted>(`${base}/${id}/content`, body({ revision }));
export type SentenceContentInput = Pick<import('./lesson-builder.types').AuthoringSentence, 'translation' | 'ipa' | 'ipaNeedsReview' | 'vocabulary' | 'grammar' | 'dictationSelected' | 'shadowingSelected'>;
export const saveSentenceContent = (id: string, revision: number, sentenceId: string, content: SentenceContentInput) =>
  api<LessonDraft>(`${base}/${id}/sentences/${sentenceId}/content`, { method: 'PUT', body: JSON.stringify({ revision, content }) });
export const reviewContent = (id: string, revision: number) => api<LessonDraft>(`${base}/${id}/content-review`, body({ revision }));
export const saveContentProfile = (id: string, revision: number, profile: Pick<NonNullable<LessonDraft['profile']>, 'cefr' | 'contentType'>) =>
  api<LessonDraft>(`${base}/${id}/content-profile`, { method: 'PUT', body: JSON.stringify({ revision, ...profile }) });

export const saveTiming = (id: string, revision: number, sentenceId: string, words: import('./lesson-builder.types').AuthoringWord[]) =>
  api<LessonDraft>(`${base}/${encodeURIComponent(id)}/sentences/${encodeURIComponent(sentenceId)}/timing`, { method: 'PUT', body: JSON.stringify({ revision, words }) });
export type Voice = { id: string; accent: string; name: string; gender: 'FEMALE' | 'MALE'; sampleAvailable: boolean };
export type VoiceCatalog = { defaultVoice: string; voices: Voice[]; sampleText: string; sampleJobId: string | null; sampleStatus: string | null; sampleProgress: number; sampleError: string | null };
export const prepareVoiceSamples = () => api<{ jobId: string }>(`${base}/voices/samples`, { method: 'POST' });
export const fetchVoiceSample = (id: string, signal?: AbortSignal) => http.get<Blob>(`${base}/voices/${encodeURIComponent(id)}/sample`, { responseType: 'blob', signal }).then(response => response.data);
export const fetchVoices = (signal?: AbortSignal) => api<VoiceCatalog>(`${base}/voices`, { signal });
export const createTextDraft = (payload: { title?: string; text: string; voice: string; startProcessing?: boolean; categoryIds: string[]; description?: string; imageUrl?: string; options?: import("./lesson-builder.types").AuthoringOptions }) => api<LessonDraft>(`${base}/text-drafts`, body(payload));
export type AudioSource = { id: string; durationMs: number; contentType: string; originalFilename?: string | null };
export const fetchAudioSource = (id: string, signal?: AbortSignal) => api<AudioSource>(`${base}/audio-sources/${encodeURIComponent(id)}`, { signal });
export const uploadAudio = (file: File) => { const data = new FormData(); data.append('file', file); return http.post<AudioSource>(`${base}/audio-sources`, data, { timeout: 330_000 }).then(response => response.data); };
export const createAudioDraft = (payload: { title?: string; sourceId: string; startMs: number; endMs: number; categoryIds: string[]; description?: string; imageUrl?: string; options?: import("./lesson-builder.types").AuthoringOptions }) => api<BuildAccepted>(`${base}/audio-drafts`, body(payload));
export const synthesizeText = (id: string, revision: number) => api<BuildAccepted>(`${base}/${id}/synthesize`, body({ revision }));
export const saveVoice = (id: string, revision: number, voice: string) => api<LessonDraft>(`${base}/${id}/voice`, { method: 'PUT', body: JSON.stringify({ revision, voice }) }).then(draft => ({ ...draft, endMs: draft.endMs ?? 0 }));

export const saveTextSource = (id: string, revision: number, text: string) => api<LessonDraft>(`${base}/${encodeURIComponent(id)}/text`, { method: 'PUT', body: JSON.stringify({ revision, text }) }).then(draft => ({ ...draft, endMs: draft.endMs ?? 0 }));

export const runLesson = (id: string, revision: number) => api<BuildAccepted>(`${base}/${id}/run`, body({ revision }));
export const saveChanges = (id: string, draft: LessonDraft, changed: Set<string>, optionsChanged: boolean, profileChanged: boolean, metadata?: unknown) =>
  api<LessonDraft>(`${base}/${id}/changes`, { method: 'PUT', body: JSON.stringify({ changes: { revision: draft.revision, voice: changed.has('_voice') ? draft.ttsVoice : null, options: optionsChanged ? draft.options : null, profile: profileChanged ? { ...draft.profile, manual: true } : null, sentences: draft.sentences.map(row => ({ id: row.id.startsWith('new:') || !row.id ? null : row.id, text: row.text, content: changed.has(row.id) ? {translation:row.translation,ipa:row.ipa,ipaNeedsReview:row.ipaNeedsReview ?? false,vocabulary:row.vocabulary,grammar:row.grammar,dictationSelected:row.dictationSelected,shadowingSelected:row.shadowingSelected} : null, cloze: row.cloze, clozeManual: row.clozeManual ?? false })) }, metadata }) });
export const fetchReadiness = (id: string) => api<{ ready: boolean; blockers: string[] }>(`${base}/${id}/readiness`);
export const publishLesson = (id: string, revision: number) => api<LessonDraft>(`${base}/${id}/publish`, body({ revision }));
export const duplicateLesson = (id: string, revision: number) => api<LessonDraft>(`${base}/${id}/duplicate`, body({ revision }));
export const fetchDiagnostics = (id: string, job: string) => api<Record<string, unknown>[]>(`${base}/${id}/jobs/${job}/diagnostics`);

export const cancelChain = (id:string,revision:number) => api<LessonDraft>(`${base}/${id}/cancel`,body({revision}));
