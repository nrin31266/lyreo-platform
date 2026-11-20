import type { LexiconDraft, Sense, Translation } from '../library/library.types';

export function candidateScope(candidate: Translation) {
  return candidate.origin ? candidate.origin.itemId : candidate.itemId;
}
export function candidatesForSense(value: LexiconDraft, sense: Sense) {
  return value.translations.filter(candidate => {
    const scope = candidateScope(candidate);
    return candidate.senseId === sense.id || ((!scope || scope === sense.itemId) && !candidate.senseId);
  });
}
export function translationPreview(current: string | null, proposed: string, mode: string) {
  return mode === 'APPEND' && current?.trim() ? `${current}; ${proposed}` : proposed;
}
export function linkCandidate(value: LexiconDraft, sense: Sense, candidate: Translation, mode: string): LexiconDraft {
  if (!candidatesForSense(value, sense).some(row => row.id === candidate.id)) return value;
  return { ...value,
    senses: value.senses.map(row => row.id === sense.id ? { ...row, translationVi: translationPreview(row.translationVi, candidate.wordVi, mode), translationStatus: 'NEEDS_REVIEW' } : row),
    translations: value.translations.map(row => row.id === candidate.id ? { ...row, origin: row.origin ?? { itemId: row.itemId, senseId: row.senseId, linkStatus: row.linkStatus }, itemId: sense.itemId, senseId: sense.id, linkStatus: 'DIRECT_SENSE' } : row),
  };
}
export function unlinkCandidate(candidate: Translation): Translation {
  const itemId = candidateScope(candidate);
  return { ...candidate, itemId, senseId: null, linkStatus: itemId ? 'ITEM_CANDIDATE' : 'ENTRY_CANDIDATE' };
}
