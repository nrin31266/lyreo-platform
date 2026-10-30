import "./lesson-builder.css";
import { RiRefreshLine as RefreshCw } from "@remixicon/react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { CreateLessonPage } from "./CreateLessonPage";
import { UnsavedChangesGuard } from "./UnsavedChangesGuard";
import { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";
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
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
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
  const { lessonId } = useParams<{ lessonId: string }>();
  const [draft, setDraft] = useState<LessonDraft | null>(null);
  const latestDraftRef = useRef<LessonDraft | null>(null);
  const [sentences, setSentences] = useState<TranscriptSentence[]>([]);
  const [editorRevision, setEditorRevision] = useState(0);
  const editorRevisionRef = useRef<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const dirtyRef = useRef(false);
  dirtyRef.current = dirty;
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
  const [contentReviewed, setContentReviewed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [inspectError, setInspectError] = useState("");
  const [currentMs, setCurrentMs] = useState(0);
  const [seek, setSeek] = useState<{ atMs: number; sequence: number } | null>(
    null,
  );
  const [splitOffsets, setSplitOffsets] = useState<Record<number, number>>({});

  function acceptDraft(next: LessonDraft, replaceEditor = true) {
    if (
      latestDraftRef.current?.id === next.id &&
      next.revision < latestDraftRef.current.revision
    )
      return;
    latestDraftRef.current = next;
    setDraft(next);
    if (replaceEditor && next.revision !== editorRevisionRef.current) {
      editorRevisionRef.current = next.revision;
      setContentReviewed(false);
      setKaraokeConfirmed(false);
      setSentences(
        next.sentences.map(({ id, text, usage }) => ({ id, text, usage })),
      );
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
    refetchInterval: (q) => (q.state.data?.jobs.some(activeJob) ? 2500 : false),
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
    } catch (cause) {
      setError(
        cause instanceof ApiError && cause.status === 409
          ? t("lessonAuthoring.conflict")
          : cause instanceof Error
            ? cause.message
            : String(cause),
      );
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

  const nextStep = published
    ? "lessonAuthoring.published"
    : pending
      ? "catalog.waitForJob"
      : dirty || candidatesDirty
        ? "catalog.saveBeforeContinue"
        : !draft?.sentences.length
          ? "catalog.prepareNext"
          : !approved
            ? "catalog.reviewTranscriptNext"
            : !timingValid
              ? "catalog.reviewTimingNext"
              : !karaokeReviewed
                ? "catalog.confirmKaraokeNext"
                : !semanticReady
                  ? "catalog.generateNext"
                  : "catalog.reviewCandidatesNext";

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

  const isHistoricalJob = (job: AuthoringJob) =>
    job.status === "SUCCEEDED" ||
    (["FAILED", "CANCELLED"].includes(job.status) &&
      (published || job !== draft?.jobs.at(-1)));
  const historyJobs = draft?.jobs.filter(isHistoricalJob) ?? [];
  function renderJob(job: AuthoringJob) {
    if (!draft) return null;
    return (
      <JobProgress
        key={job.jobId}
        job={job}
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

  return (
    <section className="authoring-page" data-editing={Boolean(lessonId)}>
      <UnsavedChangesGuard dirty={dirty || candidatesDirty} />
      <div className="authoring-heading">
        <h1>{draft?.title ?? t("lessonAuthoring.title")}</h1>
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
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <Badge variant="secondary">
              {t("lessonAuthoring.status", { status: describe(draft.status) })}
            </Badge>
            <span>
              {t("lessonAuthoring.revision", { revision: draft.revision })}
            </span>
            <Link to="/lessons/new">{t("lessonAuthoring.newLesson")}</Link>
            <Button
              type="button"
              variant="ghost"
              size={dirty || candidatesDirty ? "default" : "icon"}
              aria-label={t(dirty || candidatesDirty ? "lessonAuthoring.discardReload" : "lessonAuthoring.refresh")}
              title={t(dirty || candidatesDirty ? "lessonAuthoring.discardReload" : "lessonAuthoring.refresh")}
              disabled={busy}
              onClick={() =>
                void perform(async () => {
                  candidatesDirtyRef.current = false;
                  editorRevisionRef.current = null;
                  acceptDraft(await authoring.fetchDraft(draft.id));
                })
              }
            >
              <RefreshCw />
              {(dirty || candidatesDirty) && t("lessonAuthoring.discardReload")}
            </Button>
          </div>
          {published ? (
            <p role="status" className="text-success">
              {t("lessonAuthoring.published")}
            </p>
          ) : null}
          <div className="authoring-jobs grid gap-2">
            {draft.jobs.filter((job) => !isHistoricalJob(job)).map(renderJob)}
            {historyJobs.length ? (
              <Accordion type="single" collapsible>
                <AccordionItem value="job-history">
                  <AccordionTrigger className="py-2">
                    <span className="flex items-center gap-2">
                      {t("lessonAuthoring.jobHistory")}
                      <Badge variant="secondary">{historyJobs.length}</Badge>
                    </span>
                  </AccordionTrigger>
                  <AccordionContent>
                    <div className="grid gap-2">
                      {historyJobs.map(renderJob)}
                    </div>
                  </AccordionContent>
                </AccordionItem>
              </Accordion>
            ) : null}
          </div>
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
          >
            <ResizablePanel
              defaultSize="33%"
              minSize="25%"
              className="authoring-player-pane"
              id="authoring-player"
              tabIndex={-1}
            >
              <div className="authoring-player">
                <header className="authoring-pane-heading">
                  <h2>{t("lessonAuthoring.karaokeTitle")}</h2>
                </header>
                <div className="flex flex-col gap-3">
                  <YouTubePreview
                    videoId={draft.videoId}
                    startMs={draft.startMs}
                    endMs={draft.endMs}
                    sentences={dirty ? [] : draft.sentences}
                    seek={seek}
                    onTimeChange={setCurrentMs}
                    onAvailabilityChange={setPlayerAvailable}
                  />
                  <p>
                    {formatTime(currentMs)} /{" "}
                    {formatTime(draft.endMs - draft.startMs)}
                  </p>
                  <p>
                    {t("lessonAuthoring.timing", {
                      status: describe(draft.timingStatus),
                    })}
                  </p>
                </div>
              </div>
            </ResizablePanel>
            <ResizableHandle
              withHandle
              aria-label={t("lessonAuthoring.resizePanels")}
            />
            <ResizablePanel
              defaultSize="67%"
              minSize="40%"
              className="authoring-editor-pane"
            >
              <Tabs
                value={editorTab}
                onValueChange={setEditorTab}
                className="authoring-tabs min-w-0"
              >
                <TabsList aria-label={t("lessonAuthoring.title")}>
                  <TabsTrigger value="transcript">
                    {t("lessonAuthoring.transcriptTab")}
                  </TabsTrigger>
                  <TabsTrigger value="timing">
                    {t("catalog.step_timing")}
                  </TabsTrigger>
                  <TabsTrigger value="content">
                    {t("lessonAuthoring.contentTab")}
                  </TabsTrigger>
                </TabsList>
                <TabsContent
                  value="transcript"
                  className="authoring-transcript-tab"
                >
                  <div className="authoring-transcript-card">
                    <div className="authoring-transcript-body flex flex-col gap-3">
                      <p className="text-sm text-muted-foreground">
                        {t("lessonAuthoring.transcriptNote")}
                      </p>
                      <div className="authoring-editor-toolbar flex flex-wrap items-center gap-2">
                        <label className="flex items-center gap-2">
                          <Checkbox
                            checked={issuesOnly}
                            onCheckedChange={(checked) =>
                              setIssuesOnly(checked === true)
                            }
                          />
                          {t("lessonAuthoring.issuesOnly")}
                        </label>
                        <label className="flex items-center gap-2">
                          <Checkbox
                            disabled={blocked}
                            checked={
                              sentences.length > 0 &&
                              selectedRows.length === sentences.length
                            }
                            onCheckedChange={(checked) =>
                              setSelectedRows(
                                checked === true
                                  ? sentences.map((_, i) => i)
                                  : [],
                              )
                            }
                          />
                          {t("lessonAuthoring.selectAll")}
                        </label>
                        {usages.map((usage) => (
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
                      <div className="authoring-sentence-list divide-y divide-border">
                        {sentences.map((sentence, index) => {
                          const saved = draft.sentences.find(
                            (s) => s.id === sentence.id,
                          );
                          if (issuesOnly && !saved?.issues.length) return null;
                          return (
                            <div
                              key={sentence.id ?? `new-${index}`}
                              className="authoring-sentence-row flex flex-col gap-2 py-3 first:pt-0 last:pb-0"
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
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="sm"
                                  disabled={dirty || !saved?.words.length}
                                  onClick={() =>
                                    setSeek({
                                      atMs: saved!.words[0].startMs,
                                      sequence: Date.now(),
                                    })
                                  }
                                >
                                  {t("lessonAuthoring.seek")}
                                </Button>
                                <div className="authoring-sentence-actions flex flex-wrap gap-2">
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    disabled={blocked}
                                    onClick={() => {
                                      const offset = splitOffsets[index] ?? 0;
                                      const next = splitSentence(
                                        sentences,
                                        index,
                                        offset,
                                      );
                                      if (next === sentences)
                                        setError(
                                          t("lessonAuthoring.splitCaret"),
                                        );
                                      else {
                                        updateSentences(next);
                                        setSelectedRows([]);
                                      }
                                    }}
                                  >
                                    {t("lessonAuthoring.split")}
                                  </Button>
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    disabled={
                                      blocked ||
                                      index === sentences.length - 1 ||
                                      sentence.usage !==
                                        sentences[index + 1]?.usage
                                    }
                                    onClick={() => {
                                      updateSentences(
                                        mergeSentence(sentences, index),
                                      );
                                      setSelectedRows([]);
                                    }}
                                  >
                                    {t("lessonAuthoring.merge")}
                                  </Button>
                                </div>
                              </div>
                              <Textarea
                                className="authoring-sentence-text min-h-0"
                                rows={2}
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
                              {!dirty && saved?.words.length ? (
                                <p
                                  aria-label={t("lessonAuthoring.wordTiming")}
                                  className="flex flex-wrap gap-x-1"
                                >
                                  {saved.words.map((word, wi) => (
                                    <button
                                      type="button"
                                      key={wi}
                                      title={`${timecode(word.startMs)} – ${timecode(word.endMs)}`}
                                      onClick={() =>
                                        setSeek({
                                          atMs: word.startMs,
                                          sequence: Date.now(),
                                        })
                                      }
                                      className={
                                        currentMs >= word.startMs &&
                                        currentMs < word.endMs
                                          ? "rounded bg-secondary text-secondary-foreground"
                                          : ""
                                      }
                                    >
                                      {word.text}
                                    </button>
                                  ))}
                                </p>
                              ) : null}
                              {saved?.issues.length ? (
                                <ul className="text-sm text-warning">
                                  {saved.issues.map((issue, i) => (
                                    <li key={i}>{describe(issue)}</li>
                                  ))}
                                </ul>
                              ) : null}
                            </div>
                          );
                        })}
                      </div>
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
                    <p className="text-sm text-muted-foreground">
                      {draft.alignmentRequired
                        ? t("waveform.alignmentRequired")
                        : t("lessonAuthoring.reviewTiming")}
                    </p>
                    <label className="flex items-start gap-2">
                      <Checkbox
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
                      {t("lessonAuthoring.karaokeConfirmed")}
                    </label>
                    <div className="authoring-sentence-list">
                      {draft.sentences.map((sentence) => (
                        <button
                          type="button"
                          key={sentence.id}
                          className="timing-sentence"
                          onClick={() =>
                            setSeek({
                              atMs: sentence.words[0]?.startMs ?? 0,
                              sequence: Date.now(),
                            })
                          }
                        >
                          <span>{sentence.text}</span>
                          <span className="text-xs text-muted-foreground">
                            {sentence.words.length
                              ? timecode(sentence.words[0].startMs)
                              : t("lessonAuthoring.labels.TIMING_MISSING")}
                          </span>
                        </button>
                      ))}
                    </div>
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
              </Tabs>
            </ResizablePanel>
          </ResizablePanelGroup>
          <footer className="authoring-actionbar">
            <p className="text-xs text-muted-foreground">
              {!published && t(nextStep)}
            </p>
            <div className="authoring-action-buttons">
              {editorTab === "transcript" && (
                <div className="authoring-editor-actions flex flex-wrap gap-2">
                  <Button
                    type="button"
                    disabled={
                      blocked || !dirty || sentences.some((s) => !s.text.trim())
                    }
                    onClick={() =>
                      void perform(async () =>
                        acceptDraft(
                          await authoring.saveTranscript(
                            draft.id,
                            editorRevision,
                            sentences,
                          ),
                        ),
                      )
                    }
                  >
                    {t("lessonAuthoring.saveTranscript")}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={blocked || dirty || !sentences.length || approved}
                    onClick={() =>
                      void perform(async () =>
                        acceptDraft(
                          await authoring.approveTranscript(
                            draft.id,
                            draft.revision,
                          ),
                        ),
                      )
                    }
                  >
                    {approved
                      ? t("lessonAuthoring.transcriptApproved")
                      : t("lessonAuthoring.approveTranscript")}
                  </Button>
                </div>
              )}
              {editorTab === "timing" && (
                <div className="authoring-editor-actions">
                  {" "}
                  <Button
                    type="button"
                    variant="outline"
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
                  </Button>{" "}
                  <Button
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
                  </Button>
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
                    </div>
                  )}
                </div>
              )}
            </div>
          </footer>
        </>
      )}
    </section>
  );
}
