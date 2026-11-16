import { useSyncExternalStore, Fragment } from 'react';
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
  const active = useSyncExternalStore(clock.subscribe, () => sentence.words.findIndex(word => clock.get() >= word.startMs && clock.get() < word.endMs));
  return <span className={timing ? 'timing-playback' : 'playback-words'}>
    <span className={timing ? 'timing-words' : undefined}>{sentence.words.length ? sentence.words.map((word, index) => {
      const surface = word.charStart == null ? word.text : sentence.text.slice(index === 0 ? 0 : word.charStart, sentence.words[index + 1]?.charStart ?? sentence.text.length) || word.text;
      const space = surface.match(/\s+$/)?.[0] ?? (word.charStart == null && index < sentence.words.length - 1 ? ' ' : '');
      return <Fragment key={index}><button type="button" className={timing ? 'timing-word' : 'playback-word'} data-active={index === active} onClick={() => onSeek(word.startMs)} title={timing ? undefined : `${timecode(word.startMs)}–${timecode(word.endMs)}`}>
        {surface.trimEnd()}
      </button>{space}</Fragment>;
    }) : sentence.text}</span>
  </span>;
}
