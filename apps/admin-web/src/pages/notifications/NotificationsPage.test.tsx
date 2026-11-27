import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { TooltipProvider } from '@/components/ui/tooltip';
import { i18n } from '@/i18n';
import { fetchLearners, type LearnerEntry } from '@/pages/learners/learners.api';
import { NotificationsPage } from './NotificationsPage';
import { createAdminNotification, fetchAdminActivity, fetchAdminNotifications } from './notifications.api';

vi.mock('@/pages/learners/learners.api', () => ({ fetchLearners: vi.fn() }));
vi.mock('./notifications.api', async importOriginal => ({
  ...(await importOriginal<typeof import('./notifications.api')>()),
  fetchAdminNotifications: vi.fn(),
  fetchAdminActivity: vi.fn(),
  createAdminNotification: vi.fn(),
}));

const learner: LearnerEntry = {
  learnerId: '6eca0ef7-d80d-42e5-b694-d010a45ac233', displayName: 'Ada', email: 'ada@example.test',
  firstSeenAt: '2026-11-23T01:00:00Z', onboardingStatus: 'PENDING', onboardingCompletedAt: null,
  goal: null, currentLevel: null, dailyMinutes: null, focusArea: null, avatarPreset: null,
  avatarUploadId: null, toeicTargetScore: null,
};

beforeEach(async () => {
  vi.clearAllMocks();
  await i18n.changeLanguage('en');
  Object.defineProperty(Element.prototype, 'scrollIntoView', { value: vi.fn(), configurable: true });
  vi.mocked(fetchAdminNotifications).mockResolvedValue([]);
  vi.mocked(fetchLearners).mockResolvedValue({ items: [learner], total: 1, page: 0, size: 20 });
});

afterEach(cleanup);

function openPage() {
  const query = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<I18nextProvider i18n={i18n}><QueryClientProvider client={query}><MemoryRouter><TooltipProvider>
    <NotificationsPage />
  </TooltipProvider></MemoryRouter></QueryClientProvider></I18nextProvider>);
  return userEvent.setup();
}

it('requires a learner selection and sends only the selected IDs', async () => {
  const user = openPage();
  await user.click(screen.getByRole('button', { name: 'New announcement' }));
  const dialog = within(screen.getByRole('dialog'));
  await user.type(dialog.getByLabelText('Headline'), 'Practice feedback');
  await user.type(dialog.getByLabelText('Message body'), 'Review today’s lesson.');
  await user.click(dialog.getByRole('combobox', { name: 'Status' }));
  await user.click(await screen.findByRole('option', { name: /^Published$/ }));
  expect(dialog.getByRole('button', { name: /^Publish$/ })).toBeDisabled();
  await user.click(dialog.getByRole('combobox', { name: 'Target audience' }));
  await user.click(await screen.findByRole('option', { name: 'Selected learners' }));
  expect(dialog.getByRole('button', { name: /^Publish$/ })).toBeDisabled();
  await user.click(await dialog.findByRole('checkbox', { name: /Ada/ }));
  await user.click(dialog.getByRole('button', { name: 'Publish to 1 learner' }));
  await waitFor(() => expect(createAdminNotification).toHaveBeenCalledWith(expect.objectContaining({
    targetAudience: 'SELECTED', recipientIds: [learner.learnerId], status: 'PUBLISHED',
  })));
});

it('fetches the journal by category and shows a plan transition', async () => {
  vi.mocked(fetchAdminActivity).mockResolvedValue({ page: 0, hasNext: false, items: [{
    id: 'plan', category: 'MEMBERSHIP', action: 'PLUS_STARTED', learnerId: learner.learnerId,
    learnerEmail: learner.email, actor: null, amount: null, detail: null, occurredAt: learner.firstSeenAt, scheduledFor: null,
  }, {
    id: 'schedule', category: 'NOTIFICATION', action: 'NOTIFICATION_SCHEDULED', learnerId: null,
    learnerEmail: null, actor: null, amount: null, detail: 'Practice reminder', occurredAt: learner.firstSeenAt,
    scheduledFor: '2100-11-27T18:00:00Z',
  }, {
    id: 'lock', category: 'ACCOUNT', action: 'LOCK_REQUESTED', learnerId: learner.learnerId,
    learnerEmail: learner.email, actor: null, amount: null, detail: null, occurredAt: learner.firstSeenAt, scheduledFor: null,
  }] });
  const user = openPage();
  expect(fetchAdminActivity).not.toHaveBeenCalled();
  await user.click(screen.getByRole('tab', { name: 'Activity log' }));
  expect(await screen.findByText('Moved to Plus')).toBeInTheDocument();
  expect(screen.getByText('Scheduled notification')).toBeInTheDocument();
  expect(screen.getByText(/^Expected to appear .*2100/)).toBeInTheDocument();
  expect(screen.getByText('Account lock awaiting sync')).toBeInTheDocument();
  await user.click(screen.getByRole('combobox', { name: 'Category' }));
  await user.click(await screen.findByRole('option', { name: 'Free & Plus' }));
  await waitFor(() => expect(fetchAdminActivity).toHaveBeenLastCalledWith('MEMBERSHIP', 0, expect.any(AbortSignal)));
});
