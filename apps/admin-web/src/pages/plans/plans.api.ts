import { api } from '@/lib/api';

export async function updateVocabularyPackAccess(
  packId: string,
  accessMode: 'PUBLIC' | 'FEATURE',
  requiredFeatureKey: string | null,
) {
  return api<unknown>(`/api/v1/admin/vocabulary/packs/${encodeURIComponent(packId)}/access`, {
    method: 'PUT',
    body: JSON.stringify({ accessMode, requiredFeatureKey }),
  });
}

export async function fetchVocabularyPacks(signal?: AbortSignal) {
  return api<
    Array<{
      id: string;
      sourceTopic: string;
      position: number;
      titleEn: string;
      titleVi: string;
      hidden: boolean;
      accessMode?: 'PUBLIC' | 'FEATURE';
      requiredFeatureKey?: string | null;
    }>
  >('/api/v1/admin/vocabulary/packs', { signal });
}

export type ToeicCatalogSummary = {
  id: string;
  title: string;
  year: number | null;
  type: string;
  status: string;
  accessMode: 'PUBLIC' | 'FEATURE';
  questionCount: number;
  qualityStatus?: string;
};

export type ToeicCatalogPage = {
  items: ToeicCatalogSummary[];
  total: number;
  page: number;
  size: number;
};

export async function fetchAdminToeicPage(
  options: { page?: number; size?: number; q?: string; access?: string; status?: string } = {},
  signal?: AbortSignal,
) {
  const params = new URLSearchParams();
  params.set('page', String(options.page ?? 0));
  params.set('size', String(options.size ?? 10));
  if (options.q) params.set('q', options.q);
  if (options.access && options.access !== 'ALL') params.set('access', options.access);
  if (options.status) params.set('status', options.status);
  return api<ToeicCatalogPage>(`/api/v1/admin/toeic/page?${params}`, { signal });
}

export async function updateToeicAccess(
  id: string,
  accessMode: 'PUBLIC' | 'FEATURE',
) {
  return api<unknown>(`/api/v1/admin/toeic/${encodeURIComponent(id)}/access`, {
    method: 'PUT',
    body: JSON.stringify({
      accessMode,
      requiredFeatureKey: accessMode === 'FEATURE' ? 'toeic.full-access' : null,
    }),
  });
}

export type GrammarCatalogSummary = {
  id: string;
  title: string;
  titleEn: string;
  kind: string;
  parentId: string | null;
  status: string;
  accessMode: 'PUBLIC' | 'FEATURE';
  questionCount: number;
};

export type GrammarCatalogPage = {
  items: GrammarCatalogSummary[];
  total: number;
  page: number;
  size: number;
};

export async function fetchAdminGrammarPage(
  options: { kind?: string; page?: number; size?: number; q?: string; access?: string } = {},
  signal?: AbortSignal,
) {
  const kind = options.kind ?? 'topic';
  const params = new URLSearchParams();
  params.set('page', String(options.page ?? 0));
  params.set('size', String(options.size ?? 10));
  if (options.q) params.set('q', options.q);
  if (options.access && options.access !== 'ALL') params.set('access', options.access);
  return api<GrammarCatalogPage>(`/api/v1/admin/grammar/${kind}/page?${params}`, { signal });
}

export async function updateGrammarAccess(
  kind: string,
  id: string,
  accessMode: 'PUBLIC' | 'FEATURE',
) {
  return api<unknown>(`/api/v1/admin/grammar/${kind}/${encodeURIComponent(id)}/access`, {
    method: 'PUT',
    body: JSON.stringify({
      accessMode,
      requiredFeatureKey: accessMode === 'FEATURE' ? 'grammar.advanced' : null,
    }),
  });
}
