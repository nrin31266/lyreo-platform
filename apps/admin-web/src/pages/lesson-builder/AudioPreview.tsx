import { useEffect, useRef, useState, type ComponentProps } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { RiPlayLine, RiPauseLine, RiRepeat2Line, RiPlayList2Line } from '@remixicon/react';
import { useQuery } from '@tanstack/react-query';
import { http } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { YouTubePreview } from './YouTubePreview';
import { sentencePlaybackAction, sentencePlaybackRange } from './authoring-editor';
import type { LessonDraft } from './lesson-builder.types';

type Props = Omit<ComponentProps<typeof YouTubePreview>, 'videoId' | 'thumbnailUrl'> & { src: string };
export function PrivateAudio({ url, ...props }: Omit<Props, 'src'> & { url: string }) {
  const { t } = useTranslation('admin');
  const [src, setSrc] = useState('');
  const query = useQuery({ queryKey: ['private-audio', url], queryFn: async ({ signal }) => (await http.get<Blob>(url, { signal, responseType: 'blob' })).data, staleTime: Infinity, gcTime: 0 });
  useEffect(() => {
    if (!query.data) return;
    const next = URL.createObjectURL(query.data); setSrc(next);
    return () => { URL.revokeObjectURL(next); };
  }, [query.data]);
  return <><CatalogError error={query.error} retry={() => void query.refetch()} />{query.isPending && <p role="status">{t("media.loadingAudio")}</p>}{src && <AudioPreview key={src} src={src} {...props} />}</>;
}
export function LessonPlayer({ draft, ...props }: Omit<ComponentProps<typeof YouTubePreview>, 'videoId' | 'startMs' | 'endMs'> & { draft: LessonDraft }) {
  const { t } = useTranslation('admin');
  const playbackSentences = draft.status === 'PUBLISHED' ? props.sentences : props.sentences.map(row => ({ ...row, hidden: false, usage: 'LEARN' as const }));
  if (!draft.sourceType || draft.sourceType === 'YOUTUBE') return <YouTubePreview videoId={draft.videoId ?? ''} startMs={draft.startMs} endMs={draft.endMs} {...props} sentences={playbackSentences} />;
  if (!draft.audioReady) return <p className="text-muted-foreground">{t(draft.sourceType === 'TEXT' ? 'media.audioMissing' : 'twoStep.audioPreparing')}</p>;
  return <PrivateAudio key={draft.audioVersion} url={`/api/v1/admin/lesson-authoring/${draft.id}/audio?v=${draft.audioVersion}`} startMs={0} endMs={draft.endMs - draft.startMs} {...props} sentences={playbackSentences} />;
}
export function AudioPreview({ src, startMs, endMs, sentences, seek, transport, loopRange, review, controlsHost, onTimeChange, onPlayingChange, onAvailabilityChange }: Props) {
  const { t } = useTranslation('admin');
  const audio = useRef<HTMLAudioElement>(null);
  const latest = useRef({ onTimeChange, onPlayingChange, onAvailabilityChange, review, sentences, loopRange });
  latest.current = { onTimeChange, onPlayingChange, onAvailabilityChange, review, sentences, loopRange };
  const [playing, setPlaying] = useState(false);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const [rate, setRate] = useState(1);
  const [loop, setLoop] = useState(false);
  const loopRef = useRef(false); loopRef.current = loop;
  const [continuous, setContinuous] = useState(false);
  const continuousRef = useRef(false); continuousRef.current = continuous;
  async function play() { try { await audio.current?.play(); } catch { setPlaying(false); } }
  useEffect(() => {
    latest.current.onAvailabilityChange?.(false);
    const timer = setInterval(() => {
      const player = audio.current; if (!player) return;
      const position = Math.max(0, player.currentTime * 1000 - startMs);
      latest.current.onTimeChange(position);
      const selected = latest.current.sentences.find(sentence => sentence.id === latest.current.review?.sentenceId);
      const selectedRange = selected && sentencePlaybackRange(selected, latest.current.sentences, endMs - startMs);
      const range = latest.current.loopRange ?? (loopRef.current ? selectedRange ?? { startMs: 0, endMs: endMs - startMs } : undefined);
      if (!player.paused && range && (position < range.startMs || position >= range.endMs)) player.currentTime = (startMs + range.startMs) / 1000;
      else if (!player.paused && !range && latest.current.review) {
        const action = sentencePlaybackAction(position, latest.current.review.sentenceId, continuousRef.current, latest.current.sentences, endMs - startMs);
        if (action.pause) { player.pause(); if (action.stopMs !== undefined) player.currentTime = (startMs + action.stopMs) / 1000; }
        if (action.sentenceId !== undefined) latest.current.review.onSentenceChange(action.sentenceId);
        if (action.seekMs !== undefined) player.currentTime = (startMs + action.seekMs) / 1000;
      }
      if (!player.paused && player.currentTime * 1000 >= endMs && !range) player.pause();
    }, 50);
    return () => { clearInterval(timer); latest.current.onAvailabilityChange?.(false); };
  }, [src, startMs, endMs]);
  useEffect(() => {
    if (!seek || !audio.current || !ready) return;
    audio.current.currentTime = Math.max(startMs, Math.min(endMs, startMs + seek.atMs)) / 1000;
    if (seek.play === true) void play();
    if (seek.play === false) audio.current.pause();
  }, [seek, ready, startMs, endMs]);
  useEffect(() => {
    if (!transport || !audio.current || !ready) return;
    audio.current.playbackRate = transport.rate;
    if (transport.playing) void play(); else audio.current.pause();
  }, [transport, ready]);
  useEffect(() => {
    audio.current?.pause();
    setPlaying(false); setLoop(false); setContinuous(false);
  }, [review?.context]);
  const controls = <div className="player-transport" role="group" aria-label={t('sourceSelection.playback')}>
    <Button type="button" size="icon" variant="outline" disabled={!ready} aria-label={t(playing ? 'waveform.pause' : 'waveform.play')} onClick={() => {
      if (playing) { audio.current?.pause(); return; }
      const selected = sentences.find(row => row.id === review?.sentenceId);
      const range = selected && sentencePlaybackRange(selected, sentences, endMs - startMs);
      const player = audio.current;
      if (review && !continuous && !range) return;
      if (range && !continuous && player && (player.currentTime * 1000 - startMs < range.startMs || player.currentTime * 1000 - startMs >= range.endMs)) player.currentTime = (startMs + range.startMs) / 1000;
      void play();
    }}>{playing ? <RiPauseLine /> : <RiPlayLine />}</Button>
    {[0.75, 1, 1.25].map(value => <Button key={value} type="button" size="sm" variant={rate === value ? 'secondary' : 'ghost'} disabled={!ready} aria-pressed={rate === value} onClick={() => { setRate(value); if (audio.current) audio.current.playbackRate = value; }}>{value}×</Button>)}
    {review && <Button type="button" size="icon" variant={continuous ? 'secondary' : 'ghost'} disabled={!ready} aria-pressed={continuous} aria-label={t('reviewWorkspace.continuousPlayback')} title={t('reviewWorkspace.continuousPlaybackHelp')} onClick={() => { setContinuous(value => !value); setLoop(false); }}><RiPlayList2Line /></Button>}
    <Button type="button" size="icon" variant={loop ? 'secondary' : 'ghost'} disabled={!ready} aria-pressed={loop} aria-label={t('waveform.loop')} onClick={() => { setLoop(value => !value); setContinuous(false); }}><RiRepeat2Line /></Button>
  </div>;
  return <div className="flex flex-col gap-3">
    <audio ref={audio} src={src} controls onEnded={() => {
      if (!loopRef.current && !latest.current.loopRange) return;
      const selected = latest.current.sentences.find(row => row.id === latest.current.review?.sentenceId);
      const range = latest.current.loopRange ?? (selected && sentencePlaybackRange(selected, latest.current.sentences, endMs - startMs));
      if (audio.current) { audio.current.currentTime = (startMs + (range?.startMs ?? 0)) / 1000; void play(); }
    }} preload="metadata" aria-label={t('catalog.player')} className="w-full"
      onLoadedMetadata={() => { setReady(true); latest.current.onAvailabilityChange?.(true); }}
      onError={() => { setFailed(true); setReady(false); latest.current.onAvailabilityChange?.(false); }}
      onPlay={() => { setPlaying(true); onPlayingChange?.(true); }} onPause={() => { setPlaying(false); onPlayingChange?.(false); }} />
    {failed && <p role="alert" className="text-destructive">{t("media.playbackFailed")}</p>}
    {!transport && (controlsHost ? createPortal(controls, controlsHost) : controls)}
  </div>;
}
