import { useEffect, useImperativeHandle, useRef, useState, type ComponentProps, type CSSProperties, type ReactNode, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { RiLoader4Line, RiPauseLine, RiPlayLine, RiVolumeMuteLine, RiVolumeUpLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import './audio-player.css';

type Props = Omit<ComponentProps<'audio'>, 'controls' | 'children' | 'ref'> & {
  ref?: Ref<HTMLAudioElement>;
  playbackControls?: ReactNode;
  startTime?: number;
  endTime?: number;
};
const initial = { time: 0, duration: 0, playing: false, muted: false, volume: 1, rate: 1 };
const clock = (seconds: number) => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
const progress = (value: number): CSSProperties => ({ '--audio-progress': `${value}%` } as CSSProperties);

/** Custom controls share the same media element with Lesson's sentence/loop transport. */
export function AudioPlayer({ ref, playbackControls, startTime = 0, endTime, className = '', src,
  onLoadedMetadata, onDurationChange, onTimeUpdate, onSeeked, onPlay, onPause, onEnded, onError, onRateChange, onVolumeChange, onWaiting, onPlaying, ...props }: Props) {
  const { t } = useTranslation('admin');
  const audio = useRef<HTMLAudioElement>(null);
  useImperativeHandle(ref, () => audio.current!, []);
  const [state, setState] = useState(initial);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const [waiting, setWaiting] = useState(false);
  useEffect(() => { setState(initial); setReady(false); setFailed(false); setWaiting(false); }, [src]);
  function sync() {
    const player = audio.current;
    if (player) setState({ time: player.currentTime, duration: Number.isFinite(player.duration) ? player.duration : 0,
      playing: !player.paused && !player.ended, muted: player.muted, volume: player.volume, rate: player.playbackRate });
  }
  const end = Math.min(endTime ?? state.duration, state.duration);
  const duration = Math.max(0, end - startTime);
  const position = Math.max(0, Math.min(duration, state.time - startTime));
  const available = ready && !failed;
  async function toggle() {
    const player = audio.current;
    if (!player) return;
    if (!player.paused) { player.pause(); return; }
    if (player.currentTime < startTime || duration > 0 && player.currentTime >= end) player.currentTime = startTime;
    try { await player.play(); } catch { setFailed(true); }
  }
  return <div data-slot="audio-player" role="group" aria-label={props['aria-label'] ?? t('audioPlayer.label')} className={`audio-player ${className}`}>
    <audio {...props} ref={audio} src={src} hidden preload={props.preload ?? 'metadata'}
      onLoadedMetadata={event => { sync(); setReady(true); setFailed(false); onLoadedMetadata?.(event); }}
      onDurationChange={event => { sync(); onDurationChange?.(event); }}
      onTimeUpdate={event => { sync(); onTimeUpdate?.(event); }} onSeeked={event => { sync(); onSeeked?.(event); }}
      onPlay={event => { sync(); onPlay?.(event); }} onPause={event => { sync(); setWaiting(false); onPause?.(event); }}
      onEnded={event => { sync(); setWaiting(false); onEnded?.(event); }}
      onWaiting={event => { setWaiting(true); onWaiting?.(event); }} onPlaying={event => { setWaiting(false); onPlaying?.(event); }}
      onError={event => { setFailed(true); setReady(false); setWaiting(false); onError?.(event); }}
      onRateChange={event => { sync(); onRateChange?.(event); }} onVolumeChange={event => { sync(); onVolumeChange?.(event); }} />
    <div className="audio-player-timeline">
      <input className="audio-slider" type="range" min={0} max={duration || 1} step={0.1} value={position}
        style={progress(duration ? position / duration * 100 : 0)} disabled={!available || !duration}
        aria-label={t('audioPlayer.seek')} title={t('audioPlayer.seek')} aria-valuetext={`${clock(position)} / ${clock(duration)}`}
        onChange={event => { if (audio.current) { audio.current.currentTime = startTime + Number(event.target.value); sync(); } }} />
      <span className="audio-player-clock" aria-hidden="true">{clock(position)} / {clock(duration)}</span>
    </div>
    <div className="audio-player-toolbar">
      {playbackControls === undefined ? <>
        <Button type="button" variant="secondary" size="icon-sm" disabled={!available} tooltip={t(state.playing ? 'audioPlayer.pauseHelp' : 'audioPlayer.playHelp')}
          aria-label={t(state.playing ? 'waveform.pause' : 'waveform.play')} onClick={() => void toggle()}>
          {waiting ? <RiLoader4Line className="animate-spin motion-reduce:animate-none" aria-hidden="true" /> : state.playing ? <RiPauseLine aria-hidden="true" /> : <RiPlayLine aria-hidden="true" />}
        </Button>
        <Select value={String(state.rate)} disabled={!available} onValueChange={value => { if (audio.current) { audio.current.playbackRate = Number(value); sync(); } }}>
          <SelectTrigger size="sm" aria-label={t('audioPlayer.speed')} title={t('audioPlayer.speed')} className="audio-player-speed"><SelectValue /></SelectTrigger>
          <SelectContent>{[0.75, 1, 1.25, 1.5, 2].map(rate => <SelectItem key={rate} value={String(rate)}>{rate}×</SelectItem>)}</SelectContent>
        </Select>
      </> : playbackControls}
      <div className="audio-player-volume">
        <Button type="button" variant="ghost" size="icon-sm" disabled={!available} tooltip={t(state.muted || !state.volume ? 'audioPlayer.unmute' : 'audioPlayer.mute')}
          aria-label={t(state.muted || !state.volume ? 'audioPlayer.unmute' : 'audioPlayer.mute')} onClick={() => {
            if (audio.current) { if (!audio.current.volume) audio.current.volume = 1; audio.current.muted = !(state.muted || !state.volume); sync(); }
          }}>{state.muted || !state.volume ? <RiVolumeMuteLine aria-hidden="true" /> : <RiVolumeUpLine aria-hidden="true" />}</Button>
        <input className="audio-slider" type="range" min={0} max={1} step={0.05} value={state.muted ? 0 : state.volume}
          style={progress(state.muted ? 0 : state.volume * 100)} disabled={!available} aria-label={t('audioPlayer.volume')} title={t('audioPlayer.volume')}
          aria-valuetext={`${Math.round((state.muted ? 0 : state.volume) * 100)}%`} onChange={event => {
            if (audio.current) { audio.current.volume = Number(event.target.value); audio.current.muted = false; sync(); }
          }} />
      </div>
    </div>
    {failed ? <div role="alert" className="audio-player-status text-destructive"><span>{t('audioPlayer.failed')}</span><Button type="button" variant="link" size="sm" onClick={() => {
      setFailed(false); setReady(false); audio.current?.load();
    }}>{t('audioPlayer.retry')}</Button></div> : !ready && <p role="status" className="audio-player-status text-muted-foreground">{t('media.loadingAudio')}</p>}
  </div>;
}
