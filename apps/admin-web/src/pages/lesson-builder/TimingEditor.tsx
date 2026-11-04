import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { PlaybackWords, type PlaybackClock } from "./playback-clock";
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RiEditLine, RiPlayLine, RiArrowDownLine } from "@remixicon/react";
import { TranscriptList } from './TranscriptList';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { UnsavedChangesGuard } from './UnsavedChangesGuard';
import { timecode } from './timecode';
import type { AuthoringSentence, AuthoringWord } from './lesson-builder.types';

export function TimingEditor({ activeSentenceId, onPlay, onPause, sentences, revision, clock, disabled, durationMs, onSave }: {
  activeSentenceId: string | null; onPlay: (id: string, atMs?: number) => void; onPause: () => void;
  sentences: AuthoringSentence[]; revision: number; clock: PlaybackClock; disabled: boolean; durationMs: number;
  onSave: (sentenceId: string, words: AuthoringWord[], revision: number) => Promise<boolean>;
}) {
  const { t } = useTranslation('admin');
  const [editing, setEditing] = useState<AuthoringSentence>();
  const [words, setWords] = useState<AuthoringWord[]>([]);
  const [changed, setChanged] = useState(false);
  const [editingRevision, setEditingRevision] = useState(revision);
  const [failed, setFailed] = useState(false);
  const [issueIndex, setIssueIndex] = useState(-1);
  const issues = sentences.filter(sentence => sentence.issues.length);
  return <>
    <div className="timing-list-toolbar"><span>{t('reviewWorkspace.listenSentenceHint')}</span>
      {!!issues.length && <Button size="sm" variant="ghost" onClick={() => { const next = (issueIndex + 1) % issues.length; setIssueIndex(next); onPlay(issues[next].id); }}><RiArrowDownLine />{t('authoringUi.nextIssue')} · {issues.length}</Button>}
    </div>
    <TranscriptList sentences={sentences} visibleRows={sentences.map((_,index)=>index)} filtered={false} className="timing-sentence-list" scrollToId={activeSentenceId}>
    {(sentence, index) => <article className="timing-item" id={`timing-${sentence.id}`} data-active={activeSentenceId === sentence.id} aria-current={activeSentenceId===sentence.id ? 'true' : undefined}>
      <div className="timing-item-heading"><span className="timing-number">{String(index+1).padStart(2,'0')}</span><span className="timing-range">{sentence.words.length ? `${timecode(sentence.words[0].startMs)}–${timecode(sentence.words.at(-1)!.endMs)}` : t('authoringFlow.noTiming')}</span>
      {sentence.timingSource==='MANUAL' && <Badge variant="secondary">{t('authoringUi.manual')}</Badge>}
      {sentence.usage!=='LEARN' && <Badge variant="secondary">{t(`lessonAuthoring.usages.${sentence.usage}`)}</Badge>}
      <div className="timing-item-actions">
        <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={!sentence.words.length} aria-label={t('reviewWorkspace.playSentence',{number:index+1})} onClick={() => onPlay(sentence.id)}><RiPlayLine /></Button></TooltipTrigger><TooltipContent>{t('reviewWorkspace.playSentence',{number:index+1})}</TooltipContent></Tooltip>
        <Tooltip><TooltipTrigger asChild><Button variant="ghost" size="icon-sm" disabled={disabled || !sentence.words.length} aria-label={t('authoringUi.editTiming')} onClick={() => { onPause();setEditingRevision(revision); setFailed(false); setEditing(sentence); setWords(sentence.words.map(word => ({ ...word }))); setChanged(false); }}><RiEditLine /></Button></TooltipTrigger><TooltipContent>{t('authoringUi.editTiming')}</TooltipContent></Tooltip>
      </div></div>
      {activeSentenceId === sentence.id ? <PlaybackWords clock={clock} sentence={sentence} timing onSeek={atMs=>onPlay(sentence.id,atMs)} /> : <button type="button" className="timing-sentence-preview" disabled={!sentence.words.length} onClick={()=>onPlay(sentence.id)}>{sentence.text}</button>}
      {!!sentence.issues.length && <div className="timing-issues">{sentence.issues.map(issue => <Badge key={issue} variant={issue.startsWith('TIMING_') ? 'destructive' : 'warning'}>{t(`lessonAuthoring.labels.${issue}`, { defaultValue: issue })}</Badge>)}</div>}
    </article>}
    </TranscriptList>
    <Dialog open={!!editing} onOpenChange={open => { if (!open && (!changed || window.confirm(t('catalog.unsaved')))) setEditing(undefined); }}>
      <DialogContent className="content-edit-dialog"><DialogHeader><DialogTitle>{t('authoringUi.editTiming')}</DialogTitle><DialogDescription>{t('authoringUi.timingHelp')}</DialogDescription></DialogHeader>
        <UnsavedChangesGuard dirty={!!editing && changed} />
        <form className="grid gap-3 overflow-auto p-1" onSubmit={async e => { e.preventDefault(); if (editing && await onSave(editing.id, words, editingRevision)) { setChanged(false); setEditing(undefined); } else setFailed(true); }}>
          {words.map((word, index) => <div key={index} className="timing-edit-row"><Button variant="ghost" type="button" onClick={() => { if(editing)onPlay(editing.id,word.startMs); }}>{word.text}</Button>{(['startMs', 'endMs'] as const).map(key => <label key={key} className="text-xs text-muted-foreground">{t(`authoringUi.${key}`)}<Input type="number" min={0} max={durationMs} step={1} required value={word[key]} onChange={e => { onPause();setWords(old => old.map((item, i) => index === i ? { ...item, [key]: Number(e.target.value) } : item)); setChanged(true); }} /></label>)}</div>)}
          {failed && <p role="alert" className="text-sm text-destructive">{t("authoringUi.errorInEditor")}</p>}
          <Button type="submit" disabled={disabled || !changed}>{t('authoringUi.saveTiming')}</Button>
        </form>
      </DialogContent>
    </Dialog>
  </>;
}
