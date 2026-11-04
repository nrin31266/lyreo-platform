export type TimelineView = { startMs: number; endMs: number };

export function boundedView(startMs: number, spanMs: number, durationMs: number): TimelineView {
  const span = Math.min(durationMs, Math.max(Math.min(1000, durationMs), spanMs));
  const start = Math.max(0, Math.min(durationMs - span, startMs));
  return { startMs: start, endMs: start + span };
}

export function zoomView(view: TimelineView, durationMs: number, playheadMs: number, factor: number) {
  const span = Math.min(durationMs, Math.max(Math.min(1000, durationMs), (view.endMs - view.startMs) * factor));
  const anchor = playheadMs >= view.startMs && playheadMs <= view.endMs
    ? playheadMs : (view.startMs + view.endMs) / 2;
  return boundedView(anchor - span / 2, span, durationMs);
}

export function selectionView(startMs: number, endMs: number, durationMs: number) {
  const padding = (endMs - startMs) * 0.1;
  const span = Math.max(Math.min(1000, durationMs), endMs - startMs + 2 * padding);
  return boundedView((startMs + endMs - span) / 2, span, durationMs);
}

export function validSelection(startMs: number, endMs: number, durationMs: number) {
  return Number.isFinite(startMs) && Number.isFinite(endMs) && startMs >= 0 &&
    endMs > startMs && endMs <= durationMs && endMs - startMs <= 600000;
}
