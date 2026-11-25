import { api } from '@/lib/api';

export const curriculumKey = ['admin-curriculum'] as const;

export type CurriculumStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
export type CurriculumContentType =
  | 'LESSON'
  | 'VOCABULARY_REVIEW'
  | 'GRAMMAR_PRACTICE'
  | 'TOEIC_DRILL'
  | 'TOEIC_TEST'
  | 'SPEAKING_SCENARIO';
export type CurriculumUnlockRule = 'NONE' | 'PREVIOUS_REQUIRED';

export const curriculumStatuses: CurriculumStatus[] = ['DRAFT', 'PUBLISHED', 'ARCHIVED'];
export const curriculumContentTypes: CurriculumContentType[] = [
  'LESSON',
  'VOCABULARY_REVIEW',
  'GRAMMAR_PRACTICE',
  'TOEIC_DRILL',
  'TOEIC_TEST',
  'SPEAKING_SCENARIO',
];
export const curriculumUnlockRules: CurriculumUnlockRule[] = ['NONE', 'PREVIOUS_REQUIRED'];

export type AdminCurriculumItem = {
  id: string;
  position: number;
  contentType: CurriculumContentType;
  contentReferenceId: string;
  required: boolean;
  unlockRule: CurriculumUnlockRule;
};

export type AdminCurriculumSection = {
  id: string;
  title: string;
  position: number;
  items: AdminCurriculumItem[];
};

export type AdminCurriculumPathSummary = {
  id: string;
  code: string;
  name: string;
  level: string | null;
  description: string | null;
  status: CurriculumStatus;
  createdAt?: string;
  updatedAt?: string;
};

export type AdminCurriculumPath = AdminCurriculumPathSummary & {
  sections: AdminCurriculumSection[];
};

export type SaveCurriculumItemInput = {
  position: number;
  contentType: CurriculumContentType;
  contentReferenceId: string;
  required: boolean;
  unlockRule: CurriculumUnlockRule;
};

export type SaveCurriculumSectionInput = {
  title: string;
  position: number;
  items: SaveCurriculumItemInput[];
};

export type SaveCurriculumPathInput = {
  code: string;
  name: string;
  level: string | null;
  description: string | null;
  status: CurriculumStatus;
  sections: SaveCurriculumSectionInput[];
};

const base = '/api/v1/admin/curriculum/paths';

export function fetchAdminCurriculumPaths(signal?: AbortSignal) {
  return api<AdminCurriculumPathSummary[]>(base, { signal });
}

export function fetchAdminCurriculumPath(pathId: string, signal?: AbortSignal) {
  return api<AdminCurriculumPath>(`${base}/${encodeURIComponent(pathId)}`, { signal });
}

export function createAdminCurriculumPath(body: SaveCurriculumPathInput) {
  return api<AdminCurriculumPath>(base, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function updateAdminCurriculumPath(pathId: string, body: SaveCurriculumPathInput) {
  return api<AdminCurriculumPath>(`${base}/${encodeURIComponent(pathId)}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
}

export function deleteAdminCurriculumPath(pathId: string) {
  return api<void>(`${base}/${encodeURIComponent(pathId)}`, {
    method: 'DELETE',
  });
}
