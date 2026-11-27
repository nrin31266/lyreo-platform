import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { api } from '@/lib/api';
import { CurriculumPage } from './CurriculumPage';
import { CurriculumSlicePickerDialog } from './CurriculumSlicePickerDialog';
import {
  createAdminCurriculumPath,
  deleteAdminCurriculumPath,
  fetchAdminCurriculumPath,
  fetchAdminCurriculumPaths,
  updateAdminCurriculumPath,
  type AdminCurriculumPath,
  type AdminCurriculumPathSummary,
} from './curriculum.api';

vi.mock('@/lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api')>()),
  api: vi.fn(),
}));

vi.mock('./curriculum.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./curriculum.api')>()),
  fetchAdminCurriculumPaths: vi.fn(),
  fetchAdminCurriculumPath: vi.fn(),
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
          contentTitle: 'Morning Coffee',
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

it('loads selected path detail, disables Save when clean, and publishes updates', async () => {
  const published: AdminCurriculumPath = { ...samplePath, status: 'PUBLISHED' };
  vi.mocked(updateAdminCurriculumPath).mockResolvedValueOnce(published);
  show();

  expect(await screen.findByText('Morning Coffee')).toBeInTheDocument();
  expect(screen.getByText('FOUNDATION_PATH')).toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: /Diagnostics/i })).not.toBeInTheDocument();

  const saveBtn = screen.getByRole('button', { name: 'Save path' });
  expect(saveBtn).toBeDisabled();

  fireEvent.change(screen.getByLabelText('Section 1 title'), { target: { value: 'Week 1 Updated' } });
  expect(saveBtn).toBeEnabled();

  fireEvent.click(screen.getByRole('button', { name: 'Publish path' }));

  await waitFor(() =>
    expect(updateAdminCurriculumPath).toHaveBeenCalledWith(
      samplePath.id,
      expect.objectContaining({
        code: 'FOUNDATION_PATH',
        name: 'Foundation Path',
        status: 'PUBLISHED',
        sections: [
          expect.objectContaining({
            id: samplePath.sections[0].id,
            title: 'Week 1 Updated',
            items: [
              expect.objectContaining({
                id: samplePath.sections[0].items[0].id,
              }),
            ],
          }),
        ],
      }),
    ),
  );
  expect(await screen.findByText('Curriculum changes saved')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Save path' })).toBeDisabled();

  // Renaming metadata without modifying sections omits sections from payload
  vi.mocked(updateAdminCurriculumPath).mockResolvedValueOnce({ ...published, name: 'Foundation Path v2' });
  fireEvent.click(screen.getByRole('button', { name: 'Path details' }));
  fireEvent.change(screen.getByLabelText('Path name'), { target: { value: 'Foundation Path v2' } });
  fireEvent.click(screen.getByRole('button', { name: 'Done' }));
  fireEvent.click(screen.getByRole('button', { name: 'Save path' }));
  await waitFor(() => {
    const calls = vi.mocked(updateAdminCurriculumPath).mock.calls;
    const lastPayload = calls[calls.length - 1][1];
    expect(lastPayload.name).toBe('Foundation Path v2');
    expect(lastPayload.sections).toBeUndefined();
  });
});

it('validates content UUIDs and creates a new path via Path Info dialog with auto-generated code', async () => {
  const created: AdminCurriculumPath = {
    ...samplePath,
    id: '01950000-0000-7000-8000-000000000002',
    code: 'TOEIC_SPRINT',
    name: 'TOEIC Sprint',
  };
  vi.mocked(createAdminCurriculumPath).mockResolvedValueOnce(created);
  show();

  await screen.findByText('Morning Coffee');
  fireEvent.click(screen.getByRole('button', { name: 'Create path' }));

  expect(await screen.findByText('Curriculum path details')).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Path name'), { target: { value: 'TOEIC Sprint' } });
  expect(screen.getAllByText('TOEIC_SPRINT').length).toBeGreaterThan(0);
  fireEvent.click(screen.getByRole('button', { name: 'Done' }));

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

  await waitFor(() => {
    expect(createAdminCurriculumPath).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(createAdminCurriculumPath).mock.calls[0][0];
    expect(payload.code).toBeUndefined();
    expect(payload).toEqual(
      expect.objectContaining({
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
    );
  });
});

it('opens delete confirmation dialog and deletes selected curriculum path', async () => {
  vi.mocked(deleteAdminCurriculumPath).mockResolvedValueOnce();
  show();

  expect(await screen.findByText('Morning Coffee')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Delete path' }));
  expect(await screen.findByText('Delete curriculum path')).toBeInTheDocument();

  vi.mocked(fetchAdminCurriculumPaths).mockResolvedValueOnce([]);
  fireEvent.click(screen.getByRole('button', { name: 'Delete permanently' }));

  await waitFor(() => expect(deleteAdminCurriculumPath).toHaveBeenCalledWith(samplePath.id));
});

it('renders branching interactive map with challenge crown and unlocks optional branch after previous required stage', async () => {
  vi.mocked(fetchAdminCurriculumPath).mockResolvedValueOnce({
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
            contentTitle: 'Stage One',
          },
          {
            id: '01950000-0000-7000-8000-000000000102',
            position: 2,
            contentType: 'LESSON',
            contentReferenceId: '01950000-0000-7000-8000-000000000202',
            required: false,
            unlockRule: 'PREVIOUS_REQUIRED',
            config: { activityType: 'DICTATION' },
            contentTitle: 'Optional Branch',
          },
          {
            id: '01950000-0000-7000-8000-000000000103',
            position: 3,
            contentType: 'TOEIC_TEST',
            contentReferenceId: '01950000-0000-7000-8000-000000000203',
            required: true,
            unlockRule: 'PREVIOUS_REQUIRED',
            config: { parts: [1, 2, 3, 4, 5, 6, 7], questionCount: 200 },
            contentTitle: 'Final Test',
          },
        ],
      },
    ],
  });

  show();
  expect(await screen.findByText('Stage One')).toBeInTheDocument();

  const user = (await import('@testing-library/user-event')).default.setup();
  await user.click(screen.getByRole('tab', { name: 'Interactive map' }));

  expect(await screen.findByText('Chapter 1')).toBeInTheDocument();
  expect(screen.getByText('Optional branch')).toBeInTheDocument();
  expect(screen.getByText('Challenge')).toBeInTheDocument();
  expect(screen.getAllByText('Locked')).toHaveLength(2);

  await user.click(screen.getByRole('button', { name: /Stage 1/i }));
  await user.click(screen.getByRole('button', { name: 'Mark stage completed' }));

  expect(screen.getByText('1 / 3 completed')).toBeInTheDocument();
  expect(screen.queryByText('Locked')).not.toBeInTheDocument();
});

it('loads vocabulary unit entries in slice picker and applies curated entryIds', async () => {
  const unitId = 'c9786a44-99ee-5429-ba65-3b280279d776';
  const entryA = '19fc0403-0d97-593f-a9a3-379fa1ee502b';
  const entryB = 'be85838e-fae3-5d37-8356-6f3012f66183';

  vi.mocked(api).mockImplementation(async (path: string) => {
    if (path === '/api/v1/admin/vocabulary/packs') {
      return [{ id: 'pack-1', titleEn: 'Work', titleVi: 'Công việc', hidden: false }];
    }
    if (path === '/api/v1/admin/vocabulary/packs/pack-1/units') {
      return [{ id: unitId, title: 'A1–A2 · 01', level: 'A2', entryCount: 2, hidden: false }];
    }
    if (path.startsWith(`/api/v1/admin/vocabulary/entries?unitId=${unitId}`)) {
      return {
        items: [
          { id: entryA, term: 'schedule', pos: 'noun', meaningVi: 'lịch trình', hidden: false },
          { id: entryB, term: 'deadline', pos: 'noun', meaningVi: 'hạn chót', hidden: false },
        ],
        total: 2,
      };
    }
    return [];
  });

  const onSelect = vi.fn();
  const onOpenChange = vi.fn();
  const user = (await import('@testing-library/user-event')).default.setup();

  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <CurriculumSlicePickerDialog
          open
          contentType="VOCABULARY_REVIEW"
          currentId=""
          currentConfig={{}}
          onSelect={onSelect}
          onOpenChange={onOpenChange}
        />
      </I18nextProvider>
    </QueryClientProvider>,
  );

  const unitBtn = await screen.findByRole('button', { name: /Work · A1–A2 · 01/i });
  await user.click(unitBtn);

  expect(await screen.findByText('schedule')).toBeInTheDocument();
  expect(screen.getByText('deadline')).toBeInTheDocument();

  await user.click(screen.getByText('schedule'));
  await user.click(screen.getByRole('button', { name: 'Use selected slice' }));

  expect(onSelect).toHaveBeenCalledWith(
    unitId,
    { entryIds: [entryA] },
    'Work · A1–A2 · 01',
  );
});

it('loads grammar subtopics, disables 0Q subtopics, and clamps questionCount', async () => {
  const topicId = 'd05a8248-b1e8-5b9b-b3f1-051c0cd6051b';
  const subA = '50fcb1ca-dc5a-5996-be31-18f8d667458b';
  const subEmpty = '5fc2fcd0-9db5-5f06-a659-225c6c6320c4';

  vi.mocked(api).mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/admin/grammar/topic/page')) {
      return {
        items: [{ id: topicId, title: 'Danh từ', titleEn: 'Nouns', status: 'PUBLISHED', questionCount: 44 }],
        total: 1,
        page: 0,
        size: 100,
      };
    }
    if (path.startsWith(`/api/v1/admin/grammar/subtopic/page?parentId=${topicId}`)) {
      return {
        items: [
          { id: subA, title: 'Hai danh từ đứng cạnh nhau', titleEn: 'Compound nouns', status: 'PUBLISHED', questionCount: 5 },
          { id: subEmpty, title: 'Empty subtopic', titleEn: 'Empty subtopic', status: 'PUBLISHED', questionCount: 0 },
        ],
        total: 2,
        page: 0,
        size: 100,
      };
    }
    return { items: [], total: 0, page: 0, size: 100 };
  });

  const onSelect = vi.fn();
  const onOpenChange = vi.fn();
  const user = (await import('@testing-library/user-event')).default.setup();

  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <CurriculumSlicePickerDialog
          open
          contentType="GRAMMAR_PRACTICE"
          currentId=""
          currentConfig={{}}
          onSelect={onSelect}
          onOpenChange={onOpenChange}
        />
      </I18nextProvider>
    </QueryClientProvider>,
  );

  await user.click(await screen.findByRole('button', { name: /Nouns/i }));
  expect(await screen.findByText('Compound nouns')).toBeInTheDocument();

  const checkboxes = screen.getAllByRole('checkbox');
  expect(checkboxes[1]).toBeDisabled();

  await user.click(checkboxes[0]);
  expect(screen.getAllByText('Up to 5 questions in selected scope').length).toBeGreaterThan(0);

  await user.click(screen.getByRole('button', { name: 'Use selected slice' }));
  expect(onSelect).toHaveBeenCalledWith(
    topicId,
    { subtopicIds: [subA], questionCount: 5 },
    'Nouns',
  );
});

it('loads TOEIC placements, toggles parts, and clamps questionCount', async () => {
  const testId = '8832fd01-4378-5ae4-acc6-20aa57af5ba8';

  vi.mocked(api).mockImplementation(async (path: string) => {
    if (path.startsWith('/api/v1/admin/toeic/page')) {
      return {
        items: [{ id: testId, title: 'Test 1', year: 2019, status: 'PUBLISHED', questionCount: 200 }],
        total: 1,
        page: 0,
        size: 100,
      };
    }
    if (path === `/api/v1/admin/toeic/${testId}`) {
      return {
        id: testId,
        revision: 1,
        hasUnpublishedChanges: false,
        status: 'PUBLISHED',
        publicationId: null,
        updatedAt: null,
        updatedBy: null,
        draft: {
          title: 'Test 1',
          code: '2019_1',
          type: 'FULL_TEST',
          year: 2019,
          testNumber: 1,
          listeningDurationSeconds: 2700,
          readingDurationSeconds: 4500,
          accessMode: 'PUBLIC',
          requiredFeatureKey: null,
          groups: [],
          placements: [
            { id: 'p1', itemId: 'q1', publicationId: null, groupId: null, part: 1, questionNumber: 1, gapNumber: null },
            { id: 'p2', itemId: 'q2', publicationId: null, groupId: null, part: 1, questionNumber: 2, gapNumber: null },
            { id: 'p3', itemId: 'q3', publicationId: null, groupId: null, part: 5, questionNumber: 101, gapNumber: null },
          ],
        },
      };
    }
    return { items: [], total: 0, page: 0, size: 100 };
  });

  const onSelect = vi.fn();
  const onOpenChange = vi.fn();
  const user = (await import('@testing-library/user-event')).default.setup();

  render(
    <QueryClientProvider client={client}>
      <I18nextProvider i18n={i18n}>
        <CurriculumSlicePickerDialog
          open
          contentType="TOEIC_DRILL"
          currentId=""
          currentConfig={{}}
          onSelect={onSelect}
          onOpenChange={onOpenChange}
        />
      </I18nextProvider>
    </QueryClientProvider>,
  );

  await user.click(await screen.findByRole('button', { name: /TOEIC 2019 · Test 1/i }));
  expect(await screen.findByRole('button', { name: /Part 1\s*\(2\)/i })).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: /Part 1\s*\(2\)/i }));
  expect(screen.getAllByText('Up to 3 questions in selected parts').length).toBeGreaterThan(0);

  await user.click(screen.getByRole('button', { name: 'Use selected slice' }));
  expect(onSelect).toHaveBeenCalledWith(
    testId,
    { parts: [1, 5], questionCount: 3 },
    'TOEIC 2019 · Test 1',
  );
});
