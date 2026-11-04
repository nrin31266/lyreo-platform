import { describe, expect, it } from 'vitest';
import { mergeSentence, playbackAction, sentencePlaybackAction, sentencePlaybackRange, splitSentence } from './authoring-editor';
import type { AuthoringSentence, TranscriptSentence } from './lesson-builder.types';

const rows: TranscriptSentence[] = [
  { id: 'a', text: 'Hello world.', usage: 'LEARN' },
  { id: 'b', text: 'Next sentence.', usage: 'LEARN' },
];

describe('authoring edits', () => {
  it('preserves the first sentence identity and gives the split half a new server identity', () => {
    expect(splitSentence(rows, 0, 6)).toEqual([
      { id: 'a', text: 'Hello', usage: 'LEARN' },
      { editorKey: expect.any(String), text: 'world.', usage: 'LEARN' }, rows[1],
    ]);
    expect(splitSentence(rows, 0, 0)).toBe(rows);
  });
  it('merges adjacent rows without losing content or silently changing usage', () => {
    expect(mergeSentence(rows, 0)).toEqual([{ id: 'a', text: 'Hello world. Next sentence.', usage: 'LEARN' }]);
    const different: TranscriptSentence[] = [rows[0], { ...rows[1], usage: 'WATCH_ONLY' }];
    expect(mergeSentence(different, 0)).toBe(different);
  });
});

describe('sentence listening policy', () => {
  const sentences = [
    { id: 'a', usage: 'LEARN', words: [{startMs: 100, endMs: 1000}] },
    { id: 'skip', usage: 'SKIP', words: [{startMs: 1200, endMs: 2000}] },
    { id: 'b', usage: 'WATCH_ONLY', words: [{startMs: 2500, endMs: 4000}] },
  ] as AuthoringSentence[];
  it('stops exactly at the selected sentence end by default', () => {
    expect(sentencePlaybackAction(999, 'a', false, sentences, 5000)).toEqual({});
    expect(sentencePlaybackAction(1100, 'a', false, sentences, 5000)).toEqual({pause: true, stopMs: 1000});
    expect(sentencePlaybackAction(0, 'a', false, sentences, 5000)).toEqual({seekMs: 100});
  });
  it('advances across gaps and Skip rows only in continuous mode, stopping at the final sentence', () => {
    expect(sentencePlaybackAction(1000, 'a', true, sentences, 5000)).toEqual({sentenceId: 'b', seekMs: 2500});
    expect(sentencePlaybackAction(4000, 'b', true, sentences, 5000)).toEqual({pause: true, stopMs: 4000});
    expect(sentencePlaybackAction(1000, null, true, sentences, 5000)).toEqual({pause: true});
  });
  it('bounds approximate preview by the next distinct segment anchor without inventing word timing', () => {
    const approximate = sentences.map((row,index)=>({...row,words:[],previewStartMs:index===2?2000:100}));
    expect(sentencePlaybackRange(approximate[0], approximate, 3000)).toEqual({startMs:100,endMs:2000});
    expect(sentencePlaybackRange(approximate[2], approximate, 3000)).toEqual({startMs:2000,endMs:3000});
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
