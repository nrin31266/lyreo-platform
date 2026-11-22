import { describe, expect, it } from 'vitest';
import type { LexiconDraft, Sense, Translation } from '../library/library.types';
import { candidatesForSense, linkCandidate, unlinkCandidate } from './translationCandidates';
const sense: Sense = { id: 'meaning', itemId: 'noun', definitionEn: 'Record', translationVi: 'sách', translationStatus: 'VERIFIED', rawGlosses: [], examples: [], tags: [], matchedQualifier: null };
const candidate = (id: string, itemId: string | null, senseId: string | null = null): Translation => ({ id, itemId, senseId, wordVi: 'sổ', source: 'source', sourceScope: 'entry', sourcePos: 'noun', sourceSenseQualifier: null, linkStatus: senseId ? 'DIRECT_SENSE' : 'ENTRY_CANDIDATE' });
const entry = (translations: Translation[]): LexiconDraft => ({ title: 'book', type: 'WORD', headwordId: null, sourceMetadataJson: null, licenseText: null, items: [], senses: [sense], translations, forms: [], pronunciations: [] });
describe('source translation association', () => {
  it('includes entry candidates and the same item but excludes other items and meanings', () => {
    const value = entry([candidate('entry', null), candidate('noun', 'noun'), candidate('verb', 'verb'), candidate('elsewhere', 'noun', 'other'), candidate('linked', 'noun', 'meaning')]);
    expect(candidatesForSense(value, sense).map(row => row.id)).toEqual(['entry', 'noun', 'linked']);
  });
  it('previews append without verifying, keeps source provenance and restores original scope on unlink', () => {
    const source = { ...candidate('entry', null), sourceContext: { record_line: 3 }, tags: ['source-tag'] };
    const result = linkCandidate(entry([source]), sense, source, 'APPEND');
    expect(result.senses[0]).toMatchObject({ translationVi: 'sách; sổ', translationStatus: 'NEEDS_REVIEW' });
    expect(result.translations[0]).toMatchObject({ itemId: 'noun', senseId: 'meaning', sourceContext: { record_line: 3 }, tags: ['source-tag'] });
    expect(unlinkCandidate(result.translations[0])).toMatchObject({ itemId: null, senseId: null, linkStatus: 'ENTRY_CANDIDATE' });
    expect(result.senses[0].translationVi).toBe('sách; sổ');
  });
  it('does not steal a candidate from another meaning or source item', () => {
    const other = candidate('other', 'verb'); const value = entry([other]);
    expect(linkCandidate(value, sense, other, 'REPLACE')).toBe(value);
  });
});
