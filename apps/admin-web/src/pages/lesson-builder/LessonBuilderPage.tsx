import './lesson-builder.css';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { RiPlayLine, RiTimeLine, RiMoreLine, RiSettings3Line, RiLoader4Line, RiHistoryLine, RiArrowGoBackLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Textarea } from '@/components/ui/textarea';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem } from '@/components/ui/dropdown-menu';
import { useJobConnection } from '@/lib/realtime/JobRealtimeProvider';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { useMetadata } from '@/pages/lessons/catalog.queries';
import { deleteDraft, saveMetadata, setVisibility, metadataSchema, type Metadata } from '@/pages/lessons/catalog.api';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { CreateMetadataFields } from './CreateMetadataFields';
import { CreateLessonPage } from './CreateLessonPage';
import { LessonPlayer } from './AudioPreview';
import { VoiceSelect } from './VoiceSelect';
import { TimingEditor } from './TimingEditor';
import { TranscriptList } from './TranscriptList';
import { UnsavedChangesGuard } from './UnsavedChangesGuard';
import { createPlaybackClock, PlaybackWords } from './playback-clock';
import { activeJob, useCodeLabel } from './AuthoringJobProgress';
import { ProcessingHistory } from './ProcessingHistory';
import { AuthoringWorkflow } from './AuthoringWorkflow';
import { LessonEditorSkeleton, TranscriptSkeleton } from './AuthoringSkeleton';
import { timecode } from './timecode';
import { AnnotatedTranscript, ClozePreview, ContentOptionsPanel, ContentProfile, SentenceContentEditor, defaultContentOptions } from './ContentEditor';
import * as authoring from './lesson-builder.api';
import type { AuthoringSentence, LessonDraft } from './lesson-builder.types';

export function LessonBuilderPage() {
  const { lessonId } = useParams(); const [params] = useSearchParams(); const id = lessonId ?? params.get('draft');
  return id ? <LessonEditorPage key={id} lessonId={id} /> : <CreateLessonPage />;
}
function LessonEditorPage({ lessonId }: { lessonId: string }) {
  const { t } = useTranslation('admin'), describe = useCodeLabel(), navigate = useNavigate(), client = useQueryClient();
  const connection = useJobConnection();
  const studioRef = useRef<HTMLElement>(null);
  const [draft, setDraft] = useState<LessonDraft>(); const latest = useRef<LessonDraft | undefined>(undefined), persisted = useRef<LessonDraft | undefined>(undefined);
  const [dirty, setDirty] = useState<Set<string>>(new Set()), dirtyRef = useRef(new Set<string>());
  const [editorEpoch, setEditorEpoch] = useState(0);
  const [busy, setBusy] = useState(false), busyRef = useRef(false), [error, setError] = useState('');
  const [settings, setSettings] = useState(false), [history, setHistory] = useState(false), [preview, setPreview] = useState(false);
  const [confirm, setConfirm] = useState<'run' | 'delete'>('run'), [confirmOpen, setConfirmOpen] = useState(false), [readiness, setReadiness] = useState<string[]>([]);
  const [mode, setMode] = useState<'view' | 'DICTATION' | 'SHADOWING'>('view'), [level, setLevel] = useState<'easy' | 'medium' | 'full'>('easy');
  const [clozeEditing, setClozeEditing] = useState(false), [showIpa, setShowIpa] = useState(false), [activeId, setActiveId] = useState<string | null>(null);
  const [clock] = useState(createPlaybackClock), [seek, setSeek] = useState<{ atMs: number; sequence: number; play?: boolean } | null>(null);
  const [controlsHost, setControlsHost] = useState<HTMLDivElement | null>(null), [previewControlsHost, setPreviewControlsHost] = useState<HTMLDivElement | null>(null), [available, setAvailable] = useState(false);
  const [undoSelection, setUndoSelection] = useState<{ field: 'dictationSelected' | 'shadowingSelected'; values: Map<string, boolean> }>();
  const metadata = useMetadata(lessonId);
  const form = useForm<Metadata>({ resolver: zodResolver(metadataSchema), defaultValues: { title: '', description: '', imageUrl: '', categoryIds: [] } });
  const metadataDirty = form.formState.isDirty;
  useEffect(() => { if (metadata.data && !form.formState.isDirty) form.reset({ title: metadata.data.title, description: metadata.data.description, imageUrl: metadata.data.imageUrl ?? '', categoryIds: metadata.data.categories.map(c => c.id) }); }, [metadata.data, form]);
  const query = useQuery({ queryKey: ['authoring', lessonId], queryFn: ({ signal }) => authoring.fetchDraft(lessonId, signal), refetchInterval: q => q.state.data?.jobs.some(activeJob) ? connection === 'connected' ? 5000 : 2500 : false });
  const accept = useCallback((next: LessonDraft) => {
    persisted.current = next; latest.current = next; setDraft(next); dirtyRef.current = new Set(); setDirty(new Set()); setUndoSelection(undefined);
    client.setQueryData(['authoring', lessonId], next);
  }, [client, lessonId]);
  useEffect(() => {
    if (!query.data || busyRef.current) return;
    if (!dirtyRef.current.size) { persisted.current = query.data; latest.current = query.data; setDraft(query.data); }
    else setDraft(old => old ? { ...old, jobs: query.data!.jobs } : query.data);
  }, [query.data, query.dataUpdatedAt]);
  function mark(key: string) {
    const next = new Set(dirtyRef.current); next.add(key);
    const before = persisted.current, after = latest.current;
    if (before && after) for (const field of next) {
      const value = (draft: LessonDraft) => field === '_transcript' ? draft.sentences.map(row => [row.id, row.text])
        : field === '_options' ? draft.options : field === '_profile' ? draft.profile : field === '_voice' ? draft.ttsVoice
        : field === '_selection' ? draft.sentences.map(row => [row.dictationSelected, row.shadowingSelected])
        : draft.sentences.find(row => row.id === field);
      if (JSON.stringify(value(before)) === JSON.stringify(value(after))) next.delete(field);
    }
    dirtyRef.current = next; setDirty(next);
  }
  function changeDraft(fields: Partial<LessonDraft>, key: string) {
    if (!latest.current) return;
    const next = { ...latest.current, ...fields }; latest.current = next; setDraft(next); mark(key);
  }
  function changeSentence(id: string, fields: Partial<AuthoringSentence>, content = true) {
    if (!latest.current) return;
    pause(); changeDraft({ sentences: latest.current.sentences.map(row => row.id === id ? { ...row, ...fields } : row) }, content ? id : '_transcript');
  }
  const pending = draft?.jobs.some(activeJob) ?? false, published = draft?.status === 'PUBLISHED', blocked = busy || pending || published;
  const hasChanges = dirty.size > 0 || metadataDirty;
  async function perform(action: () => Promise<void>) {
    if (busyRef.current) return false;
    busyRef.current = true; setBusy(true); setError('');
    try { await action(); void client.invalidateQueries({ queryKey: ['catalog'] }); return true; }
    catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); return false; }
    finally { busyRef.current = false; setBusy(false); }
  }
  function openConfirm(action: 'run' | 'delete') { setConfirm(action); setConfirmOpen(true); }
  async function save() {
    const current = latest.current!;
    const changes = dirtyRef.current;
    if (!changes.size && !form.formState.isDirty) return current;
    const m = form.formState.isDirty ? await new Promise<Metadata | undefined>(resolve => { void form.handleSubmit(value => resolve(value), () => resolve(undefined))(); }) : undefined;
    if (form.formState.isDirty && !m) { setSettings(true); throw new Error(t('catalog.invalidField')); }
    if (current.status==='PUBLISHED' && m) { await saveMetadata(lessonId,{...m,version: metadata.data?.version ?? 0});form.reset(m);await metadata.refetch();return current; }
    const next = await authoring.saveChanges(lessonId, current, changes, changes.has('_options'), changes.has('_profile'), m ? {...m,version:metadata.data?.version ?? 0}:undefined);
    accept(next); if (m) { form.reset(m); void metadata.refetch(); }
    return next;
  }
  const saveRef = useRef<() => void>(() => {}); saveRef.current = () => { if (!busy && !pending && hasChanges && (!published || metadataDirty)) void perform(async () => { await save(); }); };
  useEffect(() => { const handler = (e: KeyboardEvent) => { if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); saveRef.current(); } }; document.addEventListener('keydown', handler); return () => document.removeEventListener('keydown', handler); }, []);
  function pause() { setSeek({ atMs: clock.get(), sequence: Date.now(), play: false }); }
  function discardChanges() {
    if (!persisted.current || busyRef.current || pending) return;
    pause();
    const restored={...structuredClone(persisted.current),jobs:draft?.jobs ?? persisted.current.jobs};
    latest.current=restored;setDraft(restored);dirtyRef.current=new Set();setDirty(new Set());form.reset();
    setUndoSelection(undefined);setClozeEditing(false);setMode('view');setLevel('easy');setActiveId(null);
    setPreview(false);setReadiness([]);setConfirmOpen(false);setSettings(false);setError('');setEditorEpoch(value=>value+1);
  }
  function play(id: string, atMs?: number) { const row = latest.current?.sentences.find(s => s.id === id); const at = atMs ?? row?.words[0]?.startMs ?? row?.previewStartMs; if (at == null) return; setActiveId(id); setSeek({ atMs: at, sequence: Date.now(), play: true }); }
  async function openPreview() { await save(); if (!published) { const status = await authoring.fetchReadiness(lessonId); setReadiness(status.blockers); } setPreview(true); }
  function bulk(activity: 'DICTATION' | 'SHADOWING', choice: 'all' | 'none' | 'suggested') {
    if (!draft) return;
    const field = activity === 'DICTATION' ? 'dictationSelected' : 'shadowingSelected';
    setUndoSelection({ field, values: new Map(draft.sentences.map(row => [row.id, row[field]])) });
    const rows = draft.sentences.map(row => ({ ...row, [field]: choice === 'all' ? !!row.words.length : choice === 'none' ? false : !!row.words.length && !row.exclusions?.[activity] }));
    rows.forEach(row => mark(row.id)); changeDraft({ sentences: rows }, '_selection');
  }
  function split(index: number) {
    const row = latest.current!.sentences[index], text = row.text, raw = window.prompt(t('finish.splitPrompt'), String(Math.floor(text.length / 2)));
    if (raw == null) return; const at = Number(raw); if (!Number.isInteger(at) || at < 1 || at >= text.length || !/\s/.test(text[at - 1] + text[at])) { setError(t('finish.splitBoundary')); return; }
    const left = text.slice(0, at).trim(), right = text.slice(at).trim(); if (!left || !right) return;
    const newRow = { ...row, id: `new:${crypto.randomUUID()}`, text: right, words: [], translation: null, ipa: null, vocabulary: [], grammar: [], cloze: undefined, clozeManual: false };
    const rows = [...latest.current!.sentences]; rows.splice(index, 1, { ...row, text: left }, newRow); changeDraft({ sentences: rows }, '_transcript');
  }
  useEffect(() => { const element=studioRef.current; if(!element) return; const update=()=>element.style.setProperty('--studio-left', `${element.getBoundingClientRect().left}px`); if(typeof ResizeObserver === 'undefined') return; const observer=new ResizeObserver(update); observer.observe(element); update(); return ()=>observer.disconnect(); },[!!draft]);
  if (query.isPending && !draft) return <LessonEditorSkeleton />;
  if (!draft) return <CatalogError error={query.error} retry={() => void query.refetch()} />;
  const options = draft.options ?? defaultContentOptions;
  const playbackRows = draft.sentences.filter(row => !preview || mode === 'view' || row[mode === 'DICTATION' ? 'dictationSelected' : 'shadowingSelected']);
  const playbackId = playbackRows.some(row => row.id === activeId) ? activeId : playbackRows.find(row => row.words.length)?.id ?? null;
  const needsUpdate = !draft.audioReady || draft.alignmentRequired || draft.contentNeeded || dirty.has('_transcript') || dirty.has('_options') || dirty.has('_voice');
  const nextSteps = !draft.audioReady || dirty.has('_voice') || draft.sourceType === 'TEXT' && dirty.has('_transcript') ? `${draft.sourceType === 'TEXT' ? 'TTS' : 'STT'} → alignment → ${t('finish.contentStep')}` : draft.alignmentRequired || dirty.has('_transcript') ? `alignment → ${t('finish.contentStep')}` : t('finish.missingFeatures');
  return <section ref={studioRef} className="lesson-studio">
    <UnsavedChangesGuard dirty={hasChanges} />
    <header className="studio-heading"><div><Link to="/lessons" className="text-sm text-muted-foreground">{t('catalog.lessons')}</Link><h1>{form.watch('title') || draft.title}</h1>{published && <Badge variant="success">{t("catalog.published")}</Badge>}</div><div className="studio-heading-actions"><Button variant="outline" disabled={busy || pending} onClick={() => setSettings(true)}><RiSettings3Line />{t('finish.settings')}</Button><Button variant="outline" onClick={() => setHistory(true)}><RiHistoryLine />{t('finish.lessonJobs')} <span className="studio-job-count">{draft.jobs.length}</span></Button><Button disabled={busy || pending} onClick={() => void perform(openPreview)}>{t(published ? 'finish.previewPublished' : 'finish.previewPublish')}</Button><DropdownMenu><DropdownMenuTrigger asChild><Button size="icon" variant="ghost" aria-label={t('catalog.actions')}><RiMoreLine /></Button></DropdownMenuTrigger><DropdownMenuContent>{published && <DropdownMenuItem disabled={busy || pending || hasChanges} onSelect={() => void perform(async () => { await setVisibility(lessonId,!metadata.data?.visible,metadata.data?.version);await metadata.refetch(); })}>{t(metadata.data?.visible ? "catalog.unlist" : "catalog.relist")}</DropdownMenuItem>}<DropdownMenuItem disabled={busy || pending || hasChanges} onSelect={() => void perform(async () => { const copy = await authoring.duplicateLesson(lessonId, draft.revision); navigate(`/lessons/${copy.id}`); })}>{t('finish.duplicate')}</DropdownMenuItem><DropdownMenuItem disabled={busy || pending} onSelect={() => openConfirm('delete')}>{t('finish.delete')}</DropdownMenuItem></DropdownMenuContent></DropdownMenu></div></header>
    <AuthoringWorkflow draft={draft} needsUpdate={needsUpdate} disabled={busy} onRetry={() => void perform(async () => { const next = await save(); await authoring.runLesson(lessonId, next.revision); const response = await query.refetch(); if (response.data) accept(response.data); })} onCancel={() => void perform(async () => { accept(await authoring.cancelChain(lessonId, latest.current!.revision)); void query.refetch(); })} />
    {(error || query.error) && <div role="alert" className="studio-error"><CatalogError error={query.error} />{error && <p>{error}</p>}<Button variant="outline" size="sm" onClick={() => { if (!hasChanges || window.confirm(t('catalog.unsaved'))) { dirtyRef.current = new Set(); setDirty(new Set()); void query.refetch(); } }}>{t('finish.reload')}</Button></div>}

    <div className="studio-workspace"><aside className="studio-player"><LessonPlayer draft={draft} sentences={playbackRows} seek={seek} controlsHost={preview ? previewControlsHost : controlsHost} onTimeChange={clock.set} onAvailabilityChange={setAvailable} onPlayingChange={playing => { if (playing && activeId !== playbackId) setActiveId(playbackId); }} review={{ sentenceId: playbackId, context: preview ? `preview-${mode}` : 'studio', onSentenceChange: setActiveId }} />{draft.statistics && draft.sentences.length > 0 && <p className="studio-statistics">{t('finish.statistics', { sentences: draft.statistics.sentenceCount, words: draft.statistics.wordCount, duration: timecode(draft.statistics.durationMs), pace: draft.statistics.wordsPerMinute ?? '—' })}</p>}</aside>
      <div className="studio-content">{draft.sentences.length > 0 && <><div className="studio-list-toolbar"><h2>{t('lessonAuthoring.transcriptTab')} <span>{draft.sentences.length}</span></h2><label className="finish-check"><Checkbox checked={showIpa} onCheckedChange={v => setShowIpa(v === true)} />{t('finish.showIpa')}</label><Button size="sm" variant={clozeEditing ? 'secondary' : 'ghost'} disabled={blocked || dirty.has('_transcript')} onClick={() => setClozeEditing(v => !v)}>{t('finish.editCloze')}</Button></div>
      {showIpa && <p className="studio-help">{t('finish.ipaHelp', { accent: options.accent })}</p>}
      <div className="studio-bulk">{(['DICTATION', 'SHADOWING'] as const).filter(a => options.activities.includes(a)).map(activity => <DropdownMenu key={activity}><DropdownMenuTrigger asChild><Button variant="outline" size="sm" disabled={blocked}>{t(`contentAuthoring.${activity}`)} · {draft.sentences.filter(row => row[activity === 'DICTATION' ? 'dictationSelected' : 'shadowingSelected']).length}</Button></DropdownMenuTrigger><DropdownMenuContent>{(['all', 'none', 'suggested'] as const).map(choice => <DropdownMenuItem key={choice} onSelect={() => bulk(activity, choice)}>{t(`finish.${choice}`)}</DropdownMenuItem>)}</DropdownMenuContent></DropdownMenu>)}{undoSelection && <Button size="sm" variant="ghost" disabled={blocked} onClick={() => { changeDraft({ sentences: latest.current!.sentences.map(row => ({ ...row, [undoSelection.field]: undoSelection.values.get(row.id) ?? row[undoSelection.field] })) }, '_selection'); setUndoSelection(undefined); }}>{t('finish.undo')}</Button>}{clozeEditing && <><Button size="sm" variant={level === 'easy' ? 'secondary' : 'ghost'} onClick={() => setLevel('easy')}>{t('finish.easy')}</Button><Button size="sm" variant={level === 'medium' ? 'secondary' : 'ghost'} onClick={() => setLevel('medium')}>{t('finish.medium')}</Button></>}</div>
      </>}
      <TimingEditor key={editorEpoch} onPlay={play} onPause={pause} revision={draft.revision} disabled={blocked || hasChanges} durationMs={draft.endMs - draft.startMs} onSave={async (id, words, revision) => perform(async () => { accept(await authoring.saveTiming(lessonId, revision, id, words)); })}>{editTiming => <TranscriptList sentences={draft.sentences} visibleRows={draft.sentences.map((_, i) => i)} filtered={false} pageScroll scrollToId={activeId} className="studio-sentences">{(sentence, index) => <article className="studio-sentence" data-active={activeId === sentence.id} key={sentence.id || `new-${index}`}><div className="studio-sentence-heading"><span>{index + 1}</span><span>{sentence.words.length ? `${timecode(sentence.words[0].startMs)} – ${timecode(sentence.words.at(-1)!.endMs)}` : t('finish.timingMissing')}</span>{sentence.contentStale && <Badge variant="warning">{t('contentAuthoring.stale')}</Badge>}<div><Button size="icon-sm" variant="ghost" aria-label={t('lessonAuthoring.seek')} disabled={!available || !sentence.words.length || dirty.has('_transcript')} onClick={() => play(sentence.id)}><RiPlayLine /></Button><Button size="icon-sm" variant="ghost" aria-label={t('authoringUi.editTiming')} title={hasChanges ? t('finish.saveBeforeTiming') : t('authoringUi.editTiming')} disabled={blocked || hasChanges || !sentence.words.length} onClick={() => editTiming(sentence)}><RiTimeLine /></Button><DropdownMenu><DropdownMenuTrigger asChild><Button size="icon-sm" variant="ghost" aria-label={t('catalog.actions')} disabled={blocked}><RiMoreLine /></Button></DropdownMenuTrigger><DropdownMenuContent><DropdownMenuItem onSelect={() => split(index)}>{t('lessonAuthoring.split')}</DropdownMenuItem><DropdownMenuItem disabled={index === draft.sentences.length - 1} onSelect={() => { const rows = [...draft.sentences]; rows.splice(index, 2, { ...sentence, text: `${sentence.text} ${rows[index + 1].text}` }); changeDraft({ sentences: rows }, '_transcript'); }}>{t('lessonAuthoring.merge')}</DropdownMenuItem></DropdownMenuContent></DropdownMenu></div></div>
      {activeId === sentence.id && !dirty.has('_transcript') && <div className="studio-karaoke"><PlaybackWords timing clock={clock} sentence={sentence} onSeek={atMs => play(sentence.id, atMs)} /></div>}
      <Textarea rows={1} className="studio-transcript-input" aria-label={t('lessonAuthoring.sentence', { number: index + 1 })} value={sentence.text} disabled={blocked} onChange={e => { if (sentence.id) changeSentence(sentence.id, { text: e.target.value }, false); else { const rows = [...draft.sentences]; rows[index] = { ...sentence, text: e.target.value }; changeDraft({ sentences: rows }, '_transcript'); } }} />
      {!!(sentence.vocabulary.length + sentence.grammar.length) && <AnnotatedTranscript sentence={sentence} />}
      {sentence.contentStale && !!sentence.manualFields?.length && <Button size="sm" variant="outline" disabled={blocked || dirty.has('_transcript')} onClick={()=>mark(sentence.id)}>{t('finish.keepManual')}</Button>}
      <SentenceContentEditor sentence={sentence} options={options} disabled={blocked || !sentence.id || dirty.has('_transcript')} showIpa={showIpa} onChange={value => changeSentence(sentence.id, value)} />
      {clozeEditing && sentence.clozeManual && <Button size="sm" variant="ghost" disabled={blocked} onClick={()=>changeSentence(sentence.id,{clozeManual:false})}>{t('finish.autoCloze')}</Button>}
      {clozeEditing && sentence.dictationSelected && <ClozePreview sentence={sentence} level={level} editable={!blocked} onChange={value => changeSentence(sentence.id, value)} />}
      {!!sentence.issues.length && <p className="studio-help">{sentence.issues.filter(issue => !['SHORT_SENTENCE', 'LONG_SENTENCE'].includes(issue)).map(describe).join(' · ')}</p>}
      </article>}</TranscriptList>}</TimingEditor>
      {!draft.sentences.length && pending ? <TranscriptSkeleton /> : !draft.sentences.length && <p className="py-8 text-muted-foreground">{t('finish.noTranscript')}</p>}
      </div></div>
    <footer className="studio-dock"><div ref={setControlsHost} className="studio-transport" />{!pending && <span role="status">{busy && <RiLoader4Line className="animate-spin size-3" aria-hidden="true" />}{busy ? t('finish.working') : hasChanges ? t('finish.unsaved') : t('finish.saved')}</span>}<div className="studio-dock-actions">{hasChanges && <Button variant="ghost" tooltip={t('library.discardHelp')} disabled={busy || pending} onClick={discardChanges}><RiArrowGoBackLine aria-hidden="true" />{t('library.discard')}</Button>}<Button variant="outline" disabled={busy || pending || !hasChanges || published && !metadataDirty} onClick={() => void perform(async () => { await save(); })}>{t('finish.save')} <kbd>⌘/Ctrl S</kbd></Button>{!pending && <Button disabled={blocked || !needsUpdate} onClick={() => openConfirm('run')}>{draft.audioReady ? t('finish.updateAffected') : t('finish.create')}</Button>}</div></footer>
    <Sheet open={settings} onOpenChange={setSettings}><SheetContent className="studio-sheet"><SheetHeader><SheetTitle>{t('finish.settings')}</SheetTitle><SheetDescription>{t('finish.settingsHelp')}</SheetDescription></SheetHeader><section><h3>{t('finish.lessonInfo')}</h3><fieldset disabled={busy || pending}><CreateMetadataFields form={form} /></fieldset><ContentProfile draft={draft} disabled={blocked} onChange={profile => changeDraft({ profile }, '_profile')} /></section>{draft.sourceType === 'TEXT' && <VoiceSelect value={draft.ttsVoice ?? ''} disabled={blocked} onChange={ttsVoice => changeDraft({ ttsVoice }, '_voice')} />}<ContentOptionsPanel draft={draft} disabled={blocked} onChange={options => changeDraft({ options }, '_options')} /><Button disabled={busy || pending || !hasChanges || published && !metadataDirty} onClick={() => void perform(async () => { await save(); setSettings(false); })}>{t('finish.save')}</Button></SheetContent></Sheet>
    <Sheet open={history} onOpenChange={setHistory}><SheetContent className="studio-sheet studio-history"><SheetHeader><SheetTitle>{t('finish.lessonJobs')}</SheetTitle><SheetDescription>{t('finish.historyHelp')}</SheetDescription></SheetHeader><ProcessingHistory lessonId={lessonId} jobs={draft.jobs} /></SheetContent></Sheet>
    <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}><DialogContent><DialogHeader><DialogTitle>{t(confirm === 'run' ? 'finish.updateAffected' : 'finish.delete')}</DialogTitle><DialogDescription>{confirm === 'run' ? t('finish.runSteps', { steps: nextSteps }) : t(published ? 'finish.softDeleteHelp' : 'finish.deleteHelp', { title: draft.title })}</DialogDescription></DialogHeader>{error && <p role="alert" className="text-destructive">{error}</p>}<Button variant={confirm === 'delete' ? 'destructive' : 'default'} disabled={busy} onClick={() => void perform(async () => { if (confirm === 'delete') { await deleteDraft(lessonId, metadata.data?.version); navigate('/lessons'); } else { const next = await save(); await authoring.runLesson(lessonId, next.revision); const response=await query.refetch(); if(response.data) accept(response.data); } setConfirmOpen(false); })}>{t(confirm === 'run' ? 'finish.runConfirm' : 'finish.delete')}</Button></DialogContent></Dialog>
    <Sheet open={preview} onOpenChange={setPreview}><SheetContent className="studio-sheet studio-preview"><SheetHeader><SheetTitle>{t(published ? 'finish.previewPublished' : 'finish.previewPublish')}</SheetTitle><SheetDescription>{t(published ? 'finish.publishedHelp' : 'finish.previewHelp')}</SheetDescription></SheetHeader><h2>{draft.title}</h2><p className="studio-help">{draft.statistics && t('finish.statistics', { sentences: draft.statistics.sentenceCount, words: draft.statistics.wordCount, duration: timecode(draft.statistics.durationMs), pace: draft.statistics.wordsPerMinute ?? '—' })} · {draft.profile?.cefr ?? 'UNKNOWN'} · {t(`contentAuthoring.types.${draft.profile?.contentType ?? 'UNKNOWN'}`)} · {options.activities.map(activity => `${t(`contentAuthoring.${activity}`)}: ${draft.sentences.filter(row => row[activity === 'DICTATION' ? 'dictationSelected' : 'shadowingSelected']).length}`).join(' · ')}</p>{metadata.data?.description && <p className="studio-help">{metadata.data.description}</p>}<div ref={setPreviewControlsHost} className="studio-preview-transport" /><div className="studio-bulk">{(['view', 'DICTATION', 'SHADOWING'] as const).filter(value => value === 'view' || options.activities.includes(value)).map(value => <Button size="sm" variant={mode === value ? 'secondary' : 'outline'} key={value} onClick={() => setMode(value)}>{t(value === 'view' ? 'finish.view' : `contentAuthoring.${value}`)}</Button>)}</div>{mode === 'DICTATION' && <div className="studio-bulk">{(['easy', 'medium', 'full'] as const).map(value => <Button size="sm" variant={level === value ? 'secondary' : 'ghost'} key={value} onClick={() => setLevel(value)}>{t(`finish.${value}`)}</Button>)}</div>}
    {draft.sentences.filter(s => mode === 'view' || s[mode === 'DICTATION' ? 'dictationSelected' : 'shadowingSelected']).map(sentence => <article className="studio-preview-sentence" key={sentence.id}><Button size="icon-sm" variant="ghost" aria-label={t('lessonAuthoring.seek')} disabled={!available} onClick={() => play(sentence.id)}><RiPlayLine /></Button>{mode === 'DICTATION' ? <ClozePreview sentence={sentence} level={level} /> : <PlaybackWords timing clock={clock} sentence={sentence} onSeek={atMs => play(sentence.id, atMs)} />}{mode === 'view' && <><p>{sentence.translation}</p><p className="finish-ipa">{sentence.ipa}</p><AnnotatedTranscript sentence={sentence} />{[...sentence.vocabulary, ...sentence.grammar].map((note, i) => <p key={i}><strong>{note.surface}</strong> · {note.explanation}</p>)}</>}</article>)}
    {error && <p role="alert" className="text-destructive">{error}</p>}{!!readiness.length && <div role="alert" className="studio-error"><strong>{t('finish.blockers')}</strong><ul>{readiness.map((value, i) => <li key={i}>{t(`finish.blocker.${value.split(':')[0]}`, { defaultValue: value })}{value.includes(':') ? ` #${value.split(':')[1]}` : ''}</li>)}</ul></div>}{!published && <p className="studio-help">{t('finish.warningsHelp')}</p>}{!published && <Button disabled={busy || pending || readiness.length > 0} onClick={() => void perform(async () => { accept(await authoring.publishLesson(lessonId, draft.revision)); setPreview(false); void metadata.refetch(); })}>{t('finish.publish')}</Button>}</SheetContent></Sheet>
  </section>;
}
