import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { RewardsPage } from './RewardsPage';
import {
  createAdminMission,
  fetchAdminAchievements,
  fetchAdminMissions,
  fetchEntitlementAudit,
  fetchEntitlementFeatures,
  fetchEntitlementQuotas,
  fetchRewardPolicy,
} from '@/pages/overview/admin-ops.api';

vi.mock('@/pages/overview/admin-ops.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/overview/admin-ops.api')>()),
  fetchAdminMissions: vi.fn(),
  createAdminMission: vi.fn(),
  fetchAdminAchievements: vi.fn(),
  fetchRewardPolicy: vi.fn(),
  fetchEntitlementFeatures: vi.fn(),
  fetchEntitlementQuotas: vi.fn(),
  fetchEntitlementAudit: vi.fn(),
}));

let client: QueryClient;

beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(fetchAdminMissions).mockResolvedValue([
    {
      id: 'm-1',
      code: 'DAILY_SHADOWING_3',
      title: 'Practice 3 Shadowing lines',
      metric: 'SHADOWING_SENTENCE',
      target: 3,
      xpReward: 25,
      diamondReward: 3,
      recurrence: 'DAILY',
      ruleVersion: 'dev-v1',
      enabled: true,
    },
  ]);
  vi.mocked(fetchAdminAchievements).mockResolvedValue([]);
  vi.mocked(fetchRewardPolicy).mockResolvedValue({
    ruleVersion: 'dev-v1',
    directLessonActivityDiamondsEnabled: false,
    cutoverEffectiveAt: null,
    xpPerLevel: 100,
  });
  vi.mocked(fetchEntitlementFeatures).mockResolvedValue([]);
  vi.mocked(fetchEntitlementQuotas).mockResolvedValue([]);
  vi.mocked(fetchEntitlementAudit).mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  client.clear();
});

it('displays missions and creates a new mission definition', async () => {
  vi.mocked(createAdminMission).mockResolvedValueOnce({
    id: 'm-2',
    code: 'DAILY_VOCAB_10',
    title: 'Review 10 flashcards',
    metric: 'LESSON_COMPLETED',
    target: 1,
    xpReward: 20,
    diamondReward: 2,
    recurrence: 'DAILY',
    ruleVersion: 'dev-v1',
    enabled: true,
  });

  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter>
          <RewardsPage />
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );

  expect(await screen.findByText('DAILY_SHADOWING_3')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'New mission' }));
  fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'DAILY_VOCAB_10' } });
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Review 10 flashcards' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

  await waitFor(() =>
    expect(createAdminMission).toHaveBeenCalledWith(
      expect.objectContaining({
        code: 'DAILY_VOCAB_10',
        title: 'Review 10 flashcards',
      }),
    ),
  );
});
