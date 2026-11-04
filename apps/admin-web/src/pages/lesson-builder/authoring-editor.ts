import type { AuthoringSentence, TranscriptSentence } from './lesson-builder.types';

export function splitSentence(sentences: TranscriptSentence[], index: number, offset: number): TranscriptSentence[] {
  const sentence = sentences[index];
  const left = sentence.text.slice(0, offset).trim();
  const right = sentence.text.slice(offset).trim();
  if (!left || !right) return sentences;
  return [...sentences.slice(0, index), { ...sentence, text: left },
    { editorKey: crypto.randomUUID(), text: right, usage: sentence.usage }, ...sentences.slice(index + 1)];
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

export function sentencePlaybackRange(sentence: AuthoringSentence, sentences: AuthoringSentence[], durationMs: number) {
  const startMs = sentence.words[0]?.startMs ?? sentence.previewStartMs;
  if (startMs == null) return undefined;
  // Before alignment only a containing segment is known, never claim exact sentence timing.
  const endMs = sentence.words.at(-1)?.endMs ?? sentences
    .map(row => row.previewStartMs).find(at => at != null && at > startMs) ?? durationMs;
  return endMs > startMs ? { startMs, endMs } : undefined;
}

export function sentencePlaybackAction(atMs: number, sentenceId: string | null, continuous: boolean, sentences: AuthoringSentence[], durationMs: number) {
  const sentence = sentences.find(row => row.id === sentenceId);
  const range = sentence && sentencePlaybackRange(sentence, sentences, durationMs);
  if (!range) return { pause: true };
  if (atMs < range.startMs) return { seekMs: range.startMs };
  if (atMs < range.endMs) return {};
  const next = continuous && sentences.slice(sentences.indexOf(sentence!) + 1)
    .find(row => row.usage !== 'SKIP' && sentencePlaybackRange(row, sentences, durationMs));
  return next ? { sentenceId: next.id, seekMs: sentencePlaybackRange(next, sentences, durationMs)!.startMs }
    : { pause: true, stopMs: range.endMs };
}

export function thoughtGroupPreview(sentence: Pick<AuthoringSentence, 'text' | 'thoughtGroups'>): string {
  // Match Core AuthoringText tokens; observed timing groups can cross semantic boundaries.
  const tokens = [...sentence.text.matchAll(/[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*/gu)];
  return sentence.thoughtGroups.map(({ tokenStart, tokenEnd }) =>
    sentence.text.slice(tokenStart === 0 ? 0 : tokens[tokenStart].index,
      tokenEnd === tokens.length ? sentence.text.length : tokens[tokenEnd].index).trim(),
  ).join(' / ');
}
