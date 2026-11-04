import { createPlaybackClock, PlaybackTime } from './playback-clock';
import { lazy, Suspense, useEffect, useState } from 'react';
import { RiPlayLine as Play, RiPauseLine as Pause, RiRepeat2Line as Repeat2 } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { YouTubePreview } from './YouTubePreview';
import { parseTimecode, timecode } from './timecode';
import { validSelection } from './timeline-view';

const RangeTimeline = lazy(() => import('./RangeTimeline').then(module => ({ default: module.RangeTimeline })));
const display = (value: number | null) => value === null ? '' : timecode(value);

/** Source selection is metadata-only; audio is prepared by the explicit transcript action. */
export function SourceRangeEditor({ videoId, thumbnailUrl, durationMs, initialPositionMs, startMs, endMs, onRangeChange, onValidityChange }: {
  videoId: string; thumbnailUrl?: string | null; durationMs: number; initialPositionMs: number;
  startMs: number | null; endMs: number | null;
  onRangeChange: (start: number | null, end: number | null) => void;
  onValidityChange?: (valid: boolean) => void;
}) {
  const { t } = useTranslation('admin');
  const [start, setStart] = useState(display(startMs));
  const [end, setEnd] = useState(display(endMs));
  const [clock] = useState(() => { const value = createPlaybackClock(); value.set(initialPositionMs); return value; });
  const [playing, setPlaying] = useState(false);
  const [ready, setReady] = useState(false);
  const [loop, setLoop] = useState(false);
  const [rate, setRate] = useState(1);
  const [error, setError] = useState(false);
  const [seek, setSeek] = useState({ atMs: initialPositionMs, sequence: 0 });
  const [transport, setTransport] = useState({ playing: false, rate: 1, sequence: 0 });
  useEffect(() => { setStart(display(startMs)); setEnd(display(endMs)); setError(false); }, [startMs, endMs]);
  const a = parseTimecode(start), b = parseTimecode(end);
  const valid = a !== null && b !== null && validSelection(a, b, durationMs);
  const selected = startMs !== null && endMs !== null && validSelection(startMs, endMs, durationMs);
  useEffect(() => { onValidityChange?.(valid); }, [valid, onValidityChange]);
  function commit() {
    if (a !== null && a >= 0 && a < durationMs && end === '') {
      onRangeChange(a, null); setError(false);
    } else if (valid && a !== null && b !== null) {
      onRangeChange(a, b); setError(false);
    } else setError(start !== '' || end !== '');
  }
  function mark(edge: 'start' | 'end') {
    const at = Math.round(Math.max(0, Math.min(durationMs, clock.get())));
    const nextStart = edge === 'start' ? at : startMs;
    const nextEnd = edge === 'end' ? at : endMs;
    if (nextStart === null || nextStart >= durationMs ||
      (nextEnd !== null && !validSelection(nextStart, nextEnd, durationMs))) {
      setError(true); return;
    }
    setStart(display(nextStart)); setEnd(display(nextEnd)); setError(false);
    onRangeChange(nextStart, nextEnd);
  }
  function play() {
    if (!playing && clock.get() >= durationMs) setSeek(old => ({ atMs: startMs ?? initialPositionMs, sequence: old.sequence + 1 }));
    setTransport(old => ({ playing: !playing, rate, sequence: old.sequence + 1 }));
  }
  return <div className="source-range-editor">
    <div className="source-player"><YouTubePreview videoId={videoId} thumbnailUrl={thumbnailUrl} startMs={0} endMs={durationMs} sentences={[]} seek={seek} onTimeChange={clock.set} onAvailabilityChange={setReady} onPlayingChange={setPlaying} transport={transport} loopRange={loop && selected ? { startMs, endMs } : undefined} /></div>
    <div className="player-transport source-transport" role="group" aria-label={t('sourceSelection.playback')}>
      <Tooltip delayDuration={400}><TooltipTrigger asChild><Button type="button" size="icon" variant="outline" disabled={!ready} aria-label={t(playing ? 'waveform.pause' : 'waveform.play')} onClick={play}>{playing ? <Pause /> : <Play />}</Button></TooltipTrigger><TooltipContent>{t(playing ? 'waveform.pause' : 'waveform.play')}</TooltipContent></Tooltip>
      <div role="group" aria-label={t('sourceSelection.speed')} className="source-speed">{[0.75, 1, 1.25].map(value => <Button key={value} type="button" variant={rate === value ? 'secondary' : 'ghost'} size="sm" aria-pressed={rate === value} disabled={!ready} onClick={() => { setRate(value); setTransport(old => ({ ...old, rate: value, sequence: old.sequence + 1 })); }}>{value}×</Button>)}</div>
      <Tooltip delayDuration={400}><TooltipTrigger asChild><Button type="button" variant={loop && selected ? 'secondary' : 'ghost'} disabled={!ready || !selected} aria-pressed={loop && selected} onClick={() => setLoop(!loop)}><Repeat2 data-icon="inline-start" />{t('waveform.loop')}</Button></TooltipTrigger><TooltipContent>{t('sourceSelection.loopHint')}</TooltipContent></Tooltip>
      <span className="source-clock text-xs text-muted-foreground"><PlaybackTime clock={clock} /> / {timecode(durationMs)}</span>
    </div>
    <div className="source-timeline">
      <Suspense fallback={<Skeleton className="h-36 w-full" />}>
        <RangeTimeline clock={clock} durationMs={durationMs} startMs={startMs} endMs={endMs} onRangeChange={onRangeChange} onSeek={atMs => setSeek(old => ({ atMs, sequence: old.sequence + 1 }))} />
      </Suspense>
      <div className="source-selection-controls">
        {(['start', 'end'] as const).map(edge => <div key={edge} className="source-boundary">
          <label htmlFor={`source-${edge}`}>{t(`waveform.${edge}`)}</label>
          <div className="source-boundary-controls">
            <Input id={`source-${edge}`} aria-label={t(`waveform.${edge}`)} placeholder="00:00.000" value={edge === 'start' ? start : end} aria-invalid={(start !== '' && (a === null || a >= durationMs)) || (end !== '' && !valid)} onChange={event => (edge === 'start' ? setStart : setEnd)(event.target.value)} onBlur={commit} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); commit(); } }} />
            <Button type="button" variant="outline" disabled={!ready || (edge === 'end' && startMs === null)} onClick={() => mark(edge)}>{t(edge === 'start' ? 'sourceSelection.markStart' : 'sourceSelection.markEnd')}</Button>
          </div>
        </div>)}
        {selected && <Badge variant="secondary" aria-label={t('sourceSelection.duration')}>{timecode(endMs - startMs)}</Badge>}
      </div>
      <div className="source-selection-help"><p className="text-xs text-muted-foreground" role="status">{t(selected ? 'sourceSelection.limit' : startMs === null ? 'sourceSelection.chooseRange' : 'sourceSelection.chooseEnd')}</p>
        <Dialog><DialogTrigger asChild><Button type="button" size="sm" variant="ghost">{t('sourceSelection.help')}</Button></DialogTrigger><DialogContent><DialogHeader><DialogTitle>{t('sourceSelection.help')}</DialogTitle><DialogDescription>{t('sourceSelection.helpText')}</DialogDescription></DialogHeader></DialogContent></Dialog>
      </div>
      {error && <p role="alert" className="text-sm text-destructive">{t('waveform.invalidRange')}</p>}
    </div>
  </div>;
}
