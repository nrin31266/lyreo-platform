import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { fetchAdminActivity } from '@/pages/notifications/notifications.api';
import { fetchAdminDashboard, type AdminDashboardSummary } from './admin-ops.api';
import { OverviewPage } from './OverviewPage';

vi.mock('@/pages/overview/admin-ops.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/overview/admin-ops.api')>()),
  fetchAdminDashboard: vi.fn(),
}));

vi.mock('@/pages/notifications/notifications.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/notifications/notifications.api')>()),
  fetchAdminActivity: vi.fn(),
}));

beforeAll(() => {
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
});

const mockDashboard14: AdminDashboardSummary = {
  period: { days: 14, fromDate: '2026-11-15', toDate: '2026-11-28' },
  kpis: {
    activeLearners: 128,
    previousActiveLearners: 100,
    activeLearnersToday: 42,
    totalLearners: 320,
    newLearners: 24,
    previousNewLearners: 20,
    onboardedLearners: 280,
    activePlusLearners: 64,
    plusStartedInPeriod: 9,
    studySeconds: 54_000,
    previousStudySeconds: 45_000,
    lessonsCompleted: 85,
    vocabularyReviews: 410,
    grammarAnswers: 290,
    toeicQuestions: 195,
    practiceTotal: 980,
    previousPracticeTotal: 800,
    aiTutorTurns: 145,
    aiTutorTurnsToday: 18,
    aiTutorActiveLearners: 31,
    aiTutorTotalConversations: 74,
  },
  dailySeries: [
    {
      activityDate: '2026-11-27',
      activeLearners: 36,
      newLearners: 3,
      studySeconds: 4200,
      lessonCount: 12,
      vocabularyReviewCount: 55,
      grammarAnswerCount: 38,
      toeicQuestionCount: 25,
    },
    {
      activityDate: '2026-11-28',
      activeLearners: 42,
      newLearners: 4,
      studySeconds: 5100,
      lessonCount: 14,
      vocabularyReviewCount: 64,
      grammarAnswerCount: 40,
      toeicQuestionCount: 30,
    },
  ],
  operations: {
    jobsRunning: 2,
    jobsFailed24h: 1,
    jobsSucceeded24h: 18,
    aiProvidersEnabled: 2,
    aiProvidersHealthy: 2,
    aiProvidersUnhealthy: 0,
    aiInvocations24h: 240,
    aiErrors24h: 3,
    aiAvgLatencyMs24h: 410,
    lockedAccounts: 1,
    pendingAccountSync: 0,
    scheduledNotifications: 2,
  },
  contentInventory: {
    lessonsPublished: 18,
    lessonsDraft: 3,
    curriculumPathsPublished: 5,
    curriculumPathsDraft: 1,
    curriculumEnrollmentsActive: 112,
    curriculumEnrollmentsCompleted: 19,
    vocabularyPacksPublished: 12,
    vocabularyUnitsTotal: 48,
    grammarTopicsPublished: 16,
    toeicTestsPublished: 6,
    lexiconHeadwordsTotal: 2500,
  },
  topCurriculumPaths: [
    {
      pathId: '11111111-1111-1111-1111-111111111111',
      code: 'TOEIC_500_FOUNDATION',
      name: 'TOEIC 500+ Foundation',
      level: 'A2',
      status: 'PUBLISHED',
      enrolledLearners: 72,
      completedLearners: 14,
      averageCompletionPercent: 58,
    },
  ],
  topWeaknesses: [
    {
      weaknessType: 'TOEIC_PART',
      weaknessKey: '2',
      affectedLearners: 29,
      averageSeverity: 68,
      totalSamples: 140,
      lastUpdatedAt: '2026-11-28T00:00:00Z',
    },
  ],
};

let client: QueryClient;

beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(fetchAdminDashboard).mockResolvedValue(mockDashboard14);
  vi.mocked(fetchAdminActivity).mockResolvedValue({
    items: [
      {
        id: 'reward-policy:1',
        category: 'REWARD',
        action: 'REWARD_POLICY_UPDATED',
        learnerId: null,
        learnerEmail: null,
        actor: 'admin@lyreo.app',
        detail: 'XP 120 (+20, x1.03)',
        amount: null,
        occurredAt: '2026-11-28T00:00:00Z',
        scheduledFor: null,
      },
    ],
    page: 0,
    hasNext: false,
  });
});

afterEach(() => {
  cleanup();
  client.clear();
});

it('renders platform KPIs, operations health, content inventory, weaknesses, and activity', async () => {
  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={['/']}>
          <OverviewPage />
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );

  expect(screen.getByText('Platform Operations Overview')).toBeInTheDocument();
  expect(await screen.findByText('128')).toBeInTheDocument();
  expect(screen.getByText('64')).toBeInTheDocument();
  expect(screen.getByText('980')).toBeInTheDocument();
  expect(screen.getByText(/15h/)).toBeInTheDocument();
  expect(screen.getByText('Action required')).toBeInTheDocument();
  expect(screen.getByText('TOEIC 500+ Foundation')).toBeInTheDocument();
  expect(screen.getByText('TOEIC Part 2')).toBeInTheDocument();
  expect(await screen.findByText('Updated global reward policy')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Table' }));
  expect(await screen.findByText('Nov 28')).toBeInTheDocument();
  expect(screen.getByText('55')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: '7 days' }));
  await waitFor(() =>
    expect(fetchAdminDashboard).toHaveBeenCalledWith(7, expect.anything()),
  );
});
