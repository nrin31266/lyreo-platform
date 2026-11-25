import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { OverviewPage } from './OverviewPage';
import { fetchAdminCurriculumPaths } from '@/pages/curriculum/curriculum.api';
import { fetchJobs } from '@/pages/jobs/jobs.api';
import { fetchLearners } from '@/pages/learners/learners.api';
import { fetchLessons } from '@/pages/lessons/catalog.api';
import { fetchAdminAnalyticsSummary, fetchAdminChatSummary } from './admin-ops.api';

vi.mock('./admin-ops.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./admin-ops.api')>()),
  fetchAdminAnalyticsSummary: vi.fn(),
  fetchAdminChatSummary: vi.fn(),
}));

vi.mock('@/pages/curriculum/curriculum.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/curriculum/curriculum.api')>()),
  fetchAdminCurriculumPaths: vi.fn(),
}));

vi.mock('@/pages/lessons/catalog.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/lessons/catalog.api')>()),
  fetchLessons: vi.fn(),
}));

vi.mock('@/pages/learners/learners.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/learners/learners.api')>()),
  fetchLearners: vi.fn(),
}));

vi.mock('@/pages/jobs/jobs.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/jobs/jobs.api')>()),
  fetchJobs: vi.fn(),
}));

let client: QueryClient;

beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(fetchAdminAnalyticsSummary).mockResolvedValue({
    activeLearnersToday: 3,
    activeLearners7d: 5,
    studySeconds7d: 9600,
    lessonsCompleted7d: 9,
    vocabularyReviews7d: 140,
    grammarAnswers7d: 62,
    toeicQuestions7d: 155,
    dailySeries: [
      {
        activityDate: '2026-11-26',
        activeLearners: 3,
        studySeconds: 3300,
        lessonCount: 2,
        vocabularyReviewCount: 38,
        grammarAnswerCount: 18,
        toeicQuestionCount: 30,
      },
    ],
    topWeaknesses: [
      {
        weaknessType: 'LESSON_ACTIVITY',
        weaknessKey: 'SHADOWING',
        affectedLearners: 2,
        averageSeverity: 31.2,
        totalSamples: 11,
        lastUpdatedAt: '2026-11-26T00:00:00Z',
      },
    ],
  });
  vi.mocked(fetchAdminCurriculumPaths).mockResolvedValue([
    {
      id: 'path-1',
      code: 'TRAVEL_SURVIVAL_A1',
      name: 'Travel English',
      level: 'A1',
      description: null,
      status: 'PUBLISHED',
    },
  ]);
  vi.mocked(fetchLessons).mockResolvedValue({ items: [], total: 5, page: 0, size: 5 });
  vi.mocked(fetchLearners).mockResolvedValue({ items: [], total: 8, page: 0, size: 20 });
  vi.mocked(fetchJobs).mockResolvedValue({ items: [], total: 0, page: 0, size: 5 });
  vi.mocked(fetchAdminChatSummary).mockResolvedValue({
    totalConversations: 2,
    activeLearners: 2,
    userTurnsToday: 1,
    userTurns7d: 6,
    recentConversations: [],
  });
});

afterEach(() => {
  cleanup();
  client.clear();
});

it('renders platform operations KPIs, 7-day breakdown, and weaknesses', async () => {
  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter>
          <OverviewPage />
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );

  expect(await screen.findByText('Platform Operations Overview')).toBeInTheDocument();
  expect(await screen.findByText('140')).toBeInTheDocument();
  expect(screen.getByText('SHADOWING')).toBeInTheDocument();
  expect(screen.getByText('2026-11-26')).toBeInTheDocument();
});
