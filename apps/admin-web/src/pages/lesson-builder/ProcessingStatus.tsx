import { useTranslation } from 'react-i18next';
import { RiLoader4Line, RiCheckLine, RiErrorWarningLine, RiRefreshLine, RiCloseCircleLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { activeJob, authoringJobErrorKey, jobRetryLabelKey, jobProblemSentence, JobDuration, useCodeLabel } from './AuthoringJobProgress';
import { Badge } from '@/components/ui/badge';
import type { AuthoringJob } from './lesson-builder.types';

export function ProcessingStatus({ job, label, onRetry, onCancel, onInspectProblem, onUseWhisperX, disabled = false }: {
  job?: AuthoringJob; label?: string; onRetry?: () => void; onCancel?: () => void; onInspectProblem?: () => void; onUseWhisperX?: () => void; disabled?: boolean;
}) {
  const { t, i18n } = useTranslation('admin');
  const describe = useCodeLabel();
  const running = !job || activeJob(job);
  const failed = job?.status === 'FAILED';
  const waiting = job && ['QUEUED', 'RETRY_WAIT', 'CANCEL_REQUESTED'].includes(job.status);
  const title = label ?? (job ? describe(job.currentStep && job.status === 'RUNNING' ? job.currentStep : job.operation) : '');
  const percent = job?.status === 'RUNNING' && job.progressPercent != null && job.progressPercent > 0 ? job.progressPercent : undefined;
  return <section className="processing-status" data-state={failed ? 'error' : running ? 'running' : job?.status === 'CANCELLED' ? 'cancelled' : 'complete'} aria-label={t('twoStep.processingStatus')}>
    <div className="processing-status-heading">
      {running ? <RiLoader4Line className="animate-spin" aria-hidden="true" /> : failed ? <RiErrorWarningLine aria-hidden="true" /> : job?.status === 'CANCELLED' ? <RiCloseCircleLine aria-hidden="true" /> : <RiCheckLine aria-hidden="true" />}
      <div className="processing-status-copy">
        <div className="processing-status-title" role="status" aria-live="polite"><strong>{title}</strong>{job ? <Badge variant={failed ? 'destructive' : 'secondary'}>{describe(job.status)}</Badge> : <span>{t('twoStep.pleaseWait')}</span>}</div>
        {failed && <p role="alert">{t(`lessonAuthoring.jobErrors.${job && authoringJobErrorKey(job)}`, { defaultValue: job?.errorMessage || t('twoStep.processingFailed') })}</p>}
        {failed && job?.errorMessage && <p className="processing-error-detail">{job.errorCode}: {job.errorMessage}</p>}
        {job?.status === 'RETRY_WAIT' && <p>{job.nextRetryAt ? t('lessonAuthoring.retryAt', { time: new Date(job.nextRetryAt).toLocaleString(i18n.language) }) : t('twoStep.waitingRetry')}</p>}
        {job?.status === 'CANCELLED' && <p>{t('twoStep.cancelledHelp')}</p>}
      </div>
      {job && <div className="processing-status-duration"><JobDuration job={job} /></div>}
      {(onInspectProblem || onRetry || onUseWhisperX || (running && onCancel)) && <div className="processing-status-actions">
        {onInspectProblem && job && <Button variant="outline" size="sm" disabled={disabled} onClick={onInspectProblem}>{t('twoStep.inspectSentence', {number:jobProblemSentence(job)})}</Button>}
        {onRetry && job && <Button variant={onInspectProblem ? 'ghost' : 'outline'} size="sm" disabled={disabled} onClick={onRetry}><RiRefreshLine aria-hidden="true" />{t(jobRetryLabelKey(job))}</Button>}
        {onUseWhisperX && <Button variant="outline" size="sm" disabled={disabled} onClick={onUseWhisperX}>{t('captions.useWhisperX')}</Button>}
        {running && onCancel && <Button variant="ghost" size="sm" disabled={disabled || job?.status === 'CANCEL_REQUESTED'} onClick={onCancel}>{t('lessonAuthoring.cancel')}</Button>}
      </div>}
    </div>
    {running && <Progress value={waiting ? undefined : percent} aria-label={title} className={percent === undefined || waiting ? 'processing-indeterminate' : undefined} />}

  </section>;
}
