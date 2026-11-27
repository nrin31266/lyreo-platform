import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { ApiError } from '@/lib/api';
import { JobsPage } from './JobsPage';
import * as api from './jobs.api';
import * as authoring from '@/pages/lesson-builder/lesson-builder.api';
import type { Job } from './jobs.types';
vi.mock('./jobs.api', () => ({ fetchJobs: vi.fn(), fetchJob: vi.fn(), cancelJob: vi.fn() }));
vi.mock('@/pages/lesson-builder/lesson-builder.api', () => ({ fetchDiagnostics: vi.fn() }));
const job: Job = { id: '11111111-1111-4111-8111-111111111111', jobType: 'LESSON_AUTHORING', status: 'FAILED', currentStep: 'ALIGNING', progressPercent: 40, attemptCount: 1, maxAttempts: 3, createdAt: '2026-11-16T18:00:00Z', startedAt: '2026-11-16T18:00:01Z', finishedAt: '2026-11-16T18:00:04Z', errorCode: 'TIMING_INVALID', errorMessage: 'sentence=2, overlap detected', ownerModule: 'lesson', ownerReferenceId: 'lesson-a' };
let client: QueryClient;
beforeEach(async () => { vi.clearAllMocks(); await i18n.changeLanguage('en'); client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } }); vi.mocked(api.fetchJobs).mockResolvedValue({ items: [job], total: 1, page: 0, size: 20 }); vi.mocked(api.fetchJob).mockResolvedValue(job); });
afterEach(() => { cleanup(); client.clear(); });
function show(url = '/jobs') { return render(<QueryClientProvider client={client}><I18nextProvider i18n={i18n}><MemoryRouter initialEntries={[url]}><JobsPage /></MemoryRouter></I18nextProvider></QueryClientProvider>); }
it('lists jobs without a UUID and opens real failure details with the lesson link', async () => {
  show(); const row = await screen.findByRole('row', { name: /Lesson authoring/ });
  expect(row).toHaveTextContent('TIMING_INVALID'); expect(api.fetchJob).not.toHaveBeenCalled();
  fireEvent.click(within(row).getByRole('button', { name: 'Job details' }));
  const detail = await screen.findByRole('dialog');
  expect(await within(detail).findByRole('alert')).toHaveTextContent('sentence=2, overlap detected');
  expect(within(detail).getByRole('link', { name: 'Open lesson' })).toHaveAttribute('href', '/lessons/lesson-a');
  expect(within(detail).queryByRole('button', { name: 'Request cancellation' })).not.toBeInTheDocument();
});
it('retains filters and opens deep links without manual inspection', async () => {
  show(`/jobs?job=${job.id}&status=FAILED&type=LESSON_AUTHORING&page=2`);
  await screen.findByRole('dialog'); await waitFor(() => expect(api.fetchJob).toHaveBeenCalled());
  const params = vi.mocked(api.fetchJobs).mock.calls[0][0];
  expect(params.get('status')).toBe('FAILED'); expect(params.get('type')).toBe('LESSON_AUTHORING'); expect(params.get('page')).toBe('2');
});
it('rejects invalid IDs without sending an inspection request', async () => {
  show(); fireEvent.change(screen.getByRole('textbox', { name: 'Job UUID' }), { target: { value: 'not-an-id' } });
  fireEvent.click(screen.getByRole('button', { name: 'Inspect' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Enter a Job UUID'); expect(api.fetchJob).not.toHaveBeenCalled();
});
it('offers filter recovery for empty results and keeps pagination bounded', async () => {
  vi.mocked(api.fetchJobs).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 }); show('/jobs?status=ACTIVE');
  expect(await screen.findByText('No jobs found')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled(); expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
  await waitFor(() => expect(vi.mocked(api.fetchJobs).mock.calls.at(-1)![0].get('status')).toBe(''));
});
it('preserves loaded rows after a network failure and offers refresh', async () => {
  show(); await screen.findByRole('row', { name: /Lesson authoring/ });
  vi.mocked(api.fetchJobs).mockRejectedValue(new ApiError(0, 'offline'));
  fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
  await screen.findByRole('alert'); expect(screen.getByRole('row', { name: /Lesson authoring/ })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Retry' })).toBeEnabled();
});
it('renders private diagnostics as text only after an explicit request', async () => {
  vi.mocked(authoring.fetchDiagnostics).mockResolvedValue([{ payload: { output: '<script>alert(1)</script>' } }]); show(`/jobs?job=${job.id}`);
  const detail = await screen.findByRole('dialog'); await within(detail).findByText('TIMING_INVALID'); expect(authoring.fetchDiagnostics).not.toHaveBeenCalled();
  fireEvent.click(within(detail).getByRole('button', { name: 'Technical details' }));
  await within(detail).findByText(/<script>alert/); expect(detail.querySelector('script')).toBeNull();
});
it('confirms cancellation, prevents duplicate submits and preserves failure for retry', async () => {
  vi.mocked(api.fetchJob).mockResolvedValue({ ...job, status: 'RUNNING', errorCode: null, errorMessage: null, finishedAt: null });
  let reject!: (error: Error) => void; vi.mocked(api.cancelJob).mockImplementation(() => new Promise((_, failure) => { reject = failure; }));
  show(`/jobs?job=${job.id}`); const detail = await screen.findByRole('dialog');
  fireEvent.click(await within(detail).findByRole('button', { name: 'Request cancellation' })); expect(api.cancelJob).not.toHaveBeenCalled();
  const dialog = screen.getByRole('dialog', { name: 'Cancel this background job?' });
  const confirm = within(dialog).getByRole('button', { name: 'Request cancellation' });
  fireEvent.click(confirm); fireEvent.click(confirm); await waitFor(() => expect(api.cancelJob).toHaveBeenCalledTimes(1)); expect(confirm).toBeDisabled();
  await act(async () => reject(new Error('Temporary failure'))); expect(await within(dialog).findByRole('alert')).toBeInTheDocument(); expect(confirm).toBeEnabled();
});
it('shows cancellation as pending and removes the cancel action after acceptance', async () => {
  vi.mocked(api.fetchJob).mockResolvedValue({ ...job, status: 'CANCEL_REQUESTED', errorCode: null, errorMessage: null, finishedAt: null });
  show(`/jobs?job=${job.id}`); const detail = await screen.findByRole('dialog');
  expect(await within(detail).findByText(/Cancellation requested. The worker/)).toBeInTheDocument();
  expect(within(detail).queryByRole('button', { name: 'Request cancellation' })).not.toBeInTheDocument();
});
it('refreshes the list and selected job after cancellation is accepted', async () => {
  vi.mocked(api.fetchJob).mockResolvedValue({ ...job, status: 'RUNNING', errorCode: null, errorMessage: null, finishedAt: null });
  vi.mocked(api.cancelJob).mockResolvedValue(undefined);
  show(`/jobs?job=${job.id}`);
  const detail = await screen.findByRole('dialog');
  fireEvent.click(await within(detail).findByRole('button', { name: 'Request cancellation' }));
  vi.mocked(api.fetchJob).mockResolvedValue({ ...job, status: 'CANCEL_REQUESTED', errorCode: null, errorMessage: null, finishedAt: null });
  fireEvent.click(within(screen.getByRole('dialog', { name: 'Cancel this background job?' })).getByRole('button', { name: 'Request cancellation' }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Cancel this background job?' })).not.toBeInTheDocument());
  expect(await screen.findByText(/Cancellation requested. The worker/)).toBeInTheDocument();
  expect(api.fetchJobs).toHaveBeenCalledTimes(2);
  expect(api.fetchJob).toHaveBeenCalledTimes(2);
});
it('filters with quick status pills and links directly to authoring lessons from table rows', async () => {
  show();
  const row = await screen.findByRole('row', { name: /Lesson authoring/ });
  expect(within(row).getByRole('link', { name: /Open lesson 11111111/i })).toHaveAttribute('href', '/lessons/lesson-a');
  fireEvent.click(screen.getByRole('button', { name: 'Failed' }));
  await waitFor(() => expect(vi.mocked(api.fetchJobs).mock.calls.at(-1)![0].get('status')).toBe('FAILED'));
});
