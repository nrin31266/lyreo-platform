import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { TooltipProvider } from '@/components/ui/tooltip';
import { i18n } from '@/i18n';
import { ApiError } from '@/lib/api';
import { LearnerDetailPage, LearnersPage } from './LearnersPage';
import {
  adjustLearnerDiamonds,
  fetchLearner,
  fetchLearnerGamification,
  fetchLearnerAccount,
  resetLearnerPassword,
  setLearnerAccountLock,
  fetchLearnerPlan,
  fetchLearners,
  updateLearnerPlan,
  updateLearnerAppearance,
  type LearnerEntry,
} from './learners.api';
import { learnerAvatarColor, learnerInitials } from './LearnerAvatar';
import { avatarPackIds, avatarPortraitSource, portraitPacks } from './learner-avatar-presets';
import {
  fetchLearnerAnalytics,
  fetchLearnerStudyProfile,
} from '@/pages/overview/admin-ops.api';

vi.mock('@/pages/overview/admin-ops.api', async importOriginal => ({
  ...(await importOriginal<typeof import('@/pages/overview/admin-ops.api')>()),
  fetchLearnerAnalytics: vi.fn(),
  fetchLearnerStudyProfile: vi.fn(),
}));

vi.mock('./learners.api', async importOriginal => ({
  ...(await importOriginal<typeof import('./learners.api')>()),
  fetchLearners: vi.fn(),
  fetchLearner: vi.fn(),
  updateLearnerAppearance: vi.fn(),
  fetchLearnerAvatar: vi.fn(),
  uploadLearnerAvatar: vi.fn(),
  fetchLearnerPlan: vi.fn(),
  updateLearnerPlan: vi.fn(),
  fetchLearnerGamification: vi.fn(),
  fetchLearnerAccount: vi.fn(),
  resetLearnerPassword: vi.fn(),
  setLearnerAccountLock: vi.fn(),
  adjustLearnerDiamonds: vi.fn(),
}));

const ada: LearnerEntry = {
  learnerId: '6eca0ef7-d80d-42e5-b694-d010a45ac233',
  displayName: 'Ada',
  email: 'ada@example.test',
  firstSeenAt: '2026-11-23T01:00:00Z',
  onboardingStatus: 'COMPLETED',
  onboardingCompletedAt: '2026-11-23T02:00:00Z',
  goal: 'CONVERSATION',
  currentLevel: 'A2',
  dailyMinutes: 10,
  focusArea: 'LISTENING',
  avatarPreset: null,
  avatarUploadId: null,
  toeicTargetScore: null,
};
const pending: LearnerEntry = {
  ...ada,
  learnerId: 'a7c7c755-2df9-4aa8-a7ce-9acc5ae93775',
  displayName: null,
  email: 'new@example.test',
  onboardingStatus: 'PENDING',
  onboardingCompletedAt: null,
  goal: null,
  currentLevel: null,
  dailyMinutes: null,
  focusArea: null,
};

let client: QueryClient;
beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(fetchLearners).mockResolvedValue({ items: [ada], total: 41, page: 0, size: 20 });
  vi.mocked(fetchLearner).mockResolvedValue(ada);
  vi.mocked(fetchLearnerAccount).mockResolvedValue({ management: 'UNMANAGED', locked: false, syncPending: false });
  vi.mocked(fetchLearnerPlan).mockResolvedValue({ tier: 'FREE', validUntil: null, history: [] });
  vi.mocked(fetchLearnerAnalytics).mockResolvedValue({
    hasHistory: false,
    today: { fromDate: '2026-11-26', toDate: '2026-11-26', activeDays: 0, studySeconds: 0, lessonCount: 0, vocabularyReviewCount: 0, grammarAnswerCount: 0, toeicQuestionCount: 0 },
    week: { fromDate: '2026-11-20', toDate: '2026-11-26', activeDays: 0, studySeconds: 0, lessonCount: 0, vocabularyReviewCount: 0, grammarAnswerCount: 0, toeicQuestionCount: 0 },
    skills: [],
    weaknesses: [],
  });
  vi.mocked(fetchLearnerStudyProfile).mockResolvedValue({
    hasHistory: true,
    today: { fromDate: '2026-11-26', toDate: '2026-11-26', activeDays: 1, studySeconds: 900, lessonCount: 2, vocabularyReviewCount: 15, grammarAnswerCount: 10, toeicQuestionCount: 5 },
    week: { fromDate: '2026-11-20', toDate: '2026-11-26', activeDays: 5, studySeconds: 4500, lessonCount: 6, vocabularyReviewCount: 80, grammarAnswerCount: 30, toeicQuestionCount: 20 },
    month: { fromDate: '2026-10-28', toDate: '2026-11-26', activeDays: 12, studySeconds: 10800, lessonCount: 14, vocabularyReviewCount: 180, grammarAnswerCount: 70, toeicQuestionCount: 45 },
    dailySeries: [
      { date: '2026-11-25', studySeconds: 600, lessonCount: 1, vocabularyReviewCount: 10, grammarAnswerCount: 5, toeicQuestionCount: 0 },
      { date: '2026-11-26', studySeconds: 900, lessonCount: 2, vocabularyReviewCount: 15, grammarAnswerCount: 10, toeicQuestionCount: 5 },
    ],
    skills: [{ skillCode: 'LISTENING', score: 82.5, sampleCount: 10, updatedAt: '2026-11-26T00:00:00Z' }],
    weaknesses: [
      { weaknessType: 'GRAMMAR_TOPIC', weaknessKey: 'tenses', label: 'Verb Tenses & Aspect', severity: 64, sampleCount: 12, updatedAt: '2026-11-26T00:00:00Z' },
      { weaknessType: 'TOEIC_PART', weaknessKey: '5', label: null, severity: 52, sampleCount: 4, updatedAt: '2026-11-26T00:00:00Z' },
    ],
    enrolledPaths: [{ pathId: 'p-1', pathName: 'Core A2–B1 Track', level: 'A2', status: 'IN_PROGRESS', completedItems: 3, totalItems: 8, startedAt: '2026-11-10T00:00:00Z' }],
    unlockedAchievements: [{ achievementId: 'a-1', code: 'STREAK_7', title: '7-Day Streak', badgeTier: 'BRONZE', badgeAssetId: null, unlockedAt: '2026-11-25T00:00:00Z' }],
    breakdown: { lessonsCompleted: 5, lessonsInProgress: 2, vocabCardsTotal: 120, vocabCardsMastered: 48, grammarAttempts: 30, grammarAccuracyPct: 83, toeicAttempts: 3, bestToeicScore: 760 },
  });
  vi.mocked(fetchLearnerGamification).mockResolvedValue({
    learnerId: ada.learnerId,
    xp: 120,
    level: 2,
    xpIntoLevel: 20,
    xpForNextLevel: 100,
    diamondBalance: 15,
    currentStreak: 3,
    longestStreak: 5,
    lastActiveDate: '2026-11-25',
    transactions: [],
  });
});

afterEach(() => {
  cleanup();
  client.clear();
});

function show(url = '/learners') {
  return render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <TooltipProvider>
          <MemoryRouter initialEntries={[url]}>
            <Routes>
              <Route path="/learners" element={<LearnersPage />} />
              <Route path="/learners/:learnerId" element={<LearnerDetailPage />} />
            </Routes>
          </MemoryRouter>
        </TooltipProvider>
      </I18nextProvider>
    </QueryClientProvider>,
  );
}

it('queries the selected status and page, selects inline in inspector, and preserves query on return link', async () => {
  show('/learners?status=COMPLETED&query=ada');
  await screen.findByRole('button', { name: 'Ada' });
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  await waitFor(() => expect(fetchLearners).toHaveBeenCalledWith(1, 'ada', 'COMPLETED', expect.any(AbortSignal)));
  fireEvent.click(screen.getByRole('button', { name: 'Ada' }));
  expect(await screen.findByText('10 min/day')).toBeInTheDocument();
  expect(screen.getByText('Conversation')).toBeInTheDocument();
  expect(screen.getByRole('searchbox')).toHaveValue('ada');
  expect(screen.getByRole('button', { name: 'Page 2' })).toHaveAttribute('aria-current', 'page');

  cleanup();
  show(`/learners/${ada.learnerId}?status=COMPLETED&query=ada&page=1`);
  fireEvent.click(await screen.findByRole('link', { name: 'Back to learner profiles' }));
  expect(await screen.findByRole('searchbox')).toHaveValue('ada');
});

it('debounces name or email search and resets to the first page', async () => {
  show('/learners?status=PENDING&page=2');
  await screen.findByRole('button', { name: 'Ada' });
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: ' new@example.test ' } });
  await waitFor(() => expect(fetchLearners).toHaveBeenCalledWith(0, 'new@example.test', 'PENDING', expect.any(AbortSignal)));
});

it('shows a pending learner with missing details', async () => {
  vi.mocked(fetchLearner).mockResolvedValue(pending);
  show(`/learners/${pending.learnerId}`);
  expect(await screen.findByRole('heading', { name: 'new@example.test' })).toBeInTheDocument();
  expect(screen.getByText('Pending')).toBeInTheDocument();
  expect(screen.getAllByText('Not provided')).toHaveLength(5);
});

it('recovers from a request failure and displays the filtered empty state', async () => {
  vi.mocked(fetchLearners).mockRejectedValueOnce(new ApiError(0, 'offline'));
  vi.mocked(fetchLearners).mockResolvedValueOnce({ items: [], total: 0, page: 0, size: 20 });
  show('/learners?query=missing');
  await screen.findByRole('alert');
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByText('No learners found')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
  await waitFor(() => expect(fetchLearners).toHaveBeenLastCalledWith(0, '', '', expect.any(AbortSignal)));
});

it('opens avatar and name controls on demand and saves separate changes', async () => {
  const portrait = { ...ada, avatarPreset: 'RED_PANDA' as const };
  vi.mocked(updateLearnerAppearance).mockResolvedValueOnce(portrait);
  vi.mocked(fetchLearner).mockResolvedValue(ada);
  show(`/learners/${ada.learnerId}`);
  await screen.findByRole('heading', { name: 'Ada' });
  expect(screen.queryByRole('textbox', { name: 'Display name' })).not.toBeInTheDocument();
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Change avatar' }));
  expect(await screen.findByRole('dialog', { name: 'Change avatar' })).toBeInTheDocument();
  vi.mocked(fetchLearner).mockResolvedValue(portrait);
  fireEvent.click(screen.getByRole('button', { name: 'Red panda' }));
  fireEvent.click(screen.getByRole('button', { name: 'Save avatar' }));
  await waitFor(() => expect(updateLearnerAppearance).toHaveBeenCalledWith(ada.learnerId, { avatarPreset: 'RED_PANDA' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  fireEvent.click(screen.getByRole('button', { name: 'Edit name' }));
  fireEvent.change(screen.getByRole('textbox', { name: 'Display name' }), { target: { value: 'Mai' } });
  vi.mocked(updateLearnerAppearance).mockResolvedValueOnce({ ...portrait, displayName: 'Mai' });
  vi.mocked(fetchLearner).mockResolvedValue({ ...portrait, displayName: 'Mai' });
  fireEvent.click(screen.getByRole('button', { name: 'Save name' }));
  expect(await screen.findByRole('heading', { name: 'Mai' })).toBeInTheDocument();
});

it('can cancel a name change without sending a write', async () => {
  show(`/learners/${ada.learnerId}`);
  await screen.findByRole('heading', { name: 'Ada' });
  fireEvent.click(screen.getByRole('button', { name: 'Edit name' }));
  fireEvent.change(screen.getByRole('textbox', { name: 'Display name' }), { target: { value: 'Unsaved' } });
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('textbox', { name: 'Display name' })).not.toBeInTheDocument();
  expect(updateLearnerAppearance).not.toHaveBeenCalled();
});

it('shows all collections in one scroller and saves an avatar from a later group', async () => {
  vi.mocked(updateLearnerAppearance).mockResolvedValue({ ...ada, avatarPreset: 'COMIC_LUNA' });
  show(`/learners/${ada.learnerId}`);
  fireEvent.click(await screen.findByRole('button', { name: 'Change avatar' }));
  const catalog = within(screen.getByRole('region', { name: 'Avatar collections' }));
  expect(catalog.queryByRole('tablist')).not.toBeInTheDocument();
  expect(catalog.getAllByRole('heading').map(heading => heading.textContent))
    .toEqual(['Animals', 'Flowers', 'Characters', 'Comics']);
  expect(catalog.getAllByRole('button')).toHaveLength(28);
  for (const pack of avatarPackIds) {
    for (const preset of portraitPacks[pack]) expect(avatarPortraitSource(preset)).toBeTruthy();
  }
  fireEvent.click(catalog.getByRole('button', { name: /^Luna$/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Save avatar' }));
  await waitFor(() => expect(updateLearnerAppearance).toHaveBeenCalledWith(ada.learnerId, { avatarPreset: 'COMIC_LUNA' }));
});

it('uses the first and last name with a fallback for a missing display name', () => {
  expect(learnerInitials('Đặng Minh Ánh', null)).toBe('ĐÁ');
  expect(learnerInitials(null, 'gia-huy@example.test')).toBe('GH');
  expect(learnerInitials(null, null)).toBe('?');
  expect(learnerAvatarColor(ada.learnerId)).toBe(learnerAvatarColor(ada.learnerId));
  expect(learnerAvatarColor(ada.learnerId)).not.toBe(learnerAvatarColor(pending.learnerId));
});

it('uses initials when a saved avatar is absent from the catalog', async () => {
  vi.mocked(fetchLearner).mockResolvedValue({ ...ada, avatarPreset: 'CAT' });
  show(`/learners/${ada.learnerId}`);
  const avatar = await screen.findByRole('button', { name: 'Change avatar' });
  expect(within(avatar).getByText('AD')).toBeInTheDocument();
  fireEvent.click(avatar);
  expect(screen.getByRole('button', { name: 'Initials' })).toHaveAttribute('aria-pressed', 'true');
  expect(updateLearnerAppearance).not.toHaveBeenCalled();
});

it('upgrades a learner to Plus, downgrades back to Free, and adjusts diamonds', async () => {
  const plusChange = { tier: 'PLUS' as const, changedAt: '2026-11-25T00:00:00Z' };
  const freeChange = { tier: 'FREE' as const, changedAt: '2026-11-25T01:00:00Z' };
  vi.mocked(updateLearnerPlan)
    .mockResolvedValueOnce({ tier: 'PLUS', validUntil: null, history: [plusChange] })
    .mockResolvedValueOnce({ tier: 'FREE', validUntil: null, history: [freeChange, plusChange] });
  const adjustedSummary = {
    learnerId: ada.learnerId,
    xp: 120,
    level: 2,
    xpIntoLevel: 20,
    xpForNextLevel: 100,
    diamondBalance: 40,
    currentStreak: 3,
    longestStreak: 5,
    lastActiveDate: '2026-11-25',
    transactions: [
      {
        id: 'tx-1',
        amount: 25,
        balanceAfter: 40,
        reason: 'Manual QA credit',
        sourceType: 'ADMIN_ADJUSTMENT',
        sourceReference: 'tx-1',
        createdAt: '2026-11-25T01:00:00Z',
      },
    ],
  };
  vi.mocked(adjustLearnerDiamonds).mockImplementation(async () => {
    vi.mocked(fetchLearnerGamification).mockResolvedValue(adjustedSummary);
    return {
      transactionId: 'tx-1',
      learnerId: ada.learnerId,
      amount: 25,
      balance: 40,
      reason: 'Manual QA credit',
      idempotencyKey: 'key-1',
      ruleVersion: 'dev-v1',
      createdAt: '2026-11-25T01:00:00Z',
    };
  });

  show(`/learners/${ada.learnerId}`);
  const planTab = await screen.findByRole('tab', { name: 'Free & Plus' });
  fireEvent.mouseDown(planTab);
  fireEvent.click(planTab);
  expect(await screen.findByText('Membership Plan')).toBeInTheDocument();

  fireEvent.click(await screen.findByRole('button', { name: 'Upgrade to Plus' }));
  fireEvent.click(screen.getByRole('button', { name: 'Confirm Plus upgrade' }));
  await waitFor(() => expect(updateLearnerPlan).toHaveBeenNthCalledWith(1, ada.learnerId, 'PLUS', undefined));

  fireEvent.click(await screen.findByRole('button', { name: 'Switch to Free' }));
  fireEvent.click(screen.getByRole('button', { name: 'Confirm switch to Free' }));
  await waitFor(() => expect(updateLearnerPlan).toHaveBeenNthCalledWith(2, ada.learnerId, 'FREE', undefined));
  await screen.findByRole('button', { name: 'Upgrade to Plus' });
  expect(updateLearnerPlan).toHaveBeenCalledTimes(2);
  const history = screen.getByText('Plan history');
  fireEvent.click(history);
  expect(within(history.closest('details')!).getAllByRole('listitem').map((row) => row.querySelector('span span')?.textContent))
    .toEqual(['Free', 'Lyreo Plus']);

  const rewardsTab = screen.getByRole('tab', { name: 'XP & Diamonds' });
  fireEvent.mouseDown(rewardsTab);
  fireEvent.click(rewardsTab);
  fireEvent.click(await screen.findByRole('button', { name: 'Adjust diamonds' }));
  fireEvent.change(screen.getByLabelText('Diamond delta (+ / -)'), { target: { value: '25' } });
  fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'Manual QA credit' } });
  fireEvent.click(screen.getByRole('button', { name: 'Apply adjustment' }));
  await waitFor(() =>
    expect(adjustLearnerDiamonds).toHaveBeenCalledWith(
      ada.learnerId,
      expect.objectContaining({
        amount: 25,
        reason: 'Manual QA credit',
      }),
    ),
  );
  expect(await screen.findByText('Manual QA credit')).toBeInTheDocument();
});

it('displays rich study profile with daily series, enrolled paths, unlocked badges, and resolved weakness labels', async () => {
  show(`/learners/${ada.learnerId}`);
  const study = await screen.findByRole('tab', { name: 'Study & skills' });
  expect(screen.queryByText('Core A2–B1 Track')).not.toBeInTheDocument();
  fireEvent.mouseDown(study);
  expect(await screen.findByText('Core A2–B1 Track')).toBeInTheDocument();
  expect(screen.getByText('7-Day Streak')).toBeInTheDocument();
  expect(screen.getByText(/Verb Tenses & Aspect/)).toBeInTheDocument();
  expect(screen.getByText('Highest scaled score 760')).toBeInTheDocument();
  expect(screen.getByText('TOEIC Part 5')).toBeInTheDocument();
  expect(screen.getByText(/Incomplete sentences/)).toBeInTheDocument();
  const help = screen.getByRole('button', { name: /calculation for .*weakness/i });
  act(() => help.focus());
  expect(await screen.findByRole('tooltip')).toHaveTextContent(/mean error/i);
});


it('shows the TOEIC target and compact streak in the profile and unmeasured skills in their own tab', async () => {
  const example = await fetchLearnerStudyProfile(ada.learnerId);
  vi.mocked(fetchLearnerStudyProfile).mockResolvedValue({ ...example, hasHistory: false, skills: [] });
  vi.mocked(fetchLearner).mockResolvedValue({ ...ada, toeicTargetScore: 750 });
  show(`/learners/${ada.learnerId}`);
  expect(await screen.findByText('750 points')).toBeInTheDocument();
  expect(await screen.findByText('3 current days · 5 best')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Edit TOEIC target' })).not.toBeInTheDocument();
  expect(screen.queryByText('No score yet')).not.toBeInTheDocument();
  fireEvent.mouseDown(screen.getByRole('tab', { name: 'Study & skills' }));
  expect(await screen.findAllByText('No score yet')).toHaveLength(6);
});

it('lets Admin choose password rotation and locks then unlocks a learner', async () => {
  vi.mocked(fetchLearnerAccount).mockResolvedValue({ management: 'AVAILABLE', locked: false, syncPending: false });
  vi.mocked(resetLearnerPassword).mockResolvedValue(undefined);
  vi.mocked(setLearnerAccountLock)
    .mockResolvedValueOnce({ management: 'AVAILABLE', locked: true, syncPending: false })
    .mockResolvedValueOnce({ management: 'AVAILABLE', locked: false, syncPending: false });
  show(`/learners/${ada.learnerId}`);
  fireEvent.click(await screen.findByRole('button', { name: 'Reset password' }));
  let dialog = screen.getByRole('dialog');
  const temporary = within(dialog).getByRole('checkbox');
  expect(temporary).not.toBeChecked();
  fireEvent.click(temporary);
  fireEvent.change(within(dialog).getByLabelText('New password'), { target: { value: 'Fixture-Password!87' } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Reset password' }));
  expect(await screen.findByText('Password reset.')).toBeInTheDocument();
  expect(resetLearnerPassword).toHaveBeenCalledWith(ada.learnerId, 'Fixture-Password!87', true);

  fireEvent.click(screen.getByRole('button', { name: 'Lock account' }));
  dialog = screen.getByRole('dialog');
  expect(within(dialog).getByRole('textbox')).not.toBeRequired();
  fireEvent.click(within(dialog).getByRole('button', { name: 'Lock account' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Unlock account' }));
  dialog = screen.getByRole('dialog');
  fireEvent.change(within(dialog).getByLabelText('Reason (optional)'), { target: { value: 'QA recovery' } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Unlock account' }));
  expect(await screen.findByText('Account unlocked.')).toBeInTheDocument();
  expect(setLearnerAccountLock).toHaveBeenNthCalledWith(1, ada.learnerId, true, undefined);
  expect(setLearnerAccountLock).toHaveBeenNthCalledWith(2, ada.learnerId, false, 'QA recovery');
});
