import { useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import { timecode } from './timecode';
import type { AuthoringSentence } from './lesson-builder.types';

/** Playback ticks notify leaf controls; editing forms do not render at player frequency. */
export function createPlaybackClock() {
  let position = 0;
  const listeners = new Set<() => void>();
  return {
    get: () => position,
    set: (next: number) => { if (position === next) return; position = next; listeners.forEach(notify => notify()); },
    subscribe: (notify: () => void) => { listeners.add(notify); return () => { listeners.delete(notify); }; },
  };
}
export type PlaybackClock = ReturnType<typeof createPlaybackClock>;
export function PlaybackTime({ clock }: { clock: PlaybackClock }) {
  const value = useSyncExternalStore(clock.subscribe, clock.get);
  return <span className="tabular-nums">{timecode(value)}</span>;
}
export function PlaybackCursor({ clock, durationMs, onSeek }: { clock: PlaybackClock; durationMs: number; onSeek: (atMs: number) => void }) {
  const { t } = useTranslation('admin');
  const value = useSyncExternalStore(clock.subscribe, clock.get);
  const position = Math.min(durationMs, Math.max(0, value));
  return <>
    <span className="source-range-playhead" aria-hidden="true" style={{left:`${durationMs > 0 ? position / durationMs * 100 : 0}%`}} />
    <input className="source-playback-handle" type="range" min={0} max={durationMs} step={1} value={position}
      aria-label={t('authoringUi.seekPosition')} aria-valuetext={timecode(position)} disabled={durationMs <= 0}
      onKeyDown={event => event.stopPropagation()}
      onChange={event => { const atMs = Number(event.target.value); clock.set(atMs); onSeek(atMs); }} />
  </>;
}
export function SeekBar({ clock, durationMs, onSeek }: { clock: PlaybackClock; durationMs: number; onSeek: (atMs: number) => void }) {
  const { t } = useTranslation('admin');
  const value = useSyncExternalStore(clock.subscribe, clock.get);
  return <label className="player-seek"><span className="sr-only">{t('authoringUi.seekPosition')}</span><input type="range" min={0} max={durationMs} step={100} value={Math.min(durationMs, Math.max(0,value))} aria-label={t('authoringUi.seekPosition')} onChange={e => { const at=Number(e.target.value); clock.set(at); onSeek(at); }} /><span className="text-xs text-muted-foreground tabular-nums">{timecode(value)} / {timecode(durationMs)}</span></label>;
}
export function PlaybackWords({ clock, sentence, onSeek, timing = false }: { clock: PlaybackClock; sentence: AuthoringSentence; onSeek: (atMs: number) => void; timing?: boolean }) {
  const { t } = useTranslation('admin');
  const active = useSyncExternalStore(clock.subscribe, () => sentence.words.findIndex(word => clock.get() >= word.startMs && clock.get() < word.endMs));
  return <span className={timing ? 'timing-words' : 'playback-words'}>{sentence.words.length ? sentence.words.map((word,index) => <button type="button" className={timing ? 'timing-word' : 'playback-word'} data-active={index===active} key={index} onClick={()=>onSeek(word.startMs)} title={`${timecode(word.startMs)}–${timecode(word.endMs)}`}><span>{word.text}</span>{timing && <><small>{timecode(word.startMs)}</small>{word.tokenEnd-word.tokenStart>1 && <span className="text-warning">{t('authoringUi.grouped')}</span>}</>}</button>) : sentence.text}</span>;
}
