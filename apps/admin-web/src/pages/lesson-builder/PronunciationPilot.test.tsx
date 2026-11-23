import { describe, expect, it } from 'vitest';
import { markedReference, wordPlaybackRange } from './PronunciationFeedback';
import type { PronunciationWord } from './pronunciation.api';

function word(text: string, referenceIndex: number | null, error: PronunciationWord['error'] = 'NONE'): PronunciationWord {
  return { text, referenceIndex, error, startMs: null, endMs: null, accuracy: null, phonemes: [] };
}

describe('pronunciation reference mapping', () => {
  it('keeps punctuation and places inserted and repeated words by their assessment position', () => {
    const parts = markedReference('“Go, go!” Don’t wait.', [
      word('go', 0), word('uh', null, 'INSERTION'), word('go', 1, 'MISPRONUNCIATION'),
      word("don't", 2), word('wait', 3, 'OMISSION'),
    ]);
    expect(parts).toEqual([
      { text: '“' }, { text: 'Go', wordIndex: 0 }, { text: ', ' }, { text: 'uh', wordIndex: 1 },
      { text: 'go', wordIndex: 2 }, { text: '!” ' }, { text: 'Don’t', wordIndex: 3 },
      { text: ' ' }, { text: 'wait', wordIndex: 4 }, { text: '.' },
    ]);
  });

  it('falls back when indices are missing, duplicated or point to a different word', () => {
    expect(markedReference('Go, go!', [word('go', null), word('go', 1)])).toBeUndefined();
    expect(markedReference('Go, go!', [word('go', 0), word('go', 0)])).toBeUndefined();
    expect(markedReference('Go, go!', [word('go', 0), word('stop', 1)])).toBeUndefined();
  });
});

describe('pronunciation playback', () => {
  it('pads words and clamps either end to the recording', () => {
    expect(wordPlaybackRange({ startMs: 40, endMs: 330 }, 800)).toEqual({ start: 0, end: 0.48 });
    expect(wordPlaybackRange({ startMs: 500, endMs: 760 }, 800)).toEqual({ start: 0.4, end: 0.8 });
    expect(wordPlaybackRange({ startMs: null, endMs: null }, 800)).toBeUndefined();
    expect(wordPlaybackRange({ startMs: 760, endMs: 900 }, 800)).toBeUndefined();
  });
});
