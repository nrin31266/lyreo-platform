import { useState } from 'react';
import { RiAddLine, RiErrorWarningLine, RiPlayLine, RiSubtractLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Link } from 'react-router-dom';
import { ipaExample } from '@/pages/phonetics/ipa';
import { IpaAudioButtons } from '@/pages/phonetics/IpaAudioButtons';
import { IpaGuide } from '@/pages/phonetics/IpaGuide';
import type { useIpaAudio } from '@/pages/phonetics/useIpaAudio';
import { cn } from 'cn';
import type { PronunciationAttempt, PronunciationSpan, PronunciationWord } from './pronunciation.api';
import { timecode } from './timecode';

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
type Issue = Exclude<PronunciationWord['error'], 'NONE'>;
const issueVariant = {
  MISPRONUNCIATION: 'destructive',
  OMISSION: 'warning',
  INSERTION: 'outline',
} as const;
const issueColor = {
  MISPRONUNCIATION: 'text-destructive',
  OMISSION: 'text-warning',
  INSERTION: 'text-primary',
};

function IssueBadge({ error, count }: { error: Issue; count?: number }) {
  const { t } = useTranslation('admin');
  const Icon = errorIcon[error];
  return <Badge variant={issueVariant[error]} className="text-foreground">
    <Icon aria-hidden="true" className={issueColor[error]} />{t(`pronunciationPilot.word.${error}`)}{count == null ? '' : ` · ${count}`}
  </Badge>;
}

export function wordPlaybackRange(span: PronunciationSpan, durationMs: number) {
  const { startMs, endMs } = span;
  if (startMs === null || endMs === null || startMs < 0 || endMs <= startMs || endMs > durationMs) return undefined;
  return { start: Math.max(0, startMs - 100) / 1000, end: Math.min(durationMs, endMs + 150) / 1000 };
}

type Result = NonNullable<PronunciationAttempt['result']>;
const issueTypes = ['MISPRONUNCIATION', 'OMISSION', 'INSERTION'] as const;

const scoreStyles = {
  overall: 'border-primary/65 bg-primary/5 text-primary',
  accuracy: 'border-warning/65 bg-warning/5 text-warning',
  fluency: 'border-success/65 bg-success/5 text-success',
  completeness: 'border-violet-500/65 bg-violet-500/5 text-violet-700 dark:text-violet-300',
};

export function PronunciationFeedback({ referenceText, result, activeWord, loaded, play, pause, sampleAudio, beforeSample }: {
  referenceText: string;
  result: Result;
  activeWord: number | undefined;
  loaded: boolean;
  play: (span: PronunciationSpan) => void;
  pause: () => void;
  sampleAudio: ReturnType<typeof useIpaAudio>;
  beforeSample: () => void;
}) {
  const { t } = useTranslation('admin');
  const [selection, setSelection] = useState<{ word: number; sound?: string }>();
  const { words, durationMs, scores, transcript } = result;
  const parts = markedReference(referenceText, words);
  const firstIssue = words.findIndex(word => word.error !== 'NONE');
  const selectedIndex = selection?.word ?? firstIssue;
  const selected = words[selectedIndex];
  const phonemes = [...(selected?.phonemes ?? [])].sort((a, b) => (a.accuracy ?? Infinity) - (b.accuracy ?? Infinity));
  const example = ipaExample(selection?.sound ?? phonemes[0]?.ipa ?? '');

  function inspect(word: PronunciationWord, index: number) {
    setSelection({ word: index });
    if (loaded && wordPlaybackRange(word, durationMs)) play(word);
    else pause();
  }

  function markedWord(index: number, text: string) {
    const word = words[index];
    const issue = word.error === 'NONE' ? undefined : word.error;
    const Icon = issue && errorIcon[issue];
    const label = issue && t(`pronunciationPilot.word.${issue}`);
    return <button type="button" className={cn(
      'inline rounded-sm px-0.5 text-left wrap-anywhere hover:bg-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
      errorStyle[word.error], word.error === 'INSERTION' && 'mx-1 px-1',
      index === selectedIndex && 'outline outline-offset-1 outline-ring',
      index === activeWord && 'bg-primary/20 ring-2 ring-primary ring-offset-1 ring-offset-background',
    )} aria-label={t('pronunciationPilot.inspectWord', {
      word: text, position: (word.referenceIndex ?? index) + 1, context: label ? 'issue' : undefined, issue: label,
    })} aria-pressed={index === selectedIndex} data-playing={index === activeWord || undefined}
      onClick={() => inspect(word, index)}>
      {Icon && <Icon aria-hidden="true" className={cn('mr-0.5 inline size-3.5 align-[-0.1em]', issue && issueColor[issue])} />}
      <span lang="en">{text}</span>
      {(word.error === 'OMISSION' || word.error === 'INSERTION') &&
        <span aria-hidden="true" className="ml-1 text-xs font-medium">{label}</span>}
    </button>;
  }

  return <div className="grid min-w-0 gap-4">
    <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4">
      {(['overall', 'accuracy', 'fluency', 'completeness'] as const).map(metric => <div key={metric}
        className={cn('rounded-b-md border-t-2 px-2.5 py-2', scoreStyles[metric])}>
        <dt className="text-sm text-foreground">{t(`pronunciationPilot.score.${metric}`)}</dt>
        <dd className="text-xl leading-tight font-semibold tabular-nums">{scores[metric] == null ? '—' : Math.round(scores[metric])}</dd>
      </div>)}
    </dl>
    <div className="grid gap-2">
      <h3 className="font-medium">{t('pronunciationPilot.wordReview')}</h3>
      {parts ? <p className="text-lg leading-[2.1] wrap-anywhere">{parts.map((part, index) =>
        <span key={index}>{part.wordIndex == null ? part.text : markedWord(part.wordIndex, part.text)}</span>)}</p>
        : <>
          <p lang="en" className="text-lg leading-relaxed wrap-anywhere">{referenceText}</p>
          {words.length > 0 && <ul className="flex flex-wrap gap-2">{words.map((word, index) =>
            <li key={index}>{markedWord(index, word.text)}</li>)}</ul>}
        </>}
      <div className="flex flex-wrap gap-1.5">
        {firstIssue < 0 && words.length > 0 && <Badge variant="success">{t('pronunciationPilot.noIssues')}</Badge>}
        {issueTypes.map(error => {
          const count = words.filter(word => word.error === error).length;
          return count > 0 && <IssueBadge key={error} error={error} count={count} />;
        })}
      </div>
    </div>
    {selected && <div className="grid min-w-0 gap-3 rounded-lg border p-3">
      <div className="grid gap-1" role="status">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <strong lang="en" className="text-lg wrap-anywhere">{selected.text}</strong>
          {selected.error === 'NONE' ? <span>{t('pronunciationPilot.word.NONE')}</span> : <IssueBadge error={selected.error} />}
          {selected.accuracy != null && <span className="text-muted-foreground tabular-nums">{Math.round(selected.accuracy)}/100</span>}
        </div>
        <p className="text-muted-foreground">{t(`pronunciationPilot.explanation.${selected.error}`)}</p>
      </div>
      {selected.error === 'OMISSION' ? <p>{t('pronunciationPilot.noWordAudio')}</p>
        : wordPlaybackRange(selected, durationMs) && selected.startMs != null && selected.endMs != null
          ? <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" variant="outline" disabled={!loaded} aria-label={t('pronunciationPilot.play', { text: selected.text })}
              onClick={() => play(selected)}><RiPlayLine aria-hidden="true" />{t('pronunciationPilot.playWord')}</Button>
            <span className="text-xs tabular-nums text-muted-foreground">{timecode(selected.startMs)} – {timecode(selected.endMs)}</span>
          </div> : <p>{t('pronunciationPilot.noWordTiming')}</p>}
      {phonemes.length > 0 && <div className="grid gap-2 border-t pt-2">
        <h4 className="font-medium">{t('pronunciationPilot.phonemes')}</h4>
        <ul className="flex flex-wrap gap-2">
          {phonemes.map((phoneme, index) => <li key={index} className="grid content-start justify-items-start gap-1">
            <Button size="sm" variant={(selection?.sound ?? phonemes[0]?.ipa) === phoneme.ipa ? 'secondary' : 'outline'}
              aria-label={loaded && wordPlaybackRange(phoneme, durationMs)
                ? t('pronunciationPilot.play', { text: phoneme.ipa })
                : t('phonetics.selectSymbol', { sound: phoneme.ipa })}
              aria-pressed={(selection?.sound ?? phonemes[0]?.ipa) === phoneme.ipa}
              onClick={() => {
                setSelection({ word: selectedIndex, sound: phoneme.ipa });
                if (loaded && wordPlaybackRange(phoneme, durationMs)) play(phoneme);
                else pause();
              }}>
              {wordPlaybackRange(phoneme, durationMs) && loaded && <RiPlayLine aria-hidden="true" />}
              /{phoneme.ipa}/ · {phoneme.accuracy ?? '—'}
            </Button>
            {index === 0 && phoneme.accuracy != null && selected.error === 'MISPRONUNCIATION' &&
              <span className="text-xs font-medium text-warning">{t('pronunciationPilot.listenToPhoneme')}</span>}
          </li>)}
        </ul>
        {example && <div className="grid gap-3 rounded-md bg-muted/50 p-3">
          <IpaAudioButtons example={example} audio={sampleAudio} beforePlay={beforeSample} />
          <div className="flex flex-wrap items-center gap-2">
            <span lang="en" className="text-muted-foreground">{example.word} /{example.transcription}/</span>
            <Link to={`/phonetics?sound=${encodeURIComponent(example.ipa)}`} target="_blank" rel="noopener"
              className="text-xs text-primary underline underline-offset-2">{t('navigation.phonetics')}</Link>
          </div>
          <IpaGuide sound={example.ipa} />
        </div>}
      </div>}
    </div>}
    {transcript && <div className="grid gap-1">
      <h3 className="font-medium">{t('pronunciationPilot.heard')}</h3>
      <p lang="en" className="wrap-anywhere text-muted-foreground">{transcript}</p>
    </div>}
  </div>;
}
