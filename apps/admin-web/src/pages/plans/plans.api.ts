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
