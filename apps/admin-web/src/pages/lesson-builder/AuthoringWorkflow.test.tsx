import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { adminI18nResources } from '@lyreo/i18n';
import { AuthoringWorkflow } from './AuthoringWorkflow';
import type { LessonDraft } from './lesson-builder.types';

const draft: LessonDraft = {
  id: 'lesson', title: 'Lesson', videoId: '', sourceType: 'YOUTUBE', sourceReference: '', startMs: 0, endMs: 1000,
  revision: 1, transcriptRevision: 1, status: 'TRANSCRIPT_REVIEW', timingStatus: 'MISSING',
  approvedRevision: null, karaokeReviewedRevision: null, semanticRevision: null,
  audioReady: false, alignmentRequired: true, contentComplete: true, sentences: [], jobs: [],
};
afterEach(cleanup);
async function show(value: LessonDraft, needsUpdate = true) {
  const i18n = createInstance();
  await i18n.use(initReactI18next).init({ resources: adminI18nResources, lng: 'en', ns: ['admin'] });
  render(<I18nextProvider i18n={i18n}><AuthoringWorkflow draft={value} needsUpdate={needsUpdate} disabled={false} onCancel={vi.fn()} onRetry={vi.fn()} /></I18nextProvider>);
}
it('does not mark later stages complete during prepare, even with an old completion flag', async () => {
  await show({ ...draft, jobs: [{ jobId: 'prepare', operation: 'PREPARE', status: 'RUNNING', currentStep: 'TRANSCRIBING', usage: [] }] });
  expect(screen.getByRole('listitem', { name: /Prepare audio & transcript: Processing/ })).toHaveAttribute('aria-current', 'step');
  expect(screen.getByRole('listitem', { name: /Align word timing: Waiting/ })).toBeInTheDocument();
  expect(screen.getByRole('listitem', { name: /Create learning content: Waiting/ })).toBeInTheDocument();
  expect(screen.getAllByRole('progressbar')).toHaveLength(1);
  expect(screen.getByRole('listitem', { name: /Prepare audio & transcript: Processing/ }).querySelector('svg')).toBeNull();
});
it('keeps content waiting after alignment fails and never reports an empty draft ready', async () => {
  await show({ ...draft, jobs: [{ jobId: 'align', operation: 'ALIGN', status: 'FAILED', usage: [] }] }, false);
  expect(screen.queryByText('Ready draft')).not.toBeInTheDocument();
  expect(screen.getByRole('listitem', { name: /Create learning content: Waiting/ })).toBeInTheDocument();
});
it('shows only one completion summary after all checkpoints are valid', async () => {
  await show({ ...draft, audioReady: true, alignmentRequired: false, sentences: [{ id: 'sentence', text: 'Hello.', usage: 'LEARN', words: [], issues: [], vocabulary: [], grammar: [], hints: [], notes: [], thoughtGroups: [], dictationEligible: true, shadowingEligible: true, dictationSelected: true, shadowingSelected: true }] }, false);
  const region = screen.getByRole('region', { name: 'Lesson progress' });
  expect(within(region).getByText('Ready draft')).toBeInTheDocument();
  expect(within(region).queryByRole('list')).not.toBeInTheDocument();
  expect(within(region).queryByRole('progressbar')).not.toBeInTheDocument();
});
