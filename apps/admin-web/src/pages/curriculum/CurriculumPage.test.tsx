import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { CurriculumPage } from './CurriculumPage';
import {
  createAdminCurriculumPath,
  deleteAdminCurriculumPath,
  fetchAdminCurriculumPath,
  fetchAdminCurriculumPaths,
  fetchCurriculumPathDiagnostics,
  updateAdminCurriculumPath,
  type AdminCurriculumPath,
  type AdminCurriculumPathSummary,
} from './curriculum.api';

vi.mock('./curriculum.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./curriculum.api')>()),
  fetchAdminCurriculumPaths: vi.fn(),
  fetchAdminCurriculumPath: vi.fn(),
  fetchCurriculumPathDiagnostics: vi.fn(),
  createAdminCurriculumPath: vi.fn(),
  updateAdminCurriculumPath: vi.fn(),
  deleteAdminCurriculumPath: vi.fn(),
}));

const summary: AdminCurriculumPathSummary = {
  id: '01950000-0000-7000-8000-000000000001',
  code: 'FOUNDATION_PATH',
  name: 'Foundation Path',
  level: 'A2',
  description: 'Core onboarding steps',
  status: 'DRAFT',
  coverAssetId: null,
  accessMode: 'PUBLIC',
  requiredFeatureKey: null,
};

const samplePath: AdminCurriculumPath = {
  ...summary,
  sections: [
    {
      id: '01950000-0000-7000-8000-000000000011',
      title: 'Week 1',
      position: 1,
      items: [
        {
          id: '01950000-0000-7000-8000-000000000101',
          position: 1,
          contentType: 'LESSON',
          contentReferenceId: '01950000-0000-7000-8000-000000000201',
          required: true,
          unlockRule: 'NONE',
          config: { activityType: 'SHADOWING' },
        },
      ],
    },
  ],
};

let client: QueryClient;

beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(fetchAdminCurriculumPaths).mockResolvedValue([summary]);
  vi.mocked(fetchAdminCurriculumPath).mockResolvedValue(samplePath);
  vi.mocked(fetchCurriculumPathDiagnostics).mockResolvedValue({
    pathId: samplePath.id,
    totalItems: 1,
    validItems: 1,
    missingItems: 0,
    lockedForFreeUsers: 0,
    checks: [],
  });
});

afterEach(() => {
  cleanup();
  client.clear();
});

function show() {
  return render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <MemoryRouter>
          <CurriculumPage />
        </MemoryRouter>
      </I18nextProvider>
    </QueryClientProvider>,
  );
}

it('loads selected path detail and publishes updates', async () => {
  const published: AdminCurriculumPath = { ...samplePath, status: 'PUBLISHED' };
  vi.mocked(updateAdminCurriculumPath).mockResolvedValueOnce(published);
  show();

  expect(await screen.findByDisplayValue('Foundation Path')).toBeInTheDocument();
  expect(screen.getByDisplayValue('FOUNDATION_PATH')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Publish path' }));

  await waitFor(() =>
    expect(updateAdminCurriculumPath).toHaveBeenCalledWith(
      samplePath.id,
      expect.objectContaining({
        code: 'FOUNDATION_PATH',
        name: 'Foundation Path',
        status: 'PUBLISHED',
      }),
    ),
  );
  expect(await screen.findByText('Curriculum changes saved')).toBeInTheDocument();
});

it('validates content UUIDs and creates a new path with sections and items', async () => {
  const created: AdminCurriculumPath = {
    ...samplePath,
    id: '01950000-0000-7000-8000-000000000002',
    code: 'TOEIC_SPRINT',
    name: 'TOEIC Sprint',
  };
  vi.mocked(createAdminCurriculumPath).mockResolvedValueOnce(created);
  show();

  await screen.findByDisplayValue('Foundation Path');
  fireEvent.click(screen.getByRole('button', { name: 'Create path' }));

  fireEvent.change(screen.getByLabelText('Path name'), { target: { value: 'TOEIC Sprint' } });
  fireEvent.change(screen.getByLabelText('Path code'), { target: { value: 'TOEIC_SPRINT' } });
  fireEvent.change(screen.getByLabelText('Section 1 title'), { target: { value: 'Listening' } });
  fireEvent.change(screen.getByLabelText('Content reference ID (UUID)'), {
    target: { value: 'not-a-uuid' },
  });

  fireEvent.click(screen.getByRole('button', { name: 'Save path' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid UUID');
  expect(createAdminCurriculumPath).not.toHaveBeenCalled();

  fireEvent.change(screen.getByLabelText('Content reference ID (UUID)'), {
    target: { value: '01950000-0000-7000-8000-000000000999' },
  });
  fireEvent.click(screen.getByRole('button', { name: 'Save path' }));

  await waitFor(() =>
    expect(createAdminCurriculumPath).toHaveBeenCalledWith(
      expect.objectContaining({
        code: 'TOEIC_SPRINT',
        name: 'TOEIC Sprint',
        status: 'DRAFT',
        sections: [
          expect.objectContaining({
            title: 'Listening',
            position: 1,
            items: [
              expect.objectContaining({
                contentType: 'LESSON',
                contentReferenceId: '01950000-0000-7000-8000-000000000999',
                position: 1,
              }),
            ],
          }),
        ],
      }),
    ),
  );
});

it('opens delete confirmation dialog and deletes selected curriculum path', async () => {
  vi.mocked(deleteAdminCurriculumPath).mockResolvedValueOnce();
  show();

  expect(await screen.findByDisplayValue('Foundation Path')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Delete path' }));
  expect(await screen.findByText('Delete curriculum path')).toBeInTheDocument();

  vi.mocked(fetchAdminCurriculumPaths).mockResolvedValueOnce([]);
  fireEvent.click(screen.getByRole('button', { name: 'Delete permanently' }));

  await waitFor(() => expect(deleteAdminCurriculumPath).toHaveBeenCalledWith(samplePath.id));
});

it('switches to stepping-stone game map preview and simulates completing a stage', async () => {
  show();
  expect(await screen.findByDisplayValue('Foundation Path')).toBeInTheDocument();

  const user = (await import('@testing-library/user-event')).default.setup();
  await user.click(screen.getByRole('tab', { name: 'Interactive map' }));

  expect(await screen.findByText('Chapter 1')).toBeInTheDocument();
  expect(screen.getAllByText('Week 1').length).toBeGreaterThan(0);
  const stageBtn = screen.getByRole('button', { name: /Stage 1/i });
  await user.click(stageBtn);
  await user.click(screen.getByRole('button', { name: 'Mark stage completed' }));

  expect(screen.getByText('1 / 1 completed')).toBeInTheDocument();
});
