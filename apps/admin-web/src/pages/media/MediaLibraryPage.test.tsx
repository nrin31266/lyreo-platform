import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { MediaLibraryPage } from './MediaLibraryPage';
import { fetchContentMediaList } from '@/pages/overview/admin-ops.api';

vi.mock('@/pages/overview/admin-ops.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/overview/admin-ops.api')>()),
  fetchContentMediaList: vi.fn(),
  uploadContentMedia: vi.fn(),
}));

let client: QueryClient;

beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(fetchContentMediaList).mockResolvedValue({
    items: [
      {
        id: '50000000-0000-0000-0000-000000000106',
        mimeType: 'image/webp',
        sizeBytes: 7300,
        filename: 'vocab-coffee-ordering.webp',
        synthetic: false,
        createdBy: 'seed-demo',
        createdAt: '2026-11-26T00:00:00Z',
      },
    ],
    total: 1,
    page: 0,
    size: 12,
  });
});

afterEach(() => {
  cleanup();
  client.clear();
});

it('lists stored images and audio assets with MIME and size metadata', async () => {
  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter>
          <MediaLibraryPage />
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );

  expect(await screen.findByText('vocab-coffee-ordering.webp')).toBeInTheDocument();
  expect(screen.getByText('image/webp')).toBeInTheDocument();
});
