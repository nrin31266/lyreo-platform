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
  MISPRONUNCIATION: 'bg-destructive/15 underline decoration-destructive decoration-2 underline-offset-4',
  OMISSION: 'border border-dashed border-warning bg-warning/15',
  INSERTION: 'border border-dotted border-primary bg-primary/15',
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
  return { start: Math.max(0, startMs - 15) / 1000, end: Math.min(durationMs, endMs + 25) / 1000 };
}

type Result = NonNullable<PronunciationAttempt['result']>;
const issueTypes = ['MISPRONUNCIATION', 'OMISSION', 'INSERTION'] as const;

const scoreStyles = {
  overall: 'bg-primary/5 text-primary',
  accuracy: 'bg-warning/5 text-warning',
  fluency: 'bg-success/5 text-success',
  completeness: 'bg-violet-500/5 text-violet-700 dark:text-violet-300',
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
  const [selection, setSelection] = useState<{ word: number; sound?: number }>();
  const { words, durationMs, scores, transcript } = result;
  const parts = markedReference(referenceText, words);
  const firstIssue = words.findIndex(word => word.error !== 'NONE');
  const selectedIndex = selection?.word ?? firstIssue;
  const selected = words[selectedIndex];
  const phonemes = [...(selected?.phonemes ?? [])].sort((a, b) => (a.accuracy ?? Infinity) - (b.accuracy ?? Infinity));
  const soundIndex = selection?.sound ?? 0;
  const sound = phonemes[soundIndex]?.ipa;
  const example = sound && ipaExample(sound);
  const selectedRange = selected && wordPlaybackRange(selected, durationMs);

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

  return <div className="grid min-w-0 gap-3">
    <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4">
      {(['overall', 'accuracy', 'fluency', 'completeness'] as const).map(metric => <div key={metric}
        className={cn('rounded-md px-2.5 py-2', scoreStyles[metric])}>
        <dt className="text-sm text-foreground">{t(`pronunciationPilot.score.${metric}`)}</dt>
        <dd className="text-xl leading-tight font-semibold tabular-nums">{scores[metric] == null ? '—' : Math.round(scores[metric])}</dd>
      </div>)}
    </dl>
    <div className="grid gap-1.5">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <h3 className="font-medium">{t('pronunciationPilot.wordReview')}</h3>
        {firstIssue < 0 && words.length > 0 && <Badge variant="success">{t('pronunciationPilot.noIssues')}</Badge>}
        {issueTypes.map(error => {
          const count = words.filter(word => word.error === error).length;
          return count > 0 && <IssueBadge key={error} error={error} count={count} />;
        })}
      </div>
      {parts ? <p className="text-lg leading-[1.9] wrap-anywhere">{parts.map((part, index) =>
        <span key={index}>{part.wordIndex == null ? part.text : markedWord(part.wordIndex, part.text)}</span>)}</p>
        : <>
          <p lang="en" className="text-lg leading-relaxed wrap-anywhere">{referenceText}</p>
          {words.length > 0 && <ul className="flex flex-wrap gap-2">{words.map((word, index) =>
            <li key={index}>{markedWord(index, word.text)}</li>)}</ul>}
        </>}
    </div>
    {selected && <div className="grid min-w-0 gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div role="status" className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
          <strong lang="en" className="text-lg wrap-anywhere">{selected.text}</strong>
          {selected.error === 'NONE' ? <span>{t('pronunciationPilot.word.NONE')}</span> : <IssueBadge error={selected.error} />}
          {selected.accuracy != null && <span className="text-muted-foreground tabular-nums">{Math.round(selected.accuracy)}/100</span>}
        </div>
        {selected.error !== 'OMISSION' && selectedRange && selected.startMs != null && selected.endMs != null &&
          <Button size="sm" variant="outline" disabled={!loaded} aria-label={t('pronunciationPilot.play', { text: selected.text })}
            title={`${timecode(selected.startMs)} – ${timecode(selected.endMs)}`}
            onClick={() => play(selected)}><RiPlayLine aria-hidden="true" />{t('pronunciationPilot.playWord')}</Button>}
      </div>
      {(selected.error === 'OMISSION' || !selectedRange) && <p className="text-sm text-muted-foreground">
        {t(selected.error === 'OMISSION' ? 'pronunciationPilot.noWordAudio' : 'pronunciationPilot.noWordTiming')}
      </p>}
      {phonemes.length > 0 && <div className="grid gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <h4 className="text-sm font-medium">{t('pronunciationPilot.phonemes')}</h4>
          <ul className="flex flex-wrap gap-1.5">
            {phonemes.map((phoneme, index) => {
              const playable = loaded && Boolean(wordPlaybackRange(phoneme, durationMs));
              const flagged = index === 0 && phoneme.accuracy != null && selected.error === 'MISPRONUNCIATION';
              return <li key={index}>
                <Button size="sm" variant={soundIndex === index ? 'secondary' : 'outline'}
                  className={cn(flagged && 'border-warning bg-warning/10 text-warning hover:bg-warning/15')}
                  title={flagged ? t('pronunciationPilot.listenToPhoneme') : undefined}
                  aria-description={flagged ? t('pronunciationPilot.listenToPhoneme') : undefined}
                  aria-label={playable ? t('pronunciationPilot.playHeardPhoneme', { text: phoneme.ipa })
                    : t('phonetics.selectSymbol', { sound: phoneme.ipa })}
                  aria-pressed={soundIndex === index}
                  onClick={() => {
                    setSelection({ word: selectedIndex, sound: index });
                    if (playable) play(phoneme);
                    else pause();
                  }}>
                  {flagged && <RiErrorWarningLine aria-hidden="true" />}
                  {playable && <RiPlayLine aria-hidden="true" />}
                  /{phoneme.ipa}/ · {phoneme.accuracy ?? '—'}
                </Button>
              </li>;
            })}
          </ul>
        </div>
        {example ? <div className="grid gap-2 rounded-md bg-muted/50 p-2.5">
          <IpaAudioButtons example={example} audio={sampleAudio} beforePlay={beforeSample} />
          <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
            <details className="min-w-0 flex-1 text-sm">
              <summary className="cursor-pointer font-medium">{t('phonetics.howTo')}</summary>
              <div className="pt-2"><IpaGuide example={example} hideHeading hideSource /></div>
            </details>
            <Link to={`/phonetics?sound=${encodeURIComponent(example.ipa)}`} target="_blank" rel="noopener"
              className="text-xs text-primary underline underline-offset-2">{t('navigation.phonetics')}</Link>
          </div>
        </div> : <p className="text-sm text-muted-foreground">{t('phonetics.noReference', { sound })}</p>}
      </div>}
    </div>}
    {transcript && <div className="grid gap-1 text-sm">
      <h3 className="font-medium">{t('pronunciationPilot.heard')}</h3>
      <p lang="en" className="wrap-anywhere text-muted-foreground">{transcript}</p>
    </div>}
    {result.providerRaw && <details className="text-sm">
      <summary className="cursor-pointer font-medium">{t('pronunciationPilot.azureRaw')}</summary>
      <pre className="mt-1 max-h-80 overflow-auto whitespace-pre-wrap rounded-md bg-muted p-2 text-xs">{JSON.stringify(result.providerRaw, null, 2)}</pre>
    </details>}
  </div>;
}
