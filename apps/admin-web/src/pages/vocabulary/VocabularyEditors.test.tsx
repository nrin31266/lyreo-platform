import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { I18nextProvider } from 'react-i18next';
import { afterEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { http } from '@/lib/api';
import { EntryDialog, supportsClozeExample } from './VocabularyEditors';
import { VocabularyPage } from './VocabularyPage';
import { fetchVocabulary, vocabularyKey, type Entry, type EntrySummary, type Pack } from './vocabulary-api';

vi.mock('./vocabulary-api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./vocabulary-api')>()),
  fetchVocabulary: vi.fn(),
}));

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); });

it('opens the editor on the current revision instead of initializing from its stale cache', async () => {
  await i18n.changeLanguage('en');
  const summary: EntrySummary = {
    id: 'word', term: 'library', definitionEn: 'A place to borrow books.', meaningVi: 'thư viện',
    packId: 'pack', unitId: 'unit', unitTitle: 'Books', level: 'A1', pos: 'n',
    ipa: '/ˈlaɪ.brer.i/', senseCount: 1, audioId: 'us-1',
    hidden: false, needsReview: false, missingVietnamese: false,
  };
  const cached: Entry = {
    id: 'word', term: 'library', revision: 2, hidden: false,
    media: { image: null, ukAudio: 'uk-1', usAudio: 'us-1', exampleAudio: null, secondExampleAudio: null },
    content: {
      ipaUk: '/ˈlaɪ.brər.i/', ipaUs: '/ˈlaɪ.brer.i/',
      senses: [{ pos: 'n', definitionEn: summary.definitionEn, meaningVi: summary.meaningVi }],
      examples: ['The library opened.', 'I went to the library.'], examplesVi: null, collocationsVi: null,
      needsReview: false, contextNotes: null, collocations: ['public library'], family: [], synonyms: ['archive'], antonyms: [],
    },
  };
  const client = new QueryClient({ defaultOptions: { queries: { staleTime: 30_000, retry: false } } });
  client.setQueryData([...vocabularyKey, 'entry', summary.id], cached);
  let resolve!: (entry: Entry) => void;
  vi.mocked(fetchVocabulary).mockReturnValue(new Promise<Entry>((done) => { resolve = done; }));
  const view = render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter>
    <EntryDialog summary={summary} onClose={() => {}} onSaved={() => {}} />
  </MemoryRouter></I18nextProvider></QueryClientProvider>);

  expect(screen.queryByRole('switch', { name: /Flag for review/i })).not.toBeInTheDocument();
  resolve({ ...cached, revision: 3, content: { ...cached.content, needsReview: true } });
  expect(await screen.findByRole('switch', { name: /Flag for review/i })).toBeChecked();
  expect(screen.getByText('Cloze ready')).toBeVisible();
  expect(screen.getByRole('tab', { name: /Meanings/i })).toBeVisible();
  expect(screen.getByRole('tab', { name: /Examples/i })).toBeVisible();
  expect(screen.getByRole('tab', { name: /Related/i })).toBeVisible();
  view.unmount();
  client.clear();
});

it('detects exact single-word cloze readiness accurately', () => {
  expect(supportsClozeExample('order', ['Your order is ready.'])).toBe(true);
  expect(supportsClozeExample('order', ['The order describes another order.'])).toBe(false);
  expect(supportsClozeExample('order', ['Border patrols waited.'])).toBe(false);
});

it('plays pronunciation in-place with Web Audio pre-roll without mounting a layout-shifting player bar', async () => {
  await i18n.changeLanguage('vi');
  let startedBuffer: AudioBuffer | null = null;
  class FakeAudioContext {
    state: AudioContextState = 'running';
    destination = {} as AudioDestinationNode;
    resume = vi.fn().mockResolvedValue(undefined);
    decodeAudioData = vi.fn().mockResolvedValue({
      sampleRate: 1000,
      length: 500,
      numberOfChannels: 1,
      getChannelData: () => new Float32Array(500).fill(0.25),
    } as unknown as AudioBuffer);
    createBuffer(channels: number, length: number, sampleRate: number) {
      const data = new Float32Array(length);
      return {
        numberOfChannels: channels,
        length,
        sampleRate,
        getChannelData: () => data,
      } as unknown as AudioBuffer;
    }
    createBufferSource() {
      const node = {
        buffer: null as AudioBuffer | null,
        onended: null as (() => void) | null,
        connect: vi.fn(),
        disconnect: vi.fn(),
        stop: vi.fn(),
        start: vi.fn(() => { startedBuffer = node.buffer; }),
      };
      return node as unknown as AudioBufferSourceNode;
    }
  }
  vi.stubGlobal('AudioContext', FakeAudioContext);
  vi.spyOn(http, 'get').mockResolvedValue({ data: new ArrayBuffer(16) });

  const packs: Pack[] = [
    { id: 'pack-1', titleEn: 'Education', titleVi: 'Giáo dục', hidden: true, revision: 1, unitCount: 2, entryCount: 30, coverImage: null },
  ];
  const items: EntrySummary[] = [
    {
      id: 'entry-1', packId: 'pack-1', unitId: 'unit-1', unitTitle: 'A1 · 01', level: 'A1',
      term: 'library', pos: 'n', ipa: '/ˈlaɪ.brer.i/', senseCount: 2,
      definitionEn: 'A place to borrow books.', meaningVi: 'thư viện',
      audioId: 'us-clip-1', hidden: false, needsReview: true, missingVietnamese: false,
    },
  ];
  vi.mocked(fetchVocabulary).mockImplementation(async (path: string) => {
    if (path === '/packs') return packs;
    if (path.startsWith('/packs/pack-1/units')) return [{ id: 'unit-1', title: 'A1 · 01', level: 'A1', hidden: false, revision: 0, entryCount: 30 }];
    return { items, total: 1 };
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={['/vocabulary?pack=pack-1&review=NEEDS_REVIEW']}>
    <VocabularyPage />
  </MemoryRouter></I18nextProvider></QueryClientProvider>);

  expect(await screen.findByText('thư viện')).toBeVisible();
  expect(screen.getByText('A place to borrow books.')).toBeVisible();
  expect(screen.getByText('/ˈlaɪ.brer.i/')).toBeVisible();
  expect(screen.getByText('+1 nghĩa')).toBeVisible();

  const playButton = screen.getByRole('button', { name: 'Nghe phát âm library' });
  fireEvent.click(playButton);
  await screen.findByRole('button', { name: 'Nghe phát âm library', pressed: true });
  expect(startedBuffer).not.toBeNull();
  // 120ms pre-roll (120 samples at 1kHz) + 500 original samples + 40ms tail (40 samples) = 660 samples
  expect(startedBuffer!.length).toBe(660);
  expect(Math.abs(startedBuffer!.getChannelData(0)[0])).toBeCloseTo(1e-5, 8);
  expect(startedBuffer!.getChannelData(0)[120]).toBeCloseTo(0.25, 5);

  fireEvent.click(screen.getByRole('button', { name: 'Bỏ bộ lọc' }));
  expect(await screen.findByRole('heading', { name: 'Tất cả bộ từ' })).toBeVisible();
  vi.unstubAllGlobals();
  view.unmount();
  client.clear();
});
