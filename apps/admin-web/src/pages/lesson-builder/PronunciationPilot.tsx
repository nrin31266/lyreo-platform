import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { RiLoader4Line, RiMicLine, RiPauseLine, RiPlayLine, RiStopLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { AudioPlayer } from '@/components/AudioPlayer';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { useIpaAudio } from '@/pages/phonetics/useIpaAudio';
import { PronunciationFeedback, wordPlaybackRange } from './PronunciationFeedback';
import { PlaybackWords, type PlaybackClock } from './playback-clock';
import type { AuthoringSentence } from './lesson-builder.types';
import { ApiError } from '@/lib/api';
import { useJobConnection } from '@/lib/realtime/JobRealtimeProvider';
import { recordingPreview } from './recording-preview';
import {
  cancelPronunciation, fetchPronunciation, fetchPronunciationAudio, fetchPronunciationLimits, submitPronunciation,
  type AcceptedPronunciation, type PronunciationSpan, type PronunciationSummary,
} from './pronunciation.api';

type Capture = { stop: () => void; discard: () => void };

const terminal = (status: string | undefined) => status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED';

function LoadingStatus({ children, placeholder = false }: { children: ReactNode; placeholder?: boolean }) {
  return <div className="grid gap-2">
    <p role="status" className="flex items-center gap-2 text-muted-foreground">
      <RiLoader4Line aria-hidden="true" className="size-4 shrink-0 motion-safe:animate-spin" />{children}
    </p>
    {placeholder && <Skeleton aria-hidden="true" className="h-12 rounded-lg motion-reduce:animate-none" />}
  </div>;
}

export function PronunciationPilot({ lessonId, sentence, referenceClock, referencePlaying, revision, currentRevision, accepted, onAccepted, onSummary, playReference, pauseReference, onConflict }: {
  lessonId: string;
  sentence: AuthoringSentence;
  referenceClock: PlaybackClock;
  referencePlaying: boolean;
  revision: number;
  currentRevision: number;
  accepted?: AcceptedPronunciation;
  onAccepted: (value?: AcceptedPronunciation) => void;
  onSummary: (sentenceId: string, attemptId: string, summary: PronunciationSummary) => void;
  playReference?: (atMs?: number) => void;
  pauseReference: () => void;
  onConflict: () => void;
}) {
  const { t } = useTranslation('admin');
  const sentenceId = sentence.id;
  const ipaAudio = useIpaAudio();
  const connection = useJobConnection();
  const inputId = useId();
  const [upload, setUpload] = useState<{ file: File; key: string; captured: boolean }>();
  const [remaining, setRemaining] = useState<number>();
  const [requestingMic, setRequestingMic] = useState(false);
  const [sending, setSending] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [expired, setExpired] = useState(false);
  const [error, setError] = useState('');
  const [audioUrl, setAudioUrl] = useState('');
  const [uploadUrl, setUploadUrl] = useState('');
  const [loaded, setLoaded] = useState(false);
  const [activeWord, setActiveWord] = useState<number>();
  const player = useRef<HTMLAudioElement>(null);
  const uploadPlayer = useRef<HTMLAudioElement>(null);
  const frame = useRef(0);
  const clip = useRef<ReturnType<typeof wordPlaybackRange>>(undefined);
  const alive = useRef(false);
  const capture = useRef<Capture>(null);
  const current = revision === currentRevision;

  const limits = useQuery({
    queryKey: ['pronunciation-limits', lessonId, sentenceId, revision],
    queryFn: ({ signal }) => fetchPronunciationLimits(lessonId, sentenceId, revision, signal),
    enabled: !accepted && current,
    retry: false,
  });
  const activeId = !expired ? accepted?.attemptId : undefined;
  const attempt = useQuery({
    queryKey: ['job', accepted?.jobId, 'pronunciation', activeId],
    queryFn: ({ signal }) => fetchPronunciation(activeId!, signal),
    enabled: !!activeId,
    retry: false,
    gcTime: 0,
    refetchInterval: query => terminal(query.state.data?.status) ||
      (query.state.error instanceof ApiError && query.state.error.status === 404)
      ? false : connection === 'connected' ? 5_000 : 2_500,
  });
  const recording = useQuery({
    queryKey: ['pronunciation-audio', activeId],
    queryFn: ({ signal }) => fetchPronunciationAudio(activeId!, signal),
    enabled: !!activeId && attempt.data?.status === 'SUCCEEDED',
    staleTime: Infinity,
    retry: false,
    gcTime: 0,
  });

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      capture.current?.discard();
      cancelAnimationFrame(frame.current);
    };
  }, []);
  useEffect(() => {
    if (!accepted) return;
    const remaining = Date.parse(accepted.expiresAt) - Date.now();
    setExpired(remaining <= 0);
    const expiry = setTimeout(() => setExpired(true), Math.max(0, remaining));
    return () => clearTimeout(expiry);
  }, [accepted]);
  useEffect(() => {
    setLoaded(false);
    setActiveWord(undefined);
    if (!activeId || !recording.data) return;
    const url = URL.createObjectURL(recording.data);
    setAudioUrl(url);
    return () => {
      clip.current = undefined;
      cancelAnimationFrame(frame.current);
      URL.revokeObjectURL(url);
      setAudioUrl('');
    };
  }, [activeId, recording.data]);
  useEffect(() => {
    if (!upload) return;
    let disposed = false;
    let url: string | undefined;
    const preview = upload.captured ? recordingPreview(upload.file) : Promise.resolve(upload.file);
    void preview.catch(() => upload.file).then(blob => {
      if (disposed) return;
      url = URL.createObjectURL(blob);
      setUploadUrl(url);
    });
    return () => {
      disposed = true;
      if (url) URL.revokeObjectURL(url);
      setUploadUrl('');
    };
  }, [upload]);

  function selectFile(file: File, captured = false) {
    setError('');
    if (file.size <= 0 || (limits.data && file.size > limits.data.maxUploadBytes)) {
      setUpload(undefined);
      setError(t('pronunciationPilot.fileSize'));
      return;
    }
    setUpload({ file, key: crypto.randomUUID(), captured });
  }

  async function record() {
    if (!limits.data) return;
    const seconds = limits.data.maxDurationSeconds;
    setError('');
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      setError(t('pronunciationPilot.micUnavailable'));
      return;
    }
    setRequestingMic(true);
    ipaAudio.stop();
    pauseReference();
    uploadPlayer.current?.pause();
    let stream: MediaStream | undefined;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const media = stream;
      if (!alive.current) {
        media.getTracks().forEach(track => track.stop());
        return;
      }
      const type = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4']
        .find(value => MediaRecorder.isTypeSupported(value));
      const recorder = new MediaRecorder(media, type ? { mimeType: type } : undefined);
      const chunks: Blob[] = [];
      const deadline = performance.now() + seconds * 1000;
      let discarded = false;
      const stop = () => { if (recorder.state !== 'inactive') recorder.stop(); };
      const autoStop = setTimeout(stop, seconds * 1000);
      const clock = setInterval(() => setRemaining(Math.max(0, Math.ceil((deadline - performance.now()) / 1000))), 250);
      function release() {
        clearTimeout(autoStop);
        clearInterval(clock);
        media.getTracks().forEach(track => track.stop());
        capture.current = null;
      }
      capture.current = { stop, discard: () => { discarded = true; release(); stop(); } };
      recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
      recorder.onstop = () => {
        if (discarded) return;
        release();
        if (!alive.current) return;
        setRemaining(undefined);
        const mime = recorder.mimeType || chunks[0]?.type || '';
        const extension = mime.includes('mp4') ? 'm4a' : mime.includes('ogg') ? 'ogg' : 'webm';
        selectFile(new File(chunks, `recording.${extension}`, { type: mime }), true);
      };
      recorder.onerror = () => {
        capture.current?.discard();
        if (alive.current) { setRemaining(undefined); setError(t('pronunciationPilot.micUnavailable')); }
      };
      recorder.start();
      setRemaining(seconds);
    } catch {
      capture.current?.discard();
      stream?.getTracks().forEach(track => track.stop());
      if (alive.current) setError(t('pronunciationPilot.micUnavailable'));
    } finally {
      if (alive.current) setRequestingMic(false);
    }
  }

  async function send() {
    if (!upload || !current) return;
    setSending(true);
    setError('');
    try {
      const response = await submitPronunciation(lessonId, sentenceId, revision, upload.file, upload.key);
      onAccepted(response);
      if (alive.current) setUpload(undefined);
    } catch (cause) {
      if (!alive.current) return;
      if (cause instanceof ApiError && cause.status === 409) {
        onConflict();
        setError(t('pronunciationPilot.revisionConflict'));
      } else if (cause instanceof ApiError && cause.status === 0) {
        setError(t('catalog.connectionFailed'));
      } else setError(cause instanceof Error ? cause.message : t('pronunciationPilot.submitError'));
    } finally {
      if (alive.current) setSending(false);
    }
  }

  async function cancel() {
    if (!activeId) return;
    setCancelling(true);
    try {
      await cancelPronunciation(activeId);
      await attempt.refetch();
    } catch {
      if (alive.current) setError(t('pronunciationPilot.cancelError'));
    } finally {
      if (alive.current) setCancelling(false);
    }
  }

  function stopClip() {
    clip.current = undefined;
    cancelAnimationFrame(frame.current);
  }

  async function replay(span: PronunciationSpan, durationMs: number) {
    const range = wordPlaybackRange(span, durationMs);
    const audio = player.current;
    if (!range || !audio || !loaded) return;
    ipaAudio.stop();
    pauseReference();
    stopClip();
    clip.current = range;
    audio.currentTime = range.start;
    const stop = () => {
      if (clip.current !== range || audio.paused) return;
      trackWord(audio);
      if (audio.currentTime < range.end) frame.current = requestAnimationFrame(stop);
      else { audio.pause(); audio.currentTime = range.end; }
    };
    try {
      await audio.play();
      if (clip.current === range) frame.current = requestAnimationFrame(stop);
    } catch {
      if (clip.current !== range) return;
      stopClip();
      if (alive.current) setError(t('pronunciationPilot.playError'));
    }
  }

  const status = attempt.data?.status;
  const isRecording = remaining !== undefined;
  const result = activeId ? attempt.data?.result : undefined;
  const scored = result?.outcome === 'SCORED' ? result : undefined;
  const assessedRevision = attempt.data?.revision;
  const oldRevision = attempt.data && assessedRevision !== currentRevision ? attempt.data : undefined;

  useEffect(() => {
    if (!activeId || !scored?.words.length || assessedRevision == null) return;
    onSummary(sentenceId, activeId, {
      revision: assessedRevision,
      issueCount: scored.words.filter(word => word.error !== 'NONE').length,
    });
  }, [activeId, assessedRevision, onSummary, scored, sentenceId]);

  const canPlayReference = playReference && !requestingMic && !isRecording && !oldRevision;
  function listenToReference(atMs?: number) {
    ipaAudio.stop();
    uploadPlayer.current?.pause();
    player.current?.pause();
    stopClip();
    playReference?.(atMs);
  }

  function trackWord(audio: HTMLAudioElement) {
    const at = audio.currentTime * 1000;
    const index = scored?.words.findIndex(word => word.startMs != null && word.endMs != null
      && word.startMs <= at && at < word.endMs);
    setActiveWord(index !== undefined && index >= 0 && !audio.paused ? index : undefined);
  }

  const missing = attempt.error instanceof ApiError && attempt.error.status === 404;
  const localControls = !accepted && current && limits.data;

  return <>
    {(!scored || canPlayReference) && <div role="group" aria-label={t('pronunciationPilot.referenceGroup')}
      className="grid gap-2 rounded-lg border bg-muted/30 p-3">
      <Button size="sm" variant="outline" className="justify-self-start" disabled={!canPlayReference}
        onClick={() => referencePlaying ? pauseReference() : listenToReference()}>
        {referencePlaying ? <RiPauseLine aria-hidden="true" /> : <RiPlayLine aria-hidden="true" />}
        {t(referencePlaying ? 'pronunciationPilot.pauseReference' : 'pronunciationPilot.reference')}
      </Button>
      <div lang="en" className="studio-karaoke text-base wrap-anywhere">
        {canPlayReference && sentence.words.length > 0
          ? <PlaybackWords timing clock={referenceClock} sentence={sentence} onSeek={listenToReference} />
          : oldRevision?.referenceText ?? sentence.text}
      </div>
    </div>}
    <section aria-labelledby={`${inputId}-panel`} className="flex min-w-0 flex-col gap-4">
    <h2 id={`${inputId}-panel`} aria-live="polite" className="font-semibold">
      {t(result ? 'pronunciationPilot.results' : 'pronunciationPilot.recording')}
    </h2>
    {oldRevision && <Alert>
      <AlertDescription>
        <p>{t('pronunciationPilot.oldRevision', { revision: oldRevision.revision })}</p>
      </AlertDescription>
    </Alert>}
    {error && <Alert variant="destructive" role="alert"><AlertDescription>{error}</AlertDescription></Alert>}
    {!accepted && !current && <p role="status">{t('pronunciationPilot.revisionConflict')}</p>}
    {!accepted && current && limits.isPending && <LoadingStatus placeholder>{t('pronunciationPilot.loadingLimits')}</LoadingStatus>}
    {!accepted && current && limits.isError && <p role="alert">{limits.error.message} <Button size="sm" variant="link" onClick={() => void limits.refetch()}>{t('pronunciationPilot.retry')}</Button></p>}
    {localControls && <>
      <p className="text-muted-foreground">{t('pronunciationPilot.limit', { seconds: limits.data.maxDurationSeconds })}</p>
      <div className="grid gap-4">
        <div className="flex flex-wrap items-center gap-2">
          <Button variant={isRecording ? 'destructive' : 'outline'} disabled={requestingMic || sending} onClick={() => {
            if (isRecording) capture.current?.stop();
            else void record();
          }}>
            {isRecording ? <RiStopLine aria-hidden="true" /> : <RiMicLine aria-hidden="true" />}
            {t(isRecording ? 'pronunciationPilot.stop' : 'pronunciationPilot.record')}
          </Button>
          {isRecording && <time className="tabular-nums">{t('pronunciationPilot.remaining', { seconds: remaining })}</time>}
        </div>
        <div className="flex min-w-0 max-w-md flex-col gap-1">
          <label htmlFor={inputId}>{t('pronunciationPilot.upload')}</label>
          <Input id={inputId} type="file" accept=".webm,.ogg,.opus,.m4a,.mp4,.wav,.mp3,audio/*"
            disabled={isRecording || requestingMic || sending} onChange={event => {
              const file = event.currentTarget.files?.[0];
              if (file) selectFile(file);
              event.currentTarget.value = '';
            }} />
        </div>
      </div>
      {upload && !isRecording && <div className="grid gap-3 border-t pt-4">
        <p className="break-all font-medium">{upload.file.name}</p>
        {uploadUrl ? <AudioPlayer ref={uploadPlayer} src={uploadUrl} aria-label={t('pronunciationPilot.recordingPreview')}
          onPlay={pauseReference} /> : <LoadingStatus>{t('pronunciationPilot.preparingAudio')}</LoadingStatus>}
        <Button className="justify-self-start" disabled={requestingMic || sending} onClick={() => { uploadPlayer.current?.pause(); void send(); }}>
          {sending && <RiLoader4Line aria-hidden="true" className="motion-safe:animate-spin" />}
          {t(sending ? 'pronunciationPilot.sending' : 'pronunciationPilot.submit')}
        </Button></div>}
      {sending && <p role="status" className="sr-only">{t('pronunciationPilot.sending')}</p>}
    </>}
    {accepted && (expired || missing) && <p role="status">{t('pronunciationPilot.expired')}</p>}
    {activeId && !missing && !status && !attempt.isError && <LoadingStatus placeholder>{t('pronunciationPilot.loading')}</LoadingStatus>}
    {activeId && !missing && attempt.isError && <p role="alert">{t('pronunciationPilot.loadError')} <Button size="sm" variant="link" onClick={() => void attempt.refetch()}>{t('pronunciationPilot.retry')}</Button></p>}
    {activeId && status && !terminal(status) && <div className="flex flex-wrap items-center gap-2">
      <LoadingStatus>{t(`pronunciationPilot.status.${cancelling ? 'CANCEL_REQUESTED' : status}`)}</LoadingStatus>
      {status !== 'CANCEL_REQUESTED' && <Button size="sm" variant="outline" disabled={cancelling} onClick={() => void cancel()}>{t('pronunciationPilot.cancel')}</Button>}
    </div>}
    {activeId && status === 'FAILED' && <p role="alert">{t(`pronunciationPilot.failure.${attempt.data?.errorCode}`, { defaultValue: t('pronunciationPilot.status.FAILED') })}</p>}
    {activeId && status === 'CANCELLED' && <p role="status">{t('pronunciationPilot.status.CANCELLED')}</p>}
    {result && <>
      {scored ?
        <PronunciationFeedback key={activeId}
          referenceText={attempt.data!.referenceText} result={scored}
          activeWord={activeWord} loaded={loaded} play={span => void replay(span, scored.durationMs)}
          pause={() => { ipaAudio.stop(); stopClip(); player.current?.pause(); }}
          sampleAudio={ipaAudio}
          beforeSample={() => {
            pauseReference();
            stopClip();
            player.current?.pause();
          }} />
        : <p role="status">{t('pronunciationPilot.noSpeech')}</p>}
      {audioUrl && <AudioPlayer ref={player} src={audioUrl} aria-label={t('pronunciationPilot.recording')}
        onPlay={event => { ipaAudio.stop(); pauseReference(); trackWord(event.currentTarget); }}
        onTimeUpdate={event => trackWord(event.currentTarget)}
        onSeeked={event => {
          const audio = event.currentTarget;
          if (clip.current && (audio.currentTime < clip.current.start - 0.035 || audio.currentTime >= clip.current.end)) stopClip();
          trackWord(audio);
        }}
        onPause={() => { stopClip(); setActiveWord(undefined); }}
        onEnded={() => { stopClip(); setActiveWord(undefined); }}
        onLoadedMetadata={() => setLoaded(true)} onError={() => setLoaded(false)} />}
      {!audioUrl && !recording.isError && <LoadingStatus placeholder>{t('pronunciationPilot.loadingAudio')}</LoadingStatus>}
      {recording.isError && <p role="alert">{t('pronunciationPilot.playError')} <Button size="sm" variant="link" onClick={() => void recording.refetch()}>{t('pronunciationPilot.retry')}</Button></p>}
    </>}
    {accepted && current && (expired || missing || terminal(status)) &&
      <Button size="sm" variant="outline" className="self-start" onClick={() => {
        ipaAudio.stop(); setError(''); setExpired(false); onAccepted();
      }}>{t('pronunciationPilot.newAttempt')}</Button>}
    </section>
  </>;
}
