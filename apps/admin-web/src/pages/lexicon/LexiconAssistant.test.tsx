import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { api } from '@/lib/api';
import type { Document, LexiconDraft } from '../library/library.types';
import { LexiconAssistant } from './LexiconAssistant';
vi.mock('@/lib/api', async original => ({ ...await original<typeof import('@/lib/api')>(), api: vi.fn() }));
afterEach(() => { cleanup(); vi.clearAllMocks(); });
const draft: LexiconDraft = { title: 'book', type: 'WORD', headwordId: null, sourceMetadataJson: null, licenseText: null, items: [{ id: 'noun', partOfSpeech: 'noun', posTitle: null, etymologyText: null }], senses: [{ id: 'selected', itemId: 'noun', definitionEn: 'Printed pages', translationVi: 'sách', translationStatus: 'NEEDS_REVIEW', examples: [], rawGlosses: [], tags: [], matchedQualifier: null }], forms: [], pronunciations: [], translations: [] };
const document: Document<LexiconDraft> = { id: 'word', revision: 3, status: 'DRAFT', publicationId: null, hasUnpublishedChanges: true, draft, updatedAt: null, updatedBy: null };
const result = { id: 'request', jobId: 'job', operation: 'TRANSLATE', baseRevision: 3, acceptedRevision: null, applied: {}, input: { senses: [{ id: 'selected' }] }, output: { suggestions: [{ senseId: 'selected', field: 'translationVi', value: 'quyển sách', candidateId: null, reason: 'Printed pages rather than a reservation' }], warnings: [] } };
async function mount({ row = result, dirty = false, status = 'SUCCEEDED' } = {}) {
  await i18n.changeLanguage('en');
  const adopt = vi.fn(); const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(api).mockImplementation(async (url, init) => url.endsWith('/ai') ? init?.method === 'POST' ? row : [row] : url.includes('/jobs/') ? { id: 'job', status } : document);
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><LexiconAssistant document={document} dirty={dirty} adopt={adopt} context={{ senseId: 'selected' }} /></MemoryRouter></I18nextProvider></QueryClientProvider>);
  await userEvent.click(screen.getByText('AI for this meaning'));
  return { adopt, cache };
}
it('requests AI for the same selected meaning and accepts an explicit append after preview', async () => {
  const { adopt, cache } = await mount();
  await screen.findByText('Printed pages rather than a reservation');
  expect(screen.getByText('sách')).toBeVisible();
  expect(adopt).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole('combobox', { name: 'Apply translation' }));
  await userEvent.click(screen.getByRole('option', { name: 'Append to current text' }));
  expect(screen.getByText('sách; quyển sách')).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: 'Accept into draft' }));
  await waitFor(() => expect(adopt).toHaveBeenCalledWith(document));
  expect(api).toHaveBeenCalledWith('/api/v1/admin/lexicon/word/ai/request/accept', expect.objectContaining({ body: JSON.stringify({ revision: 3, index: 0, mode: 'APPEND' }) }));
  await userEvent.click(screen.getByRole('button', { name: 'Translate meaning' }));
  await waitFor(() => expect(api).toHaveBeenCalledWith('/api/v1/admin/lexicon/word/ai', expect.objectContaining({ method: 'POST', body: JSON.stringify({ revision: 3, operation: 'TRANSLATE', senseIds: ['selected'], brief: '' }) })));
  cache.clear();
});
it('keeps stale suggestions visible but prevents accepting over newer content', async () => {
  const { adopt, cache } = await mount({ row: { ...result, baseRevision: 2 } });
  await screen.findByText('Printed pages rather than a reservation');
  expect(screen.getByRole('button', { name: 'Accept into draft' })).toBeDisabled();
  expect(adopt).not.toHaveBeenCalled(); cache.clear();
});
it('does not offer to accept an AI translation identical to the current draft', async () => {
  const { adopt, cache } = await mount({ row: { ...result, output: { ...result.output, suggestions: [{ ...result.output.suggestions[0]!, value: 'sách' }] } } });
  expect(await screen.findByRole('button', { name: 'Matches current draft' })).toBeDisabled();
  expect(adopt).not.toHaveBeenCalled(); cache.clear();
});
it('disables new requests while one is active and allows cancellation without changing the draft', async () => {
  const { adopt, cache } = await mount({ status: 'RUNNING' });
  await waitFor(() => expect(screen.getByRole('button', { name: 'Translate meaning' })).toBeDisabled());
  await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
  await waitFor(() => expect(api).toHaveBeenCalledWith('/api/v1/jobs/job/cancel', { method: 'POST' }));
  expect(adopt).not.toHaveBeenCalled(); cache.clear();
});
it('supports a Vietnamese source meaning without inventing an English definition',async()=>{
  await i18n.changeLanguage('en');
  const cache=new QueryClient({defaultOptions:{queries:{retry:false}}});
  const vietnamese={...document,draft:{...draft,senses:[{...draft.senses[0]!,definitionEn:null,translationVi:'Sách, quyển sách.'}]}};
  vi.mocked(api).mockResolvedValue([]);
  render(<QueryClientProvider client={cache}><I18nextProvider i18n={i18n}><MemoryRouter><LexiconAssistant document={vietnamese} dirty={false} adopt={vi.fn()} context={{senseId:'selected'}} /></MemoryRouter></I18nextProvider></QueryClientProvider>);
  await userEvent.click(screen.getByText('AI for this meaning'));
  expect(screen.getByText(/Sách, quyển sách/)).toBeVisible();
  await waitFor(()=>expect(screen.getByRole('button',{name:'Translate meaning'})).toBeEnabled());
  cache.clear();
});
