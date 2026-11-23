import { useId, useState } from 'react';
import { RiAddLine, RiErrorWarningLine, RiPlayLine, RiSubtractLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { cn } from 'cn';
import type { PronunciationSpan, PronunciationWord } from './pronunciation.api';

type Part = { text: string; wordIndex?: number };

function referenceTokens(text: string) {
  // Keep source offsets while applying the adapter's apostrophe and word rules.
  return [...text.matchAll(/[\p{L}\p{N}]+(?:['’‘][\p{L}\p{N}]+)*/gu)];
}

export function markedReference(referenceText: string, words: PronunciationWord[]): Part[] | undefined {
  const tokens = referenceTokens(referenceText);
  const parts: Part[] = [];
  const key = (text: string) => text.replace(/[’‘]/g, "'").toLowerCase();
  let nextToken = 0;
  let offset = 0;
  for (const [wordIndex, word] of words.entries()) {
    if (word.error === 'INSERTION') {
      if (word.referenceIndex != null) return;
      parts.push({ text: word.text, wordIndex });
      continue;
    }
    const token = tokens[nextToken];
    if (word.referenceIndex !== nextToken || !token || key(token[0]) !== key(word.text)) return;
    if (offset < token.index) parts.push({ text: referenceText.slice(offset, token.index) });
    parts.push({ text: token[0], wordIndex });
    offset = token.index + token[0].length;
    nextToken++;
    // Punctuation between reference words belongs to the reference even when a spoken word is inserted.
    const nextOffset = tokens[nextToken]?.index ?? referenceText.length;
    if (offset < nextOffset) parts.push({ text: referenceText.slice(offset, nextOffset) });
    offset = nextOffset;
  }
  if (nextToken !== tokens.length || !tokens.length) return;
  return parts;
}

const errorStyle = {
  NONE: '',
  MISPRONUNCIATION: 'bg-destructive/10 underline decoration-destructive decoration-2 underline-offset-4',
  OMISSION: 'border border-dashed border-warning/60 bg-warning/10',
  INSERTION: 'border border-dotted border-primary/60 bg-primary/10',
};
const errorIcon = {
  MISPRONUNCIATION: RiErrorWarningLine,
  OMISSION: RiSubtractLine,
  INSERTION: RiAddLine,
};

export function wordPlaybackRange(span: PronunciationSpan, durationMs: number) {
  const { startMs, endMs } = span;
  if (startMs === null || endMs === null || startMs < 0 || endMs <= startMs || endMs > durationMs) return undefined;
  return { start: Math.max(0, startMs - 100) / 1000, end: Math.min(durationMs, endMs + 150) / 1000 };
}

export function PronunciationFeedback({ referenceText, words, durationMs, activeWord, loaded, play, pause, playReference }: {
  referenceText: string;
  words: PronunciationWord[];
  durationMs: number;
  activeWord: number | undefined;
  loaded: boolean;
  play: (span: PronunciationSpan) => void;
  pause: () => void;
  playReference?: () => void;
}) {
  const { t } = useTranslation('admin');
  const title = useId();
  const detail = useId();
  const [selectedIndex, setSelectedIndex] = useState<number>();
  const parts = markedReference(referenceText, words);
  const issues = ['MISPRONUNCIATION', 'OMISSION', 'INSERTION'] as const;
  const selected = selectedIndex == null ? undefined : words[selectedIndex];
  const phonemes = [...(selected?.phonemes ?? [])].sort((a, b) => (a.accuracy ?? Infinity) - (b.accuracy ?? Infinity));

  function wordButton(word: PronunciationWord, index: number, text: string) {
    const label = word.error === 'NONE' ? undefined : t(`pronunciationPilot.word.${word.error}`);
    const Icon = word.error === 'NONE' ? undefined : errorIcon[word.error];
    return <button type="button" className={cn(
      'inline rounded-sm px-0.5 text-left wrap-anywhere hover:bg-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
      errorStyle[word.error], word.error === 'INSERTION' && 'mx-1 px-1',
      index === selectedIndex && 'outline-1 outline-offset-2 outline-ring',
      index === activeWord && 'bg-primary/20 ring-2 ring-primary ring-offset-1 ring-offset-background',
    )}
      aria-label={t('pronunciationPilot.inspectWord', { word: text, position: (word.referenceIndex ?? index) + 1, context: label ? 'issue' : undefined, issue: label })}
      aria-pressed={index === selectedIndex} aria-controls={selected ? detail : undefined}
      data-playing={index === activeWord || undefined}
      onClick={() => {
        setSelectedIndex(index);
        if (loaded && wordPlaybackRange(word, durationMs)) play(word);
        else pause();
      }}>
      {Icon && <Icon aria-hidden="true" className={cn('mr-0.5 inline size-3.5 align-[-0.1em]',
        word.error === 'OMISSION' ? 'text-warning' : word.error === 'INSERTION' ? 'text-primary' : 'text-destructive')} />}
      <span lang="en">{text}</span>
      {(word.error === 'OMISSION' || word.error === 'INSERTION') &&
        <span aria-hidden="true" className="ml-1 text-xs font-medium">{label}</span>}
    </button>;
  }

  return <div className="grid min-w-0 gap-3" aria-labelledby={title}>
    <h3 id={title} className="font-medium">{t('pronunciationPilot.wordReview')}</h3>
    {parts ? <p className="text-lg leading-[2.1] wrap-anywhere">{parts.map((part, index) =>
      <span key={index}>{part.wordIndex == null ? part.text : wordButton(words[part.wordIndex], part.wordIndex, part.text)}</span>)}</p>
      : <>
        <p lang="en" className="text-lg leading-relaxed wrap-anywhere">{referenceText}</p>
        <ul className="flex flex-wrap gap-2">{words.map((word, index) =>
          word.error === 'NONE' ? null : <li key={index}>{wordButton(word, index, word.text)}</li>)}</ul>
      </>}
    <div className="flex flex-wrap gap-1.5">
      {issues.map(error => {
        const count = words.filter(word => word.error === error).length;
        const Icon = errorIcon[error];
        return count > 0 && <Badge key={error} variant={error === 'MISPRONUNCIATION' ? 'destructive' : error === 'OMISSION' ? 'warning' : 'outline'}>
          <Icon aria-hidden="true" />{t(`pronunciationPilot.word.${error}`)} · {count}
        </Badge>;
      })}
    </div>
    {selected && <div id={detail} className="grid gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1" role="status">
        <strong lang="en" className="text-base wrap-anywhere">{selected.text}</strong>
        <span>{t(`pronunciationPilot.word.${selected.error}`)}</span>
        {selected.accuracy != null && <span className="tabular-nums">{Math.round(selected.accuracy)}/100</span>}
      </div>
      <p className="text-muted-foreground">{t(`pronunciationPilot.explanation.${selected.error}`)}</p>
      {selected.error === 'OMISSION' ? <div className="flex flex-wrap items-center gap-2">
        <p>{t('pronunciationPilot.noWordAudio')}</p>
        {playReference && <Button size="sm" variant="outline" onClick={playReference}>
          <RiPlayLine aria-hidden="true" />{t('pronunciationPilot.reference')}
        </Button>}
      </div> : wordPlaybackRange(selected, durationMs) ? <Button size="sm" variant="outline" disabled={!loaded}
        aria-label={t('pronunciationPilot.play', { text: selected.text })} className="justify-self-start" onClick={() => play(selected)}>
        <RiPlayLine aria-hidden="true" />{t('pronunciationPilot.playWord')}
      </Button> : <p>{t('pronunciationPilot.noWordTiming')}</p>}
      {phonemes.length > 0 && <div className="grid gap-1">
        <p className="font-medium">{t('pronunciationPilot.phonemes')}</p>
        <ul className="flex flex-wrap gap-2">
          {phonemes.map((phoneme, index) => <li key={index} className="grid content-start justify-items-start gap-1">
            {wordPlaybackRange(phoneme, durationMs)
              ? <Button size="sm" variant="outline" disabled={!loaded}
                aria-label={t('pronunciationPilot.play', { text: phoneme.ipa })} onClick={() => play(phoneme)}>
                <RiPlayLine aria-hidden="true" />{phoneme.ipa} · {phoneme.accuracy ?? '—'}
              </Button>
              : <span className="px-1 py-1.5">{phoneme.ipa} · {phoneme.accuracy ?? '—'}</span>}
            {index === 0 && phoneme.accuracy != null && selected.error === 'MISPRONUNCIATION' &&
              <span className="text-sm text-muted-foreground">{t('pronunciationPilot.listenToPhoneme')}</span>}
          </li>)}
        </ul>
      </div>}
    </div>}
  </div>;
}
