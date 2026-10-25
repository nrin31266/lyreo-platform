import type { AuthoringSentence, TranscriptSentence } from './lesson-builder.types';

export function splitSentence(sentences: TranscriptSentence[], index: number, offset: number): TranscriptSentence[] {
  const sentence = sentences[index];
  const left = sentence.text.slice(0, offset).trim();
  const right = sentence.text.slice(offset).trim();
  if (!left || !right) return sentences;
  return [...sentences.slice(0, index), { ...sentence, text: left },
    { text: right, usage: sentence.usage }, ...sentences.slice(index + 1)];
}

export function mergeSentence(sentences: TranscriptSentence[], index: number): TranscriptSentence[] {
  if (index >= sentences.length - 1) return sentences;
  const first = sentences[index];
  const second = sentences[index + 1];
  if (first.usage !== second.usage) return sentences;
  return [...sentences.slice(0, index), { ...first, text: `${first.text.trim()} ${second.text.trim()}` },
    ...sentences.slice(index + 2)];
}

export function playbackAction(sourceMs: number, startMs: number, endMs: number, sentences: AuthoringSentence[]) {
  if (sourceMs >= endMs) return { pause: true };
  if (sourceMs < startMs) return { seekMs: startMs };
  const relativeMs = sourceMs - startMs;
  const skipped = sentences.find(sentence => sentence.usage === 'SKIP' && sentence.words.length > 0
    && relativeMs >= sentence.words[0].startMs && relativeMs < sentence.words.at(-1)!.endMs);
  if (skipped) {
    const next = startMs + skipped.words.at(-1)!.endMs;
    return next >= endMs ? { pause: true } : { seekMs: next };
  }
  return {};
}
