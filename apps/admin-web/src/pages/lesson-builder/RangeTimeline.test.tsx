import { TooltipProvider } from '@/components/ui/tooltip';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import type { TimelineEditor, TimelineState } from '@xzdarcy/react-timeline-editor';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '@/i18n';
import { RangeTimeline } from './RangeTimeline';
import { createPlaybackClock } from './playback-clock';
import { boundedView, selectionView, validSelection, zoomView } from './timeline-view';

const model = vi.hoisted(() => ({ props: null as TimelineEditor | null, setTime: vi.fn(), setScrollLeft: vi.fn() }));
vi.mock('@xzdarcy/react-timeline-editor', async () => {
  const { forwardRef, useImperativeHandle } = await import('react');
  return { Timeline: forwardRef<TimelineState, TimelineEditor>((props, ref) => {
    model.props = props;
    useImperativeHandle(ref, () => ({ setTime: model.setTime, setScrollLeft: model.setScrollLeft }) as unknown as TimelineState);
    return <div>{props.editorData[0].actions.map(action => <div key={action.id}>{props.getActionRender?.(action, props.editorData[0])}</div>)}</div>;
  }) };
});
beforeEach(() => {
  vi.clearAllMocks();
  model.setScrollLeft.mockReset();
  model.props = null;
  vi.useFakeTimers();
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ width: 612 } as DOMRect);
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function setup(durationMs = 120000) {
  const clock = createPlaybackClock(), onSeek = vi.fn(), onRangeChange = vi.fn();
  render(<I18nextProvider i18n={i18n}><TooltipProvider><RangeTimeline clock={clock} durationMs={durationMs}
    startMs={10000} endMs={60000} onSeek={onSeek} onRangeChange={onRangeChange} /></TooltipProvider></I18nextProvider>);
  return { clock, onSeek, onRangeChange, playhead: screen.getByRole('slider', { name: i18n.t('admin:authoringUi.seekPosition') }) };
}

it('seeks in milliseconds and synchronizes the library cursor without another playback engine', () => {
  const { clock, onSeek, onRangeChange, playhead } = setup();
  fireEvent.change(playhead, { target: { value: '30000' } });
  expect(clock.get()).toBe(30000);
  expect(onSeek).toHaveBeenCalledExactlyOnceWith(30000);
  act(() => clock.set(45000));
  expect(model.setTime).toHaveBeenLastCalledWith(45);
  expect(playhead).toHaveValue('45000');
  expect(playhead).toHaveAttribute('aria-valuetext', '00:45.000');
  expect(onRangeChange).not.toHaveBeenCalled();
});

it('zooms, fits selection, fits the video and scrolls without changing selection or seeking', () => {
  const { onSeek, onRangeChange, playhead } = setup();
  fireEvent.click(screen.getByRole('button', { name: 'Fit selection' }));
  expect(screen.getByText('00:05.000–01:05.000')).toBeInTheDocument();
  expect(playhead).toHaveValue('0');
  fireEvent.click(screen.getByRole('button', { name: 'Zoom timeline in' }));
  expect(screen.getByText('00:20.000–00:50.000')).toBeInTheDocument();
  const pixelsPerMs = model.props!.scaleWidth! / (model.props!.scale! * 1000);
  act(() => { vi.advanceTimersByTime(20); });
  act(() => model.props!.onScroll?.({ scrollLeft: 50000 * pixelsPerMs } as Parameters<NonNullable<TimelineEditor['onScroll']>>[0]));
  expect(screen.getByText('00:50.000–01:20.000')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Full video' }));
  expect(playhead).toHaveAttribute('min', '0');
  expect(playhead).toHaveAttribute('max', '120000');
  expect(onSeek).not.toHaveBeenCalled();
  expect(onRangeChange).not.toHaveBeenCalled();
});

it('ignores synchronous library scroll echoes while fitting or resizing the viewport', () => {
  setup();
  model.setScrollLeft.mockImplementation(() => {
    model.props!.onScroll?.({ scrollLeft: 0 } as Parameters<NonNullable<TimelineEditor['onScroll']>>[0]);
  });
  fireEvent.click(screen.getByRole('button', { name: 'Fit selection' }));
  expect(screen.getByText('00:05.000–01:05.000')).toBeInTheDocument();
  act(() => { vi.advanceTimersByTime(20); });
  expect(screen.getByText('00:05.000–01:05.000')).toBeInTheDocument();
});

it('preserves the view when its container is temporarily hidden and shown again', () => {
  let measure!: () => void;
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { measure = callback; }
    observe() {} unobserve() {} disconnect() {}
  });
  setup();
  fireEvent.click(screen.getByRole('button', { name: 'Fit selection' }));
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({ width: 0 } as DOMRect);
  act(() => measure());
  expect(screen.queryByRole('slider', { name: 'Playback position' })).not.toBeInTheDocument();
  expect(screen.getByText('00:05.000–01:05.000')).toBeInTheDocument();
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({ width: 612 } as DOMRect);
  act(() => measure());
  expect(screen.getByRole('slider', { name: 'Playback position' })).toBeInTheDocument();
  expect(screen.getByText('00:05.000–01:05.000')).toBeInTheDocument();
});

it('does not drift the viewport when the browser rounds a programmatic scroll offset', () => {
  setup();
  fireEvent.click(screen.getByRole('button', { name: 'Fit selection' }));
  const scrollLeft = Math.round(model.setScrollLeft.mock.lastCall![0]);
  act(() => { vi.advanceTimersByTime(20); });
  act(() => model.props!.onScroll?.({ scrollLeft } as Parameters<NonNullable<TimelineEditor['onScroll']>>[0]));
  expect(screen.getByText('00:05.000–01:05.000')).toBeInTheDocument();
});

it('accepts valid library edits and rejects reversed, out-of-source and over-ten-minute selections', () => {
  const { onRangeChange } = setup(1200000);
  const props = model.props!;
  const action = props.editorData[0].actions[0], row = props.editorData[0];
  for (const [start, end] of [[-1, 2], [2, 1], [1190, 1210], [0, 601]]) {
    expect(props.onActionResizing?.({ action, row, start, end, dir: 'right' })).toBe(false);
  }
  expect(props.onActionMoving?.({ action, row, start: 20.001, end: 40.002 })).toBe(true);
  act(() => props.onChange?.([{ ...row, actions: [{ ...action, start: 20.001, end: 40.002 }] }]));
  expect(onRangeChange).toHaveBeenCalledExactlyOnceWith(20001, 40002);
});

it('restores controlled selection data when the library mutates an invalid commit', () => {
  const { onRangeChange } = setup();
  const data = model.props!.editorData;
  data[0].actions[0].end = 700;
  act(() => model.props!.onChange?.(data));
  expect(onRangeChange).not.toHaveBeenCalled();
  expect(model.props!.editorData[0].actions[0].end).toBe(60);
});

it('does not seek when a drag releases outside its handle and clicks the common row ancestor', () => {
  const { onSeek } = setup();
  const row = document.createElement('div');
  const props = model.props!;
  const event = { target: row, currentTarget: row } as unknown as Parameters<NonNullable<TimelineEditor['onClickRow']>>[0];
  props.onActionResizing?.({ action: props.editorData[0].actions[0], row: props.editorData[0], start: 10, end: 30, dir: 'right' });
  act(() => props.onClickRow?.(event, { row: props.editorData[0], time: 30 }));
  expect(onSeek).not.toHaveBeenCalled();
  fireEvent.pointerDown(screen.getByRole('group', { name: 'Range and playback timeline' }));
  act(() => props.onClickRow?.(event, { row: props.editorData[0], time: 30 }));
  expect(onSeek).toHaveBeenCalledExactlyOnceWith(30000);
});

it('preserves keyboard seeking and keyboard movement of the selection', () => {
  const { clock, onSeek, onRangeChange } = setup();
  fireEvent.keyDown(screen.getByRole('group', { name: 'Range and playback timeline' }), { key: 'ArrowRight', shiftKey: true });
  expect(clock.get()).toBe(10000);
  expect(onSeek).toHaveBeenLastCalledWith(10000);
  fireEvent.keyDown(screen.getByRole('slider', { name: 'Move selection across the full video' }), { key: 'End' });
  expect(onRangeChange).toHaveBeenLastCalledWith(70000, 120000);
});

it('anchors zoom on a visible playhead and otherwise uses the viewport center', () => {
  expect(zoomView({ startMs: 10000, endMs: 110000 }, 120000, 90000, 0.5)).toEqual({ startMs: 65000, endMs: 115000 });
  expect(zoomView({ startMs: 10000, endMs: 110000 }, 120000, 0, 0.5)).toEqual({ startMs: 35000, endMs: 85000 });
  expect(boundedView(5000, 0, 120000)).toEqual({ startMs: 5000, endMs: 6000 });
  expect(selectionView(0, 500, 500)).toEqual({ startMs: 0, endMs: 500 });
  expect(validSelection(0, 600000, 1200000)).toBe(true);
  expect(validSelection(NaN, 600000, 1200000)).toBe(false);
});

it('renders an empty source track, seeks without selecting, then shows only a pending start marker', () => {
  const clock = createPlaybackClock(), onSeek = vi.fn(), onRangeChange = vi.fn();
  const view = (start: number | null) => <I18nextProvider i18n={i18n}><TooltipProvider><RangeTimeline
    clock={clock} durationMs={120000} startMs={start} endMs={null} onSeek={onSeek} onRangeChange={onRangeChange} /></TooltipProvider></I18nextProvider>;
  const { rerender } = render(view(null));
  expect(model.props!.editorData[0].actions).toEqual([]);
  expect(screen.getByRole('button', {name:'Fit selection'})).toBeDisabled();
  fireEvent.change(screen.getByRole('slider', {name:'Playback position'}), {target:{value:'30000'}});
  expect(onSeek).toHaveBeenLastCalledWith(30000);
  fireEvent.click(screen.getByRole('button', {name:'Zoom timeline in'}));
  expect(onRangeChange).not.toHaveBeenCalled();
  rerender(view(15000));
  expect(model.props!.editorData[0].actions).toEqual([]);
  expect(screen.getByRole('img', {name:'Start: 00:15.000'})).toBeInTheDocument();
  expect(screen.queryByRole('slider', {name:'Move selected range'})).not.toBeInTheDocument();
});

it('zooms with vertical wheel around the pointer without seeking or editing the selection', () => {
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({ left:0, width:612, height:114 } as DOMRect);
  const { onSeek, onRangeChange } = setup();
  const timeline = screen.getByRole('group', {name:i18n.t('admin:authoringUi.timeline')});
  expect(fireEvent.wheel(timeline, {deltaY:-300, clientX:162})).toBe(false);
  expect(screen.getByText('00:15.000–01:15.000')).toBeInTheDocument();
  fireEvent.wheel(timeline, {deltaY:300, clientX:162});
  expect(screen.getByText('00:00.000–02:00.000')).toBeInTheDocument();
  expect(onSeek).not.toHaveBeenCalled();
  expect(onRangeChange).not.toHaveBeenCalled();
});
it('keeps wheel zoom within one second and the full source, including line and page deltas', () => {
  vi.mocked(HTMLElement.prototype.getBoundingClientRect).mockReturnValue({ left:0, width:612, height:114 } as DOMRect);
  setup();
  const timeline = screen.getByRole('group', {name:i18n.t('admin:authoringUi.timeline')});
  for (let i=0;i<12;i++) fireEvent.wheel(timeline, {deltaY:-20, deltaMode:1, clientX:312});
  const span = () => 600 * model.props!.scale! * 1000 / model.props!.scaleWidth!;
  expect(span()).toBeCloseTo(1000);
  for (let i=0;i<12;i++) fireEvent.wheel(timeline, {deltaY:3, deltaMode:2, clientX:312});
  expect(span()).toBeCloseTo(120000);
});
it('leaves browser zoom and horizontal or Shift-wheel scrolling unhandled', () => {
  setup();
  const timeline = screen.getByRole('group', {name:i18n.t('admin:authoringUi.timeline')});
  for (const event of [{ctrlKey:true}, {metaKey:true}, {shiftKey:true}, {deltaX:400}, {deltaY:0}]) {
    expect(fireEvent.wheel(timeline, {deltaY:-100, clientX:312, ...event})).toBe(true);
  }
  expect(screen.getByText('00:00.000–02:00.000')).toBeInTheDocument();
});
