import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { ApiError } from '@/lib/api';
import { LearnerDetailPage, LearnersPage } from './LearnersPage';
import {
  adjustLearnerDiamonds,
  createLearnerGrant,
  fetchLearner,
  fetchLearnerGamification,
  fetchLearnerGrants,
  fetchLearners,
  revokeLearnerGrant,
  updateLearnerAppearance,
  type LearnerEntry,
} from './learners.api';
import { learnerAvatarColor, learnerInitials } from './LearnerAvatar';
import { avatarPackIds, avatarPortraitSource, portraitPacks } from './learner-avatar-presets';
import { fetchLearnerAnalytics } from '@/pages/overview/admin-ops.api';

vi.mock('@/pages/overview/admin-ops.api', async importOriginal => ({
  ...(await importOriginal<typeof import('@/pages/overview/admin-ops.api')>()),
  fetchLearnerAnalytics: vi.fn(),
}));

vi.mock('./learners.api', async importOriginal => ({
  ...(await importOriginal<typeof import('./learners.api')>()),
  fetchLearners: vi.fn(),
  fetchLearner: vi.fn(),
  updateLearnerAppearance: vi.fn(),
  fetchLearnerAvatar: vi.fn(),
  uploadLearnerAvatar: vi.fn(),
  fetchLearnerGrants: vi.fn(),
  createLearnerGrant: vi.fn(),
  revokeLearnerGrant: vi.fn(),
  fetchLearnerGamification: vi.fn(),
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
  vi.mocked(fetchLearnerGrants).mockResolvedValue([]);
  vi.mocked(fetchLearnerAnalytics).mockResolvedValue({
    hasHistory: false,
    today: { fromDate: '2026-11-26', toDate: '2026-11-26', activeDays: 0, studySeconds: 0, lessonCount: 0, vocabularyReviewCount: 0, grammarAnswerCount: 0, toeicQuestionCount: 0 },
    week: { fromDate: '2026-11-20', toDate: '2026-11-26', activeDays: 0, studySeconds: 0, lessonCount: 0, vocabularyReviewCount: 0, grammarAnswerCount: 0, toeicQuestionCount: 0 },
    skills: [],
    weaknesses: [],
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
        <MemoryRouter initialEntries={[url]}>
          <Routes>
            <Route path="/learners" element={<LearnersPage />} />
            <Route path="/learners/:learnerId" element={<LearnerDetailPage />} />
          </Routes>
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );
}

it('queries the selected status and page, then keeps both when returning from a detail', async () => {
  show('/learners?status=COMPLETED&query=ada');
  await screen.findByRole('link', { name: 'Ada' });
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  await waitFor(() => expect(fetchLearners).toHaveBeenCalledWith(1, 'ada', 'COMPLETED', expect.any(AbortSignal)));
  fireEvent.click(screen.getByRole('link', { name: 'Ada' }));
  expect(await screen.findByText('10 min/day')).toBeInTheDocument();
  expect(screen.getByText('Conversation')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('link', { name: 'Back to learner profiles' }));
  expect(await screen.findByRole('searchbox')).toHaveValue('ada');
  expect(screen.getByRole('button', { name: 'Page 2' })).toHaveAttribute('aria-current', 'page');
});

it('debounces name or email search and resets to the first page', async () => {
  show('/learners?status=PENDING&page=2');
  await screen.findByRole('link', { name: 'Ada' });
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

it('grants and revokes an entitlement with a reason and adjusts diamonds', async () => {
  const activeGrant = {
    id: 'grant-1',
    userId: ada.learnerId,
    featureKey: 'vocabulary.plus-packs',
    status: 'ACTIVE',
    validFrom: '2026-11-25T00:00:00Z',
    validUntil: null,
    sourceType: 'ADMIN_MANUAL',
    sourceReference: 'VIP QA',
    createdBy: 'admin',
    createdAt: '2026-11-25T00:00:00Z',
    revokedAt: null,
    revokedBy: null,
    revocationReason: null,
  };
  vi.mocked(fetchLearnerGrants).mockResolvedValueOnce([]).mockResolvedValue([activeGrant]);
  vi.mocked(createLearnerGrant).mockResolvedValueOnce(activeGrant);
  vi.mocked(revokeLearnerGrant).mockResolvedValueOnce({
    ...activeGrant,
    status: 'REVOKED',
    revokedAt: '2026-11-25T01:00:00Z',
    revokedBy: 'admin',
    revocationReason: 'Expired trial',
  });
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
  expect(await screen.findByText('Entitlement grants')).toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: 'Grant feature' }));
  fireEvent.change(screen.getByLabelText('Reference note (optional)'), { target: { value: 'VIP QA' } });
  fireEvent.click(screen.getByRole('button', { name: 'Save grant' }));
  await waitFor(() =>
    expect(createLearnerGrant).toHaveBeenCalledWith(
      expect.objectContaining({
        userId: ada.learnerId,
        featureKey: 'vocabulary.plus-packs',
        sourceType: 'ADMIN_MANUAL',
        sourceReference: 'VIP QA',
      }),
    ),
  );

  fireEvent.click(await screen.findByRole('button', { name: 'Revoke' }));
  fireEvent.click(screen.getByRole('button', { name: 'Revoke grant' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Enter a reason before submitting.');
  fireEvent.change(screen.getByLabelText('Reason'), { target: { value: 'Expired trial' } });
  fireEvent.click(screen.getByRole('button', { name: 'Revoke grant' }));
  await waitFor(() => expect(revokeLearnerGrant).toHaveBeenCalledWith('grant-1', 'Expired trial'));

  fireEvent.click(screen.getByRole('button', { name: 'Adjust diamonds' }));
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
