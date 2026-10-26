import { api } from "@/lib/api";
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
  api<LessonDraft>(`${base}/${encodeURIComponent(id)}`, { signal });
export const saveTranscript = (
  id: string,
  revision: number,
  sentences: TranscriptSentence[],
) =>
  api<LessonDraft>(`${base}/${encodeURIComponent(id)}/transcript`, {
    method: "PUT",
    body: JSON.stringify({ revision, sentences }),
  });
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
export const generateSemantic = (id: string, revision: number) =>
  api<BuildAccepted>(
    `${base}/${encodeURIComponent(id)}/generate`,
    body({ revision }),
  );
export const saveCandidates = (
  id: string,
  revision: number,
  dictationSentenceIds: string[],
  shadowingSentenceIds: string[],
) =>
  api<LessonDraft>(`${base}/${encodeURIComponent(id)}/candidates`, {
    method: "PUT",
    body: JSON.stringify({
      revision,
      dictationSentenceIds,
      shadowingSentenceIds,
    }),
  });
export const publishLesson = (id: string, revision: number) =>
  api<LessonDraft>(
    `${base}/${encodeURIComponent(id)}/publish`,
    body({ revision }),
  );

export const prepareTranscript = (id: string, revision: number) =>
  api<BuildAccepted>(
    `${base}/${encodeURIComponent(id)}/prepare`,
    body({ revision }),
  );
