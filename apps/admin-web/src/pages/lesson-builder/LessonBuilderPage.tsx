import './lesson-builder.css';
import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { usePanelRef } from 'react-resizable-panels';
import { RiPlayLine, RiScissorsLine, RiMergeCellsHorizontal, RiSaveLine, RiRefreshLine, RiTimeLine, RiContractLeftLine, RiExpandLeftLine, RiArrowUpSLine, RiEyeLine, RiEyeOffLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { Textarea } from '@/components/ui/textarea';
import { Tabs, TabsContent } from '@/components/ui/tabs';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetTrigger } from '@/components/ui/sheet';
import { ResizablePanelGroup, ResizablePanel, ResizableHandle } from '@/components/ui/resizable';
import { Skeleton } from '@/components/ui/skeleton';
import { ApiError } from '@/lib/api';
import { useJobConnection } from '@/lib/realtime/JobRealtimeProvider';
import { cancelJob } from '@/pages/jobs/jobs.api';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { CreateLessonPage } from './CreateLessonPage';
import { SourceSummary } from './SourceSummary';
import { LessonPlayer } from './AudioPreview';
import { VoiceSelect } from './VoiceSelect';
import { TimingEditor } from './TimingEditor';
import { TranscriptList } from './TranscriptList';
import { UnsavedChangesGuard } from './UnsavedChangesGuard';
import { createPlaybackClock, PlaybackTime } from './playback-clock';
import { mergeSentence, splitSentence } from './authoring-editor';
import { activeJob, JobProgress, JobDuration, jobProblemSentence, useCodeLabel } from './AuthoringJobProgress';
import { ProcessingStatus } from './ProcessingStatus';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMetadata, catalogKeys } from '@/pages/lessons/catalog.queries';
import { metadataSchema, saveMetadata, type Metadata } from '@/pages/lessons/catalog.api';
import { CreateMetadataFields } from './CreateMetadataFields';
import { timecode } from './timecode';
import * as authoring from './lesson-builder.api';
import type { AuthoringJob, LessonDraft, TranscriptSentence } from './lesson-builder.types';

export function LessonBuilderPage() {
  const { lessonId } = useParams();
  const [params] = useSearchParams();
  const id = lessonId ?? params.get('draft');
  return id ? <LessonEditorPage key={id} lessonId={id} /> : <CreateLessonPage />;
}
function LessonEditorPage({ lessonId }: { lessonId: string }) {
  const { t } = useTranslation('admin');
  const describe = useCodeLabel();
  const connection = useJobConnection();
  const client = useQueryClient();
  const [draft, setDraft] = useState<LessonDraft | null>(null);
  const latest = useRef<LessonDraft | null>(null);
  const [sentences, setSentences] = useState<TranscriptSentence[]>([]);
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  const editorRevision = useRef<number | null>(null);
  const [editorTab, setEditorTab] = useState('transcript');
  const [editingSource, setEditingSource] = useState(false);
  const [metadataDirty, setMetadataDirty] = useState(false);
  const [activeSentenceId, setActiveSentenceId] = useState<string | null>(null);
  const [inspectRequest, setInspectRequest] = useState(0);
  const [issuesOnly, setIssuesOnly] = useState(false);
  const [splitOffsets, setSplitOffsets] = useState<Record<number, number>>({});
  const [karaokeConfirmed, setKaraokeConfirmed] = useState(false);
  const [playerAvailable, setPlayerAvailable] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const location = useLocation();
  const [error, setError] = useState(() => location.state?.preparationFailed ? t('twoStep.synthesisSubmissionFailed') : '');
  const [clock] = useState(createPlaybackClock);
  const [seek, setSeek] = useState<{ atMs: number; sequence: number; play?: boolean } | null>(null);
  const [playbackHost, setPlaybackHost] = useState<HTMLDivElement | null>(null);
  const [playerCollapsed, setPlayerCollapsed] = useState(false);
  const [mobilePane, setMobilePane] = useState('content');
  const playerPanel = usePanelRef();

  function acceptDraft(next: LessonDraft, replaceEditor = true) {
    if (latest.current?.id === next.id && next.revision < latest.current.revision) return;
    const previous = latest.current;
    latest.current = next;
    setDraft(next);
    if (replaceEditor && next.revision !== editorRevision.current) {
      editorRevision.current = next.revision;
      setSentences(next.sentences.map(({ id, text, hidden }) => ({ id, text, ...(hidden != null ? { hidden } : {}) })));
      setActiveSentenceId(old => next.sentences.some(row => row.id === old) ? old : next.sentences[0]?.id ?? null);
      setDirty(false); dirtyRef.current = false;
      setKaraokeConfirmed(false);
    }
    const reviewed = next.karaokeReviewedRevision === next.transcriptRevision;
    if (next.sourceType === 'TEXT' && !next.audioReady) setEditorTab('source');
    else if (previous?.sourceType === 'TEXT' && !previous.audioReady && next.audioReady) { setEditorTab('transcript'); setEditingSource(false); }
    if (!previous && (reviewed || (next.approvedRevision === next.transcriptRevision && ['VALID', 'DRAFT_VALID'].includes(next.timingStatus) && !next.alignmentRequired))) setEditorTab('timing');
  }
  const query = useQuery({
    queryKey: ['authoring', lessonId],
    queryFn: ({ signal }) => authoring.fetchDraft(lessonId!, signal),
    staleTime: 0,
    refetchInterval: q => q.state.data?.jobs.some(activeJob) ? connection === 'connected' ? 30000 : 2500 : false,
  });
  useEffect(() => { if (query.data) acceptDraft(query.data, !dirtyRef.current); }, [query.data, query.dataUpdatedAt]);

  async function perform(action: () => Promise<void>) {
    if (busyRef.current) return false;
    busyRef.current = true; setBusy(true); setError('');
    try {
      await action();
      if (latest.current) client.setQueryData(['authoring', lessonId], latest.current);
      void client.invalidateQueries({ queryKey: ['catalog'] });
      return true;
    } catch (cause) {
      setError(cause instanceof ApiError && cause.status === 409 ? t('lessonAuthoring.conflict') : cause instanceof Error ? cause.message : String(cause));
      return false;
    } finally { busyRef.current = false; setBusy(false); }
  }
  function updateSentences(next: TranscriptSentence[]) {
    setSentences(next); setDirty(true); dirtyRef.current = true; setKaraokeConfirmed(false);
  }
  const pending = draft?.jobs.some(activeJob) ?? false;
  const published = draft?.status === 'PUBLISHED';
  const textSource = draft?.sourceType === 'TEXT';
  const sourcePending = !!draft && (textSource ? !draft.audioReady : !draft.sentences.length);
  const sourceText = sentences.map(row => row.text).join('\n\n');
  const spokenDirty = dirty && (sentences.length !== draft?.sentences.length || sentences.some((row,index) => row.text !== draft?.sentences[index]?.text));
  const blocked = busy || pending || published;
  const approved = !!draft && draft.approvedRevision === draft.transcriptRevision;
  const timingValid = !!draft && ['VALID', 'DRAFT_VALID'].includes(draft.timingStatus) && !draft.alignmentRequired;
  const reviewed = !!draft && approved && timingValid && draft.karaokeReviewedRevision === draft.transcriptRevision;
  const summaryJob = draft?.jobs.filter(activeJob).at(-1) ?? draft?.jobs.at(-1);
  const problemSentence = summaryJob ? draft?.sentences[(jobProblemSentence(summaryJob) ?? 0)-1] : undefined;
  const issues = new Map((draft?.sentences ?? []).map(row => [row.id, row.issues.filter(code => !code.startsWith('TIMING_') && code !== 'GROUPED_TIMING'
    && !(code === 'SHORT_SENTENCE' && row.issues.includes('POSSIBLE_REPEAT')))]));
  const problemCount = sentences.filter(row => !row.hidden && issues.get(row.id ?? '')?.length).length;
  const visibleCount = sentences.filter(row => !row.hidden).length;
  const visibleRows = sentences.flatMap((row, index) => !issuesOnly || (!row.hidden && issues.get(row.id ?? '')?.length) ? [index] : []);
  function playSentence(id: string, atMs?: number) {
    const row = draft?.sentences.find(sentence => sentence.id === id);
    const start = atMs ?? row?.words[0]?.startMs ?? row?.previewStartMs;
    if (start == null) return;
    setActiveSentenceId(id); setSeek(old => ({ atMs: start, sequence: (old?.sequence ?? 0) + 1, play: true }));
  }
  function pausePreview() { setSeek(old => ({ atMs: clock.get(), sequence: (old?.sequence ?? 0) + 1, play: false })); }
  function saveCurrentTranscript() {
    const saving = textSource && (sourcePending || editingSource)
      ? authoring.saveTextSource(draft!.id, editorRevision.current!, sourceText)
      : authoring.saveTranscript(draft!.id, editorRevision.current!, sentences);
    return saving.then(next => { editorRevision.current = null; return next; });
  }
  async function saveApproveAlign() {
    if (!draft) return;
    await perform(async () => {
      let next = draft;
      if (dirty) { next = await saveCurrentTranscript(); acceptDraft(next); }
      if (next.sourceType === 'TEXT' && !next.audioReady) { setError(t('media.audioMissing')); return; }
      if (next.approvedRevision !== next.transcriptRevision) { next = await authoring.approveTranscript(next.id, next.revision); acceptDraft(next); }
      if (next.alignmentRequired || !['VALID', 'DRAFT_VALID'].includes(next.timingStatus)) {
        await authoring.alignTranscript(next.id, next.revision); acceptDraft(await authoring.fetchDraft(next.id));
      }
      setEditorTab('timing'); setKaraokeConfirmed(false);
    });
  }
  function retryJob(job: AuthoringJob) {
    if (!draft) return;
    void perform(async () => {
      const retry = job.operation === 'TTS' ? authoring.synthesizeText : job.operation === 'PREPARE' ? authoring.prepareTranscript : authoring.alignTranscript;
      await retry(draft.id, draft.revision); acceptDraft(await authoring.fetchDraft(draft.id));
    });
  }
  function jobActions(job: AuthoringJob) {
    return {
      onCancel: () => void perform(async () => { await cancelJob(job.jobId); acceptDraft(await authoring.fetchDraft(draft!.id), !dirtyRef.current); }),
      onRetry: job === draft?.jobs.at(-1) && ['PREPARE', 'TTS', 'ALIGN'].includes(job.operation) && ['FAILED', 'CANCELLED'].includes(job.status) && !blocked && !dirty && (job.operation !== 'ALIGN' || approved) ? () => retryJob(job) : undefined,
    };
  }
  return <Tabs asChild value={editorTab} onValueChange={setEditorTab}>
    <section className="authoring-page" data-editing data-review="true" data-stage={editorTab} data-preparing={sourcePending}>
      <UnsavedChangesGuard dirty={dirty} />
      <div className="authoring-heading"><h1 title={draft?.title}>{draft?.title ?? t('lessonAuthoring.title')}</h1><Badge variant={reviewed && !dirty ? 'success' : 'secondary'}>{t(reviewed && !dirty ? 'twoStep.reviewedDraft' : 'twoStep.draft')}</Badge>{draft && (<div className="review-document-actions">
            <Tooltip><TooltipTrigger asChild><Button size="icon-sm" variant="ghost" aria-label={t(playerCollapsed ? 'authoringUi.showPlayer' : 'authoringUi.hidePlayer')} aria-expanded={!playerCollapsed} aria-controls="authoring-player" onClick={() => { if (playerPanel.current?.isCollapsed()) playerPanel.current.expand(); else playerPanel.current?.collapse(); }}>{playerCollapsed ? <RiExpandLeftLine /> : <RiContractLeftLine />}</Button></TooltipTrigger><TooltipContent>{t(playerCollapsed ? 'authoringUi.showPlayer' : 'authoringUi.hidePlayer')}</TooltipContent></Tooltip>

            <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={blocked || !dirty} aria-label={t('lessonAuthoring.saveTranscript')} onClick={() => void perform(async () => acceptDraft(await saveCurrentTranscript()))}><RiSaveLine /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.saveTranscript')}</TooltipContent></Tooltip>
            <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={busy} aria-label={t(dirty ? 'lessonAuthoring.discardReload' : 'lessonAuthoring.refresh')} onClick={() => { if (!dirty || window.confirm(t('catalog.unsaved'))) void perform(async () => { editorRevision.current = null; acceptDraft(await authoring.fetchDraft(draft.id)); }); }}><RiRefreshLine /></Button></TooltipTrigger><TooltipContent>{t(dirty ? 'lessonAuthoring.discardReload' : 'lessonAuthoring.refresh')}</TooltipContent></Tooltip>
            {timingValid && <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={blocked || dirty || !approved} aria-label={t('lessonAuthoring.realign')} onClick={() => void perform(async () => { await authoring.alignTranscript(draft.id, draft.revision); acceptDraft(await authoring.fetchDraft(draft.id)); setKaraokeConfirmed(false); })}><RiTimeLine /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.realign')}</TooltipContent></Tooltip>}
          </div>)}</div>
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
      <CatalogError error={query.error} retry={() => void query.refetch()} />
      {!draft ? !query.isError && <div role="status" aria-label={t('lessonAuthoring.loading')} className="grid gap-4"><Skeleton className="h-8 w-2/3" /><Skeleton className="h-64 w-full" /></div> : <>
        {busy && !pending ? <ProcessingStatus label={t('twoStep.submitting')} /> : summaryJob && summaryJob.status !== 'SUCCEEDED' && <ProcessingStatus job={summaryJob} {...jobActions(summaryJob)} onInspectProblem={!dirty && approved && problemSentence ? () => { setIssuesOnly(false); setEditorTab('transcript'); setActiveSentenceId(problemSentence.id); setInspectRequest(value=>value+1); } : undefined} />}
        {connection !== 'connected' && pending && <p role="status" className="text-sm text-muted-foreground">{t(`authoringFlow.connection_${connection}`)}</p>}
        {reviewed && !dirty && !pending && <div className="review-complete" role="status"><span>{t('twoStep.completeTitle')}</span><Button asChild variant="ghost" size="sm"><Link to="/lessons">{t('twoStep.backToLessons')}</Link></Button></div>}
        <div className="workspace-pane-switch"><Button variant={mobilePane === 'player' ? 'secondary' : 'outline'} onClick={() => setMobilePane('player')}>{t('catalog.player')}</Button><Button variant={mobilePane === 'content' ? 'secondary' : 'outline'} onClick={() => setMobilePane('content')}>{t('catalog.editor')}</Button></div>
        <ResizablePanelGroup orientation="horizontal" className="authoring-workspace" data-mobile-pane={mobilePane} data-player-collapsed={playerCollapsed}>
          <ResizablePanel panelRef={playerPanel} collapsible collapsedSize="0%" defaultSize="40%" minSize={240} onResize={size => setPlayerCollapsed(size.asPercentage === 0)} className="authoring-player-pane" id="authoring-player">
            <div className="authoring-player" style={{ display: playerCollapsed ? 'none' : undefined }}><header className="authoring-pane-heading"><h2>{t('reviewWorkspace.listenAlong')}</h2><span className="text-xs tabular-nums text-muted-foreground">{timecode(draft.startMs)}–{timecode(draft.endMs)}</span></header>
              {textSource && (sourcePending || editingSource) ? <div className="draft-text-source">
                <label htmlFor="draft-source-text">{t('media.text')}</label><Textarea id="draft-source-text" rows={12} maxLength={10000} disabled={blocked} value={sourceText} onChange={event => updateSentences([{ id: sentences[0]?.id, text: event.target.value }])} />
                <p className="text-xs text-muted-foreground">{t('twoStep.textPreparationHelp')}</p>
              </div> : <LessonPlayer draft={draft} sentences={dirty ? [] : draft.sentences.filter(row => editorTab !== 'timing' || !row.hidden)} seek={seek} review={!sourcePending && !dirty ? { sentenceId: activeSentenceId, context: `${draft.id}:${draft.revision}:${editorTab}`, onSentenceChange: setActiveSentenceId } : undefined} controlsHost={playbackHost} onTimeChange={clock.set} onAvailabilityChange={setPlayerAvailable} />}
              {textSource ? <div className="draft-voice-summary"><VoiceSelect value={draft.ttsVoice ?? ''} disabled={blocked || dirty} onChange={voice => void perform(async () => { acceptDraft(await authoring.saveVoice(draft.id, draft.revision, voice)); setEditingSource(true); })} />
                {!sourcePending && <Button variant="ghost" size="sm" disabled={blocked} onClick={() => setEditingSource(value => !value)}>{t(editingSource ? 'twoStep.closeSource' : 'twoStep.editSource')}</Button>}
              </div> : <details className="review-source-details"><summary>{t('reviewWorkspace.selectedSource')}</summary><SourceSummary draft={draft} /></details>}

            </div>
          </ResizablePanel>
          <ResizableHandle withHandle aria-label={t('lessonAuthoring.resizePanels')} />
          <ResizablePanel defaultSize="60%" minSize={360} className="authoring-editor-pane"><div className="authoring-tabs min-w-0">
            {!sourcePending && <div className="authoring-review-modes" role="group" aria-label={t('authoringFlow.reviewMode')}><Button size="sm" variant={editorTab === 'transcript' ? 'secondary' : 'ghost'} aria-pressed={editorTab === 'transcript'} onClick={() => setEditorTab('transcript')}>{t('authoringFlow.editWords')}</Button><Button size="sm" variant={editorTab === 'timing' ? 'secondary' : 'ghost'} aria-pressed={editorTab === 'timing'} disabled={!approved || dirty} onClick={() => { if (sentences.find(row => row.id === activeSentenceId)?.hidden) setActiveSentenceId(sentences.find(row => !row.hidden)?.id ?? null); setEditorTab('timing'); }}>{t('authoringFlow.listenReview')}</Button><span className="review-sentence-count">{t('reviewWorkspace.sentenceCount', { count: editorTab === 'timing' ? visibleCount : sentences.length })}</span></div>}
            <TabsContent value="source" className="authoring-source-tab"><DraftSourceMetadata id={draft.id} disabled={blocked} onDirtyChange={setMetadataDirty} /><p className="source-preparation-note">{t('twoStep.prepareBeforeReview')}</p></TabsContent>
            <TabsContent value="transcript" className="authoring-transcript-tab"><div className="authoring-transcript-card"><div className="authoring-transcript-body flex flex-col gap-3">
              <div className="authoring-editor-toolbar"><Tooltip><TooltipTrigger asChild><Button size="sm" variant={issuesOnly ? 'secondary' : 'outline'} aria-pressed={issuesOnly} onClick={() => setIssuesOnly(!issuesOnly)}>{t('reviewWorkspace.transcriptSuggestions', { count: problemCount })}</Button></TooltipTrigger><TooltipContent className="max-w-sm">{t('sentenceVisibility.suggestionsHelp')}</TooltipContent></Tooltip><span className="text-xs text-muted-foreground">{t('reviewWorkspace.wordsHint')}</span></div>
              {!sentences.length && !pending && <div className="review-timing-empty"><p>{t('twoStep.noTranscript')}</p>{draft.sourceType !== 'TEXT' && <Button disabled={blocked} onClick={() => void perform(async () => { await authoring.prepareTranscript(draft.id, draft.revision); acceptDraft(await authoring.fetchDraft(draft.id)); })}>{t('lessonAuthoring.retryPreparation')}</Button>}</div>}
              <TranscriptList sentences={sentences} visibleRows={visibleRows} filtered={issuesOnly} scrollToId={activeSentenceId} scrollRequest={inspectRequest} emptyMessage={issuesOnly ? t('authoringUi.noProblemSentences') : undefined}>
                {(sentence, index) => {
                  const saved = draft.sentences.find(row => row.id === sentence.id);
                  const start = saved?.words[0]?.startMs ?? saved?.previewStartMs;
                  return <div className="authoring-sentence-row" data-active={activeSentenceId === sentence.id} data-hidden={!!sentence.hidden}>
                    <div className="authoring-sentence-heading"><span>{t('lessonAuthoring.sentence', { number: index + 1 })}</span><span className="authoring-sentence-time">{dirty ? t('reviewWorkspace.timingAfterSave') : saved?.words.length ? `${timecode(saved.words[0].startMs)}–${timecode(saved.words.at(-1)!.endMs)}` : start != null ? `≈ ${timecode(start)}` : t('authoringFlow.noTiming')}</span>
                      <Tooltip><TooltipTrigger asChild><Button type="button" variant="ghost" size="icon-xs" className="sentence-visibility-toggle" data-visible={!sentence.hidden} aria-label={t('sentenceVisibility.label', {number:index+1})} aria-pressed={!sentence.hidden} disabled={blocked || (!sentence.hidden && visibleCount <= 1)} onClick={() => updateSentences(sentences.map((row, i) => i === index ? { ...row, hidden: !row.hidden } : row))}>
                        {sentence.hidden ? <RiEyeOffLine aria-hidden="true" /> : <RiEyeLine aria-hidden="true" />}<span className="sr-only">{t(sentence.hidden ? 'sentenceVisibility.hidden' : 'sentenceVisibility.visible')}</span>
                      </Button></TooltipTrigger><TooltipContent>{t(!sentence.hidden && visibleCount <= 1 ? 'sentenceVisibility.keepOne' : sentence.hidden ? 'sentenceVisibility.show' : 'sentenceVisibility.hide')}</TooltipContent></Tooltip>
                      <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={dirty || start == null} aria-label={t('lessonAuthoring.seek')} onClick={() => sentence.id && playSentence(sentence.id)}><RiPlayLine /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.seek')}</TooltipContent></Tooltip>
                      <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={blocked} aria-label={t('lessonAuthoring.split')} onClick={() => { const next = splitSentence(sentences, index, splitOffsets[index] ?? 0); if (next === sentences) setError(t('lessonAuthoring.splitCaret')); else updateSentences(next); }}><RiScissorsLine /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.split')}</TooltipContent></Tooltip>
                      <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={blocked || index === sentences.length - 1 || !!sentence.hidden !== !!sentences[index+1]?.hidden} aria-label={t('twoStep.mergeNext', {number:index+2})} onClick={() => updateSentences(mergeSentence(sentences, index))}><RiMergeCellsHorizontal /></Button></TooltipTrigger><TooltipContent className="max-w-sm"><strong>{t(!!sentence.hidden !== !!sentences[index+1]?.hidden ? 'sentenceVisibility.mergeSameState' : 'twoStep.mergeNext', {number:index+2})}</strong>{sentences[index+1] && <p>{sentences[index+1].text.slice(0,120)}{sentences[index+1].text.length>120 ? '…' : ''}</p>}</TooltipContent></Tooltip>
                    </div>
                    <Textarea rows={1} className="authoring-sentence-text min-h-0" aria-label={t('lessonAuthoring.sentenceText', { number: index + 1 })} value={sentence.text} disabled={blocked} onSelect={event => { const offset = event.currentTarget.selectionStart; setSplitOffsets(old => ({ ...old, [index]: offset })); }} onChange={event => updateSentences(sentences.map((row, i) => i === index ? { ...row, text: event.target.value } : row))} />
                    {!!issues.get(sentence.id ?? '')?.length && <ul className="text-sm text-warning">{issues.get(sentence.id ?? '')!.map(issue => <li key={issue}>{describe(issue)}</li>)}</ul>}
                  </div>;
                }}
              </TranscriptList>
              {dirty && <p role="status" className="text-warning">{t('lessonAuthoring.unsaved')}</p>}
            </div></div></TabsContent>
            <TabsContent value="timing" className="authoring-timing-tab"><div className="timing-review-body">
              {!draft.sentences.some(row => !row.hidden && row.words.length) ? <div className="review-timing-empty"><h2>{t('reviewWorkspace.noTimingTitle')}</h2><p>{t(pending ? 'twoStep.timingProcessing' : 'reviewWorkspace.noTimingHelp')}</p></div> : <>
                {draft.alignmentRequired && <p className="text-sm text-warning">{t('waveform.alignmentRequired')}</p>}
                <TimingEditor activeSentenceId={activeSentenceId} onPlay={playSentence} onPause={pausePreview} revision={draft.revision} sentences={draft.sentences} clock={clock} durationMs={draft.endMs - draft.startMs} disabled={blocked || dirty || !approved} onSave={async (id, words, revision) => perform(async () => acceptDraft(await authoring.saveTiming(draft.id, revision, id, words)))} />
                {!reviewed && <label className="timing-review-confirm flex items-center gap-2"><Checkbox aria-label={t('lessonAuthoring.karaokeConfirmed')} checked={karaokeConfirmed} disabled={blocked || dirty || !approved || !timingValid || !playerAvailable} onCheckedChange={checked => setKaraokeConfirmed(checked === true)} /><span>{t('reviewWorkspace.confirmTiming')}</span></label>}
              </>}
            </div></TabsContent>
          </div></ResizablePanel>
        </ResizablePanelGroup>
        <footer className="authoring-actionbar"><div className="authoring-footer-status">{summaryJob && <Sheet><SheetTrigger asChild><Button size="sm" variant="ghost" aria-label={t('authoringUi.processingHistory')}><RiArrowUpSLine />{t('authoringUi.processingHistory')}<JobDuration job={summaryJob} /></Button></SheetTrigger><SheetContent side="bottom" className="authoring-history-sheet max-h-[60dvh] overflow-hidden"><SheetHeader><SheetTitle>{t('authoringUi.processingHistory')}</SheetTitle><SheetDescription>{t('authoringUi.jobCount', { count: draft.jobs.length })}</SheetDescription></SheetHeader><div className="authoring-job-details" role="region" aria-label={t('authoringUi.jobDetails')} tabIndex={0}>{[...draft.jobs].reverse().map(job => <JobProgress key={job.jobId} job={job} historical={job !== summaryJob} {...jobActions(job)} />)}</div></SheetContent></Sheet>}</div>
          <div className="review-footer-playback" ref={setPlaybackHost}><span className="review-footer-clock"><PlaybackTime clock={clock} /> / {timecode(draft.endMs - draft.startMs)}</span></div>
          <div className="authoring-action-buttons">
            {textSource && (sourcePending || spokenDirty || editingSource) && <Button disabled={blocked || metadataDirty || !sourceText.trim()} onClick={() => void perform(async () => { let next = draft; if (dirty) { next = await saveCurrentTranscript(); acceptDraft(next); } await authoring.synthesizeText(next.id, next.revision); acceptDraft(await authoring.fetchDraft(next.id)); setEditingSource(false); })}>{t('media.synthesize')}</Button>}
            {editorTab === 'transcript' && !(textSource && (sourcePending || spokenDirty || editingSource)) && <Button disabled={blocked || !sentences.length || sentences.some(row => !row.text.trim()) || (draft.sourceType === 'TEXT' && (!draft.audioReady || spokenDirty))} onClick={() => void saveApproveAlign()}>{t('authoringFlow.saveApproveAlign')}</Button>}
            {editorTab === 'timing' && !timingValid && <Button disabled={blocked || dirty || !approved} onClick={() => void perform(async () => { await authoring.alignTranscript(draft.id, draft.revision); acceptDraft(await authoring.fetchDraft(draft.id)); })}>{t('lessonAuthoring.align')}</Button>}
            {editorTab === 'timing' && timingValid && <Button disabled={blocked || dirty || !approved || !playerAvailable || !karaokeConfirmed || reviewed} onClick={() => void perform(async () => acceptDraft(await authoring.reviewKaraoke(draft.id, draft.revision)))}>{t(reviewed ? 'twoStep.reviewedDraft' : 'twoStep.saveReviewed')}</Button>}
          </div>
        </footer>
      </>}
    </section>
  </Tabs>;
}

function DraftSourceMetadata({ id, disabled, onDirtyChange }: { id: string; disabled: boolean; onDirtyChange: (dirty: boolean) => void }) {
  const metadata = useMetadata(id);
  if (metadata.isPending) return <Skeleton className="h-48" />;
  if (metadata.isError) return <CatalogError error={metadata.error} retry={() => void metadata.refetch()} />;
  return <SavedSourceMetadata key={id} lesson={metadata.data} disabled={disabled} onDirtyChange={onDirtyChange} />;
}
function SavedSourceMetadata({ lesson, disabled, onDirtyChange }: { lesson: import('@/pages/lessons/catalog.api').LessonItem; disabled: boolean; onDirtyChange: (dirty: boolean) => void }) {
  const { t } = useTranslation('admin');
  const client = useQueryClient();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const form = useForm<Metadata>({ resolver: zodResolver(metadataSchema), defaultValues: {
    title: lesson.title, description: lesson.description ?? '', imageUrl: lesson.imageUrl ?? '', categoryIds: lesson.categories.map(row => row.id),
  } });
  useEffect(() => { onDirtyChange(form.formState.isDirty); return () => onDirtyChange(false); }, [form.formState.isDirty, onDirtyChange]);
  return <form className="draft-source-metadata" onSubmit={form.handleSubmit(async values => {
    setSaving(true); setError(null);
    try { await saveMetadata(lesson.id, values); form.reset(values); await client.invalidateQueries({queryKey:catalogKeys.all}); await client.invalidateQueries({queryKey:['authoring',lesson.id]}); }
    catch (cause) { setError(cause instanceof Error ? cause : new Error(String(cause))); }
    finally { setSaving(false); }
  })}><h2>{t('reviewWorkspace.lessonInformation')}</h2><CatalogError error={error} /><fieldset disabled={disabled || saving}><CreateMetadataFields form={form} /></fieldset><Button type="submit" variant="outline" disabled={disabled || saving || !form.formState.isDirty}>{t(saving ? 'catalog.saving' : 'catalog.save')}</Button><UnsavedChangesGuard dirty={form.formState.isDirty} /></form>;
}
