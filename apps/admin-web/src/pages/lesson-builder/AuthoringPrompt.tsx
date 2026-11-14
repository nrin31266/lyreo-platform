import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RiQuestionAnswerLine, RiFileCopyLine, RiCheckLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Field, FieldLabel, FieldDescription } from '@/components/ui/field';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';

type Source = 'youtube' | 'text' | 'audio';

export function AuthoringPrompt({ type }: { type: Source }) {
  const { t } = useTranslation('admin');
  const [source, setSource] = useState(type);
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState<'idle' | 'copying' | 'copied' | 'failed'>('idle');
  const editorType = useRef(type);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const copyButton = useRef<HTMLButtonElement>(null);
  const attempt = useRef(0);
  const prompt = [t('authoringPrompt.request'),t(`authoringPrompt.${source}.prompt`),t('authoringPrompt.randomNote')].join('\n\n');
  function resetCopy() { attempt.current++; setStatus('idle'); }
  useEffect(() => () => { attempt.current++; }, []);
  function changeOpen(next: boolean) {
    resetCopy();
    if (next && editorType.current !== type) { setSource(type); editorType.current = type; }
    setOpen(next);
  }
  async function copy() {
    const current = ++attempt.current;
    setStatus('copying');
    try {
      await navigator.clipboard.writeText(prompt);
      if (attempt.current === current) setStatus('copied');
    } catch {
      if (attempt.current !== current) return;
      setStatus('failed'); textarea.current?.focus(); textarea.current?.select();
    }
  }
  return <Dialog open={open} onOpenChange={changeOpen}>
    <DialogTrigger asChild><Button type="button" variant="outline"><RiQuestionAnswerLine />{t('authoringPrompt.trigger')}</Button></DialogTrigger>
    <DialogContent onOpenAutoFocus={event => { event.preventDefault(); copyButton.current?.focus(); }} className="flex max-h-[calc(100dvh-2rem)] flex-col gap-4 sm:max-w-2xl">
      <DialogHeader><DialogTitle>{t(`authoringPrompt.${source}.title`)}</DialogTitle><DialogDescription>{t('authoringPrompt.intro')}</DialogDescription></DialogHeader>
      <div className="min-h-0 space-y-4 overflow-y-auto p-1">
        <Field><FieldLabel htmlFor="prompt-source">{t('authoringPrompt.sourceLabel')}</FieldLabel>
          <Select value={source} onValueChange={next => { if(next === 'youtube' || next === 'text' || next === 'audio') { resetCopy(); setSource(next); } }}>
            <SelectTrigger id="prompt-source" className="w-full"><SelectValue /></SelectTrigger>
            <SelectContent><SelectGroup><SelectItem value="youtube">YouTube</SelectItem><SelectItem value="text">{t('media.textType')}</SelectItem><SelectItem value="audio">Audio</SelectItem></SelectGroup></SelectContent>
          </Select>
          <FieldDescription>{t('authoringPrompt.sourceHelp')}</FieldDescription>
        </Field>
        <ol className="list-decimal space-y-1 pl-5 text-sm text-muted-foreground">
          <li>{t('authoringPrompt.stepCopy')}</li><li>{t('authoringPrompt.stepAnswer')}</li><li>{t(`authoringPrompt.${source}.returnStep`)}</li>
        </ol>
        <Field><FieldLabel htmlFor="authoring-prompt">{t('authoringPrompt.label')}</FieldLabel>
          <Textarea id="authoring-prompt" ref={textarea} value={prompt} readOnly className="h-60 min-h-32 field-sizing-fixed" />
        </Field>
      </div>
      {status === 'failed' && <p role="alert" className="text-sm text-destructive">{t('authoringPrompt.copyFailed')}</p>}
      {status === 'copied' && <p role="status" className="text-sm text-muted-foreground">{t('authoringPrompt.copiedHelp')}</p>}
      <DialogFooter><Button ref={copyButton} type="button" disabled={status === 'copying'} onClick={() => void copy()}>{status === 'copied' ? <RiCheckLine /> : <RiFileCopyLine />}{t(`authoringPrompt.${status === 'copying' ? 'copying' : status === 'copied' ? 'copied' : 'copy'}`)}</Button></DialogFooter>
    </DialogContent>
  </Dialog>;
}
