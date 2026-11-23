import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { adminI18nResources } from '@lyreo/i18n';
import { ProcessingHistory } from './ProcessingHistory';
import { fetchDiagnostics } from './lesson-builder.api';
import type { AuthoringJob } from './lesson-builder.types';

vi.mock('./lesson-builder.api', () => ({ fetchDiagnostics: vi.fn().mockResolvedValue([]) }));
afterEach(() => { cleanup(); vi.clearAllMocks(); });
const job: AuthoringJob = {
  jobId: 'content-job', operation: 'CONTENT', status: 'SUCCEEDED',
  createdAt: '2026-11-17T00:00:00Z', startedAt: '2026-11-17T00:00:01Z', finishedAt: '2026-11-17T00:00:29Z',
  result: { version: 1, recordedAt: '2026-11-17T00:00:28Z', transcriptRevision: 1, sentences: 75, words: 816, audioDurationMs: 412000, timedSentences: 75,
    completedFeatures: { TRANSLATION: 75, IPA: 75 }, vocabularyNotes: 8, grammarNotes: 3, dictationSentences: 75, shadowingSentences: 75, warnings: 1 },
  usage: [],
};
async function show(jobs: AuthoringJob[]) {
  const i18n = createInstance();
  await i18n.use(initReactI18next).init({ resources: adminI18nResources, lng: 'en', ns: ['admin'] });
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><I18nextProvider i18n={i18n}><ProcessingHistory lessonId="lesson" jobs={jobs} /></I18nextProvider></QueryClientProvider>);
}
it('shows checkpoint coverage and separates LLM API usage from local IPA without opening debug', async () => {
  const llm = { provider: 'GEMINI', model: 'flash', capability: 'GENERAL_LLM', latencyMs: 500, status: 'FAILED', fallback: false };
  const ipa = { provider: 'LOCAL_NLP', model: 'lightweight', capability: 'NLP', latencyMs: 10, status: 'SUCCEEDED', fallback: false };
  await show([{ ...job, usage: [llm, { ...llm, provider: 'GROQ', model: 'oss', inputTokens: 2661, outputTokens: 4400, status: 'SUCCEEDED', fallback: true }, ...Array.from({ length: 75 }, () => ipa)] }]);
  expect(screen.getByText('75 sentences · 816 words · audio 6:52')).toBeInTheDocument();
  expect(screen.getByText('8 vocabulary notes · 3 grammar notes')).toBeInTheDocument();
  expect(screen.getByText('Selected: 75 Dictation · 75 Shadowing')).toBeInTheDocument();
  expect(screen.getByText('2 LLM API calls · 75 audio/IPA calls')).toBeInTheDocument();
  expect(screen.getByText('LLM tokens: 2661 + ? input / 4400 + ? output · ? = unreported')).toBeInTheDocument();
  expect(screen.getByText('Failed route calls: 1')).toBeInTheDocument();
  expect(screen.getByText('Fallback route calls: 1')).toBeInTheDocument();
  expect(screen.getByText('Queue 1.0s')).toBeInTheDocument();
  expect(fetchDiagnostics).not.toHaveBeenCalled();
  expect(document.querySelector('pre')).toBeNull();
});
it('keeps partial results and backend errors visible', async () => {
  await show([{ ...job, status: 'FAILED', errorCode: 'CONTENT_PARTIAL', errorMessage: 'sentence=41: missing IPA', result: { ...job.result!, completedFeatures: { TRANSLATION: 75, IPA: 40 } } }]);
  expect(screen.getByText('40/75')).toBeInTheDocument();
  expect(screen.getByText('CONTENT_PARTIAL: sentence=41: missing IPA')).toBeInTheDocument();
  expect(fetchDiagnostics).not.toHaveBeenCalled();
});
it('does not invent historical results, fallback or token usage', async () => {
  await show([{ ...job, result: null, usage: [{ provider: 'LOCAL_NLP', model: 'lightweight', latencyMs: 10 }] }]);
  expect(screen.getByText('No result checkpoint recorded for this job.')).toBeInTheDocument();
  expect(screen.getByText('Legacy calls have no recorded outcome or fallback status.')).toBeInTheDocument();
  expect(screen.queryByText(/LLM tokens:/)).not.toBeInTheDocument();
  expect(screen.queryByText(/Fallback route calls:/)).not.toBeInTheDocument();
});
it('loads raw diagnostics only on demand and collapses them again', async () => {
  vi.mocked(fetchDiagnostics).mockResolvedValue([
    { payload: { output: '<script>alert(1)</script>' } },
    { payload: { event: 'CONTENT_BATCH_SPLIT' } },
    { payload: { media_stage: 'AUDIO_DOWNLOAD' } },
  ]);
  await show([job]);
  const button = screen.getByRole('button', { name: 'Debug' });
  expect(button).toHaveAttribute('aria-expanded', 'false');
  fireEvent.click(button);
  await screen.findByText(/<script>alert/);
  expect(screen.getByText('AI batches split: 1')).toBeInTheDocument();
  expect(screen.getByText('Failed audio downloads: 1')).toBeInTheDocument();
  expect(fetchDiagnostics).toHaveBeenCalledWith('lesson', 'content-job');
  expect(document.querySelector('script')).toBeNull();
  fireEvent.click(button);
  expect(document.querySelector('pre')).toBeNull();
});
