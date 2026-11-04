import { useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { Timeline, type TimelineState, type TimelineEditor } from '@xzdarcy/react-timeline-editor';
import '@xzdarcy/react-timeline-editor/dist/react-timeline-editor.css';
import { RiZoomInLine, RiZoomOutLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import type { PlaybackClock } from './playback-clock';
import { timecode } from './timecode';
import { boundedView, selectionView, validSelection, zoomView, type TimelineView } from './timeline-view';

const START_LEFT = 12;
const EFFECTS = { source: { id: 'source', name: 'Source' } };

function TimelineSeek({ clock, view, durationMs, pixelsPerMs, onSeek }: {
  clock: PlaybackClock; view: TimelineView; durationMs: number; pixelsPerMs: number; onSeek: (atMs: number) => void;
}) {
  const { t } = useTranslation('admin');
  const atMs = useSyncExternalStore(clock.subscribe, clock.get);
  return <input type="range" className="source-timeline-seek" min={0} max={durationMs} step={1}
    style={{ left: START_LEFT - view.startMs * pixelsPerMs, width: durationMs * pixelsPerMs }}
    value={Math.max(0, Math.min(durationMs, atMs))}
    aria-label={t('authoringUi.seekPosition')} aria-valuetext={timecode(atMs)}
    onKeyDown={event => event.stopPropagation()}
    onChange={event => onSeek(Number(event.target.value))} />;
}

/** The library edits ranges; YouTube remains the only playback clock. */
export function RangeTimeline({ clock, durationMs, startMs: start, endMs: end, onSeek, onRangeChange }: {
  clock: PlaybackClock; durationMs: number; startMs: number | null; endMs: number | null;
  onSeek: (atMs: number) => void; onRangeChange: (start: number, end: number) => void;
}) {
  const { t } = useTranslation('admin');
  const startMs = start ?? 0, endMs = end ?? 0;
  const selected = start !== null && end !== null && validSelection(start, end, durationMs);
  const host = useRef<HTMLDivElement>(null);
  const editor = useRef<TimelineState>(null);
  const hover = useRef<HTMLSpanElement>(null);
  const dragged = useRef(false);
  const programmaticScroll = useRef(false);
  const scrollFrame = useRef<number | null>(null);
  const [width, setWidth] = useState(0);
  const [selectionRevision, setSelectionRevision] = useState(0);
  const [viewport, setViewport] = useState<TimelineView>({ startMs: 0, endMs: durationMs });
  const view = boundedView(viewport.startMs, viewport.endMs - viewport.startMs, durationMs);
  const spanMs = view.endMs - view.startMs;
  const pixelsPerMs = Math.max(1, width - START_LEFT) / Math.max(1, spanMs);
  const wantedStep = spanMs / 1000 / Math.max(1, width / 100);
  const magnitude = 10 ** Math.floor(Math.log10(Math.max(0.001, wantedStep)));
  const scale = ([1, 2, 5, 10].find(value => value * magnitude >= wantedStep) ?? 10) * magnitude;
  const scaleCount = Math.max(1, Math.ceil(durationMs / 1000 / scale));
  const rows = useMemo<TimelineEditor['editorData']>(() => [{ id: 'source', actions: selected ? [{
    id: 'selection', effectId: 'source', start: startMs / 1000, end: endMs / 1000,
    minStart: 0, maxEnd: durationMs / 1000,
  }] : [] }], [startMs, endMs, durationMs, selected, selectionRevision]);

  useLayoutEffect(() => {
    const element = host.current;
    if (!element) return;
    const measure = () => setWidth(Math.max(0, element.getBoundingClientRect().width));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useLayoutEffect(() => {
    syncScroll(view.startMs * pixelsPerMs);
    return () => { if (scrollFrame.current !== null) cancelAnimationFrame(scrollFrame.current); };
  }, [view.startMs, pixelsPerMs]);
  useEffect(() => {
    const sync = () => editor.current?.setTime(Math.max(0, Math.min(durationMs, clock.get())) / 1000);
    sync();
    return clock.subscribe(sync);
  }, [clock, durationMs, width]);

  useEffect(() => {
    const element = host.current;
    if (!element || width <= START_LEFT) return;
    function wheel(event: WheelEvent) {
      // Keep browser zoom and horizontal/Shift-wheel scrolling available.
      if (event.ctrlKey || event.metaKey || event.shiftKey || !event.deltaY || Math.abs(event.deltaX) >= Math.abs(event.deltaY)) return;
      event.preventDefault();
      event.stopPropagation();
      const box = element!.getBoundingClientRect();
      const fraction = Math.max(0, Math.min(1, (event.clientX - box.left - START_LEFT) / (box.width - START_LEFT)));
      const pixels = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? box.height : 1);
      const factor = 2 ** (Math.max(-300, Math.min(300, pixels)) / 300);
      setViewport(previous => {
        const span = previous.endMs - previous.startMs;
        const nextSpan = Math.min(durationMs, Math.max(Math.min(1000, durationMs), span * factor));
        return boundedView(previous.startMs + (span - nextSpan) * fraction, nextSpan, durationMs);
      });
    }
    // React's delegated wheel listener is passive; prevent native grid scrolling here.
    element.addEventListener('wheel', wheel, { passive: false, capture: true });
    return () => element.removeEventListener('wheel', wheel, true);
  }, [durationMs, width]);

  function seek(atMs: number) {
    const at = Math.round(Math.max(0, Math.min(durationMs, atMs)));
    clock.set(at);
    onSeek(at);
  }
  function syncScroll(left: number) {
    if (scrollFrame.current !== null) cancelAnimationFrame(scrollFrame.current);
    programmaticScroll.current = true;
    editor.current?.setScrollLeft(Math.round(left));
    scrollFrame.current = requestAnimationFrame(() => {
      programmaticScroll.current = false;
      scrollFrame.current = null;
    });
  }
  function valid(start: number, end: number) {
    return validSelection(Math.round(start * 1000), Math.round(end * 1000), durationMs);
  }
  function moveRange(key: string, shift: boolean) {
    const delta = (key === 'ArrowRight' ? 1 : -1) * (shift ? 10000 : 1000);
    const next = key === 'Home' ? 0 : key === 'End' ? durationMs - (endMs - startMs)
      : Math.max(0, Math.min(durationMs - (endMs - startMs), startMs + delta));
    onRangeChange(next, next + endMs - startMs);
  }
  return <div className="source-timeline-editor">
    <div className="source-timeline-toolbar" role="toolbar" aria-label={t('sourceTimeline.controls')}>
      <Tooltip delayDuration={400}><TooltipTrigger asChild><Button type="button" variant="outline" size="icon" aria-label={t('sourceTimeline.zoomOut')}
        disabled={spanMs >= durationMs} onClick={() => setViewport(zoomView(view, durationMs, clock.get(), 2))}><RiZoomOutLine /></Button></TooltipTrigger><TooltipContent>{t('sourceTimeline.zoomOut')}</TooltipContent></Tooltip>
      <Tooltip delayDuration={400}><TooltipTrigger asChild><Button type="button" variant="outline" size="icon" aria-label={t('sourceTimeline.zoomIn')}
        disabled={spanMs <= Math.min(1000, durationMs)} onClick={() => setViewport(zoomView(view, durationMs, clock.get(), 0.5))}><RiZoomInLine /></Button></TooltipTrigger><TooltipContent>{t('sourceTimeline.zoomIn')}</TooltipContent></Tooltip>
      <Button type="button" variant="ghost" size="sm" onClick={() => setViewport({ startMs: 0, endMs: durationMs })}>{t('sourceTimeline.fitVideo')}</Button>
      <Button type="button" variant="ghost" size="sm" disabled={!selected} onClick={() => setViewport(selectionView(startMs, endMs, durationMs))}>{t('sourceTimeline.fitSelection')}</Button>
      <span className="text-xs text-muted-foreground tabular-nums">{timecode(view.startMs)}–{timecode(view.endMs)}</span>
    </div>
    <div ref={host} className="source-timeline-surface" tabIndex={0} role="group" aria-label={t('authoringUi.timeline')}
      onPointerDownCapture={() => { dragged.current = false; }}
      onPointerMove={event => {
        if (!hover.current) return;
        const box = event.currentTarget.getBoundingClientRect();
        const x = event.clientX - box.left;
        hover.current.textContent = timecode(Math.max(0, Math.min(durationMs, view.startMs + (x - START_LEFT) / pixelsPerMs)));
        hover.current.style.left = `${Math.max(40, Math.min(width - 40, x))}px`;
        hover.current.hidden = false;
      }}
      onPointerLeave={() => { if (hover.current) hover.current.hidden = true; }}
      onKeyDown={event => {
        if (event.target !== event.currentTarget || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        seek(event.key === 'Home' ? 0 : event.key === 'End' ? durationMs
          : clock.get() + (event.key === 'ArrowRight' ? 1 : -1) * (event.shiftKey ? 10000 : 1000));
      }}>
      {/* Zero-width virtualized grids can recurse while synchronizing scroll positions. */}
      {width > START_LEFT && <Timeline ref={editor} editorData={rows} effects={EFFECTS} scale={scale} scaleWidth={scale * 1000 * pixelsPerMs}
        scaleSplitCount={5} startLeft={START_LEFT} minScaleCount={scaleCount} maxScaleCount={scaleCount}
        rowHeight={48} style={{ width: '100%', height: 112 }} autoScroll autoReRender={false}
        getScaleRender={seconds => seconds * 1000 <= durationMs ? timecode(seconds * 1000) : ''}
        onScroll={params => {
          const { scrollLeft } = params;
          if (params.clientWidth === 0) return;
          if (programmaticScroll.current) return;
          if (Math.abs(scrollLeft - view.startMs * pixelsPerMs) <= 1) return;
          const next = boundedView(scrollLeft / pixelsPerMs, spanMs, durationMs);
          if (Math.abs(next.startMs - view.startMs) > 0.5) setViewport(next);
          if (Math.abs(scrollLeft - next.startMs * pixelsPerMs) > 1) syncScroll(next.startMs * pixelsPerMs);
        }}
        onCursorDrag={seconds => { dragged.current = true; seek(seconds * 1000); }}
        onClickTimeArea={seconds => { seek(seconds * 1000); return false; }}
        onClickRow={(event, { time }) => { if (!dragged.current && event.target === event.currentTarget) seek(time * 1000); }}
        onClickActionOnly={(event, { time }) => { event.stopPropagation(); if (!dragged.current) seek(time * 1000); }}
        onActionMoving={({ start, end }) => { dragged.current = true; return valid(start, end); }}
        onActionResizing={({ start, end }) => { dragged.current = true; return valid(start, end); }}
        onChange={data => {
          const action = data[0]?.actions[0];
          if (action && valid(action.start, action.end)) onRangeChange(Math.round(action.start * 1000), Math.round(action.end * 1000));
          // The library mutates action data; restore controlled props after a rejected commit.
          else setSelectionRevision(value => value + 1);
          return false;
        }}
        getActionRender={() => <div className="source-timeline-selection" role="slider" tabIndex={0}
          aria-label={t('waveform.moveRange')} aria-valuemin={0} aria-valuemax={durationMs - (endMs - startMs)}
          aria-valuenow={startMs} aria-valuetext={`${timecode(startMs)}–${timecode(endMs)}`}
          onKeyDown={event => {
            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
            event.preventDefault(); event.stopPropagation(); moveRange(event.key, event.shiftKey);
          }}>{timecode(startMs)}–{timecode(endMs)}</div>} />}
      {width > START_LEFT && <TimelineSeek clock={clock} view={view} durationMs={durationMs} pixelsPerMs={pixelsPerMs} onSeek={seek} />}
      {!selected && start !== null && start >= view.startMs && start <= view.endMs && <span className="source-start-marker" role="img" aria-label={`${t('waveform.start')}: ${timecode(start)}`} style={{ left: START_LEFT + (start - view.startMs) * pixelsPerMs }} />}
      <span ref={hover} className="source-timeline-hover" aria-hidden="true" hidden />
    </div>
  </div>;
}
