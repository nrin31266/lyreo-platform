import './jobs.css';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { RiRefreshLine, RiSearchLine, RiLoader4Line, RiExternalLinkLine, RiFileCopyLine, RiDownloadLine, RiCheckLine } from '@remixicon/react';
import { AdminPagination } from '@/components/AdminPagination';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { statusBadgeVariant } from '@/components/ContentBadges';
import { Badge } from '@/components/ui/badge';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { Empty, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { JobDuration } from '@/pages/lesson-builder/AuthoringJobProgress';
import { fetchDiagnostics } from '@/pages/lesson-builder/lesson-builder.api';
import { useJobConnection } from '@/lib/realtime/JobRealtimeProvider';
import { cancelJob, fetchJob, fetchJobs } from './jobs.api';
import type { Job } from './jobs.types';

const active = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED']);
const cancellable = new Set(['QUEUED', 'RUNNING', 'RETRY_WAIT']);
const statuses = ['ACTIVE', 'QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED', 'FAILED', 'SUCCEEDED', 'CANCELLED'];
const jobTypes = ['LESSON_AUTHORING', 'LESSON_BUILD', 'LESSON_SOURCE_PREVIEW', 'VOICE_SAMPLE_PREPARE', 'YOUTUBE_CHANNEL_SYNC', 'DATA_IMPORT', 'LEXICON_EDITORIAL_AI', 'QUESTION_EDITORIAL_AI'];
const authoring = (job: Job) => job.ownerModule === 'lesson' && job.jobType === 'LESSON_AUTHORING' && !!job.ownerReferenceId;
function JobStatus({ job }: { job: Job }) {
  const { t } = useTranslation('admin');
  return <Badge variant={statusBadgeVariant(job.status)}>
    {active.has(job.status) && <RiLoader4Line className="size-3 motion-safe:animate-spin" aria-hidden="true" />}
    {t(`lessonAuthoring.labels.${job.status}`, { defaultValue: job.status })}
  </Badge>;
}
function Duration({ job }: { job: Job }) {
  return <JobDuration job={{ ...job, jobId: job.id, operation: job.jobType, usage: [] }} />;
}
function JobSkeleton() {
  const { t } = useTranslation('admin');
  return <div role="status" aria-label={t('loading')} className="jobs-skeleton">{[0, 1, 2, 3, 4].map(i => <div key={i}><Skeleton className="h-10 w-2/5" /><Skeleton className="h-5 w-24" /><Skeleton className="h-5 w-1/4" /></div>)}</div>;
}
export function JobsPage() {
  const { t, i18n } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const queryId = params.get('job') ?? '', status = params.get('status') ?? '', type = params.get('type') ?? '';
  const page = Math.max(0, Number(params.get('page')) || 0);
  const [id, setId] = useState(queryId), [validationError, setValidationError] = useState('');
  useEffect(() => { setId(queryId); setValidationError(''); }, [queryId]);
  const connection = useJobConnection();
  const validId = z.uuid().safeParse(queryId).success;
  const list = useQuery({ queryKey: ['jobs', status, type, page], queryFn: ({ signal }) => fetchJobs(new URLSearchParams({ status, type, page: String(page), size: '20' }), signal), placeholderData: keepPreviousData, refetchInterval: connection === 'connected' ? 30000 : 5000 });
  const query = useQuery({ queryKey: ['job', queryId], queryFn: ({ signal }) => fetchJob(queryId, signal), enabled: validId,
    refetchInterval: q => q.state.data && active.has(q.state.data.status) ? connection === 'connected' ? 30000 : 2500 : false });
  function update(key: string, value: string) {
    const next = new URLSearchParams(params); if (value) next.set(key, value); else next.delete(key);
    if (key !== 'page' && key !== 'job') next.delete('page');
    setParams(next);
  }
  function lookup(event: FormEvent) {
    event.preventDefault();
    const value = id.trim();
    if (!z.uuid().safeParse(value).success) { setValidationError(t('jobs.enterUuid')); return; }
    setValidationError('');
    if (value === queryId) void query.refetch(); else update('job', value);
  }
  function refresh() { void list.refetch(); if (validId) void query.refetch(); }
  const date = (value?: string | null) => value ? new Date(value).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' }) : '—';
  return <section className="jobs-page">
    <header className="page-heading"><div><h1>{t('jobs.title')}</h1><p className="lead">{t('jobs.lead')}</p></div><Button variant="outline" disabled={list.isFetching || query.isFetching} onClick={refresh}><RiRefreshLine className={list.isFetching || query.isFetching ? 'motion-safe:animate-spin' : ''} />{t('jobs.refresh')}</Button></header>
    <div className="jobs-toolbar">
      <div className="jobs-filters"><Select value={status || 'ALL'} onValueChange={value => update('status', value === 'ALL' ? '' : value)}><SelectTrigger aria-label={t('jobs.status')}><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">{t('jobs.allStatuses')}</SelectItem>{statuses.map(value => <SelectItem key={value} value={value}>{value === 'ACTIVE' ? t('jobs.active') : t(`lessonAuthoring.labels.${value}`)}</SelectItem>)}</SelectContent></Select>
      <Select value={type || 'ALL'} onValueChange={value => update('type', value === 'ALL' ? '' : value)}><SelectTrigger aria-label={t('jobs.type')}><SelectValue /></SelectTrigger><SelectContent><SelectItem value="ALL">{t('jobs.allTypes')}</SelectItem>{jobTypes.map(value => <SelectItem key={value} value={value}>{t(`jobs.types.${value}`)}</SelectItem>)}</SelectContent></Select></div>
      <form className="jobs-lookup" onSubmit={lookup}><Input value={id} aria-label={t('jobs.jobUuid')} placeholder={t('jobs.lookupPlaceholder')} onChange={e => { setId(e.target.value); setValidationError(''); }} aria-invalid={!!validationError} /><Button type="submit" variant="outline" disabled={query.isFetching || !id.trim()}><RiSearchLine />{t('jobs.inspect')}</Button></form>
    </div>
    {validationError && <p role="alert" className="text-sm text-destructive">{validationError}</p>}
    <div className="jobs-sync" role="status"><span className="jobs-connection" data-connected={connection === 'connected'} />{t(`jobs.connection.${connection}`)}<span>{t('jobs.lastUpdated', { time: list.dataUpdatedAt ? new Date(list.dataUpdatedAt).toLocaleTimeString(i18n.language) : '—' })}</span>{list.isFetching && <RiLoader4Line className="size-3 motion-safe:animate-spin" aria-hidden="true" />}</div>
    <CatalogError error={list.error} retry={() => void list.refetch()} retrying={list.isFetching} />
    {list.isPending ? <JobSkeleton /> : list.data?.items.length ? <div className="jobs-table" aria-busy={list.isFetching}><Table><TableHeader><TableRow>{['task', 'status', 'progress', 'created', 'duration', 'actions'].map(key => <TableHead key={key}>{t(`jobs.${key}`)}</TableHead>)}</TableRow></TableHeader><TableBody>{list.data.items.map(job => <TableRow key={job.id} data-state={queryId === job.id ? 'selected' : undefined}>
      <TableCell><button className="jobs-task" onClick={() => update('job', job.id)}><strong>{t(`jobs.types.${job.jobType}`, { defaultValue: job.jobType })}</strong><span>{t('jobs.shortId', { id: job.id.slice(0, 8) })} · {t('jobs.attempt', { current: job.attemptCount, max: job.maxAttempts })}</span></button></TableCell>
      <TableCell><JobStatus job={job} /></TableCell>
      <TableCell><div className="jobs-progress"><span>{job.currentStep ? t(`lessonAuthoring.labels.${job.currentStep}`, { defaultValue: job.currentStep }) : t('jobs.waiting')}</span>{active.has(job.status) && <Progress value={job.progressPercent} aria-label={t('jobs.progress')} />}{job.errorCode && <span className="text-destructive">{job.errorCode}</span>}</div></TableCell>
      <TableCell className="jobs-date">{date(job.createdAt)}</TableCell><TableCell><Duration job={job} /></TableCell><TableCell><Button variant="outline" size="sm" onClick={() => update('job', job.id)}>{t('jobs.details')}</Button></TableCell>
    </TableRow>)}</TableBody></Table></div> : !list.isError && <Empty><EmptyTitle>{t('jobs.emptyTitle')}</EmptyTitle><EmptyDescription>{t(status || type ? 'jobs.emptyFiltered' : 'jobs.emptyDescription')}</EmptyDescription>{(status || type) && <Button variant="outline" onClick={() => { const next = new URLSearchParams(params); ['status', 'type', 'page'].forEach(key => next.delete(key)); setParams(next); }}>{t('jobs.clearFilters')}</Button>}</Empty>}
    {list.data && <footer className="jobs-pagination"><span>{t('jobs.total', { count: list.data.total })}</span><AdminPagination page={list.data.page} pageCount={Math.ceil(list.data.total / list.data.size)} disabled={list.isFetching || list.isError} onPageChange={page => update('page', page ? String(page) : '')} /></footer>}
    <Sheet open={!!queryId} onOpenChange={open => { if (!open) update('job', ''); }}><SheetContent className="jobs-sheet"><SheetHeader><SheetTitle>{t('jobs.details')}</SheetTitle><SheetDescription>{t('jobs.detailsHelp')}</SheetDescription></SheetHeader>
      {!validId ? <p role="alert">{t('jobs.enterUuid')}</p> : query.isPending ? <JobSkeleton /> : <><CatalogError error={query.error} retry={() => void query.refetch()} retrying={query.isFetching} />{query.data && <JobDetails key={query.data.id} job={query.data} date={date} />}</>}
    </SheetContent></Sheet>
  </section>;
}
function JobDetails({ job, date }: { job: Job; date: (value?: string | null) => string }) {
  const { t } = useTranslation('admin'), client = useQueryClient();
  const [confirm, setConfirm] = useState(false), [technical, setTechnical] = useState(false), [copied, setCopied] = useState(false), [copyError, setCopyError] = useState(false);
  const cancelling = useRef(false);
  const mutation = useMutation({ mutationFn: cancelJob, onSuccess: async () => { setConfirm(false); await Promise.all([client.invalidateQueries({ queryKey: ['job', job.id] }), client.invalidateQueries({ queryKey: ['jobs'] })]); } });
  const diagnostics = useQuery({ queryKey: ['authoring-diagnostics', job.ownerReferenceId, job.id], queryFn: () => fetchDiagnostics(job.ownerReferenceId!, job.id), enabled: technical && !!authoring(job), refetchInterval: technical && active.has(job.status) ? 2500 : false });
  const json = JSON.stringify({ job, diagnostics: diagnostics.data }, null, 2);
  async function cancel() { if (cancelling.current) return; cancelling.current = true; try { await mutation.mutateAsync(job.id); } catch { /* Keep the dialog and error available for retry. */ } finally { cancelling.current = false; } }
  async function copy() { try { await navigator.clipboard.writeText(json); setCopied(true); setCopyError(false); } catch { setCopyError(true); } }
  return <div className="jobs-detail">
    <div className="jobs-detail-heading"><h2>{t(`jobs.types.${job.jobType}`, { defaultValue: job.jobType })}</h2><JobStatus job={job} /></div><code className="jobs-id">{job.id}</code>
    <section className="jobs-current"><h3>{t('jobs.progress')}</h3><p>{job.currentStep ? t(`lessonAuthoring.labels.${job.currentStep}`, { defaultValue: job.currentStep }) : t('jobs.waiting')} <span>{job.progressPercent}%</span></p><Progress value={job.progressPercent} aria-label={t('jobs.progress')} /><p>{t('jobs.attempt', { current: job.attemptCount, max: job.maxAttempts })} · <Duration job={job} /></p></section>
    {(job.errorCode || job.errorMessage) && <section className="jobs-failure" role="alert"><h3>{job.errorCode ?? t('jobs.failure')}</h3><p>{job.errorMessage}</p>{job.status === 'RETRY_WAIT' && <p>{t('jobs.retryAt', { time: date(job.nextRetryAt) })}</p>}</section>}
    {job.status === 'CANCEL_REQUESTED' && <p className="jobs-help">{t('jobs.cancellationPending')}</p>}
    <dl className="jobs-dates">{[['created', job.createdAt], ['started', job.startedAt], ['finished', job.finishedAt], ['nextRetry', job.nextRetryAt]].map(([label, value]) => <div key={label}><dt>{t(`jobs.${label}`)}</dt><dd>{date(value)}</dd></div>)}</dl>
    <p className="jobs-help">{t('jobs.durationHelp')}</p>
    <div className="jobs-detail-actions">{authoring(job) && <Button asChild><Link to={`/lessons/${job.ownerReferenceId}`}><RiExternalLinkLine />{t('jobs.openLesson')}</Link></Button>}{cancellable.has(job.status) && <Button variant="outline" onClick={() => { mutation.reset(); setConfirm(true); }}>{t('jobs.requestCancellation')}</Button>}</div>
    {authoring(job) && <section className="jobs-technical"><Button variant="outline" onClick={() => setTechnical(value => !value)} aria-expanded={technical}>{t('finish.technical')}</Button>{technical && <><CatalogError error={diagnostics.error} retry={() => void diagnostics.refetch()} retrying={diagnostics.isFetching} />{diagnostics.isPending ? <Skeleton className="h-40 w-full" /> : diagnostics.data && <><p className="jobs-help">{t(diagnostics.data.length ? 'jobs.diagnosticHelp' : 'finish.noDiagnostic')}</p><div className="jobs-detail-actions"><Button variant="outline" size="sm" disabled={diagnostics.isFetching || diagnostics.isError} onClick={() => void copy()}>{copied ? <RiCheckLine /> : <RiFileCopyLine />}{t(copied ? 'jobs.copied' : 'finish.copyDiagnostic')}</Button><Button variant="outline" size="sm" disabled={diagnostics.isFetching || diagnostics.isError} onClick={() => { const url = URL.createObjectURL(new Blob([json], { type: 'application/json' })), link = document.createElement('a'); link.href = url; link.download = `job-${job.id}.json`; link.click(); URL.revokeObjectURL(url); }}><RiDownloadLine />{t('finish.downloadDiagnostic')}</Button></div>{copyError && <p role="alert">{t('jobs.copyFailed')}</p>}<pre tabIndex={0}>{json}</pre></>}</>}</section>}
    <Dialog open={confirm} onOpenChange={open => { if (!mutation.isPending) setConfirm(open); }}><DialogContent><DialogHeader><DialogTitle>{t('jobs.cancelDialog.title')}</DialogTitle><DialogDescription>{t('jobs.cancelDialog.description')}</DialogDescription></DialogHeader><CatalogError error={mutation.error} />{!cancellable.has(job.status) && <p>{t('jobs.noLongerCancellable')}</p>}<div className="jobs-detail-actions"><Button variant="outline" disabled={mutation.isPending} onClick={() => setConfirm(false)}>{t('common:actions.cancel')}</Button><Button variant="destructive" disabled={mutation.isPending || !cancellable.has(job.status)} onClick={() => void cancel()}>{mutation.isPending && <RiLoader4Line className="motion-safe:animate-spin" />}{t('jobs.cancelDialog.confirm')}</Button></div></DialogContent></Dialog>
  </div>;
}
