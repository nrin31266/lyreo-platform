import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import type { LexiconDraft } from '../library/library.types';
import { DictionaryPreview } from './DictionaryPreview';

afterEach(cleanup);

it('groups meanings by part of speech, keeps extra meanings accessible, and edits the selected sense', async () => {
  await i18n.changeLanguage('vi');
  const edit = vi.fn();
  const entry: LexiconDraft = {
    title: 'bank', type: 'WORD', headwordId: null, sourceMetadataJson: null, licenseText: null,
    items: [{ id: 'a', partOfSpeech: 'noun', posTitle: null, etymologyText: null }, { id: 'b', partOfSpeech: 'noun', posTitle: null, etymologyText: null }],
    senses: Array.from({ length: 7 }, (_, index) => ({ id: `${index}`, itemId: index < 3 ? 'a' : 'b', definitionEn: `Meaning ${index}`, translationVi: `Nghĩa ${index}`, translationStatus: 'NEEDS_REVIEW', rawGlosses: [], examples: [], tags: [], matchedQualifier: null })),
    forms: [], pronunciations: [], translations: [],
  };
  render(<I18nextProvider i18n={i18n}><DictionaryPreview entry={entry} onEditSense={edit} /></I18nextProvider>);
  expect(screen.getAllByRole('heading', { name: 'Danh từ' })).toHaveLength(1);
  expect(screen.getByText('Nghĩa 5')).not.toBeVisible();
  await userEvent.click(screen.getByText('Xem thêm 2 nghĩa'));
  expect(screen.getByText('Nghĩa 5')).toBeVisible();
  await userEvent.click(screen.getAllByRole('button', { name: 'Sửa nghĩa này' })[5]);
  expect(edit).toHaveBeenCalledWith('5', 'vi');
  await userEvent.click(screen.getByRole('button', { name: 'Anh–Anh' }));
  expect(screen.getByText('Meaning 0')).toBeVisible();
});

it('offers the available language when the selected mode has no meaning', async () => {
  await i18n.changeLanguage('vi');
  const entry: LexiconDraft = { title: 'word', type: 'WORD', headwordId: null, sourceMetadataJson: null, licenseText: null,
    items: [{ id: 'noun', partOfSpeech: 'noun', posTitle: null, etymologyText: null }],
    senses: [{ id: 'one', itemId: 'noun', definitionEn: 'English definition', translationVi: null, translationStatus: 'MISSING', rawGlosses: [], examples: [], tags: [], matchedQualifier: null }],
    forms: [], pronunciations: [], translations: [] };
  render(<I18nextProvider i18n={i18n}><DictionaryPreview entry={entry} onEditSense={vi.fn()} /></I18nextProvider>);
  expect(screen.getByText('Mục từ này chưa có nghĩa tiếng Việt.')).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: 'Xem định nghĩa tiếng Anh' }));
  expect(screen.getByText('English definition')).toBeVisible();
});
