import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { UnsavedChangesGuard } from './UnsavedChangesGuard';
import type { AuthoringSentence, AuthoringWord } from './lesson-builder.types';

export function TimingEditor({ onPlay, onPause, revision, disabled, durationMs, onSave, children }: {
  children: (edit: (sentence: AuthoringSentence) => void) => ReactNode;
  onPlay: (id: string, atMs?: number) => void; onPause: () => void;
  revision: number; disabled: boolean; durationMs: number;
  onSave: (sentenceId: string, words: AuthoringWord[], revision: number) => Promise<boolean>;
}) {
  const { t } = useTranslation('admin');
  const [editing, setEditing] = useState<AuthoringSentence>();
  const [words, setWords] = useState<AuthoringWord[]>([]);
  const [changed, setChanged] = useState(false);
  const [editingRevision, setEditingRevision] = useState(revision);
  const [failed, setFailed] = useState(false);
  function edit(sentence: AuthoringSentence) {
    onPause(); setEditingRevision(revision); setFailed(false); setEditing(sentence);
    setWords(sentence.words.map(word => ({ ...word }))); setChanged(false);
  }
  return <>
    {children(edit)}
    <Dialog open={!!editing} onOpenChange={open => { if (!open && (!changed || window.confirm(t('catalog.unsaved')))) setEditing(undefined); }}>
      <DialogContent className="content-edit-dialog"><DialogHeader><DialogTitle>{t('authoringUi.editTiming')}</DialogTitle><DialogDescription>{t('authoringUi.timingHelp')}</DialogDescription></DialogHeader>
        <UnsavedChangesGuard dirty={!!editing && changed} />
        <form className="grid gap-3 overflow-auto p-1" onSubmit={async e => { e.preventDefault(); if (editing && await onSave(editing.id, words, editingRevision)) { setChanged(false); setEditing(undefined); } else setFailed(true); }}>
          {words.map((word, index) => <div key={index} className="timing-edit-row"><Button variant="ghost" type="button" onClick={() => { if(editing)onPlay(editing.id,word.startMs); }}>{word.text}</Button>{(['startMs', 'endMs'] as const).map(key => <label key={key} className="text-xs text-muted-foreground">{t(`authoringUi.${key}`)}<Input type="number" min={0} max={durationMs} step={1} required value={word[key]} onChange={e => { const value = Number(e.currentTarget.value); onPause();setWords(old => old.map((item, i) => index === i ? { ...item, [key]: value } : item)); setChanged(true); }} /></label>)}</div>)}
          {failed && <p role="alert" className="text-sm text-destructive">{t("authoringUi.errorInEditor")}</p>}
          <Button type="submit" disabled={disabled || !changed}>{t('authoringUi.saveTiming')}</Button>
        </form>
      </DialogContent>
    </Dialog>
  </>;
}
