import { RiCheckLine, RiErrorWarningLine, RiCheckboxBlankCircleLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { ProcessingStatus } from './ProcessingStatus';
import { activeJob } from './AuthoringJobProgress';
import type { LessonDraft } from './lesson-builder.types';

export function AuthoringWorkflow({ draft, needsUpdate, disabled, onCancel, onRetry, onUseWhisperX }: {
  draft: LessonDraft; needsUpdate: boolean; disabled: boolean;
  onCancel: () => void; onRetry: () => void; onUseWhisperX: () => void;
}) {
  const { t } = useTranslation('admin');
  const current = draft.jobs.find(activeJob), last = draft.jobs.at(-1);
  const problem = !current && needsUpdate && last && ['FAILED', 'CANCELLED'].includes(last.status) ? last : undefined;
  const job = current ?? problem;
  const captionsUnavailable = problem?.operation === 'PREPARE' && problem.status === 'FAILED'
    && problem.errorCode === 'YOUTUBE_CAPTIONS_UNAVAILABLE';
  const prepared = draft.audioReady && draft.sentences.length > 0;
  const aligned = prepared && !draft.alignmentRequired;
  const complete = aligned && draft.contentComplete;
  const steps = [
    { code: draft.sourceType === 'TEXT' ? 'TTS' : 'PREPARE', label: draft.sourceType === 'TEXT' ? 'speechStage' : 'transcriptStage', done: prepared },
    { code: 'ALIGN', label: 'alignmentStage', done: aligned },
    { code: 'CONTENT', label: 'contentStage', done: complete },
  ];
  const activeIndex = steps.findIndex(step => job?.operation === step.code);
  const SummaryIcon = needsUpdate || !complete ? RiErrorWarningLine : RiCheckLine;
  return <section className="studio-workflow" aria-label={t('finish.progress')}>
    {job ? <ProcessingStatus job={job} disabled={disabled} onCancel={current ? onCancel : undefined} onRetry={problem ? onRetry : undefined} onUseWhisperX={captionsUnavailable ? onUseWhisperX : undefined} />
      : <div className="studio-workflow-summary"><SummaryIcon aria-hidden="true" /><strong>{t(draft.status === 'PUBLISHED' ? 'catalog.published' : needsUpdate || !complete ? 'finish.updateNeeded' : 'finish.readyDraft')}</strong><span>{t(draft.status === 'PUBLISHED' ? 'finish.publishedHelp' : needsUpdate || !complete ? 'finish.updateHelp' : 'finish.readyHelp')}</span></div>}
    {(job || !complete) && <ol className="studio-workflow-stages">{steps.map((step, index) => {
      const state = index === activeIndex ? current ? 'running' : 'error' : step.done && (activeIndex < 0 || index < activeIndex) ? 'done' : 'waiting';
      const Icon = state === 'done' ? RiCheckLine : state === 'error' ? RiErrorWarningLine : RiCheckboxBlankCircleLine;
      return <li key={step.code} data-state={state} aria-label={`${t(`finish.${step.label}`)}: ${t(`finish.stageState.${state}`)}`} aria-current={state === 'running' ? 'step' : undefined}>{state !== 'running' && <Icon aria-hidden="true" />}<span>{t(`finish.${step.label}`)}</span></li>;
    })}</ol>}
    {problem && <p className="studio-help">{t('finish.retryHelp')}</p>}
  </section>;
}
