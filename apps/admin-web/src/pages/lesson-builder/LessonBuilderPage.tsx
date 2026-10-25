import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { ApiError } from '@/lib/api';
import { cancelJob } from '@/pages/jobs/jobs.api';
import { mergeSentence, splitSentence } from './authoring-editor';
import * as authoring from './lesson-builder.api';
import type {
  AuthoringJob,
  LessonDraft,
  SentenceUsage,
  SourceInspection,
  TranscriptSentence,
} from './lesson-builder.types';
import { YouTubePreview } from './YouTubePreview';

const labelCodes = [
  'TRANSCRIPT_REVIEW',
  'TIMING_REVIEW',
  'CONTENT_REVIEW',
  'PUBLISHED',
  'QUEUED',
  'RUNNING',
  'RETRY_WAIT',
  'CANCEL_REQUESTED',
  'CANCELLED',
  'SUCCEEDED',
  'FAILED',
  'DRAFT_VALID',
  'VALID',
  'MISSING',
  'STALE',
  'WHISPER_DRAFT',
  'PREPARE',
  'ALIGN',
  'SEMANTIC',
  'SOURCE_PREPARE',
  'TRANSCRIBING',
  'ALIGNING',
  'GENERATING_CONTENT',
  'CHECKPOINT_SAVED',
  'LONG_SENTENCE',
  'SHORT_SENTENCE',
  'TIMING_MISSING',
  'TIMING_OVERLAP',
  'TIMING_ZERO_DURATION',
  'GROUPED_TIMING',
] as const;
function useCodeLabel() {
  const { t } = useTranslation('admin');
  return (code: string) => {
    const known = labelCodes.find((value) => value === code);
    return known ? t(`lessonAuthoring.labels.${known}`) : code;
  };
}

const usages: SentenceUsage[] = ['LEARN', 'WATCH_ONLY', 'SKIP'];
const activeJob = (job: AuthoringJob) =>
  ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED'].includes(job.status);
const formatTime = (ms: number) =>
  `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;

function JobProgress({
  job,
  onCancel,
}: {
  job: AuthoringJob;
  onCancel: () => void;
}) {
  const { t, i18n } = useTranslation('admin');
  const describe = useCodeLabel();
  const [observedSeconds, setObservedSeconds] = useState(0);
  useEffect(() => {
    setObservedSeconds(0);
    if (!activeJob(job)) return;
    const start = Date.now();
    const timer = setInterval(
      () => setObservedSeconds(Math.floor((Date.now() - start) / 1000)),
      1000,
    );
    return () => clearInterval(timer);
  }, [job.jobId, job.currentStep, job.status]);
  return (
    <div
      className="flex flex-col gap-2 rounded-lg border border-border p-3 text-sm"
      role="status"
    >
      <div className="flex flex-wrap items-center gap-3">
        <strong>{describe(job.operation)}</strong>
        <span>{describe(job.status)}</span>
        <span>{job.currentStep ? describe(job.currentStep) : null}</span>
        {activeJob(job) ? (
          <>
            <span>
              {t('lessonAuthoring.observedTime', { seconds: observedSeconds })}
            </span>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={onCancel}
            >
              {t('lessonAuthoring.cancel')}
            </Button>
          </>
        ) : null}
        <Link to={`/jobs?job=${job.jobId}`}>
          {t('lessonAuthoring.inspectJob')}
        </Link>
      </div>
      {job.nextRetryAt && job.status === 'RETRY_WAIT' ? (
        <p>
          {t('lessonAuthoring.retryAt', {
            time: new Date(job.nextRetryAt).toLocaleString(i18n.language),
          })}
        </p>
      ) : null}
      {job.errorMessage ? (
        <p className="text-destructive">{job.errorMessage}</p>
      ) : null}
      {(job.usage ?? []).map((call, index) => (
        <p key={index} className="text-sm text-muted-foreground">
          {call.provider} / {call.model} ·{' '}
          {t('lessonAuthoring.usage', {
            input: call.inputTokens ?? '—',
            output: call.outputTokens ?? '—',
            seconds:
              call.latencyMs === undefined
                ? '—'
                : (call.latencyMs / 1000).toFixed(1),
          })}
        </p>
      ))}
    </div>
  );
}

export function LessonBuilderPage() {
  const { t } = useTranslation('admin');
  const describe = useCodeLabel();
  const navigate = useNavigate();
  const { lessonId } = useParams<{ lessonId: string }>();
  const [sourceReference, setSourceReference] = useState('');
  const [inspection, setInspection] = useState<SourceInspection | null>(null);
  const [title, setTitle] = useState('');
  const [startSeconds, setStartSeconds] = useState('0');
  const [endSeconds, setEndSeconds] = useState('120');
  const [rightsConfirmed, setRightsConfirmed] = useState(false);
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
  const [error, setError] = useState('');
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

  useEffect(() => {
    setDraft(null);
    latestDraftRef.current = null;
    editorRevisionRef.current = null;
    setDirty(false);
    dirtyRef.current = false;
    setCandidatesDirty(false);
    candidatesDirtyRef.current = false;
    setKaraokeConfirmed(false);
    setContentReviewed(false);
    setError('');
    if (!lessonId) return;
    let disposed = false;
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        const next = await authoring.fetchDraft(lessonId!);
        if (disposed) return;
        acceptDraft(next, !dirtyRef.current);
      } catch (cause) {
        if (!disposed)
          setError(cause instanceof Error ? cause.message : String(cause));
      }
      if (!disposed) timer = setTimeout(refresh, 2500);
    }
    void refresh();
    return () => {
      disposed = true;
      clearTimeout(timer);
    };
  }, [lessonId]);

  async function perform(action: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (cause) {
      setError(
        cause instanceof ApiError && cause.status === 409
          ? t('lessonAuthoring.conflict')
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

  async function inspect(event: FormEvent) {
    event.preventDefault();
    await perform(async () => {
      const next = await authoring.inspectSource(sourceReference.trim());
      setInspection(next);
      if (!title.trim()) setTitle(next.title);
      setEndSeconds(String(Math.min(next.durationMs / 1000, 120)));
    });
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    await perform(async () => {
      const startMs = Math.round(Number(startSeconds) * 1000);
      const endMs = Math.round(Number(endSeconds) * 1000);
      if (
        !title.trim() ||
        !inspection ||
        !rightsConfirmed ||
        !Number.isFinite(startMs) ||
        !Number.isFinite(endMs) ||
        startMs < 0 ||
        endMs <= startMs ||
        endMs > inspection.durationMs
      ) {
        throw new Error(t('lessonAuthoring.invalidRange'));
      }
      const result = await authoring.createDraft({
        title: title.trim(),
        sourceReference: sourceReference.trim(),
        startMs,
        endMs,
        rightsConfirmed,
      });
      navigate(`/lessons/${result.lessonId}`);
    });
  }

  const published = draft?.status === 'PUBLISHED';
  const pending = draft?.jobs.some(activeJob) ?? false;
  const blocked = busy || pending || published;
  const approved = draft
    ? draft.approvedRevision === draft.transcriptRevision
    : false;
  const timingValid = draft
    ? ['VALID', 'DRAFT_VALID'].includes(draft.timingStatus)
    : false;
  const karaokeReviewed = draft
    ? draft.karaokeReviewedRevision === draft.transcriptRevision
    : false;
  const semanticReady = draft
    ? draft.semanticRevision === draft.transcriptRevision
    : false;
  const startMs = Math.round(Number(startSeconds) * 1000);
  const endMs = Math.round(Number(endSeconds) * 1000);
  const overlap = inspection?.usedRanges.some(
    (range) => startMs < range.endMs && endMs > range.startMs,
  );

  function applyCandidates(mode: 'HIGH' | 'ALL') {
    if (!draft) return;
    const selected = (activity: 'dictation' | 'shadowing') =>
      draft.sentences
        .filter(
          (s) =>
            s.usage === 'LEARN' &&
            s[`${activity}Eligible`] &&
            (mode === 'ALL' || s[`${activity}Priority`] === 'HIGH'),
        )
        .map((s) => s.id);
    setDictationIds(selected('dictation'));
    setShadowingIds(selected('shadowing'));
    setCandidatesDirty(true);
    candidatesDirtyRef.current = true;
    setContentReviewed(false);
  }

  return (
    <section className="lesson-authoring flex flex-col gap-3">
      <div className="authoring-heading">
        <h1>{draft?.title ?? t('lessonAuthoring.title')}</h1>
        {!lessonId ? (
          <p className="text-sm text-muted-foreground">
            {t('lessonAuthoring.lead')}
          </p>
        ) : null}
      </div>
      {error ? (
        <p
          role="alert"
          className="rounded-lg border border-destructive p-3 text-sm text-destructive"
        >
          {error}
        </p>
      ) : null}
      {!lessonId ? (
        <>
          <Card>
            <CardHeader>
              <CardTitle>{t('lessonAuthoring.sourceTitle')}</CardTitle>
            </CardHeader>
            <CardContent>
              <form
                onSubmit={inspect}
                className="authoring-source-toolbar flex flex-col items-end gap-3 sm:flex-row"
              >
                <label className="flex w-full flex-1 flex-col gap-1">
                  {t('lessonAuthoring.youtubeUrl')}
                  <Input
                    type="url"
                    required
                    value={sourceReference}
                    onChange={(event) => {
                      setSourceReference(event.target.value);
                      setInspection(null);
                      setRightsConfirmed(false);
                    }}
                    placeholder="https://www.youtube.com/watch?v=…"
                  />
                </label>
                <Button
                  type="submit"
                  className="w-full sm:w-auto"
                  disabled={busy || !sourceReference.trim()}
                >
                  {t('lessonAuthoring.inspect')}
                </Button>
              </form>
            </CardContent>
          </Card>
          {inspection ? (
            <Card>
              <CardHeader>
                <CardTitle>{inspection.title}</CardTitle>
              </CardHeader>
              <CardContent>
                <form onSubmit={create} className="flex flex-col gap-3">
                  {!inspection.embedAllowed ? (
                    <p role="alert" className="text-destructive">
                      {t('lessonAuthoring.embedBlocked')}
                    </p>
                  ) : null}
                  <p>
                    {t('lessonAuthoring.duration', {
                      time: formatTime(inspection.durationMs),
                    })}
                  </p>
                  <div className="authoring-source-grid grid items-start gap-4 lg:grid-cols-[minmax(0,360px)_minmax(0,1fr)]">
                    {inspection.embedAllowed ? (
                      <YouTubePreview
                        videoId={inspection.videoId}
                        startMs={
                          Number.isFinite(startMs) ? Math.max(0, startMs) : 0
                        }
                        endMs={
                          Number.isFinite(endMs) && endMs > startMs
                            ? Math.min(endMs, inspection.durationMs)
                            : inspection.durationMs
                        }
                        sentences={[]}
                        seek={null}
                        onTimeChange={setCurrentMs}
                      />
                    ) : (
                      <div />
                    )}
                    <div className="flex flex-col gap-3">
                      <label className="flex flex-col gap-1">
                        {t('lessonAuthoring.lessonTitle')}
                        <Input
                          required
                          value={title}
                          onChange={(event) => setTitle(event.target.value)}
                        />
                      </label>
                      <div className="grid grid-cols-2 gap-3">
                        <label className="flex flex-col gap-1">
                          {t('lessonAuthoring.startSeconds')}
                          <Input
                            type="number"
                            min="0"
                            step="0.001"
                            required
                            value={startSeconds}
                            onChange={(event) =>
                              setStartSeconds(event.target.value)
                            }
                          />
                        </label>
                        <label className="flex flex-col gap-1">
                          {t('lessonAuthoring.endSeconds')}
                          <Input
                            type="number"
                            min="0"
                            max={inspection.durationMs / 1000}
                            step="0.001"
                            required
                            value={endSeconds}
                            onChange={(event) =>
                              setEndSeconds(event.target.value)
                            }
                          />
                        </label>
                      </div>
                      {overlap ? (
                        <p role="status" className="text-warning">
                          {t('lessonAuthoring.overlap')}
                        </p>
                      ) : null}
                      {inspection.usedRanges.length ? (
                        <div>
                          <h3>{t('lessonAuthoring.usedRanges')}</h3>
                          <ul>
                            {inspection.usedRanges.map((range) => (
                              <li key={range.lessonId}>
                                <Link to={`/lessons/${range.lessonId}`}>
                                  {range.title}
                                </Link>{' '}
                                · {formatTime(range.startMs)}–
                                {formatTime(range.endMs)} · {range.status}
                              </li>
                            ))}
                          </ul>
                        </div>
                      ) : null}
                      <label className="flex items-start gap-2">
                        <input
                          type="checkbox"
                          checked={rightsConfirmed}
                          onChange={(event) =>
                            setRightsConfirmed(event.target.checked)
                          }
                        />
                        {t('lessonAuthoring.rights')}
                      </label>
                      <Button
                        type="submit"
                        disabled={
                          busy || !rightsConfirmed || !inspection.embedAllowed
                        }
                      >
                        {t('lessonAuthoring.prepare')}
                      </Button>
                    </div>
                  </div>
                </form>
              </CardContent>
            </Card>
          ) : null}
        </>
      ) : !draft ? (
        <p role="status">{t('lessonAuthoring.loading')}</p>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <strong>
              {t('lessonAuthoring.status', { status: describe(draft.status) })}
            </strong>
            <span>
              {t('lessonAuthoring.revision', { revision: draft.revision })}
            </span>
            <Link to="/lessons/new">{t('lessonAuthoring.newLesson')}</Link>
            <Button
              type="button"
              variant="outline"
              disabled={busy}
              onClick={() =>
                void perform(async () => {
                  candidatesDirtyRef.current = false;
                  editorRevisionRef.current = null;
                  acceptDraft(await authoring.fetchDraft(draft.id));
                })
              }
            >
              {dirty || candidatesDirty
                ? t('lessonAuthoring.discardReload')
                : t('lessonAuthoring.refresh')}
            </Button>
          </div>
          {published ? (
            <p role="status" className="text-success">
              {t('lessonAuthoring.published')}
            </p>
          ) : null}
          <div className="grid gap-2">
            {draft.jobs.map((job) => (
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
            ))}
          </div>
          {!draft.sentences.length ? (
            <Card>
              <CardContent className="pt-6">
                <p>{t('lessonAuthoring.preparing')}</p>
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
                  {t('lessonAuthoring.retryPreparation')}
                </Button>
              </CardContent>
            </Card>
          ) : null}
          <div className="authoring-workspace grid items-start gap-3 xl:grid-cols-[minmax(0,360px)_minmax(0,1fr)]">
            <Card className="authoring-player xl:sticky xl:top-16">
              <CardHeader>
                <CardTitle>{t('lessonAuthoring.karaokeTitle')}</CardTitle>
              </CardHeader>
              <CardContent className="flex flex-col gap-3">
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
                  {formatTime(currentMs)} /{' '}
                  {formatTime(draft.endMs - draft.startMs)}
                </p>
                <p>
                  {t('lessonAuthoring.timing', {
                    status: describe(draft.timingStatus),
                  })}
                </p>
                <p className="text-sm text-muted-foreground">
                  {t('lessonAuthoring.reviewTiming')}
                </p>
                <label className="flex items-start gap-2">
                  <input
                    type="checkbox"
                    checked={karaokeConfirmed}
                    disabled={
                      blocked ||
                      dirty ||
                      !approved ||
                      !timingValid ||
                      !playerAvailable
                    }
                    onChange={(event) =>
                      setKaraokeConfirmed(event.target.checked)
                    }
                  />
                  {t('lessonAuthoring.karaokeConfirmed')}
                </label>
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
                        await authoring.reviewKaraoke(draft.id, draft.revision),
                      ),
                    )
                  }
                >
                  {karaokeReviewed
                    ? t('lessonAuthoring.karaokeApproved')
                    : t('lessonAuthoring.approveKaraoke')}
                </Button>
              </CardContent>
            </Card>
            <Tabs defaultValue="transcript" className="authoring-tabs min-w-0">
              <TabsList aria-label={t('lessonAuthoring.title')}>
                <TabsTrigger value="transcript">
                  {t('lessonAuthoring.transcriptTab')}
                </TabsTrigger>
                <TabsTrigger value="content">
                  {t('lessonAuthoring.contentTab')}
                </TabsTrigger>
              </TabsList>
              <TabsContent value="transcript">
                <Card>
                  <CardContent className="flex flex-col gap-3">
                    <p className="text-sm text-muted-foreground">
                      {t('lessonAuthoring.transcriptNote')}
                    </p>
                    <div className="authoring-editor-toolbar flex flex-wrap items-center gap-2">
                      <label className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          checked={issuesOnly}
                          onChange={(event) =>
                            setIssuesOnly(event.target.checked)
                          }
                        />
                        {t('lessonAuthoring.issuesOnly')}
                      </label>
                      <label className="flex items-center gap-2">
                        <input
                          type="checkbox"
                          disabled={blocked}
                          checked={
                            sentences.length > 0 &&
                            selectedRows.length === sentences.length
                          }
                          onChange={(event) =>
                            setSelectedRows(
                              event.target.checked
                                ? sentences.map((_, i) => i)
                                : [],
                            )
                          }
                        />
                        {t('lessonAuthoring.selectAll')}
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
                                <input
                                  type="checkbox"
                                  disabled={blocked}
                                  checked={selectedRows.includes(index)}
                                  onChange={(event) =>
                                    setSelectedRows(
                                      event.target.checked
                                        ? [...selectedRows, index]
                                        : selectedRows.filter(
                                            (i) => i !== index,
                                          ),
                                    )
                                  }
                                />
                                {t('lessonAuthoring.sentence', {
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
                                    'lessonAuthoring.sentenceUsage',
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
                                {t('lessonAuthoring.seek')}
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
                                      setError(t('lessonAuthoring.splitCaret'));
                                    else {
                                      updateSentences(next);
                                      setSelectedRows([]);
                                    }
                                  }}
                                >
                                  {t('lessonAuthoring.split')}
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
                                  {t('lessonAuthoring.merge')}
                                </Button>
                              </div>
                            </div>
                            <Textarea
                              className="authoring-sentence-text min-h-0"
                              rows={2}
                              aria-label={t('lessonAuthoring.sentenceText', {
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
                                aria-label={t('lessonAuthoring.wordTiming')}
                                className="flex flex-wrap gap-x-1"
                              >
                                {saved.words.map((word, wi) => (
                                  <span
                                    key={wi}
                                    className={
                                      currentMs >= word.startMs &&
                                      currentMs < word.endMs
                                        ? 'rounded bg-secondary text-secondary-foreground'
                                        : ''
                                    }
                                  >
                                    {word.text}
                                  </span>
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
                        {t('lessonAuthoring.unsaved')}
                      </p>
                    ) : null}
                    <div className="authoring-editor-actions flex flex-wrap gap-2">
                      <Button
                        type="button"
                        disabled={
                          blocked ||
                          !dirty ||
                          sentences.some((s) => !s.text.trim())
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
                        {t('lessonAuthoring.saveTranscript')}
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        disabled={
                          blocked || dirty || !sentences.length || approved
                        }
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
                          ? t('lessonAuthoring.transcriptApproved')
                          : t('lessonAuthoring.approveTranscript')}
                      </Button>
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
                          ? t('lessonAuthoring.realign')
                          : t('lessonAuthoring.align')}
                      </Button>
                    </div>
                  </CardContent>
                </Card>
              </TabsContent>
              <TabsContent value="content">
                <Card>
                  <CardContent className="flex flex-col gap-3">
                    <Button
                      type="button"
                      disabled={
                        blocked || dirty || !approved || !karaokeReviewed
                      }
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
                        ? t('lessonAuthoring.regenerate')
                        : t('lessonAuthoring.generate')}
                    </Button>
                    {!semanticReady ? (
                      <p className="text-muted-foreground">
                        {t('lessonAuthoring.generationGate')}
                      </p>
                    ) : (
                      <>
                        <div className="flex flex-wrap gap-3">
                          <Button
                            type="button"
                            variant="outline"
                            disabled={blocked || dirty}
                            onClick={() => applyCandidates('HIGH')}
                          >
                            {t('lessonAuthoring.selectHigh')}
                          </Button>
                          <Button
                            type="button"
                            variant="outline"
                            disabled={blocked || dirty}
                            onClick={() => applyCandidates('ALL')}
                          >
                            {t('lessonAuthoring.selectAllPriorities')}
                          </Button>
                        </div>
                        {draft.sentences
                          .filter((s) => s.usage === 'LEARN')
                          .map((sentence) => (
                            <div
                              key={sentence.id}
                              className="authoring-content-row flex flex-col gap-2 border-b border-border py-3 last:border-0"
                            >
                              <strong>{sentence.text}</strong>
                              <p>{sentence.translation}</p>
                              {(['vocabulary', 'grammar'] as const).map(
                                (kind) =>
                                  sentence[kind].length ? (
                                    <div key={kind}>
                                      <h3>{t(`lessonAuthoring.${kind}`)}</h3>
                                      <ul>
                                        {sentence[kind].map(
                                          (annotation, index) => (
                                            <li key={index}>
                                              <strong>
                                                {annotation.surface}
                                              </strong>{' '}
                                              · {annotation.explanation}
                                              {annotation.headwordId
                                                ? ` · #${annotation.headwordId}`
                                                : ''}
                                            </li>
                                          ),
                                        )}
                                      </ul>
                                    </div>
                                  ) : null,
                              )}
                              {sentence.hints.length ? (
                                <p>
                                  {t('lessonAuthoring.hints')}:{' '}
                                  {sentence.hints
                                    .map((note) => note.text)
                                    .join(' · ')}
                                </p>
                              ) : null}
                              {sentence.notes.length ? (
                                <p>
                                  {t('lessonAuthoring.notes')}:{' '}
                                  {sentence.notes
                                    .map((note) => note.text)
                                    .join(' · ')}
                                </p>
                              ) : null}
                              {sentence.thoughtGroups.length ? (
                                <p>
                                  {t('lessonAuthoring.thoughtGroups')}:{' '}
                                  {sentence.thoughtGroups
                                    .map((group) =>
                                      sentence.words
                                        .filter(
                                          (word) =>
                                            word.tokenStart >=
                                              group.tokenStart &&
                                            word.tokenEnd <= group.tokenEnd,
                                        )
                                        .map((word) => word.text)
                                        .join(' '),
                                    )
                                    .join(' / ')}
                                </p>
                              ) : null}
                              <div className="flex flex-wrap gap-4">
                                {(['dictation', 'shadowing'] as const).map(
                                  (activity) => {
                                    const ids =
                                      activity === 'dictation'
                                        ? dictationIds
                                        : shadowingIds;
                                    const setter =
                                      activity === 'dictation'
                                        ? setDictationIds
                                        : setShadowingIds;
                                    return (
                                      <label
                                        key={activity}
                                        className="flex items-center gap-2"
                                      >
                                        <input
                                          type="checkbox"
                                          checked={ids.includes(sentence.id)}
                                          disabled={
                                            blocked ||
                                            dirty ||
                                            !sentence[`${activity}Eligible`]
                                          }
                                          onChange={(event) => {
                                            setter(
                                              event.target.checked
                                                ? [...ids, sentence.id]
                                                : ids.filter(
                                                    (id) => id !== sentence.id,
                                                  ),
                                            );
                                            setCandidatesDirty(true);
                                            candidatesDirtyRef.current = true;
                                            setContentReviewed(false);
                                          }}
                                        />
                                        {t(`lessonAuthoring.${activity}`)} ·{' '}
                                        {sentence[`${activity}Priority`] ?? '—'}
                                        {!sentence[`${activity}Eligible`]
                                          ? ` · ${t('lessonAuthoring.ineligible')}`
                                          : ''}
                                      </label>
                                    );
                                  },
                                )}
                              </div>
                            </div>
                          ))}
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
                          {t('lessonAuthoring.saveCandidates')}
                        </Button>
                        <label className="flex items-start gap-2">
                          <input
                            type="checkbox"
                            checked={contentReviewed}
                            disabled={blocked || dirty || candidatesDirty}
                            onChange={(event) =>
                              setContentReviewed(event.target.checked)
                            }
                          />
                          {t('lessonAuthoring.contentReviewed')}
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
                          {t('lessonAuthoring.publish')}
                        </Button>
                      </>
                    )}
                  </CardContent>
                </Card>
              </TabsContent>
            </Tabs>
          </div>
        </>
      )}
    </section>
  );
}
