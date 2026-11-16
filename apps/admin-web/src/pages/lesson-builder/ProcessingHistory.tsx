import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiCodeSSlashLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { activeJob, formatTime, JobDuration, useCodeLabel } from './AuthoringJobProgress';
import { fetchDiagnostics } from './lesson-builder.api';
import type { AuthoringJob } from './lesson-builder.types';

type Call = AuthoringJob['usage'][number];
function task(call: Call, operation: string) {
  if (['GENERAL_LLM', 'REASONING_LLM'].includes(call.capability ?? '')) return 'LLM';
  if (call.capability === 'NLP' || ['LOCAL_NLP'].includes(call.provider)) return 'IPA';
  if (call.capability) return call.capability;
  return operation === 'CONTENT' && !/^(LOCAL|QWEN|KOKORO)/.test(call.provider) ? 'LLM' : operation;
}
function tokenTotal(calls: Call[], field: 'inputTokens' | 'outputTokens') {
  const known = calls.filter(call => call[field] != null);
  const sum = known.reduce((total, call) => total + call[field]!, 0);
  return !calls.length ? '—' : known.length === calls.length ? String(sum) : known.length ? `${sum} + ?` : '?';
}
function callDuration(calls: Call[]) {
  const ms = calls.reduce((sum, call) => sum + call.latencyMs, 0);
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)}s`;
}

export function ProcessingHistory({ lessonId, jobs }: { lessonId: string; jobs: AuthoringJob[] }) {
  const { t, i18n } = useTranslation('admin'), describe = useCodeLabel();
  const [debugId, setDebugId] = useState<string>();
  if (!jobs.length) return <p className="studio-help">{t('finish.noJobs')}</p>;
  return <div className="studio-history-list">{[...jobs].reverse().map(job => {
    const result = job.result?.version === 1 ? job.result : undefined;
    const groups = new Map<string, Call[]>();
    for (const call of job.usage) {
      const key = `${task(call, job.operation)}|${call.provider}|${call.model}`;
      groups.set(key, [...(groups.get(key) ?? []), call]);
    }
    const llm = job.usage.filter(call => task(call, job.operation) === 'LLM');
    const queueMs = job.startedAt && job.createdAt ? Math.max(0, Date.parse(job.startedAt) - Date.parse(job.createdAt)) : undefined;
    return <article className="studio-history-job" key={job.jobId}>
      <header><div><h3>{describe(job.operation)}</h3><Badge variant={job.status === 'FAILED' ? 'destructive' : job.status === 'SUCCEEDED' ? 'success' : 'secondary'}>{describe(job.status)}</Badge></div><Button size="sm" variant="outline" aria-expanded={debugId === job.jobId} onClick={() => setDebugId(debugId === job.jobId ? undefined : job.jobId)}><RiCodeSSlashLine />{t('processingHistory.debug')}</Button></header>
      <div className="studio-history-time">{job.createdAt && <time dateTime={job.createdAt}>{new Date(job.createdAt).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' })}</time>}<JobDuration job={job} />{queueMs != null && <span>{t('processingHistory.queue', { seconds: (queueMs / 1000).toFixed(1) })}</span>}{(job.attemptCount ?? 0) > 1 && <span>{t('processingHistory.attempts', { count: job.attemptCount })}</span>}</div>
      {activeJob(job) && <p className="studio-help">{describe(job.currentStep || job.operation)}{job.status === 'RETRY_WAIT' && job.nextRetryAt ? ` · ${t('lessonAuthoring.retryAt', { time: new Date(job.nextRetryAt).toLocaleString(i18n.language) })}` : ''}</p>}
      {job.errorMessage && <p className="studio-history-error">{job.errorCode}: {job.errorMessage}</p>}
      {result ? <div className="studio-history-result">
        <p className="studio-help">{t('processingHistory.checkpoint', { time: new Date(result.recordedAt).toLocaleTimeString(i18n.language) })}</p>
        <p>{t('processingHistory.sourceResult', { sentences: result.sentences, words: result.words, duration: formatTime(result.audioDurationMs) })}</p>
        {job.operation === 'ALIGN' && <p>{t('processingHistory.timing', { done: result.timedSentences, total: result.sentences })}</p>}
        {job.operation === 'CONTENT' && <><dl className="studio-history-coverage">{['TRANSLATION', 'IPA', 'LEXICAL', 'GRAMMAR', 'PROFILE', 'DICTATION', 'SHADOWING'].filter(feature => feature in result.completedFeatures).map(feature => <div key={feature}><dt>{t(`processingHistory.features.${feature}`)}</dt><dd>{result.completedFeatures[feature]}/{result.sentences}</dd></div>)}</dl><p>{t('processingHistory.notes', { vocabulary: result.vocabularyNotes, grammar: result.grammarNotes })}</p><p>{t('processingHistory.selections', { dictation: result.dictationSentences, shadowing: result.shadowingSentences })}</p></>}
        {result.warnings > 0 && <p className="studio-help">{t('processingHistory.warnings', { count: result.warnings })}</p>}
      </div> : <p className="studio-help">{t(activeJob(job) ? 'processingHistory.noCheckpoint' : 'processingHistory.legacyResult')}</p>}
      {!!job.usage.length && <>
        <Table className="studio-history-calls"><TableHeader><TableRow>{['modelTask', 'calls', 'callTime'].map(key => <TableHead key={key}>{t(`processingHistory.${key}`)}</TableHead>)}</TableRow></TableHeader><TableBody>{[...groups].map(([key, calls]) => <TableRow key={key}>
          <TableCell><strong>{t(`processingHistory.tasks.${task(calls[0], job.operation)}`, { defaultValue: describe(task(calls[0], job.operation)) })}</strong><span>{calls[0].model} · {calls[0].provider}</span>{calls.some(call => call.status === 'FAILED') && <small className="text-destructive">{t('processingHistory.routeFailures', { count: calls.filter(call => call.status === 'FAILED').length })}</small>}{calls.some(call => call.fallback) && <small>{t('processingHistory.fallbacks', { count: calls.filter(call => call.fallback).length })}</small>}</TableCell>
          <TableCell>{calls.length}</TableCell><TableCell>{callDuration(calls)}</TableCell>
        </TableRow>)}</TableBody></Table>
        <p className="studio-history-totals">{t('processingHistory.callsSummary', { llm: llm.length, local: job.usage.length - llm.length })}{llm.length > 0 && <span>{t('processingHistory.tokens', { input: tokenTotal(llm, 'inputTokens'), output: tokenTotal(llm, 'outputTokens') })}{llm.some(call => call.inputTokens == null || call.outputTokens == null) && ` · ${t('processingHistory.unknownTokens')}`}</span>}</p>
        {job.usage.some(call => !call.status) && <p className="studio-help">{t('processingHistory.legacyCalls')}</p>}
      </>}
      {debugId === job.jobId && <JobDiagnostics lessonId={lessonId} job={job} />}
    </article>;
  })}</div>;
}

function JobDiagnostics({ lessonId, job }: { lessonId: string; job: AuthoringJob }) {
  const { t } = useTranslation('admin');
  const [copied, setCopied] = useState(false), [copyError, setCopyError] = useState(false);
  const diagnostics = useQuery({ queryKey: ['authoring-diagnostics', lessonId, job.jobId], queryFn: () => fetchDiagnostics(lessonId, job.jobId), refetchInterval: activeJob(job) ? 2500 : false });
  const json = JSON.stringify({ job, diagnostics: diagnostics.data }, null, 2);
  return <section className="studio-diagnostic" aria-label={t('finish.technical')}>
    <CatalogError error={diagnostics.error} retry={() => void diagnostics.refetch()} />
    {diagnostics.isPending ? <CatalogSkeleton /> : !diagnostics.data?.length ? <p>{t('finish.noDiagnostic')}</p> : null}
    <div className="studio-bulk"><Button size="sm" variant="outline" disabled={diagnostics.isPending || diagnostics.isError} onClick={async () => { try { await navigator.clipboard.writeText(json); setCopied(true); setCopyError(false); } catch { setCopyError(true); } }}>{t(copied ? 'jobs.copied' : 'finish.copyDiagnostic')}</Button><Button size="sm" variant="outline" disabled={diagnostics.isPending || diagnostics.isError} onClick={() => { const url = URL.createObjectURL(new Blob([json], { type: 'application/json' })); const link = document.createElement('a'); link.href = url; link.download = `lesson-job-${job.jobId}.json`; link.click(); URL.revokeObjectURL(url); }}>{t('finish.downloadDiagnostic')}</Button></div>
    {copyError && <p role="alert">{t('jobs.copyFailed')}</p>}<pre tabIndex={0}>{json}</pre>
  </section>;
}
