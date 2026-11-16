import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { defaultRangeExtractor, useVirtualizer } from '@tanstack/react-virtual';
import type { TranscriptSentence } from './lesson-builder.types';

export function TranscriptList<T extends TranscriptSentence>({ sentences, visibleRows, filtered, emptyMessage, children, className = '', scrollToId, scrollRequest = 0, retainedIds, pageScroll = false }: {
  pageScroll?: boolean;
  sentences: T[];
  visibleRows: number[];
  filtered: boolean;
  emptyMessage?: string;
  children: (sentence: T, index: number) => ReactNode;
  className?: string;
  scrollToId?: string | null;
  scrollRequest?: number;
  retainedIds?: ReadonlySet<string>;
}) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [scrollMargin, setScrollMargin] = useState(0);
  const scrollElement = () => pageScroll ? scrollRef.current?.closest<HTMLElement>("#main-content") ?? document.scrollingElement as HTMLElement : scrollRef.current;
  useLayoutEffect(() => { if(!pageScroll || !scrollRef.current) return; const root=scrollElement(); const update=()=>setScrollMargin(scrollRef.current!.getBoundingClientRect().top - (root?.getBoundingClientRect().top ?? 0) + (root?.scrollTop ?? 0)); update(); if(typeof ResizeObserver === "undefined") return; const observer=new ResizeObserver(update); observer.observe(scrollRef.current); return ()=>observer.disconnect(); },[pageScroll]);
  const [focusedKey, setFocusedKey] = useState<string | null>(null);
  const keys = visibleRows.map(index => sentences[index].id || sentences[index].editorKey || `new-${index}`);
  const focusedIndex = focusedKey === null ? -1 : keys.indexOf(focusedKey);
  const virtual = visibleRows.length > 20;
  const virtualizer = useVirtualizer({
    count: visibleRows.length,
    getScrollElement: scrollElement,
    scrollMargin,
    estimateSize: () => 128,
    getItemKey: index => keys[index],
    overscan: 3,
    enabled: virtual,
    rangeExtractor: range => {
      const indexes = defaultRangeExtractor(range);
      // Keep the focused editor mounted while the user scrolls elsewhere.
      if (focusedIndex >= 0) indexes.push(focusedIndex);
      keys.forEach((key, index) => { if (retainedIds?.has(key)) indexes.push(index); });
      return [...new Set(indexes)].sort((a, b) => a - b);
    },
  });
  useLayoutEffect(() => {
    setFocusedKey(null);
    if (scrollRef.current) scrollRef.current.scrollTop = 0;
    if (virtual) virtualizer.scrollToOffset(0);
  }, [filtered]);
  useLayoutEffect(() => {
    if (!scrollToId) return;
    const index=keys.indexOf(scrollToId);
    if (index<0) return;
    if (virtual) virtualizer.scrollToIndex(index,{align:'auto'});
    else scrollRef.current?.querySelector<HTMLElement>(`[data-index="${index}"]`)?.scrollIntoView?.({block:'nearest'});
  }, [scrollToId, scrollRequest]);

  const rows = virtual ? virtualizer.getVirtualItems() : visibleRows.map((_, index) => ({ index, key: keys[index], start: 0 }));
  return <div ref={scrollRef} className={`${pageScroll ? "studio-page-list" : "authoring-sentence-list"} ${className}`} role="list" style={{ overflowAnchor: 'none' }}
    onFocusCapture={event => setFocusedKey((event.target as HTMLElement).closest<HTMLElement>('[data-sentence-key]')?.dataset.sentenceKey ?? null)}
    onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocusedKey(null); }}>
    <div style={virtual ? { height: virtualizer.getTotalSize(), position: 'relative', width: '100%' } : undefined}>
      {rows.map(row => <div key={row.key} ref={virtual ? virtualizer.measureElement : undefined} data-index={row.index}
        data-sentence-key={keys[row.index]} role="listitem" aria-setsize={visibleRows.length} aria-posinset={row.index + 1}
        style={virtual ? { position: 'absolute', top: 0, left: 0, width: '100%', transform: `translateY(${row.start-scrollMargin}px)` } : undefined}>
        {children(sentences[visibleRows[row.index]], visibleRows[row.index])}
      </div>)}
    </div>
    {visibleRows.length === 0 && emptyMessage && <p role="status" className="py-6 text-sm text-muted-foreground">{emptyMessage}</p>}
  </div>;
}
