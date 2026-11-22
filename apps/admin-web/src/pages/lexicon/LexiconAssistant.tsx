import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { RiSparklingLine, RiCloseLine, RiRestartLine } from '@remixicon/react';
import { api } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Textarea } from '@/components/ui/textarea';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { VoiceSelect } from '@/pages/lesson-builder/VoiceSelect';
import { cancelJob, fetchJob } from '@/pages/jobs/jobs.api';
import { LibrarySelect } from '../library/LibrarySelect';
import { MediaPreview } from '../library/ContentMedia';
import type { Document, LexiconDraft } from '../library/library.types';
import { candidatesForSense, translationPreview } from './translationCandidates';

type Suggestion = { senseId: string; field: string; value: string; candidateId?: string | null; reason?: string };
type AiRequest = { id: string; jobId: string; operation: string; baseRevision: number; acceptedRevision: number | null; applied: Record<string, unknown>; input: { senses?: { id: string }[]; itemId?: string | null }; output: { suggestions: Suggestion[]; warnings: string[]; audioAssetId?: string | null } | null };
const activeStatuses = ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED'];
const terminalStatuses = ['SUCCEEDED', 'FAILED', 'CANCELLED'];
const endpoint = '/api/v1/admin/lexicon';

export function LexiconAssistant({ document, dirty, adopt, context }: { document: Document<LexiconDraft>; dirty: boolean; adopt: (result: Document<LexiconDraft>) => void; context: { senseId: string } | { itemId: string | null } }) {
  const { t } = useTranslation('admin');
  const cache = useQueryClient();
  const [brief, setBrief] = useState('');
  const [voice, setVoice] = useState('');
  const [open, setOpen] = useState(false);
  const alive=useRef(true);
  useEffect(()=>{alive.current=true;return ()=>{alive.current=false;};},[]);
  const sense = 'senseId' in context ? document.draft.senses.find(row => row.id === context.senseId) : undefined;
  const path = `${endpoint}/${document.id}/ai`;
  const key = ['library-ai', path];
  const history = useQuery({ queryKey: key, enabled: open && document.id !== 'new', queryFn: ({ signal }) => api<AiRequest[]>(path, { signal }), refetchInterval: open ? 3000 : false });
  const jobs = useQueries({ queries: (history.data ?? []).map(row => ({ queryKey: ['job', row.jobId], queryFn: ({ signal }: { signal: AbortSignal }) => fetchJob(row.jobId, signal), refetchInterval: (query: { state: { data?: { status: string } } }) => terminalStatuses.includes(query.state.data?.status ?? '') ? false as const : 2500 })) });
  const active = jobs.some(job => activeStatuses.includes(job.data?.status ?? ''));
  const jobsLoading = jobs.some(job => job.isPending);
  const request = useMutation({ mutationFn: async (operation: string) => {
    let current = document;
    if (current.revision === 0) { current = await api<Document<LexiconDraft>>(`${endpoint}/${current.id}/draft`, { method: 'PUT', body: JSON.stringify({ revision: 0, content: current.draft }) }); if(!alive.current) return; adopt(current); }
    return api<AiRequest>(path, { method: 'POST', body: JSON.stringify('senseId' in context ? { revision: current.revision, operation, senseIds: [context.senseId], brief } : { revision: current.revision, operation: 'TTS', voice, reviewed: true, itemId: context.itemId }) });
  }, onSuccess: () => cache.invalidateQueries({ queryKey: key }) });
  const disabled = dirty || document.id === 'new' || request.isPending || active || jobsLoading || history.isPending || !!history.error;
  const activeIds = new Set((history.data ?? []).filter((_row,index) => activeStatuses.includes(jobs[index]?.data?.status ?? '')).map(row => row.id));
  const rows = (history.data ?? []).filter(row => activeIds.has(row.id) || ( 'senseId' in context ? row.operation !== 'TTS' && (row.input.senses?.some(s => s.id === context.senseId) || row.output?.suggestions.some(s => s.senseId === context.senseId)) : row.operation === 'TTS' && (row.input.itemId ?? null) === context.itemId));
  return <details className="rounded-lg border bg-muted/15 p-4" open={open} onToggle={event => setOpen(event.currentTarget.open)}><summary className="cursor-pointer text-sm font-semibold"><RiSparklingLine className="mr-2 inline size-4 text-primary" aria-hidden="true" />{t(sense ? 'dictionary.aiForSense' : 'dictionary.speechTitle')}</summary>{open && <div className="mt-4 grid gap-3">
    <p className="text-xs text-muted-foreground">{t(sense ? 'dictionary.aiHelp' : 'dictionary.speechHelp')}</p>
    {(dirty || document.id === 'new') && <p className="text-sm text-warning">{t('library.aiSaveFirst')}</p>}
    {sense ? <><p className="text-sm font-medium">{t('library.senseNumber', { number: document.draft.senses.indexOf(sense) + 1 })} · {sense.translationVi || sense.definitionEn}</p><div className="flex flex-wrap gap-2">{['MATCH','TRANSLATE','EXAMPLE'].map(operation => <Button key={operation} size="sm" variant="outline" disabled={disabled || !(sense.translationVi?.trim() || sense.definitionEn?.trim()) || operation === 'MATCH' && candidatesForSense(document.draft, sense).length === 0} tooltip={t(`dictionary.operationHelp.${operation}`)} onClick={() => request.mutate(operation)}><RiSparklingLine aria-hidden="true" />{t(`dictionary.operations.${operation}`)}</Button>)}</div><details className="text-xs"><summary className="cursor-pointer text-muted-foreground">{t('library.aiBrief')}</summary><Textarea className="mt-2" aria-label={t('library.aiBrief')} maxLength={2000} value={brief} onChange={event => setBrief(event.target.value)} /></details></> : <><p className="text-sm font-medium">{document.draft.title}</p><VoiceSelect value={voice} onChange={setVoice} disabled={disabled} /><Button className="justify-self-start" variant="outline" size="sm" disabled={disabled || !voice} tooltip={t('library.aiTtsConfirmHelp')} onClick={() => request.mutate('TTS')}>{t('library.aiTtsConfirm')}</Button></>}
    {active && <p role="status" className="text-xs text-muted-foreground">{t('dictionary.aiRunning')}</p>}
    <CatalogError error={history.error || request.error || jobs.find(job => job.error)?.error} retry={() => { void history.refetch(); jobs.forEach(job => void job.refetch()); }} />
    {rows.map(row => <RequestResult key={row.id} request={row} path={path} document={document} dirty={dirty} senseId={sense?.id} adopt={adopt} refresh={() => cache.invalidateQueries({ queryKey: key })} retry={() => request.mutate(row.operation)} retryDisabled={disabled} />)}
  </div>}</details>;
}
function RequestResult({ request, path, document, dirty, senseId, adopt, refresh, retry, retryDisabled }: { request: AiRequest; path: string; document: Document<LexiconDraft>; dirty: boolean; senseId?: string; adopt: (value: Document<LexiconDraft>) => void; refresh: () => Promise<unknown>; retry: () => void; retryDisabled: boolean }) {
  const { t } = useTranslation('admin');
  const cache = useQueryClient();
  const job = useQuery({ queryKey: ['job', request.jobId], queryFn: ({ signal }) => fetchJob(request.jobId, signal), refetchInterval: query => terminalStatuses.includes(query.state.data?.status ?? '') ? false : 2500 });
  const cancel = useMutation({ mutationFn: () => cancelJob(request.jobId), onSuccess: () => cache.invalidateQueries({ queryKey: ['job', request.jobId] }) });
  const latest=useRef({dirty,revision:document.revision,alive:true});
  latest.current={...latest.current,dirty,revision:document.revision};
  useEffect(()=>{latest.current.alive=true;return ()=>{latest.current.alive=false;};},[]);
  const accept = useMutation({ mutationFn: async ({ index, mode }: { index: number; mode: string }) => {
    const result=await api<Document<LexiconDraft>>(`${path}/${request.id}/accept`, { method: 'POST', body: JSON.stringify({ revision: document.revision, index, mode }) });
    if(latest.current.alive && !latest.current.dirty && latest.current.revision===document.revision) adopt(result);
  }, onSuccess: refresh });
  const stale = (request.acceptedRevision ?? request.baseRevision) !== document.revision;
  const active = activeStatuses.includes(job.data?.status ?? '');
  const unavailable = dirty || stale || accept.isPending || job.data?.status !== 'SUCCEEDED';
  return <article className="grid gap-3 border-t pt-3"><div className="flex flex-wrap items-center gap-2"><Badge variant="outline">{t(`dictionary.operations.${request.operation}`, { defaultValue: t('library.aiSuggestions') })}</Badge>{job.data && <span className="text-xs text-muted-foreground">{t(`lessonAuthoring.labels.${job.data.status}`, { defaultValue: job.data.status })}</span>}{active && <Button size="sm" variant="ghost" tooltip={t('library.help.cancel')} disabled={cancel.isPending || job.data?.status === 'CANCEL_REQUESTED'} onClick={() => cancel.mutate()}><RiCloseLine aria-hidden="true" />{t('library.cancel')}</Button>}</div>
    <CatalogError error={job.error || cancel.error || accept.error} />
    {job.data?.status === 'FAILED' && <div className="grid gap-2 text-sm"><p role="alert" className="text-destructive">{t(`dictionary.aiErrors.${job.data.errorCode}`, { defaultValue: t('dictionary.aiFailed') })}</p><div className="flex flex-wrap gap-2"><Button size="sm" variant="outline" disabled={retryDisabled} onClick={retry}><RiRestartLine aria-hidden="true" />{t('dictionary.retry')}</Button><Button size="sm" variant="link" asChild><Link to="/settings/ai">{t('dictionary.aiSettings')}</Link></Button></div></div>}
    {stale && request.output && <p role="status" className="text-xs text-warning">{t('library.aiStale')}</p>}
    {request.output?.warnings.map((warning, index) => <p key={index} className="text-xs text-muted-foreground">{warning}</p>)}
    {request.output?.suggestions.map((row, index) => row.senseId === senseId && <SuggestionResult key={index} row={row} current={document.draft.senses.find(s => s.id === row.senseId)} applied={!!request.applied[`suggestion:${index}`]} disabled={unavailable} onAccept={mode => accept.mutate({ index, mode })} />)}
    {request.output?.audioAssetId && <div className="grid gap-3"><Badge className="justify-self-start" variant="outline">{t('library.syntheticAudio')}</Badge><MediaPreview assetId={request.output.audioAssetId} role="audio" /><Button variant="outline" size="sm" className="justify-self-start" tooltip={t('library.help.aiAccept')} disabled={unavailable || !!request.applied['audio:0']} onClick={() => accept.mutate({ index: 0, mode: 'REPLACE' })}>{t(request.applied['audio:0'] ? 'library.aiAccepted' : 'library.aiAccept')}</Button></div>}
    {job.data?.status === 'SUCCEEDED' && request.output?.suggestions.length === 0 && !request.output.audioAssetId && <p className="text-sm text-muted-foreground">{t('dictionary.aiNoMatch')}</p>}
  </article>;
}
function SuggestionResult({ row, current, applied, disabled, onAccept }: { row: Suggestion; current?: LexiconDraft['senses'][number]; applied: boolean; disabled: boolean; onAccept: (mode: string) => void }) {
  const { t } = useTranslation('admin');
  const [mode, setMode] = useState('REPLACE');
  const before = row.field === 'translationVi' ? current?.translationVi : row.field === 'definitionEn' ? current?.definitionEn : current?.examples.map(example => String(example.text ?? '')).join('\n');
  const after = row.field === 'translationVi' ? translationPreview(before ?? null, row.value, mode) : row.field === 'example' && before ? `${before}\n${row.value}` : row.value;
  const unchanged = row.field !== 'example' && !row.candidateId && before === after;
  return <div className="grid gap-3 rounded-lg border bg-card p-3 text-sm"><p className="font-medium">{t(`library.aiFields.${row.field}`, { defaultValue: row.field })}</p>{row.reason && <p className="text-xs text-muted-foreground">{row.reason}</p>}<div className="grid gap-3 sm:grid-cols-2"><div><p className="mb-1 text-xs text-muted-foreground">{t('dictionary.beforeApply')}</p><p className="whitespace-pre-wrap break-words">{before || '—'}</p></div><div><p className="mb-1 text-xs text-muted-foreground">{t('dictionary.afterApply')}</p><p className="whitespace-pre-wrap break-words">{after}</p></div></div>{row.field === 'translationVi' && !applied && <div className="max-w-52"><LibrarySelect label={t('dictionary.applyMode')} value={mode} onValueChange={setMode} options={['REPLACE','APPEND'].map(value => ({ value, label: t(`dictionary.modes.${value}`) }))} /></div>}<Button size="sm" variant="outline" className="justify-self-start" tooltip={t('dictionary.applyHelp')} disabled={disabled || applied || unchanged} onClick={() => onAccept(mode)}>{t(applied ? 'library.aiAccepted' : unchanged ? 'dictionary.unchanged' : 'library.aiAccept')}</Button></div>;
}
