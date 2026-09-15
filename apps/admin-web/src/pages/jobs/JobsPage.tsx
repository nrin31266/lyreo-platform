import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { cancelJob, fetchJob } from './jobs.api';
import type { Job } from './jobs.types';

const cancellable = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT']);

export function JobsPage() {
  const { t } = useTranslation(['admin', 'common']);
  const [searchParams] = useSearchParams();
  const [id, setId] = useState(searchParams.get('job') ?? '');
  const [job, setJob] = useState<Job | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  async function load(jobId = id) {
    const normalized = jobId.trim();
    if (!normalized) {
      setError(t('jobs.enterUuid'));
      setJob(null);
      return;
    }
    setLoading(true);
    try {
      setError('');
      setJob(await fetchJob(normalized));
    } catch (cause) {
      setJob(null);
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setLoading(false);
    }
  }

  async function cancel() {
    if (!job) return;
    setCancelling(true);
    try {
      await cancelJob(job.id);
      setConfirmOpen(false);
      await load(job.id);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setCancelling(false);
    }
  }

  useEffect(() => {
    const initial = searchParams.get('job');
    if (initial) void load(initial);
    // This effect intentionally reacts only to a URL-supplied job id.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  return (
    <section>
      <div className="eyebrow">{t('jobs.eyebrow')}</div>
      <h1>{t('jobs.title')}</h1>
      <p className="lead">{t('jobs.lead')}</p>

      <Card className="mt-6">
        <CardContent className="pt-6">
          <div className="row">
            <Input
              className="flex-1"
              value={id}
              onChange={event => setId(event.target.value)}
              placeholder={t('jobs.jobUuid')}
              aria-label={t('jobs.jobUuid')}
              disabled={loading}
              onKeyDown={event => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  void load();
                }
              }}
            />
            <Button disabled={loading} onClick={() => void load()}>
              {loading ? t('common:status.loading') : t('jobs.inspect')}
            </Button>
          </div>

          {error ? <p className="danger mt-4">{error}</p> : null}

          {!job && !error && !loading ? (
            <p className="muted mt-4">{t('jobs.enterUuid')}</p>
          ) : null}

          {job ? (
            <div className="job">
              <strong>{job.status}</strong>
              <span>
                {job.currentStep ?? t('jobs.waiting')} · {job.progressPercent}%
              </span>
              <progress value={job.progressPercent} max="100" />
              <small>
                {t('jobs.attempt', { current: job.attemptCount, max: job.maxAttempts })}
              </small>
              {cancellable.has(job.status) ? (
                <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
                  <DialogTrigger asChild>
                    <Button className="w-fit" variant="destructive" disabled={cancelling}>
                      {t('jobs.requestCancellation')}
                    </Button>
                  </DialogTrigger>
                  <DialogContent closeLabel={t('common:actions.close')}>
                    <DialogHeader>
                      <DialogTitle>{t('jobs.cancelDialog.title')}</DialogTitle>
                      <DialogDescription>{t('jobs.cancelDialog.description')}</DialogDescription>
                    </DialogHeader>
                    <div className="flex justify-end gap-2">
                      <Button variant="outline" disabled={cancelling} onClick={() => setConfirmOpen(false)}>
                        {t('common:actions.cancel')}
                      </Button>
                      <Button variant="destructive" disabled={cancelling} onClick={() => void cancel()}>
                        {cancelling ? t('common:status.loading') : t('jobs.cancelDialog.confirm')}
                      </Button>
                    </div>
                  </DialogContent>
                </Dialog>
              ) : null}
            </div>
          ) : null}
        </CardContent>
      </Card>
    </section>
  );
}
