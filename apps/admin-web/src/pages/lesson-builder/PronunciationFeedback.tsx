import { useId, useRef, useState } from 'react';
import { RiAddLine, RiErrorWarningLine, RiPlayLine, RiSubtractLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
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

export function PronunciationFeedback({ referenceText, result, activeWord, loaded, play, pause }: {
  referenceText: string;
  result: Result;
  activeWord: number | undefined;
  loaded: boolean;
  play: (span: PronunciationSpan) => void;
  pause: () => void;
}) {
  const { t } = useTranslation('admin');
  const detail = useId();
  const detailTab = useRef<HTMLButtonElement>(null);
  const [tab, setTab] = useState('overview');
  const [selectedIndex, setSelectedIndex] = useState<number>();
  const [issuesOnly, setIssuesOnly] = useState(false);
  const { words, durationMs, scores, transcript } = result;
  const parts = markedReference(referenceText, words);
  const firstIssue = words.findIndex(word => word.error !== 'NONE');
  const selected = words[selectedIndex ?? Math.max(0, firstIssue)];
  const phonemes = [...(selected?.phonemes ?? [])].sort((a, b) => (a.accuracy ?? Infinity) - (b.accuracy ?? Infinity));

  function inspect(word: PronunciationWord, index: number) {
    setSelectedIndex(index);
    if (tab === 'overview') {
      if (word.error === 'NONE') setIssuesOnly(false);
      setTab('details');
      detailTab.current?.focus();
    }
    if (loaded && wordPlaybackRange(word, durationMs)) play(word);
    else pause();
  }

  function wordLabel(word: PronunciationWord, index: number, text: string) {
    const issue = word.error === 'NONE' ? undefined : t(`pronunciationPilot.word.${word.error}`);
    return t('pronunciationPilot.inspectWord', {
      word: text, position: (word.referenceIndex ?? index) + 1, context: issue ? 'issue' : undefined, issue,
    });
  }

  function markedWord(index: number, text: string) {
    const word = words[index];
    const Icon = word.error === 'NONE' ? undefined : errorIcon[word.error];
    return <button type="button" className={cn(
      'inline rounded-sm px-0.5 text-left wrap-anywhere hover:bg-muted focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
      errorStyle[word.error], word.error === 'INSERTION' && 'mx-1 px-1',
      index === activeWord && 'bg-primary/20 ring-2 ring-primary ring-offset-1 ring-offset-background',
    )} aria-label={wordLabel(word, index, text)} data-playing={index === activeWord || undefined}
      onClick={() => inspect(word, index)}>
      {Icon && <Icon aria-hidden="true" className={cn('mr-0.5 inline size-3.5 align-[-0.1em]',
        word.error !== 'NONE' && issueColor[word.error])} />}
      <span lang="en">{text}</span>
      {(word.error === 'OMISSION' || word.error === 'INSERTION') &&
        <span aria-hidden="true" className="ml-1 text-xs font-medium">{t(`pronunciationPilot.word.${word.error}`)}</span>}
    </button>;
  }

  return <Tabs value={tab} onValueChange={setTab} className="min-w-0 gap-4">
    <TabsList variant="line" className="w-full justify-start border-b">
      <TabsTrigger value="overview" className="flex-none">{t('pronunciationPilot.overviewTab')}</TabsTrigger>
      <TabsTrigger ref={detailTab} value="details" className="flex-none">{t('pronunciationPilot.detailsTab')}</TabsTrigger>
    </TabsList>
    <TabsContent value="overview" className="grid gap-4">
      <div className="grid gap-2">
        <h3 className="font-medium">{t('pronunciationPilot.wordReview')}</h3>
        {parts ? <p className="text-lg leading-[2.1] wrap-anywhere">{parts.map((part, index) =>
          <span key={index}>{part.wordIndex == null ? part.text : markedWord(part.wordIndex, part.text)}</span>)}</p>
          : <>
            <p lang="en" className="text-lg leading-relaxed wrap-anywhere">{referenceText}</p>
            {firstIssue >= 0 && <ul className="flex flex-wrap gap-2">{words.map((word, index) =>
              word.error === 'NONE' ? null : <li key={index}>{markedWord(index, word.text)}</li>)}</ul>}
          </>}
        <div className="flex flex-wrap gap-1.5">
          {firstIssue < 0 && words.length > 0 && <Badge variant="success">{t('pronunciationPilot.noIssues')}</Badge>}
          {issueTypes.map(error => {
            const count = words.filter(word => word.error === error).length;
            return count > 0 && <IssueBadge key={error} error={error} count={count} />;
          })}
        </div>
      </div>
      <dl className="grid grid-cols-2 gap-3 border-y py-3 sm:grid-cols-4">
        {(['overall', 'accuracy', 'fluency', 'completeness'] as const).map(metric => <div key={metric}>
          <dt className="text-muted-foreground">{t(`pronunciationPilot.score.${metric}`)}</dt>
          <dd className="text-lg font-semibold tabular-nums">{scores[metric] == null ? '—' : Math.round(scores[metric])}</dd>
        </div>)}
      </dl>
    </TabsContent>
    <TabsContent value="details" className="grid gap-4">
      {transcript && <div className="grid gap-1">
        <h3 className="font-medium">{t('pronunciationPilot.heard')}</h3>
        <p lang="en" className="wrap-anywhere text-muted-foreground">{transcript}</p>
      </div>}
      {selected ? <div className="grid items-start gap-4 sm:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <div className="grid min-w-0 gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="font-medium">{t('pronunciationPilot.wordList')}</h3>
            {firstIssue >= 0 && <Button type="button" size="sm" variant={issuesOnly ? 'secondary' : 'outline'}
              aria-pressed={issuesOnly} onClick={() => {
                if (!issuesOnly && selected.error === 'NONE') setSelectedIndex(firstIssue);
                setIssuesOnly(!issuesOnly);
              }}>{t('pronunciationPilot.issuesOnly')}</Button>}
          </div>
          <ul className="max-h-44 divide-y overflow-y-auto rounded-lg border p-1 sm:max-h-64">
            {words.map((word, index) => (!issuesOnly || word.error !== 'NONE') && <li key={index}>
              <button type="button" aria-label={wordLabel(word, index, word.text)}
                aria-pressed={word === selected} aria-controls={detail} data-playing={index === activeWord || undefined}
                className={cn('grid w-full gap-0.5 rounded-sm px-2 py-2 text-left hover:bg-muted focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring',
                  word === selected && 'bg-muted', index === activeWord && 'ring-2 ring-inset ring-primary')}
                onClick={() => inspect(word, index)}>
                <span className="flex flex-wrap items-baseline justify-between gap-x-2">
                  <span lang="en" className="font-medium wrap-anywhere">{word.text}</span>
                  {word.accuracy != null && <span className="text-xs tabular-nums text-muted-foreground">{Math.round(word.accuracy)}/100</span>}
                </span>
                {word.error !== 'NONE' && <IssueBadge error={word.error} />}
              </button>
            </li>)}
          </ul>
        </div>
        <div id={detail} className="grid min-w-0 gap-3 rounded-lg border p-3">
          <div className="grid gap-1" role="status">
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <strong lang="en" className="text-lg wrap-anywhere">{selected.text}</strong>
              {selected.error === 'NONE'
                ? <span>{t('pronunciationPilot.word.NONE')}</span>
                : <IssueBadge error={selected.error} />}
            </div>
            <p className="text-muted-foreground">{t(`pronunciationPilot.explanation.${selected.error}`)}</p>
          </div>
          {selected.error === 'OMISSION' ? <p>{t('pronunciationPilot.noWordAudio')}</p>
            : wordPlaybackRange(selected, durationMs) && selected.startMs != null && selected.endMs != null
            ? <div className="flex flex-wrap items-center gap-2">
              <Button size="sm" variant="outline" disabled={!loaded} aria-label={t('pronunciationPilot.play', { text: selected.text })}
                onClick={() => play(selected)}>
                <RiPlayLine aria-hidden="true" />{t('pronunciationPilot.playWord')}
              </Button>
              <span className="text-xs tabular-nums text-muted-foreground">{timecode(selected.startMs)} – {timecode(selected.endMs)}</span>
            </div> : <p>{t('pronunciationPilot.noWordTiming')}</p>}
          {phonemes.length > 0 && <div className="grid gap-1 border-t pt-2">
            <h4 className="font-medium">{t('pronunciationPilot.phonemes')}</h4>
            <ul className="flex flex-wrap gap-2">
              {phonemes.map((phoneme, index) => <li key={index} className="grid content-start justify-items-start gap-1">
                {wordPlaybackRange(phoneme, durationMs)
                  ? <Button size="sm" variant="outline" disabled={!loaded}
                    aria-label={t('pronunciationPilot.play', { text: phoneme.ipa })} onClick={() => play(phoneme)}>
                    <RiPlayLine aria-hidden="true" />{phoneme.ipa} · {phoneme.accuracy ?? '—'}
                  </Button>
                  : <span className="px-1 py-1.5">{phoneme.ipa} · {phoneme.accuracy ?? '—'}</span>}
                {index === 0 && phoneme.accuracy != null && selected.error === 'MISPRONUNCIATION' &&
                  <span className="text-xs font-medium text-warning">{t('pronunciationPilot.listenToPhoneme')}</span>}
              </li>)}
            </ul>
          </div>}
        </div>
      </div> : <p className="text-muted-foreground">{t('pronunciationPilot.noWordDetails')}</p>}
    </TabsContent>
  </Tabs>;
}
