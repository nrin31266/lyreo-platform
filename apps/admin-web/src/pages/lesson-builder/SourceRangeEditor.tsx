import { createPlaybackClock, PlaybackTime } from './playback-clock';
import { lazy, Suspense, useEffect, useState } from 'react';
import { RiPlayLine as Play, RiPauseLine as Pause, RiRepeat2Line as Repeat2, RiInformationLine, RiQuestionLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { PrivateAudio } from './AudioPreview';
import { YouTubePreview } from './YouTubePreview';
import { parseTimecode, timecode } from './timecode';
import { validSelection } from './timeline-view';

const RangeTimeline = lazy(() => import('./RangeTimeline').then(module => ({ default: module.RangeTimeline })));
const display = (value: number | null) => value === null ? '' : timecode(value);

/** Source selection is metadata-only; audio is prepared by the explicit transcript action. */
export function SourceRangeEditor({ videoId, audioUrl, thumbnailUrl, durationMs, initialPositionMs, startMs, endMs, onRangeChange, onValidityChange }: {
  videoId?: string; audioUrl?: string; thumbnailUrl?: string | null; durationMs: number; initialPositionMs: number;
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
  const canSelectFullVideo = !!videoId && validSelection(0, durationMs, durationMs);
  const fullVideoSelected = canSelectFullVideo && startMs === 0 && endMs === durationMs;
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
    <div className={audioUrl ? "source-player source-player-audio" : "source-player"}><SourcePlayer videoId={videoId ?? ""} audioUrl={audioUrl} thumbnailUrl={thumbnailUrl} startMs={0} endMs={durationMs} sentences={[]} seek={seek} onTimeChange={clock.set} onAvailabilityChange={setReady} onPlayingChange={setPlaying} transport={transport} loopRange={loop && selected ? { startMs, endMs } : undefined} /></div>
    <div className="source-timeline">
      <Suspense fallback={<Skeleton className="h-36 w-full" />}>
        <RangeTimeline clock={clock} durationMs={durationMs} startMs={startMs} endMs={endMs} onRangeChange={onRangeChange} onSeek={atMs => setSeek(old => ({ atMs, sequence: old.sequence + 1 }))}
          playbackControls={<div className="source-toolbar-group" role="group" aria-label={t('sourceSelection.playback')}>
            <Tooltip delayDuration={400}><TooltipTrigger asChild><Button type="button" size="icon-sm" variant="secondary" disabled={!ready} aria-label={t(playing ? 'waveform.pause' : 'waveform.play')} onClick={play}>{playing ? <Pause /> : <Play />}</Button></TooltipTrigger><TooltipContent>{t(playing ? 'waveform.pause' : 'waveform.play')}</TooltipContent></Tooltip>
            <Select value={String(rate)} disabled={!ready} onValueChange={value => { const next = Number(value); setRate(next); setTransport(old => ({ ...old, rate: next, sequence: old.sequence + 1 })); }}><SelectTrigger size="sm" aria-label={t('sourceSelection.speed')}><SelectValue /></SelectTrigger><SelectContent>{[0.75, 1, 1.25].map(value => <SelectItem key={value} value={String(value)}>{value}×</SelectItem>)}</SelectContent></Select>
            <Tooltip delayDuration={400}><TooltipTrigger asChild><Button type="button" size="icon-sm" variant={loop && selected ? 'secondary' : 'ghost'} disabled={!ready || !selected} aria-label={t('waveform.loop')} aria-pressed={loop && selected} onClick={() => setLoop(!loop)}><Repeat2 /></Button></TooltipTrigger><TooltipContent>{t('sourceSelection.loopHint')}</TooltipContent></Tooltip>
          </div>}
          selectionControls={<>
            <div className="source-toolbar-group" role="group" aria-label={t('sourceSelection.boundaries')}>
              {(['start', 'end'] as const).map(edge => <Tooltip key={edge} delayDuration={400}><TooltipTrigger asChild><Button type="button" size="sm" variant="ghost" aria-label={t(edge === 'start' ? 'sourceSelection.markStart' : 'sourceSelection.markEnd')} disabled={!ready || (edge === 'end' && startMs === null)} onClick={() => mark(edge)}>{t(edge === 'start' ? 'sourceSelection.markStartShort' : 'sourceSelection.markEndShort')}</Button></TooltipTrigger><TooltipContent>{t(edge === 'start' ? 'sourceSelection.markStart' : 'sourceSelection.markEnd')}</TooltipContent></Tooltip>)}
              {canSelectFullVideo && <Tooltip delayDuration={400}><TooltipTrigger asChild><Button type="button" size="sm" variant={fullVideoSelected ? 'secondary' : 'ghost'} aria-label={t('sourceSelection.selectFullVideo')} aria-pressed={fullVideoSelected} onClick={() => { setStart(display(0)); setEnd(display(durationMs)); setError(false); onRangeChange(0, durationMs); }}>{t('sourceSelection.fullVideoShort')}</Button></TooltipTrigger><TooltipContent>{t('sourceSelection.selectFullVideo')}</TooltipContent></Tooltip>}
            </div>
            <Dialog><Tooltip delayDuration={400}><TooltipTrigger asChild><DialogTrigger asChild><Button type="button" size="icon-sm" variant="ghost" aria-label={t('sourceSelection.help')}><RiQuestionLine /></Button></DialogTrigger></TooltipTrigger><TooltipContent>{t('sourceSelection.help')}</TooltipContent></Tooltip><DialogContent><DialogHeader><DialogTitle>{t('sourceSelection.help')}</DialogTitle><DialogDescription>{t('sourceSelection.helpIntro')}</DialogDescription></DialogHeader><ul className="source-help-steps">{['marks', 'timecodes', 'drag', 'view', 'keyboard', 'limit'].map(key => <li key={key}>{t(`sourceSelection.helpSteps.${key}`)}</li>)}</ul></DialogContent></Dialog>
          </>} />
      </Suspense>
      <div className="source-selection-controls">
        {(['start', 'end'] as const).map(edge => <div key={edge} className="source-boundary">
          <label htmlFor={`source-${edge}`}>{t(`waveform.${edge}`)}</label>
            <Input id={`source-${edge}`} aria-label={t(`waveform.${edge}`)} placeholder="00:00.000" value={edge === 'start' ? start : end} aria-invalid={(start !== '' && (a === null || a >= durationMs)) || (end !== '' && !valid)} onChange={event => (edge === 'start' ? setStart : setEnd)(event.target.value)} onBlur={commit} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); commit(); } }} />
        </div>)}
        {selected && <Badge variant="secondary" aria-label={t('sourceSelection.duration')}>{timecode(endMs - startMs)}</Badge>}
      </div>
      <div className="source-selection-help" data-pending={!selected}><p className="text-xs text-muted-foreground" role="status">{!selected && <RiInformationLine aria-hidden="true" />}{t(fullVideoSelected ? 'sourceSelection.fullVideoSelected' : selected ? 'sourceSelection.limit' : startMs !== null ? 'sourceSelection.chooseEnd' : videoId ? canSelectFullVideo ? 'sourceSelection.chooseVideoRange' : 'sourceSelection.longVideo' : 'sourceSelection.chooseRange')}</p>
        <span className="source-clock text-xs text-muted-foreground"><PlaybackTime clock={clock} /> / {timecode(durationMs)}</span>
      </div>
      {error && <p role="alert" className="text-sm text-destructive">{t('waveform.invalidRange')}</p>}
    </div>
  </div>;
}

function SourcePlayer({ audioUrl, videoId, ...props }: React.ComponentProps<typeof YouTubePreview> & { audioUrl?: string }) {
  return audioUrl ? <PrivateAudio url={audioUrl} {...props} /> : <YouTubePreview videoId={videoId} {...props} />;
}
