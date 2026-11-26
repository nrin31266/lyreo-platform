import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { http } from '@/lib/api';
import { AuthenticatedImage } from './AuthenticatedImage';

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();
  return {
    ...actual,
    http: {
      ...actual.http,
      get: vi.fn(),
    },
  };
});

let client: QueryClient;
const originalCreateObjectUrl = URL.createObjectURL;
const originalRevokeObjectUrl = URL.revokeObjectURL;

beforeEach(() => {
  vi.resetAllMocks();
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});

afterEach(() => {
  cleanup();
  client.clear();
  URL.createObjectURL = originalCreateObjectUrl;
  URL.revokeObjectURL = originalRevokeObjectUrl;
});

it('loads protected images with axios http client and revokes object URLs', async () => {
  const blob = new Blob(['webp'], { type: 'image/webp' });
  vi.mocked(http.get).mockResolvedValueOnce({ data: blob } as never);
  const createUrl = vi.fn(() => 'blob:mock-1');
  const revokeUrl = vi.fn();
  URL.createObjectURL = createUrl;
  URL.revokeObjectURL = revokeUrl;

  const { unmount } = render(
    <QueryClientProvider client={client}>
      <AuthenticatedImage
        src="/api/v1/gamification/achievements/1/badge"
        alt="Streak Badge"
        fallback={<span>Fallback</span>}
      />
    </QueryClientProvider>,
  );

  const img = await screen.findByRole('img', { name: 'Streak Badge' });
  expect(img).toHaveAttribute('src', 'blob:mock-1');
  expect(http.get).toHaveBeenCalledWith(
    '/api/v1/gamification/achievements/1/badge',
    expect.objectContaining({ responseType: 'blob' }),
  );

  unmount();
  expect(createUrl).toHaveBeenCalledWith(blob);
  expect(revokeUrl).toHaveBeenCalledWith('blob:mock-1');
});

it('renders fallback when src is null or fetch fails', async () => {
  vi.mocked(http.get).mockRejectedValueOnce(new Error('401'));
  URL.createObjectURL = vi.fn(() => 'blob:unused');
  URL.revokeObjectURL = vi.fn();
  render(
    <QueryClientProvider client={client}>
      <AuthenticatedImage
        src="/api/v1/admin/content-media/missing/file"
        alt="Missing"
        fallback={<span>No Image</span>}
      />
    </QueryClientProvider>,
  );

  expect(await screen.findByText('No Image')).toBeInTheDocument();
});
