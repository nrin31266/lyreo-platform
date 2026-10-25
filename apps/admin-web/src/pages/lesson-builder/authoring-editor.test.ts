import { describe, expect, it } from 'vitest';
import { mergeSentence, playbackAction, splitSentence } from './authoring-editor';
import type { AuthoringSentence, TranscriptSentence } from './lesson-builder.types';

const rows: TranscriptSentence[] = [
  { id: 'a', text: 'Hello world.', usage: 'LEARN' },
  { id: 'b', text: 'Next sentence.', usage: 'LEARN' },
];

describe('authoring edits', () => {
  it('preserves the first sentence identity and gives the split half a new server identity', () => {
    expect(splitSentence(rows, 0, 6)).toEqual([
      { id: 'a', text: 'Hello', usage: 'LEARN' },
      { text: 'world.', usage: 'LEARN' }, rows[1],
    ]);
    expect(splitSentence(rows, 0, 0)).toBe(rows);
  });
  it('merges adjacent rows without losing content or silently changing usage', () => {
    expect(mergeSentence(rows, 0)).toEqual([{ id: 'a', text: 'Hello world. Next sentence.', usage: 'LEARN' }]);
    const different: TranscriptSentence[] = [rows[0], { ...rows[1], usage: 'WATCH_ONLY' }];
    expect(mergeSentence(different, 0)).toBe(different);
  });
});

describe('official player bounds after seeking', () => {
  const skipped = { usage: 'SKIP', words: [{ startMs: 1000, endMs: 2000 }] } as AuthoringSentence;
  it('uses the source offset and skips excluded ranges without changing relative timing', () => {
    expect(playbackAction(11500, 10000, 20000, [skipped])).toEqual({ seekMs: 12000 });
    expect(playbackAction(13000, 10000, 20000, [skipped])).toEqual({});
  });
  it('pauses at the chosen end even after seekTo invalidates YouTube endSeconds', () => {
    expect(playbackAction(20000, 10000, 20000, [])).toEqual({ pause: true });
    expect(playbackAction(25000, 10000, 20000, [])).toEqual({ pause: true });
    expect(playbackAction(5000, 10000, 20000, [])).toEqual({ seekMs: 10000 });
  });
  it('pauses when a skip reaches the end of the lesson range', () => {
    expect(playbackAction(11500, 10000, 12000, [skipped])).toEqual({ pause: true });
  });
});
