import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import type { LexiconDraft } from '../library/library.types';
import { TranslationCandidates } from './TranslationCandidates';

afterEach(cleanup);
it('links an identical source candidate and can unlink it without erasing the translation', async () => {
  await i18n.changeLanguage('en');
  const value: LexiconDraft = {
    title: 'book', type: 'WORD', headwordId: null, sourceMetadataJson: null, licenseText: null,
    items: [{ id: 'noun', partOfSpeech: 'noun', posTitle: null, etymologyText: null }],
    senses: [{ id: 'meaning', itemId: 'noun', definitionEn: 'Printed pages', translationVi: 'sách', translationStatus: 'VERIFIED', examples: [], tags: [], rawGlosses: [], matchedQualifier: null }],
    translations: [{ id: 'source', itemId: null, senseId: null, wordVi: 'sách', source: 'Dictionary', sourceScope: 'entry', sourcePos: 'noun', sourceSenseQualifier: null, linkStatus: 'ENTRY_CANDIDATE' }], forms: [], pronunciations: [],
  };
  const change = vi.fn();
  const view = render(<I18nextProvider i18n={i18n}><TranslationCandidates value={value} sense={value.senses[0]} onChange={change} /></I18nextProvider>);
  expect(screen.getByRole('button', { name: 'Use for this meaning' })).toBeEnabled();
  await userEvent.click(screen.getByRole('button', { name: 'Use for this meaning' }));
  const linked: LexiconDraft = change.mock.calls[0][0];
  expect(linked.senses[0]).toMatchObject({ translationVi: 'sách', translationStatus: 'NEEDS_REVIEW' });
  expect(linked.translations[0]).toMatchObject({ senseId: 'meaning', itemId: 'noun', origin: { itemId: null } });
  view.rerender(<I18nextProvider i18n={i18n}><TranslationCandidates value={linked} sense={linked.senses[0]} onChange={change} /></I18nextProvider>);
  expect(screen.getByRole('button', { name: 'Use for this meaning' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: 'Unlink' }));
  const unlinked: LexiconDraft = change.mock.calls[1][0];
  expect(unlinked.senses[0].translationVi).toBe('sách');
  expect(unlinked.translations[0]).toMatchObject({ senseId: null, itemId: null, linkStatus: 'ENTRY_CANDIDATE' });
});
