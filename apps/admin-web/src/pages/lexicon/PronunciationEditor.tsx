import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RiAddLine, RiEditLine, RiVolumeUpLine, RiDeleteBinLine } from '@remixicon/react';
import { AudioPlayer } from '@/components/AudioPlayer';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { pronunciationGroups } from './pronunciationGroups';
import { LibrarySelect } from '../library/LibrarySelect';
import { TextField } from '../library/LibraryWorkspace';
import { MediaEditor, MediaPreview } from '../library/ContentMedia';
import type { Document, LexiconDraft, Sound } from '../library/library.types';
import { LexiconAssistant } from './LexiconAssistant';

export function PronunciationEditor({ value, onChange, document, dirty, adopt }: { value: LexiconDraft; onChange: (value: LexiconDraft) => void; document: Document<LexiconDraft>; dirty: boolean; adopt: (result: Document<LexiconDraft>) => void }) {
  const { t } = useTranslation('admin');
  const [scope, setScope] = useState('ALL');
  const [editing, setEditing] = useState('');
  const scopes = [{ value: 'ENTRY', label: t('dictionary.entryScope') }, ...value.items.map((item, index) => ({ value: item.id, label: `${t(`library.partsOfSpeech.${item.partOfSpeech}`, { defaultValue: item.partOfSpeech })} · ${t('dictionary.itemNumber', { number: index + 1 })}` }))];
  const sounds = value.pronunciations
    .filter(sound => scope === 'ALL' || (sound.itemId ?? 'ENTRY') === scope)
    .sort((a, b) => value.items.findIndex(item => item.id === a.itemId) - value.items.findIndex(item => item.id === b.itemId));
  const update = (id: string, patch: Partial<Sound>) => onChange({ ...value, pronunciations: value.pronunciations.map(sound => sound.id === id ? { ...sound, ...patch } : sound) });
  return <div className="grid gap-4"><div className="flex flex-wrap items-end justify-between gap-3"><div className="min-w-52 max-w-80"><LibrarySelect label={t('dictionary.pronunciationScope')} value={scope} onValueChange={setScope} options={[{ value: 'ALL', label: t('library.all') }, ...scopes]} /></div><Button variant="outline" size="sm" tooltip={t('library.help.addPronunciation')} onClick={() => { const id = crypto.randomUUID(); onChange({ ...value, pronunciations: [...value.pronunciations, { id, itemId: ['ALL','ENTRY'].includes(scope) ? null : scope, accent: null, ipa: '', audioUrl: null, assetId: null, tags: [] }] }); setEditing(id); }}><RiAddLine aria-hidden="true" />{t('library.addPronunciation')}</Button></div>
    <p className="max-w-3xl text-xs text-muted-foreground">{t('dictionary.pronunciationHelp')}</p>
    <div className="grid gap-3">{pronunciationGroups(value.title,sounds).map(records => {
      const first=records[0], context=first.pronunciationContext;
      const accents=[...new Set(records.map(row=>row.accent ?? "unspecified"))];
      return <section key={first.id} className="rounded-lg border p-4"><div className="flex flex-wrap items-center gap-2"><h3 className="font-medium">{context?.form || value.title} {first.ipa && <span className="ml-2 font-mono">{first.ipa}</span>}</h3>{first.ipa && <Badge variant="outline">{t(context?.notation_system === 'IPA' ? 'library.ipa' : 'dictionary.sourceNotation')}</Badge>}</div><p className="mt-1 text-xs text-muted-foreground">{context?.usage || t('dictionary.unknownUsage')}</p>
        <div className="mt-3 grid gap-3">{accents.map(accent => {const recordings=records.filter(row=>(row.accent ?? 'unspecified')===accent);return <div key={accent} className="grid gap-2"><div className="flex flex-wrap items-center gap-3"><span className="text-xs font-medium">{t(`dictionary.accents.${accent}`,{defaultValue:accent})}</span><PronunciationRecording sound={recordings[0]} /><Button size="icon-sm" variant="ghost" aria-label={t('dictionary.editPronunciation')} tooltip={t('dictionary.editPronunciation')} aria-expanded={editing===recordings[0].id} onClick={()=>setEditing(editing===recordings[0].id?'':recordings[0].id)}><RiEditLine aria-hidden="true" /></Button></div>{recordings.length>1 && <details><summary className="cursor-pointer text-xs text-muted-foreground">{t('dictionary.otherRecordings',{count:recordings.length-1})}</summary><div className="mt-2 grid gap-3">{recordings.slice(1).map(sound=><div key={sound.id} className="flex gap-2"><PronunciationRecording sound={sound} /><Button size="icon-sm" variant="ghost" aria-label={t('dictionary.editPronunciation')} tooltip={t('dictionary.editPronunciation')} onClick={()=>setEditing(sound.id)}><RiEditLine aria-hidden="true" /></Button></div>)}</div></details>}</div>;})}</div>
        {records.filter(sound=>sound.id===editing).map(sound=><div key={sound.id} className="mt-4 grid gap-4 border-t pt-4"><p className="text-xs text-muted-foreground">{t('dictionary.sourceTags')}: {sound.tags.join(', ') || '—'}</p><div className="grid gap-3 md:grid-cols-3"><LibrarySelect label={t('dictionary.pronunciationScope')} value={sound.itemId ?? 'ENTRY'} options={scopes} onValueChange={itemId=>update(sound.id,{itemId:itemId==='ENTRY'?null:itemId})} /><TextField label={t('library.accent')} value={sound.accent} onChange={accent=>update(sound.id,{accent:accent||null})} /><TextField label={t('dictionary.sourceNotation')} value={sound.ipa} onChange={ipa=>update(sound.id,{ipa:ipa||null})} /></div><div className="grid gap-3 md:grid-cols-3"><TextField label={t('dictionary.spokenForm')} value={sound.pronunciationContext?.form ?? value.title} onChange={form=>update(sound.id,{pronunciationContext:{...sound.pronunciationContext,form}})} /><TextField label={t('dictionary.usageLabel')} value={sound.pronunciationContext?.usage ?? ''} onChange={usage=>update(sound.id,{pronunciationContext:{...sound.pronunciationContext,usage}})} /><LibrarySelect label={t('dictionary.notationSystem')} value={sound.pronunciationContext?.notation_system ?? 'SOURCE'} options={[{value:'IPA',label:'IPA'},{value:'SOURCE',label:t('dictionary.sourceNotation')}]} onValueChange={notation_system=>update(sound.id,{pronunciationContext:{...sound.pronunciationContext,notation_system}})} /></div><MediaEditor kind="audio" value={[]} onChange={media=>{const assetId=media[0]?.assetId;if(assetId) update(sound.id,{assetId,audioUrl:null});}} /><div className="flex items-center justify-between gap-2"><span className="min-w-0 truncate text-xs text-muted-foreground">{sound.sourceUrl || sound.audioFile}</span><Button size="icon-sm" variant="ghost" aria-label={t('library.remove')} tooltip={t('library.help.remove')} onClick={()=>onChange({...value,pronunciations:value.pronunciations.filter(row=>row.id!==sound.id)})}><RiDeleteBinLine aria-hidden="true" /></Button></div></div>)}
      </section>;
    })}{sounds.length===0 && <p className="py-4 text-sm text-muted-foreground">{t('dictionary.noPronunciations')}</p>}</div>
    <LexiconAssistant key={scope} document={document} dirty={dirty} adopt={adopt} context={{ itemId: ['ALL','ENTRY'].includes(scope) ? null : scope }} />
  </div>;
}
export function PronunciationRecording({sound}: {sound:Sound}) {
  const {t}=useTranslation('admin');
  return <>{sound.tags.includes('synthetic') && <Badge variant="outline">{t('library.syntheticAudio')}</Badge>}{sound.assetId || sound.audioUrl ? <LazyRecording sound={sound} /> : <span className="text-xs text-muted-foreground">{t('dictionary.noRecording')}</span>}</>;
}
function LazyRecording({ sound }: { sound: Sound }) {
  const { t } = useTranslation('admin');
  const [open, setOpen] = useState(false);
  if (!open) return <Button size="sm" variant="outline" className="justify-self-start" tooltip={t('dictionary.listenHelp')} onClick={() => setOpen(true)}><RiVolumeUpLine aria-hidden="true" />{t('dictionary.listen')}</Button>;
  return sound.assetId ? <MediaPreview assetId={sound.assetId} role="audio" /> : <AudioPlayer src={sound.audioUrl!} aria-label={t('library.audioPreview')} />;
}

export function FormsEditor({ value, onChange }: { value: LexiconDraft; onChange: (value: LexiconDraft) => void }) {
  const { t } = useTranslation('admin');
  const scopes = [{ value: 'ENTRY', label: t('dictionary.entryScope') }, ...value.items.map((item, index) => ({ value: item.id, label: `${t(`library.partsOfSpeech.${item.partOfSpeech}`, { defaultValue: item.partOfSpeech })} · ${t('dictionary.itemNumber', { number: index + 1 })}` }))];
  return <div className="grid gap-3"><p className="text-xs text-muted-foreground">{t('dictionary.formsHelp')}</p>{value.forms.map(form => <div key={form.id} className="grid items-end gap-3 rounded-lg border p-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]"><TextField label={t('library.form')} value={form.form} onChange={text => onChange({ ...value, forms: value.forms.map(row => row.id === form.id ? { ...row, form: text } : row) })} /><LibrarySelect label={t('dictionary.pronunciationScope')} value={form.itemId ?? 'ENTRY'} options={scopes} onValueChange={itemId => onChange({ ...value, forms: value.forms.map(row => row.id === form.id ? { ...row, itemId: itemId === 'ENTRY' ? null : itemId } : row) })} /><Button tooltip={t('library.help.remove')} size="sm" variant="ghost" onClick={() => onChange({ ...value, forms: value.forms.filter(row => row.id !== form.id) })}>{t('library.remove')}</Button>{form.tags.length > 0 && <p className="text-xs text-muted-foreground md:col-span-3">{t('dictionary.sourceTags')}: {form.tags.join(', ')}</p>}</div>)}<Button className="justify-self-start" variant="outline" size="sm" tooltip={t('library.help.addForm')} onClick={() => onChange({ ...value, forms: [...value.forms, { id: crypto.randomUUID(), itemId: null, form: '', tags: [] }] })}><RiAddLine aria-hidden="true" />{t('library.addForm')}</Button></div>;
}
