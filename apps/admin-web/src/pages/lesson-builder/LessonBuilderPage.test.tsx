import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { adminI18nResources } from '@lyreo/i18n';
import { ApiError } from '@/lib/api';
import * as api from './lesson-builder.api';
import type { LessonDraft } from './lesson-builder.types';
import { LessonBuilderPage } from './LessonBuilderPage';

vi.mock('./lesson-builder.api', () => ({
  fetchDraft: vi.fn(),
  inspectSource: vi.fn(),
  createDraft: vi.fn(),
  saveTranscript: vi.fn(),
  approveTranscript: vi.fn(),
  alignTranscript: vi.fn(),
  reviewKaraoke: vi.fn(),
  generateSemantic: vi.fn(),
  saveCandidates: vi.fn(),
  publishLesson: vi.fn(),
  prepareTranscript: vi.fn(),
}));
vi.mock('@/pages/jobs/jobs.api', () => ({ cancelJob: vi.fn() }));
vi.mock('./YouTubePreview', () => ({
  YouTubePreview: ({
    onAvailabilityChange,
  }: {
    onAvailabilityChange?: (ready: boolean) => void;
  }) => (
    <button onClick={() => onAvailabilityChange?.(true)}>Ready player</button>
  ),
}));

const original: LessonDraft = {
  id: 'lesson-a',
  title: 'A lesson',
  videoId: 'abcdefghijk',
  sourceReference: 'https://youtu.be/abcdefghijk',
  startMs: 10000,
  endMs: 130000,
  revision: 1,
  transcriptRevision: 1,
  status: 'TRANSCRIPT_REVIEW',
  timingStatus: 'DRAFT_VALID',
  approvedRevision: null,
  karaokeReviewedRevision: null,
  semanticRevision: null,
  jobs: [],
  sentences: [
    {
      id: 's1',
      text: 'Hello world.',
      usage: 'LEARN',
      issues: [],
      vocabulary: [],
      grammar: [],
      hints: [],
      notes: [],
      thoughtGroups: [],
      dictationEligible: true,
      shadowingEligible: true,
      dictationSelected: true,
      shadowingSelected: false,
      dictationPriority: 'HIGH',
      shadowingPriority: 'MEDIUM',
      words: [
        {
          text: 'Hello',
          startMs: 0,
          endMs: 500,
          charStart: 0,
          charEnd: 5,
          tokenStart: 0,
          tokenEnd: 1,
        },
        {
          text: 'world',
          startMs: 500,
          endMs: 1000,
          charStart: 6,
          charEnd: 11,
          tokenStart: 1,
          tokenEnd: 2,
        },
      ],
    },
  ],
};
let current: LessonDraft;
const i18n = createInstance();
beforeEach(async () => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  current = structuredClone(original);
  vi.mocked(api.fetchDraft).mockImplementation(async () =>
    structuredClone(current),
  );
  await i18n.use(initReactI18next).init({
    resources: adminI18nResources,
    lng: 'en',
    fallbackLng: 'en',
    ns: ['admin', 'common'],
    interpolation: { escapeValue: false },
  });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});
async function show(path = '/lessons/lesson-a') {
  await act(async () => {
    render(
      <I18nextProvider i18n={i18n}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/lessons/:lessonId" element={<LessonBuilderPage />} />
            <Route path="/lessons/new" element={<LessonBuilderPage />} />
          </Routes>
        </MemoryRouter>
      </I18nextProvider>,
    );
  });
}

describe('lesson authoring checkpoints', () => {
  it('preserves unsaved transcript text across polling and saves against the original editor revision', async () => {
    await show();
    fireEvent.change(screen.getByLabelText('Text for sentence 1'), {
      target: { value: 'My corrected transcript.' },
    });
    current = { ...current, revision: 2 };
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue(
      'My corrected transcript.',
    );
    expect(
      screen.getByRole('button', { name: 'Approve transcript' }),
    ).toBeDisabled();
    vi.mocked(api.saveTranscript).mockRejectedValue(
      new ApiError(409, 'Changed'),
    );
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save transcript' }));
    });
    expect(api.saveTranscript).toHaveBeenCalledWith('lesson-a', 1, [
      { id: 's1', text: 'My corrected transcript.', usage: 'LEARN' },
    ]);
    expect(screen.getByRole('alert')).toHaveTextContent(
      'The lesson changed or is locked',
    );
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue(
      'My corrected transcript.',
    );
  });

  it('preserves unsaved transcript text when moving between the editor tabs', async () => {
    await show();
    fireEvent.change(screen.getByLabelText('Text for sentence 1'), {
      target: { value: 'Unsaved correction.' },
    });
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Learning content' }), {
      button: 0,
      ctrlKey: false,
    });
    expect(
      screen.queryByLabelText('Text for sentence 1'),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Generate learning content' }),
    ).toBeDisabled();
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Transcript' }), {
      button: 0,
      ctrlKey: false,
    });
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue(
      'Unsaved correction.',
    );
    expect(
      screen.getByRole('button', { name: 'Save transcript' }),
    ).toBeEnabled();
  });

  it('does not unlock generation from ASR timing or transcript approval alone', async () => {
    current.approvedRevision = 1;
    await show();
    expect(screen.getByRole('tab', { name: 'Transcript' })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(
      screen.queryByRole('button', { name: 'Generate learning content' }),
    ).not.toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Learning content' }), {
      button: 0,
      ctrlKey: false,
    });
    expect(
      screen.getByRole('tab', { name: 'Learning content' }),
    ).toHaveAttribute('aria-selected', 'true');
    expect(
      screen.getByRole('button', { name: 'Generate learning content' }),
    ).toBeDisabled();
    expect(
      screen.getByRole('button', { name: 'Confirm karaoke review' }),
    ).toBeDisabled();
    expect(
      screen.getByText(
        'Grouped ASR timing needs manual review and may need dedicated alignment.',
        { exact: false },
      ),
    ).toBeInTheDocument();
    expect(api.generateSemantic).not.toHaveBeenCalled();
  });

  it('preserves custom candidate selection across polling and requires saving before publication', async () => {
    current.approvedRevision =
      current.karaokeReviewedRevision =
      current.semanticRevision =
        1;
    current.status = 'CONTENT_REVIEW';
    await show();
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Learning content' }), {
      button: 0,
      ctrlKey: false,
    });
    const shadowing = screen.getByRole('checkbox', {
      name: /Shadowing · MEDIUM/,
    });
    fireEvent.click(shadowing);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });
    expect(shadowing).toBeChecked();
    expect(
      screen.getByRole('button', { name: 'Save activity selection' }),
    ).toBeEnabled();
    expect(
      screen.getByRole('button', { name: 'Publish lesson' }),
    ).toBeDisabled();
    expect(
      screen.getByRole('checkbox', { name: /I reviewed the translations/ }),
    ).toBeDisabled();
    vi.mocked(api.saveCandidates).mockResolvedValue({
      ...current,
      revision: 2,
      sentences: [{ ...current.sentences[0], shadowingSelected: true }],
    });
    await act(async () => {
      fireEvent.click(
        screen.getByRole('button', { name: 'Save activity selection' }),
      );
    });
    expect(api.saveCandidates).toHaveBeenCalledWith(
      'lesson-a',
      1,
      ['s1'],
      ['s1'],
    );
  });

  it('saves custom candidates against the revision where editing began despite polling', async () => {
    current.approvedRevision =
      current.karaokeReviewedRevision =
      current.semanticRevision =
        1;
    await show();
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Learning content' }), {
      button: 0,
      ctrlKey: false,
    });
    fireEvent.click(
      screen.getByRole('checkbox', { name: /Shadowing · MEDIUM/ }),
    );
    current = { ...current, revision: 2 };
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });
    vi.mocked(api.saveCandidates).mockRejectedValue(
      new ApiError(409, 'Changed'),
    );
    await act(async () => {
      fireEvent.click(
        screen.getByRole('button', { name: 'Save activity selection' }),
      );
    });
    expect(api.saveCandidates).toHaveBeenCalledWith(
      'lesson-a',
      1,
      ['s1'],
      ['s1'],
    );
    expect(
      screen.getByRole('checkbox', { name: /Shadowing · MEDIUM/ }),
    ).toBeChecked();
    expect(screen.getByRole('alert')).toHaveTextContent(
      'The lesson changed or is locked',
    );
  });

  it('requires a fresh karaoke confirmation after polling receives a changed draft', async () => {
    current.approvedRevision = 1;
    await show();
    fireEvent.click(screen.getByRole('button', { name: 'Ready player' }));
    const confirmation = screen.getByRole('checkbox', {
      name: /I played and checked/,
    });
    fireEvent.click(confirmation);
    expect(confirmation).toBeChecked();
    current = { ...current, revision: 2 };
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });
    expect(confirmation).not.toBeChecked();
    expect(
      screen.getByRole('button', { name: 'Confirm karaoke review' }),
    ).toBeDisabled();
  });

  it('ignores an old poll response delivered after a newer successful save', async () => {
    await show();
    let resolvePoll!: (draft: LessonDraft) => void;
    vi.mocked(api.fetchDraft).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolvePoll = resolve;
        }),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600);
    });
    fireEvent.change(screen.getByLabelText('Text for sentence 1'), {
      target: { value: 'New saved text.' },
    });
    vi.mocked(api.saveTranscript).mockResolvedValue({
      ...current,
      revision: 2,
      sentences: [{ ...current.sentences[0], text: 'New saved text.' }],
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save transcript' }));
    });
    await act(async () => {
      resolvePoll(structuredClone(original));
    });
    expect(screen.getByLabelText('Text for sentence 1')).toHaveValue(
      'New saved text.',
    );
  });

  it('locks every content mutation after publication', async () => {
    current.status = 'PUBLISHED';
    current.approvedRevision =
      current.karaokeReviewedRevision =
      current.semanticRevision =
        1;
    await show();
    expect(screen.getByLabelText('Text for sentence 1')).toBeDisabled();
    for (const name of ['Save transcript', 'Run dedicated alignment']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Learning content' }), {
      button: 0,
      ctrlKey: false,
    });
    for (const name of [
      'Regenerate learning content',
      'Save activity selection',
      'Publish lesson',
    ]) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    expect(
      screen.getByText(
        'Published. Content and selected activities are locked.',
      ),
    ).toBeInTheDocument();
  });

  it('inspects YouTube first and requires operator responsibility before preparation', async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({
      videoId: 'abcdefghijk',
      title: 'Video',
      durationMs: 300000,
      embedAllowed: true,
      usedRanges: [
        {
          lessonId: 'existing',
          title: 'Existing',
          startMs: 0,
          endMs: 200000,
          status: 'PUBLISHED',
        },
      ],
    });
    await show('/lessons/new');
    expect(screen.queryByText('Text source')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('YouTube URL'), {
      target: { value: 'https://youtu.be/abcdefghijk' },
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Inspect video' }));
    });
    expect(api.inspectSource).toHaveBeenCalledWith(
      'https://youtu.be/abcdefghijk',
    );
    expect(
      screen.getByText(
        'This segment overlaps an existing lesson. You may continue.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Prepare transcript' }),
    ).toBeDisabled();
    fireEvent.click(
      screen.getByRole('checkbox', {
        name: /I take responsibility for the right to use this source/,
      }),
    );
    expect(
      screen.getByRole('button', { name: 'Prepare transcript' }),
    ).toBeEnabled();
  });
  it('shows a translated source error beside the URL and retries without leaking tool details', async () => {
    vi.mocked(api.inspectSource)
      .mockRejectedValueOnce(
        new ApiError(
          503,
          'private downloader stderr',
          'YOUTUBE_MEDIA_TOOL_UNAVAILABLE',
          undefined,
          'request-123',
        ),
      )
      .mockResolvedValueOnce({
        videoId: 'abcdefghijk',
        title: 'Recovered video',
        durationMs: 120000,
        embedAllowed: true,
        usedRanges: [],
      });
    await show('/lessons/new');
    fireEvent.change(screen.getByLabelText('YouTube URL'), {
      target: { value: 'https://youtu.be/abcdefghijk' },
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Inspect video' }));
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      'The Core media tools are unavailable',
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Request request-123');
    expect(
      screen.queryByText('private downloader stderr'),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText('YouTube URL')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Retry inspection' }));
    });
    expect(api.inspectSource).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('Recovered video')).toBeInTheDocument();
  });
  it('clears an earlier preparation error when inspecting another source', async () => {
    vi.mocked(api.inspectSource).mockResolvedValue({
      videoId: 'abcdefghijk',
      title: 'Video',
      durationMs: 120000,
      embedAllowed: true,
      usedRanges: [],
    });
    await show('/lessons/new');
    fireEvent.change(screen.getByLabelText('YouTube URL'), {
      target: { value: 'https://youtu.be/abcdefghijk' },
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Inspect video' }));
    });
    fireEvent.click(
      screen.getByRole('checkbox', { name: /I take responsibility/ }),
    );
    fireEvent.change(screen.getByLabelText('End (seconds)'), {
      target: { value: '0' },
    });
    await act(async () => {
      fireEvent.click(
        screen.getByRole('button', { name: 'Prepare transcript' }),
      );
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Enter a title, a valid segment within the video',
    );
    fireEvent.change(screen.getByLabelText('YouTube URL'), {
      target: { value: 'https://youtu.be/lmnopqrstuv' },
    });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Inspect video' }));
    });
    expect(api.inspectSource).toHaveBeenLastCalledWith(
      'https://youtu.be/lmnopqrstuv',
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('collapses successful history while keeping running, failed and cancelled jobs visible', async () => {
    current.jobs = [
      {
        jobId: 'completed-job',
        operation: 'PREPARE',
        status: 'SUCCEEDED',
        usage: [],
      },
      {
        jobId: 'running-job',
        operation: 'ALIGN',
        status: 'RUNNING',
        currentStep: 'Active alignment',
        usage: [],
      },
      {
        jobId: 'failed-job',
        operation: 'SEMANTIC',
        status: 'FAILED',
        errorMessage: 'Failed attempt stays visible',
        usage: [],
      },
      {
        jobId: 'cancelled-job',
        operation: 'ALIGN',
        status: 'CANCELLED',
        errorMessage: 'Cancelled attempt stays visible',
        usage: [],
      },
    ];
    await show();
    const history = screen.getByRole('button', {
      name: /Job history.*1/,
    });
    expect(history).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText('Active alignment')).toBeVisible();
    expect(screen.getByText('Failed attempt stays visible')).toBeVisible();
    expect(screen.getByText('Cancelled attempt stays visible')).toBeVisible();
    expect(
      document.querySelector('a[href="/jobs?job=completed-job"]'),
    ).not.toBeInTheDocument();
    fireEvent.click(history);
    expect(history).toHaveAttribute('aria-expanded', 'true');
    expect(
      document.querySelector('a[href="/jobs?job=completed-job"]'),
    ).toBeVisible();
    expect(screen.getByText('Active alignment')).toBeVisible();
    fireEvent.click(history);
    expect(history).toHaveAttribute('aria-expanded', 'false');
  });
  it('keeps mixed terminal job details in collapsed history after publication', async () => {
    current.status = 'PUBLISHED';
    current.jobs = [
      {
        jobId: 'old-failure',
        operation: 'SEMANTIC',
        status: 'FAILED',
        errorMessage: 'Earlier content attempt failed',
        usage: [],
      },
      {
        jobId: 'completed-retry',
        operation: 'SEMANTIC',
        status: 'SUCCEEDED',
        usage: [],
      },
      {
        jobId: 'old-cancellation',
        operation: 'ALIGN',
        status: 'CANCELLED',
        errorMessage: 'Earlier alignment was cancelled',
        usage: [],
      },
    ];
    await show();
    const history = screen.getByRole('button', { name: /Job history.*3/ });
    expect(history).toHaveAttribute('aria-expanded', 'false');
    expect(
      screen.queryByText('Earlier content attempt failed'),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText('Earlier alignment was cancelled'),
    ).not.toBeInTheDocument();
    fireEvent.click(history);
    expect(history).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Earlier content attempt failed')).toBeVisible();
    expect(screen.getByText('Earlier alignment was cancelled')).toBeVisible();
    expect(
      document.querySelector('a[href="/jobs?job=completed-retry"]'),
    ).toBeVisible();
  });
});
