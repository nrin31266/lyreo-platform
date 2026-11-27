import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { fetchAdminCurriculumPaths } from '@/pages/curriculum/curriculum.api';
import { fetchLessons } from '@/pages/lessons/catalog.api';
import {
  fetchEntitlementFeatures,
  fetchEntitlementQuotas,
  updateEntitlementQuota,
} from '@/pages/overview/admin-ops.api';
import {
  fetchAdminGrammarPage,
  fetchAdminToeicPage,
  fetchVocabularyPacks,
  updateToeicAccess,
} from './plans.api';
import { PlansPage } from './PlansPage';

vi.mock('@/pages/overview/admin-ops.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/overview/admin-ops.api')>()),
  fetchEntitlementFeatures: vi.fn(),
  fetchEntitlementQuotas: vi.fn(),
  updateEntitlementQuota: vi.fn(),
}));

vi.mock('@/pages/lessons/catalog.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/lessons/catalog.api')>()),
  fetchLessons: vi.fn(),
}));

vi.mock('./plans.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./plans.api')>()),
  fetchVocabularyPacks: vi.fn(),
  fetchAdminToeicPage: vi.fn(),
  updateToeicAccess: vi.fn(),
  fetchAdminGrammarPage: vi.fn(),
}));

vi.mock('@/pages/curriculum/curriculum.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/curriculum/curriculum.api')>()),
  fetchAdminCurriculumPaths: vi.fn(),
}));

let client: QueryClient;

beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

  vi.mocked(fetchEntitlementFeatures).mockResolvedValue([
    { featureKey: 'speech.shadowing', displayName: 'Shadowing Assessment', description: '', status: 'ACTIVE' },
  ]);
  vi.mocked(fetchEntitlementQuotas).mockResolvedValue([
    {
      featureKey: 'speech.shadowing',
      tier: 'FREE',
      limitType: 'PERIOD_USAGE',
      periodUnit: 'DAY',
      maxAmount: 5,
      policyVersion: 'v1',
    },
    {
      featureKey: 'speech.shadowing',
      tier: 'PLUS',
      limitType: 'PERIOD_USAGE',
      periodUnit: 'DAY',
      maxAmount: 100,
      policyVersion: 'v1',
    },
  ]);
  vi.mocked(fetchLessons).mockResolvedValue({
    items: [
      {
        id: 'lesson-1',
        title: 'Morning Cafe',
        sourceType: 'TEXT',
        accessMode: 'PUBLIC',
        status: 'PUBLISHED',
      } as never,
    ],
    total: 1,
    page: 0,
    size: 10,
  });
  vi.mocked(fetchAdminToeicPage).mockResolvedValue({
    items: [
      {
        id: 'toeic-1',
        title: 'ETS TOEIC 2024 Test 1',
        year: 2024,
        type: 'FULL_TEST',
        status: 'PUBLISHED',
        accessMode: 'PUBLIC',
        questionCount: 200,
      },
    ],
    total: 1,
    page: 0,
    size: 10,
  });
  vi.mocked(fetchAdminGrammarPage).mockResolvedValue({
    items: [],
    total: 0,
    page: 0,
    size: 10,
  });
  vi.mocked(fetchVocabularyPacks).mockResolvedValue([]);
  vi.mocked(fetchAdminCurriculumPaths).mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  client.clear();
});

it('displays tabbed Free/Plus quotas, showcase preview dialog with billing toggle, and toggles TOEIC access tier', async () => {
  vi.mocked(updateEntitlementQuota).mockResolvedValue({
    featureKey: 'speech.shadowing',
    tier: 'FREE',
    limitType: 'PERIOD_USAGE',
    periodUnit: 'DAY',
    maxAmount: 5,
    policyVersion: 'v1',
  });
  vi.mocked(updateToeicAccess).mockResolvedValueOnce({});

  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter>
          <PlansPage />
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );

  expect(await screen.findByText('5')).toBeInTheDocument();
  expect(screen.getByText('100')).toBeInTheDocument();

  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Preview Free & Plus' }));
  expect(await screen.findByText('Recommended')).toBeInTheDocument();
  expect(screen.getAllByText('Lyreo Plus').length).toBeGreaterThan(0);

  await user.click(screen.getByRole('button', { name: 'Upgrade to Lyreo Plus' }));

  await user.click(screen.getByRole('tab', { name: 'Content Access Matrix' }));
  const toeicTab = await screen.findByRole('tab', { name: 'TOEIC tests' });
  await user.click(toeicTab);
  expect(await screen.findByText('ETS TOEIC 2024 Test 1')).toBeInTheDocument();

  const toggle = screen.getByRole('switch');
  fireEvent.click(toggle);

  await waitFor(() =>
    expect(updateToeicAccess).toHaveBeenCalledWith('toeic-1', 'FEATURE'),
  );
});
