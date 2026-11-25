import { api } from '@/lib/api';

export const vocabularyKey = ['admin-vocabulary'] as const;
const endpoint = '/api/v1/admin/vocabulary';
export const reviewFilters = ['ALL', 'NEEDS_REVIEW', 'MISSING_VI', 'MISSING_SYNONYMS', 'MISSING_ANTONYMS', 'HIDDEN'] as const;
export const cefrLevels = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'] as const;

type Editable = { id: string; hidden: boolean; revision: number };
export type Pack = Editable & {
  titleEn: string;
  titleVi: string;
  unitCount: number;
  entryCount: number;
  coverImage: string | null;
  accessMode?: 'PUBLIC' | 'FEATURE';
  requiredFeatureKey?: string | null;
};
export type Unit = Editable & { title: string; level: string; entryCount: number };
type Sense = { pos: string; definitionEn: string; meaningVi: string | null };
export type VocabularyContent = {
  ipaUk: string;
  ipaUs: string;
  senses: Sense[];
  examples: string[];
  examplesVi: (string | null)[] | null;
  collocationsVi: (string | null)[] | null;
  needsReview: boolean | null;
  contextNotes: { pos: string; meaningVi: string; sourceGroupIds: string[] }[] | null;
  collocations: string[];
  family: string[];
  synonyms: string[];
  antonyms: string[];
};
export type EntrySummary = {
  id: string; packId: string; unitId: string; unitTitle: string; level: string; term: string;
  pos: string; ipa: string | null; senseCount: number; definitionEn: string; meaningVi: string | null;
  audioId: string | null; hidden: boolean; needsReview: boolean; missingVietnamese: boolean;
  hasConflict?: boolean;
};
export type Entry = Editable & {
  term: string;
  content: VocabularyContent;
  media: Record<'image' | 'ukAudio' | 'usAudio' | 'exampleAudio' | 'secondExampleAudio', string | null>;
};

export function fetchVocabulary<T>(path: string, signal: AbortSignal) {
  return api<T>(endpoint + path, { signal });
}

export function saveVocabulary(path: string, updates: object) {
  return api<void>(endpoint + path, { method: 'PUT', body: JSON.stringify(updates) });
}
