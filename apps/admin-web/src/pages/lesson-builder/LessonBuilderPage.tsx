import { SourceSummary } from "./SourceSummary";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem } from "@/components/ui/dropdown-menu";
import { RiMore2Line, RiPlayLine, RiScissorsLine, RiMergeCellsHorizontal, RiSaveLine, RiAddLine, RiTimeLine, RiInformationLine } from "@remixicon/react";
import { useJobConnection } from "@/lib/realtime/JobRealtimeProvider";
import { TranscriptList } from "./TranscriptList";
import { createPlaybackClock, PlaybackTime } from "./playback-clock";
import { usePanelRef } from "react-resizable-panels";
import { RiContractLeftLine as PanelLeftClose, RiExpandLeftLine as PanelLeftOpen, RiArrowUpSLine as ChevronUp } from "@remixicon/react";
import { TimingEditor } from "./TimingEditor";
import "./lesson-builder.css";
import { RiRefreshLine as RefreshCw } from "@remixicon/react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { CreateLessonPage } from "./CreateLessonPage";
import { UnsavedChangesGuard } from "./UnsavedChangesGuard";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";
import { Sheet, SheetTrigger, SheetContent, SheetHeader, SheetTitle, SheetDescription } from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
import { Skeleton } from "@/components/ui/skeleton";
import {
  ResizablePanelGroup,
  ResizablePanel,
  ResizableHandle,
} from "@/components/ui/resizable";
import { ApiError } from "@/lib/api";
import { cancelJob } from "@/pages/jobs/jobs.api";
import {
  mergeSentence,
  splitSentence,
  thoughtGroupPreview,
} from "./authoring-editor";
import * as authoring from "./lesson-builder.api";
import type {
  AuthoringJob,
  LessonDraft,
  SentenceUsage,
  TranscriptSentence,
} from "./lesson-builder.types";
import { timecode } from "./timecode";
import { YouTubePreview } from "./YouTubePreview";

import {
  JobProgress,
  authoringJobErrorKey,
  JobDuration,
  useCodeLabel,
  activeJob,
  formatTime,
} from "./AuthoringJobProgress";
const usages: SentenceUsage[] = ["LEARN", "WATCH_ONLY", "SKIP"];

export function LessonBuilderPage() {
  const { lessonId } = useParams();
  return lessonId ? <LessonEditorPage key={lessonId} /> : <CreateLessonPage />;
}
function LessonEditorPage() {
  const { t } = useTranslation(["admin", "common"]);
  const describe = useCodeLabel();
  const connection = useJobConnection();
  const { lessonId } = useParams<{ lessonId: string }>();
  const [draft, setDraft] = useState<LessonDraft | null>(null);
  const latestDraftRef = useRef<LessonDraft | null>(null);
  const [sentences, setSentences] = useState<TranscriptSentence[]>([]);
  const [editorRevision, setEditorRevision] = useState(0);
  const editorRevisionRef = useRef<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  dirtyRef.current = dirty;
  const [activeSentenceId, setActiveSentenceId] = useState<string | null>(null);
  const [selectedRows, setSelectedRows] = useState<number[]>([]);
  const [issuesOnly, setIssuesOnly] = useState(false);
  const [dictationIds, setDictationIds] = useState<string[]>([]);
  const [shadowingIds, setShadowingIds] = useState<string[]>([]);
  const [candidatesDirty, setCandidatesDirty] = useState(false);
  const [candidateRevision, setCandidateRevision] = useState(0);
  const candidatesDirtyRef = useRef(false);
  candidatesDirtyRef.current = candidatesDirty;
  const [karaokeConfirmed, setKaraokeConfirmed] = useState(false);
  const [playerAvailable, setPlayerAvailable] = useState(false);
  const [playbackHost, setPlaybackHost] = useState<HTMLDivElement | null>(null);
  const [contentReviewed, setContentReviewed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [inspectError, setInspectError] = useState("");
  const [clock] = useState(createPlaybackClock);
  const [seek, setSeek] = useState<{ atMs: number; sequence: number; play?: boolean } | null>(
    null,
  );
  const [playerCollapsed, setPlayerCollapsed] = useState(false);
  const playerPanel = usePanelRef();
  const navigatedDraft = useRef<string | undefined>(undefined);
  const previousCheckpoint = useRef("");
  const [splitOffsets, setSplitOffsets] = useState<Record<number, number>>({});

  function acceptDraft(next: LessonDraft, replaceEditor = true) {
    if (
      latestDraftRef.current?.id === next.id &&
      next.revision < latestDraftRef.current.revision
    )
      return;
    if(latestDraftRef.current?.id!==next.id) {setSeek(null);clock.set(0);}
    latestDraftRef.current = next;
    setDraft(next);
    if (replaceEditor && next.revision !== editorRevisionRef.current) {
      editorRevisionRef.current = next.revision;
      setContentReviewed(false);
      setKaraokeConfirmed(false);
      setSentences(
        next.sentences.map(({ id, text, usage }) => ({ id, text, usage })),
      );
      setActiveSentenceId(old => next.sentences.some(sentence => sentence.id === old) ? old : next.sentences[0]?.id ?? null);
      setEditorRevision(next.revision);
      setDirty(false);
      dirtyRef.current = false;
      setSelectedRows([]);
    }
    if (!candidatesDirtyRef.current) {
      setCandidateRevision(next.revision);
      setDictationIds(
        next.sentences.filter((s) => s.dictationSelected).map((s) => s.id),
      );
      setShadowingIds(
        next.sentences.filter((s) => s.shadowingSelected).map((s) => s.id),
      );
      setCandidatesDirty(false);
      candidatesDirtyRef.current = false;
    }
  }

  const client = useQueryClient();
  const query = useQuery({
    queryKey: ["authoring", lessonId],
    queryFn: ({ signal }) => authoring.fetchDraft(lessonId!, signal),
    staleTime: 0,
    refetchInterval: (q) => (q.state.data?.jobs.some(activeJob) ? connection === "connected" ? 30000 : 2500 : false),
  });
  useEffect(() => {
    if (query.data) acceptDraft(query.data, !dirtyRef.current);
  }, [query.data, query.dataUpdatedAt]);
  useEffect(() => {
    if (query.error) setError(query.error.message);
  }, [query.error]);
  const [mobilePane, setMobilePane] = useState("content");
  const [editorTab, setEditorTab] = useState("transcript");
  const actionMutation = useMutation({
    mutationFn: (action: () => Promise<void>) => action(),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["catalog"] });
      if (latestDraftRef.current)
        client.setQueryData(["authoring", lessonId], latestDraftRef.current);
    },
  });

  async function perform(action: () => Promise<void>) {
    setBusy(true);
    setError("");
    try {
      await actionMutation.mutateAsync(action);
      return true;
    } catch (cause) {
      setError(
        cause instanceof ApiError && cause.status === 409
          ? t("lessonAuthoring.conflict")
          : cause instanceof Error
            ? cause.message
            : String(cause),
      );
      return false;
    } finally {
      setBusy(false);
    }
  }

  function updateSentences(next: TranscriptSentence[]) {
    setSentences(next);
    setDirty(true);
    dirtyRef.current = true;
    setKaraokeConfirmed(false);
    setContentReviewed(false);
    setCandidatesDirty(false);
    candidatesDirtyRef.current = false;
  }

  const published = draft?.status === "PUBLISHED";
  const pending = draft?.jobs.some(activeJob) ?? false;
  const blocked = busy || pending || published;
  const approved = draft
    ? draft.approvedRevision === draft.transcriptRevision
    : false;
  const timingValid = draft
    ? ["VALID", "DRAFT_VALID"].includes(draft.timingStatus) &&
      !draft.alignmentRequired
    : false;
  const karaokeReviewed = draft
    ? draft.karaokeReviewedRevision === draft.transcriptRevision
    : false;
  const semanticReady = draft
    ? draft.semanticRevision === draft.transcriptRevision
    : false;

  const sourceReview = ["source", "transcript", "timing"].includes(editorTab);
  function playSentence(id: string, atMs?: number) {
    const sentence=draft?.sentences.find(row=>row.id===id);
    const start=atMs ?? sentence?.words[0]?.startMs ?? sentence?.previewStartMs;
    if(start==null)return;
    setActiveSentenceId(id);
    setSeek(old=>({atMs:start,sequence:(old?.sequence??0)+1,play:true}));
  }
  function pausePreview() {
    setSeek(old=>({atMs:clock.get(),sequence:(old?.sequence??0)+1,play:false}));
  }
  const checkpoint = published ? "publish" : !approved || (!timingValid && !karaokeReviewed) ? "transcript" : !karaokeReviewed ? "timing" : "content";
  useEffect(() => {
    if (!draft) return;
    if (navigatedDraft.current !== draft.id) {
      navigatedDraft.current = draft.id; previousCheckpoint.current = checkpoint; setEditorTab(checkpoint);
    } else if (previousCheckpoint.current !== checkpoint) {
      const previous = previousCheckpoint.current; previousCheckpoint.current = checkpoint;
      if (!dirtyRef.current && !candidatesDirtyRef.current) setEditorTab(current => current === previous ? checkpoint : current);
    }
  }, [draft?.id, checkpoint]);

  async function saveApproveAlign() {
    if (!draft) return;
    await perform(async () => {
      let next = draft;
      if (dirty) { next = await authoring.saveTranscript(next.id, editorRevision, sentences); acceptDraft(next); }
      if (next.approvedRevision !== next.transcriptRevision) { next = await authoring.approveTranscript(next.id, next.revision); acceptDraft(next); }
      if (next.alignmentRequired || (next.timingStatus !== 'VALID' && !(next.timingStatus === 'DRAFT_VALID' && next.alignmentRequired === false))) {
        await authoring.alignTranscript(next.id, next.revision);
        acceptDraft(await authoring.fetchDraft(next.id));
      }
      setKaraokeConfirmed(false); setEditorTab('timing');
    });
  }

  function applyCandidates(mode: "HIGH" | "ALL") {
    if (!draft) return;
    const selected = (activity: "dictation" | "shadowing") =>
      draft.sentences
        .filter(
          (s) =>
            s.usage === "LEARN" &&
            s[`${activity}Eligible`] &&
            (mode === "ALL" || s[`${activity}Priority`] === "HIGH"),
        )
        .map((s) => s.id);
    setDictationIds(selected("dictation"));
    setShadowingIds(selected("shadowing"));
    setCandidatesDirty(true);
    candidatesDirtyRef.current = true;
    setContentReviewed(false);
  }

  const transcriptIssuesById = new Map((draft?.sentences ?? []).map(sentence => [sentence.id,
    sentence.issues.filter(issue => !issue.startsWith("TIMING_") && issue !== "GROUPED_TIMING")]));
  const problemSentenceCount = sentences.filter(sentence => transcriptIssuesById.get(sentence.id ?? "")?.length).length;
  const visibleRows = sentences.flatMap((sentence, index) =>
    !issuesOnly || transcriptIssuesById.get(sentence.id ?? "")?.length ? [index] : []);

  const isHistoricalJob = (job: AuthoringJob) =>
    job.status === "SUCCEEDED" ||
    (["FAILED", "CANCELLED"].includes(job.status) &&
      (published || job !== draft?.jobs.at(-1)));
  const historyJobs = draft?.jobs.filter(isHistoricalJob) ?? [];
  const summaryJob = draft?.jobs.filter(activeJob).at(-1) ?? draft?.jobs.at(-1);
  function renderJob(job: AuthoringJob, historical = false) {
    if (!draft) return null;
    return (
      <JobProgress
        key={job.jobId}
        job={job}
        historical={historical}
        onRetry={job === draft.jobs.at(-1) && ["FAILED","CANCELLED"].includes(job.status) && !blocked && !dirty && !candidatesDirty ? () => void perform(async () => {
          const retry = job.operation === "PREPARE" ? authoring.prepareTranscript : job.operation === "ALIGN" ? authoring.alignTranscript : authoring.generateSemantic;
          await retry(draft.id,draft.revision);acceptDraft(await authoring.fetchDraft(draft.id));
        }) : undefined}
        onCancel={() =>
          void perform(async () => {
            await cancelJob(job.jobId);
            acceptDraft(
              await authoring.fetchDraft(draft.id),
              !dirtyRef.current,
            );
          })
        }
      />
    );
  }

  const stepNavigation = draft ? (
                <div className="authoring-tabbar">
                  <TabsList aria-label={t("lessonAuthoring.title")}>
                    <TabsTrigger value="source">1. {t("authoringUi.steps.source")}</TabsTrigger>
                    <TabsTrigger value={editorTab === 'timing' ? 'timing' : 'transcript'}>2. {t("authoringFlow.wordsTiming")}</TabsTrigger>
                    <TabsTrigger value="content" disabled={!karaokeReviewed || dirty} title={!karaokeReviewed ? t("catalog.confirmKaraokeNext") : undefined}>3. {t("authoringUi.steps.content")}</TabsTrigger>
                    <TabsTrigger value="publish" disabled={!semanticReady || dirty} title={!semanticReady ? t("catalog.generateNext") : undefined}>4. {t("authoringUi.steps.publish")}</TabsTrigger>
                  </TabsList>

                  <Tooltip><TooltipTrigger asChild><Button size="icon" variant="ghost" aria-label={t(playerCollapsed ? "authoringUi.showPlayer" : "authoringUi.hidePlayer")} aria-expanded={!playerCollapsed} aria-controls="authoring-player" onClick={() => { if (playerPanel.current?.isCollapsed()) playerPanel.current.expand(); else playerPanel.current?.collapse(); }}>{playerCollapsed ? <PanelLeftOpen /> : <PanelLeftClose />}</Button></TooltipTrigger><TooltipContent>{t(playerCollapsed ? "authoringUi.showPlayer" : "authoringUi.hidePlayer")}</TooltipContent></Tooltip>
                </div>
  ) : null;

  return (
    <Tabs asChild value={editorTab} onValueChange={setEditorTab}>
    <section className="authoring-page" data-editing={Boolean(lessonId)} data-review={sourceReview} data-stage={editorTab}>
      <UnsavedChangesGuard dirty={dirty || candidatesDirty} />
      <div className="authoring-heading">
        <h1 title={draft?.title}>{draft?.title ?? t("lessonAuthoring.title")}</h1>
        {!lessonId ? (
          <p className="text-sm text-muted-foreground">
            {t("lessonAuthoring.lead")}
          </p>
        ) : null}
      </div>
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}
      {!draft ? (
        query.isError ? <CatalogError error={query.error} retry={() => void query.refetch()} /> :
        <div role="status" aria-label={t("lessonAuthoring.loading")} className="grid gap-4">
          <Skeleton className="h-8 w-2/3" />
          <Skeleton className="h-64 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : (
        <>
          <div className="authoring-draft-meta flex flex-wrap items-center gap-2 text-sm">
            {!sourceReview && <Badge variant="secondary">
              {t("lessonAuthoring.status", { status: describe(draft.status) })}
            </Badge>}
            <span>
              {t("lessonAuthoring.revision", { revision: draft.revision })}
            </span>
            {sourceReview ? <div className="review-document-actions">
              <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={blocked || !dirty} aria-label={t('lessonAuthoring.saveTranscript')} onClick={() => void perform(async () => acceptDraft(await authoring.saveTranscript(draft.id,editorRevision,sentences)))}><RiSaveLine /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.saveTranscript')}</TooltipContent></Tooltip>
              <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={busy} aria-label={t(dirty || candidatesDirty ? 'lessonAuthoring.discardReload' : 'lessonAuthoring.refresh')} onClick={() => void perform(async () => { candidatesDirtyRef.current=false;editorRevisionRef.current=null;acceptDraft(await authoring.fetchDraft(draft.id)); })}><RefreshCw /></Button></TooltipTrigger><TooltipContent>{t(dirty || candidatesDirty ? 'lessonAuthoring.discardReload' : 'lessonAuthoring.refresh')}</TooltipContent></Tooltip>
              {timingValid && <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={blocked || dirty || !approved} aria-label={t('lessonAuthoring.realign')} onClick={() => void perform(async () => { await authoring.alignTranscript(draft.id,draft.revision);acceptDraft(await authoring.fetchDraft(draft.id));setKaraokeConfirmed(false); })}><RiTimeLine /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.realign')}</TooltipContent></Tooltip>}
              <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" asChild><Link to="/lessons/new" aria-label={t('lessonAuthoring.newLesson')}><RiAddLine /></Link></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.newLesson')}</TooltipContent></Tooltip>
            </div> : <>
            <DropdownMenu><Tooltip delayDuration={400}><TooltipTrigger asChild><DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={t('authoringFlow.lessonActions')}><RiMore2Line /></Button></DropdownMenuTrigger></TooltipTrigger><TooltipContent>{t('authoringFlow.lessonActions')}</TooltipContent></Tooltip><DropdownMenuContent align="end"><DropdownMenuGroup>
              <DropdownMenuItem disabled={blocked || !dirty} onSelect={() => void perform(async () => acceptDraft(await authoring.saveTranscript(draft.id,editorRevision,sentences)))}>{t('lessonAuthoring.saveTranscript')}</DropdownMenuItem>
              <DropdownMenuItem disabled={busy} onSelect={() => void perform(async () => {
                candidatesDirtyRef.current=false;editorRevisionRef.current=null;acceptDraft(await authoring.fetchDraft(draft.id));
              })}>{t(dirty || candidatesDirty ? 'lessonAuthoring.discardReload' : 'lessonAuthoring.refresh')}</DropdownMenuItem>
              {sourceReview && timingValid && <DropdownMenuItem disabled={blocked || dirty || !approved} onSelect={() => void perform(async () => { await authoring.alignTranscript(draft.id, draft.revision); acceptDraft(await authoring.fetchDraft(draft.id)); setKaraokeConfirmed(false); })}>{t("lessonAuthoring.realign")}</DropdownMenuItem>}
              <DropdownMenuItem asChild><Link to="/lessons/new">{t('lessonAuthoring.newLesson')}</Link></DropdownMenuItem>
            </DropdownMenuGroup></DropdownMenuContent></DropdownMenu>
            </>}
          </div>
          {published ? (
            <p role="status" className="text-success">
              {t("lessonAuthoring.published")}
            </p>
          ) : null}
          {!draft.sentences.length ? (
            <Alert>
              <AlertDescription className="flex flex-wrap items-center gap-2">
                <p>{t("lessonAuthoring.preparing")}</p>
                <Button
                  type="button"
                  disabled={blocked}
                  onClick={() =>
                    void perform(async () => {
                      await authoring.prepareTranscript(
                        draft.id,
                        draft.revision,
                      );
                      acceptDraft(await authoring.fetchDraft(draft.id));
                    })
                  }
                >
                  {t("lessonAuthoring.retryPreparation")}
                </Button>
              </AlertDescription>
            </Alert>
          ) : null}

          {sourceReview && stepNavigation}
          <div className="workspace-pane-switch">
            <Button
              variant={mobilePane === "player" ? "secondary" : "outline"}
              onClick={() => setMobilePane("player")}
            >
              {t("catalog.player")}
            </Button>
            <Button
              variant={mobilePane === "content" ? "secondary" : "outline"}
              onClick={() => setMobilePane("content")}
            >
              {t("catalog.editor")}
            </Button>
          </div>

          <ResizablePanelGroup
            orientation="horizontal"
            className="authoring-workspace"
            data-mobile-pane={mobilePane}
            data-player-collapsed={playerCollapsed}
          >
            <ResizablePanel
              panelRef={playerPanel}
              collapsible
              collapsedSize="0%"
              defaultSize="40%"
              minSize={sourceReview ? 240 : "25%"}
              onResize={size => setPlayerCollapsed(size.asPercentage === 0)}
              className="authoring-player-pane"
              id="authoring-player"
              tabIndex={-1}
            >
              <div className="authoring-player" style={{display: playerCollapsed ? "none" : undefined}}>
                <header className="authoring-pane-heading">
                  <h2>{t(sourceReview ? "reviewWorkspace.listenAlong" : "catalog.player")}</h2>
                  {sourceReview && <span className="text-xs text-muted-foreground tabular-nums">{timecode(draft.startMs)}–{timecode(draft.endMs)}</span>}
                </header>
                <div className="flex flex-col gap-3">
                  <YouTubePreview
                    videoId={draft.videoId}
                    startMs={draft.startMs}
                    endMs={draft.endMs}
                    sentences={dirty ? [] : draft.sentences}
                    seek={seek}
                    review={sourceReview && editorTab!=='source' ? {sentenceId:activeSentenceId,context:`${draft.id}:${draft.revision}:${editorTab}`,onSentenceChange:setActiveSentenceId} : undefined}
                    controlsHost={sourceReview ? playbackHost : null}
                    onTimeChange={clock.set}
                    onAvailabilityChange={setPlayerAvailable}
                  />

                  {!sourceReview && <p><PlaybackTime clock={clock} /> / {timecode(draft.endMs - draft.startMs)}</p>}
                  {!sourceReview && activeSentenceId && <section className="authoring-active-sentence" aria-label={t('authoringFlow.activeSentence')}><strong className="text-xs text-muted-foreground">{t('lessonAuthoring.sentence',{number:sentences.findIndex(sentence=>sentence.id===activeSentenceId)+1})}</strong><p>{sentences.find(sentence=>sentence.id===activeSentenceId)?.text}</p><span className="text-xs text-muted-foreground">{dirty ? t('lessonAuthoring.unsaved') : t('lessonAuthoring.timing',{status:describe(draft.timingStatus)})}</span></section>}


                </div>
              </div>
            </ResizablePanel>
            <ResizableHandle
              withHandle
              aria-label={t("lessonAuthoring.resizePanels")}
            />
            <ResizablePanel
              defaultSize="60%"
              minSize={sourceReview ? 360 : "40%"}
              className="authoring-editor-pane"
            >
              <div className="authoring-tabs min-w-0">
                {!sourceReview && stepNavigation}
                {(editorTab === 'transcript' || editorTab === 'timing') && <div className="authoring-review-modes" role="group" aria-label={t('authoringFlow.reviewMode')}>
                  <Button size="sm" variant={editorTab === 'transcript' ? 'secondary' : 'ghost'} aria-pressed={editorTab === 'transcript'} onClick={() => setEditorTab('transcript')}>{t('authoringFlow.editWords')}</Button>
                  <Button size="sm" variant={editorTab === 'timing' ? 'secondary' : 'ghost'} aria-pressed={editorTab === 'timing'} disabled={!approved || dirty} onClick={() => setEditorTab('timing')}>{t('authoringFlow.listenReview')}</Button>
                  <span className="review-sentence-count">{t("reviewWorkspace.sentenceCount", { count: sentences.length })}</span>
                  {editorTab==='transcript' && <span className="ml-auto text-xs text-muted-foreground">{t("reviewWorkspace.wordsHint")}</span>}
                </div>}
                <TabsContent value="source" className="authoring-source-tab">
                  <SourceSummary draft={draft} />
                </TabsContent>
                <TabsContent
                  value="transcript"
                  className="authoring-transcript-tab"
                >
                  <div className="authoring-transcript-card">
                    <div className="authoring-transcript-body flex flex-col gap-3">

                      <div className="authoring-editor-toolbar flex flex-wrap items-center gap-2">
                        <Button type="button" size="sm" variant={issuesOnly ? "secondary" : "outline"} aria-pressed={issuesOnly}
                          onClick={() => { setIssuesOnly(!issuesOnly); setSelectedRows([]); }}>
                          {t("reviewWorkspace.transcriptSuggestions", { count: problemSentenceCount })}
                        </Button>
                        <label className="flex items-center gap-2">
                          <Checkbox
                            disabled={blocked}
                            checked={
                              visibleRows.length > 0 &&
                              visibleRows.every(index => selectedRows.includes(index))
                            }
                            onCheckedChange={(checked) =>
                              setSelectedRows(
                                checked === true
                                  ? visibleRows
                                  : [],
                              )
                            }
                          />
                          {t("lessonAuthoring.selectAll")}
                        </label>
                        {!!selectedRows.length && usages.map((usage) => (
                          <Button
                            key={usage}
                            type="button"
                            size="sm"
                            variant="outline"
                            disabled={blocked || !selectedRows.length}
                            onClick={() =>
                              updateSentences(
                                sentences.map((sentence, index) =>
                                  selectedRows.includes(index)
                                    ? { ...sentence, usage }
                                    : sentence,
                                ),
                              )
                            }
                          >
                            {t(`lessonAuthoring.usages.${usage}`)}
                          </Button>
                        ))}
                      </div>
                      <TranscriptList sentences={sentences} visibleRows={visibleRows} filtered={issuesOnly} emptyMessage={issuesOnly ? t("authoringUi.noProblemSentences") : undefined}>
                        {(sentence, index) => {
                          const saved = draft.sentences.find(
                            (s) => s.id === sentence.id,
                          );
                          const transcriptIssues = transcriptIssuesById.get(sentence.id ?? "") ?? [];
                          const previewStartMs = saved?.previewStartMs ?? saved?.words[0]?.startMs;
                          return (
                            <div
                              key={sentence.id ?? sentence.editorKey ?? `new-${index}`}
                              className="authoring-sentence-row flex flex-col gap-2 border-b border-border py-3"
                              data-active={activeSentenceId === sentence.id}
                              onFocusCapture={() => setActiveSentenceId(sentence.id ?? null)}
                            >
                              <div className="authoring-sentence-heading flex flex-wrap items-center gap-2">
                                <label className="flex items-center gap-2">
                                  <Checkbox
                                    disabled={blocked}
                                    checked={selectedRows.includes(index)}
                                    onCheckedChange={(checked) =>
                                      setSelectedRows(
                                        checked === true
                                          ? [...selectedRows, index]
                                          : selectedRows.filter(
                                              (i) => i !== index,
                                            ),
                                      )
                                    }
                                  />
                                  {t("lessonAuthoring.sentence", {
                                    number: index + 1,
                                  })}
                                </label>
                                <span className="authoring-sentence-time text-xs text-muted-foreground tabular-nums">{dirty ? t("reviewWorkspace.timingAfterSave") : saved?.words.length ? `${timecode(saved.words[0].startMs)}–${timecode(saved.words.at(-1)!.endMs)}` : previewStartMs != null ? `≈ ${timecode(previewStartMs)}` : t('authoringFlow.noTiming')}</span>
                                <Select
                                  value={sentence.usage}
                                  disabled={blocked}
                                  onValueChange={(usage) =>
                                    updateSentences(
                                      sentences.map((s, i) =>
                                        i === index
                                          ? {
                                              ...s,
                                              usage: usage as SentenceUsage,
                                            }
                                          : s,
                                      ),
                                    )
                                  }
                                >
                                  <SelectTrigger
                                    className="authoring-sentence-usage w-auto min-w-32"
                                    aria-label={t(
                                      "lessonAuthoring.sentenceUsage",
                                      { number: index + 1 },
                                    )}
                                  >
                                    <SelectValue />
                                  </SelectTrigger>
                                  <SelectContent>
                                    <SelectGroup>
                                      {usages.map((usage) => (
                                        <SelectItem value={usage} key={usage}>
                                          {t(`lessonAuthoring.usages.${usage}`)}
                                        </SelectItem>
                                      ))}
                                    </SelectGroup>
                                  </SelectContent>
                                </Select>
                                <Tooltip delayDuration={400}><TooltipTrigger asChild>
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="sm"
                                  disabled={dirty || previewStartMs == null}
                                  title={!saved?.words.length && previewStartMs != null ? t("authoringUi.segmentSeek") : undefined}
                                  onClick={() => { if(sentence.id)playSentence(sentence.id); }}
                                >
                                  <RiPlayLine aria-hidden="true" /><span className="sr-only">{t("lessonAuthoring.seek")}</span>
                                </Button></TooltipTrigger><TooltipContent>{t("lessonAuthoring.seek")}</TooltipContent></Tooltip>
                                <Tooltip><TooltipTrigger asChild><Button type="button" variant="ghost" size="icon-sm" aria-label={t('lessonAuthoring.split')} disabled={blocked} onClick={() => {
                                  const next=splitSentence(sentences,index,splitOffsets[index] ?? 0);
                                  if(next===sentences)setError(t('lessonAuthoring.splitCaret'));
                                  else { updateSentences(next);setSelectedRows([]); }
                                }}><RiScissorsLine /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.split')}</TooltipContent></Tooltip>
                                <Tooltip><TooltipTrigger asChild><Button type="button" variant="ghost" size="icon-sm" aria-label={t('lessonAuthoring.merge')} disabled={blocked || index===sentences.length-1 || sentence.usage!==sentences[index+1]?.usage} onClick={() => { updateSentences(mergeSentence(sentences,index));setSelectedRows([]); }}><RiMergeCellsHorizontal /></Button></TooltipTrigger><TooltipContent>{t('lessonAuthoring.merge')}</TooltipContent></Tooltip>
                              </div>
                              <Textarea
                                className="authoring-sentence-text min-h-0"
                                rows={1}
                                aria-label={t("lessonAuthoring.sentenceText", {
                                  number: index + 1,
                                })}
                                disabled={blocked}
                                value={sentence.text}
                                onSelect={(event) => {
                                  const offset =
                                    event.currentTarget.selectionStart;
                                  setSplitOffsets((previous) => ({
                                    ...previous,
                                    [index]: offset,
                                  }));
                                }}
                                onChange={(event) =>
                                  updateSentences(
                                    sentences.map((s, i) =>
                                      i === index
                                        ? { ...s, text: event.target.value }
                                        : s,
                                    ),
                                  )
                                }
                              />
                              {transcriptIssues.length ? (
                                <ul className="text-sm text-warning">
                                  {transcriptIssues.map((issue, i) => (
                                    <li key={i}>{describe(issue)}</li>
                                  ))}
                                </ul>
                              ) : null}
                            </div>
                          );
                        }}
                      </TranscriptList>
                      {dirty ? (
                        <p role="status" className="text-warning">
                          {t("lessonAuthoring.unsaved")}
                        </p>
                      ) : null}
                    </div>
                  </div>
                </TabsContent>
                <TabsContent value="timing" className="authoring-timing-tab">
                  <div className="timing-review-body">
                    {!draft.sentences.some(sentence => sentence.words.length) ? <div className="review-timing-empty"><h2>{t("reviewWorkspace.noTimingTitle")}</h2><p>{t("reviewWorkspace.noTimingHelp")}</p></div> : <>
                    {draft.alignmentRequired && <p className="text-sm text-warning">{t("waveform.alignmentRequired")}</p>}
                    <TimingEditor activeSentenceId={activeSentenceId} onPlay={playSentence} onPause={pausePreview} revision={draft.revision} sentences={draft.sentences} clock={clock} durationMs={draft.endMs-draft.startMs} disabled={blocked || dirty || !approved} onSave={async (sentenceId, words, revision) => perform(async () => acceptDraft(await authoring.saveTiming(draft.id, revision, sentenceId, words)))} />
                    <div className="timing-review-confirm"><label className="flex items-center gap-2">
                      <Checkbox
                        aria-label={t("lessonAuthoring.karaokeConfirmed")}
                        checked={karaokeConfirmed}
                        disabled={
                          blocked ||
                          dirty ||
                          !approved ||
                          !timingValid ||
                          !playerAvailable
                        }
                        onCheckedChange={(checked) =>
                          setKaraokeConfirmed(checked === true)
                        }
                      />
                      <span>{t("reviewWorkspace.confirmTiming")}</span>
                      </label><Tooltip><TooltipTrigger asChild><Button type="button" size="icon-sm" variant="ghost" className="timing-confirm-help" aria-label={t('lessonAuthoring.reviewTiming')}><RiInformationLine /></Button></TooltipTrigger><TooltipContent className="max-w-sm">{t('lessonAuthoring.reviewTiming')} {t('lessonAuthoring.karaokeConfirmed')}</TooltipContent></Tooltip>
                    </div>
                    </>}

                  </div>
                </TabsContent>
                <TabsContent value="content" className="authoring-content-tab">
                  <div className="authoring-content-card">
                    <div className="authoring-content-body flex flex-col gap-3">
                      {!semanticReady ? (
                        <p className="text-muted-foreground">
                          {t("lessonAuthoring.generationGate")}
                        </p>
                      ) : (
                        <>
                          <div className="flex flex-wrap gap-3">
                            <Button
                              type="button"
                              variant="outline"
                              disabled={blocked || dirty}
                              onClick={() => applyCandidates("HIGH")}
                            >
                              {t("lessonAuthoring.selectHigh")}
                            </Button>
                            <Button
                              type="button"
                              variant="outline"
                              disabled={blocked || dirty}
                              onClick={() => applyCandidates("ALL")}
                            >
                              {t("lessonAuthoring.selectAllPriorities")}
                            </Button>
                          </div>
                          <div className="authoring-content-list">
                            {draft.sentences
                              .filter((s) => s.usage === "LEARN")
                              .map((sentence) => (
                                <div
                                  key={sentence.id}
                                  className="authoring-content-row flex flex-col gap-2 border-b border-border py-3 last:border-0"
                                >
                                  <strong>{sentence.text}</strong>
                                  <p>{sentence.translation}</p>
                                  {(["vocabulary", "grammar"] as const).map(
                                    (kind) =>
                                      sentence[kind].length ? (
                                        <div key={kind}>
                                          <h3>
                                            {t(`lessonAuthoring.${kind}`)}
                                          </h3>
                                          <ul>
                                            {sentence[kind].map(
                                              (annotation, index) => (
                                                <li key={index}>
                                                  <strong>
                                                    {annotation.surface}
                                                  </strong>{" "}
                                                  · {annotation.explanation}
                                                  {annotation.headwordId
                                                    ? ` · #${annotation.headwordId}`
                                                    : ""}
                                                </li>
                                              ),
                                            )}
                                          </ul>
                                        </div>
                                      ) : null,
                                  )}
                                  {sentence.hints.length ? (
                                    <p>
                                      {t("lessonAuthoring.hints")}:{" "}
                                      {sentence.hints
                                        .map((note) => note.text)
                                        .join(" · ")}
                                    </p>
                                  ) : null}
                                  {sentence.notes.length ? (
                                    <p>
                                      {t("lessonAuthoring.notes")}:{" "}
                                      {sentence.notes
                                        .map((note) => note.text)
                                        .join(" · ")}
                                    </p>
                                  ) : null}
                                  {sentence.thoughtGroups.length ? (
                                    <p>
                                      {t("lessonAuthoring.thoughtGroups")}:{" "}
                                      {thoughtGroupPreview(sentence)}
                                    </p>
                                  ) : null}
                                  <div className="flex flex-wrap gap-4">
                                    {(["dictation", "shadowing"] as const).map(
                                      (activity) => {
                                        const ids =
                                          activity === "dictation"
                                            ? dictationIds
                                            : shadowingIds;
                                        const setter =
                                          activity === "dictation"
                                            ? setDictationIds
                                            : setShadowingIds;
                                        return (
                                          <label
                                            key={activity}
                                            className="flex items-center gap-2"
                                          >
                                            <Checkbox
                                              checked={ids.includes(
                                                sentence.id,
                                              )}
                                              disabled={
                                                blocked ||
                                                dirty ||
                                                !sentence[`${activity}Eligible`]
                                              }
                                              onCheckedChange={(checked) => {
                                                setter(
                                                  checked === true
                                                    ? [...ids, sentence.id]
                                                    : ids.filter(
                                                        (id) =>
                                                          id !== sentence.id,
                                                      ),
                                                );
                                                setCandidatesDirty(true);
                                                candidatesDirtyRef.current = true;
                                                setContentReviewed(false);
                                              }}
                                            />
                                            {t(`lessonAuthoring.${activity}`)} ·{" "}
                                            {sentence[`${activity}Priority`] ??
                                              "—"}
                                            {!sentence[`${activity}Eligible`]
                                              ? ` · ${t("lessonAuthoring.ineligible")}`
                                              : ""}
                                          </label>
                                        );
                                      },
                                    )}
                                  </div>
                                </div>
                              ))}
                          </div>
                        </>
                      )}
                    </div>
                  </div>
                </TabsContent>
                <TabsContent value="publish" className="authoring-publish-tab">
                    <section className="publication-checklist">
                      <h3 className="font-medium">{t("authoringUi.review")}</h3>
                      {[
                        [t("lessonAuthoring.transcriptTab"),approved && !dirty],
                        [t("catalog.step_timing"),timingValid && karaokeReviewed],
                        [t("lessonAuthoring.contentTab"),semanticReady],
                        [t("authoringUi.activities"),!candidatesDirty],
                      ].map(([label,ready])=><div key={String(label)} className="flex items-center justify-between gap-2 text-sm"><span>{label}</span><Badge variant={ready ? "success" : "warning"}>{t(ready ? "authoringUi.ready" : "authoringUi.blocked")}</Badge></div>)}
                    </section>
                  <p className="text-sm text-muted-foreground">{t("authoringUi.selectedCounts", {d: dictationIds.length, s: shadowingIds.length})}</p>
                </TabsContent>
              </div>
            </ResizablePanel>
          </ResizablePanelGroup>
          <footer className="authoring-actionbar">
            <div className="authoring-footer-status">
              <span className="authoring-connection text-xs text-muted-foreground" hidden={sourceReview} role="status">{t(`authoringFlow.connection_${connection}`)} · {t('authoringFlow.updatedAt',{time:query.dataUpdatedAt ? new Date(query.dataUpdatedAt).toLocaleTimeString() : '—'})}</span>
              {summaryJob && <Sheet>
                <SheetTrigger asChild>
                  <Button variant="ghost" size="sm" className="min-w-0 max-w-full justify-start px-0" aria-label={t("authoringUi.processingHistory")}>
                    <ChevronUp data-icon="inline-start" />
                    <span className="truncate">{describe(summaryJob.operation)}</span>
                    <Badge variant={summaryJob.status === "FAILED" ? "destructive" : summaryJob.status === "SUCCEEDED" ? "success" : "secondary"}>{describe(summaryJob.status)}</Badge>
                    <JobDuration job={summaryJob} />
                  </Button>
                </SheetTrigger>
                <SheetContent side="bottom" className="authoring-history-sheet max-h-[60dvh] overflow-hidden">
                  <SheetHeader><SheetTitle>{t("authoringUi.processingHistory")}</SheetTitle><SheetDescription>{t("authoringUi.jobCount", {count: draft.jobs.length})}</SheetDescription></SheetHeader>
                  <div className="authoring-job-details grid auto-rows-max gap-2" role="region" aria-label={t("authoringUi.jobDetails")} tabIndex={0}>
                    {draft.jobs.filter(job => !isHistoricalJob(job)).map(job => renderJob(job))}
                    {historyJobs.length > 0 && <section className="grid auto-rows-max gap-2">
                      <h3 className="text-sm font-medium text-muted-foreground">{t("lessonAuthoring.jobHistory")} · {historyJobs.length}</h3>
                      {[...historyJobs].reverse().map(job => renderJob(job, true))}
                    </section>}
                  </div>
                </SheetContent>
              </Sheet>}
            {sourceReview && summaryJob?.status === "FAILED" && ["PREPARE", "ALIGN"].includes(summaryJob.operation) && <p className="review-processing-error" role="alert">{t(`lessonAuthoring.jobErrors.${authoringJobErrorKey(summaryJob)}`, { defaultValue: t("reviewWorkspace.processingFailedHelp") })}</p>}
            </div>
            {sourceReview && <div className="review-footer-playback" ref={setPlaybackHost}><span className="review-footer-clock"><PlaybackTime clock={clock} /> / {timecode(draft.endMs-draft.startMs)}</span></div>}
            <div className="authoring-action-buttons">
              {editorTab === 'transcript' && <Button type="button" disabled={blocked || !sentences.length || sentences.some(sentence => !sentence.text.trim())} onClick={() => void saveApproveAlign()}>{t(approved && !dirty && !timingValid ? 'lessonAuthoring.align' : 'authoringFlow.saveApproveAlign')}</Button>}
              {editorTab === "timing" && (
                <div className="authoring-editor-actions">
                  {" "}
                  {!timingValid && <Button
                    type="button"
                    disabled={blocked || dirty || !approved}
                    onClick={() =>
                      void perform(async () => {
                        await authoring.alignTranscript(
                          draft.id,
                          draft.revision,
                        );
                        acceptDraft(await authoring.fetchDraft(draft.id));
                        setKaraokeConfirmed(false);
                      })
                    }
                  >
                    {timingValid
                      ? t("lessonAuthoring.realign")
                      : t("lessonAuthoring.align")}
                  </Button>}
                  {timingValid && <Button
                    type="button"
                    disabled={
                      blocked ||
                      dirty ||
                      !approved ||
                      !timingValid ||
                      !playerAvailable ||
                      !karaokeConfirmed ||
                      karaokeReviewed
                    }
                    onClick={() =>
                      void perform(async () =>
                        acceptDraft(
                          await authoring.reviewKaraoke(
                            draft.id,
                            draft.revision,
                          ),
                        ),
                      )
                    }
                  >
                    {karaokeReviewed
                      ? t("lessonAuthoring.karaokeApproved")
                      : t("lessonAuthoring.approveKaraoke")}
                  </Button>}
                </div>
              )}
              {editorTab === "content" && (
                <div className="authoring-content-actions">
                  {" "}
                  <Button
                    type="button"
                    disabled={blocked || dirty || !approved || !karaokeReviewed}
                    onClick={() =>
                      void perform(async () => {
                        await authoring.generateSemantic(
                          draft.id,
                          draft.revision,
                        );
                        setContentReviewed(false);
                        acceptDraft(await authoring.fetchDraft(draft.id));
                      })
                    }
                  >
                    {semanticReady
                      ? t("lessonAuthoring.regenerate")
                      : t("lessonAuthoring.generate")}
                  </Button>
                  {semanticReady && (
                    <div className="authoring-content-actions">
                      <Button
                        type="button"
                        variant="outline"
                        disabled={blocked || dirty || !candidatesDirty}
                        onClick={() =>
                          void perform(async () => {
                            const next = await authoring.saveCandidates(
                              draft.id,
                              candidateRevision,
                              dictationIds,
                              shadowingIds,
                            );
                            candidatesDirtyRef.current = false;
                            acceptDraft(next);
                          })
                        }
                      >
                        {t("lessonAuthoring.saveCandidates")}
                      </Button>
                      <Button type="button" variant="outline" onClick={() => setEditorTab("publish")}>{t("authoringUi.steps.publish")}</Button>
                    </div>
                  )}
                </div>
              )}
              {editorTab === "source" && <Button onClick={() => setEditorTab("transcript")}>{t("authoringUi.steps.transcript")}</Button>}
              {editorTab === "publish" && semanticReady && <div className="authoring-content-actions">
                      <label className="flex items-start gap-2">
                        <Checkbox
                          checked={contentReviewed}
                          disabled={blocked || dirty || candidatesDirty}
                          onCheckedChange={(checked) =>
                            setContentReviewed(checked === true)
                          }
                        />
                        {t("lessonAuthoring.contentReviewed")}
                      </label>
                      <Button
                        type="button"
                        disabled={
                          blocked ||
                          dirty ||
                          candidatesDirty ||
                          !contentReviewed ||
                          !karaokeReviewed
                        }
                        onClick={() =>
                          void perform(async () =>
                            acceptDraft(
                              await authoring.publishLesson(
                                draft.id,
                                draft.revision,
                              ),
                            ),
                          )
                        }
                      >
                        {t("lessonAuthoring.publish")}
                      </Button>
              </div>}
            </div>
          </footer>
        </>
      )}
    </section>
    </Tabs>
  );
}
