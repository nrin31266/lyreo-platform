import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { RiAddLine, RiEditLine, RiLoader4Line, RiStopLine, RiVolumeUpLine, RiDeleteBinLine } from '@remixicon/react';
import { http } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { pronunciationGroups, pronunciationRegions, pronunciationScope } from './pronunciationGroups';
import { LibrarySelect } from '../library/LibrarySelect';
import { TextField } from '../library/LibraryWorkspace';
import { MediaEditor } from '../library/ContentMedia';
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
    <div className="grid gap-3">{pronunciationGroups(value.title,sounds,value.items).map(records => {
      const first=records[0], context=first.pronunciationContext;
      const item = pronunciationScope(first, value.items);
      const usage = context?.usage || (item && t(`library.partsOfSpeech.${item.partOfSpeech}`, { defaultValue: item.partOfSpeech }));
      const regionKey = (sound: Sound) => pronunciationRegions(sound).join("/");
      const accents = [...new Set(records.map(regionKey))];
      return <section key={first.id} className="rounded-lg border p-4"><div className="flex flex-wrap items-center gap-2"><h3 className="font-medium">{context?.form || value.title} {first.ipa && <span className="ml-2 font-mono">{first.ipa}</span>}</h3>{first.ipa && <Badge variant="outline">{t(context?.notation_system === 'IPA' ? 'library.ipa' : 'dictionary.sourceNotation')}</Badge>}</div>{usage && <p className="mt-1 text-xs text-muted-foreground">{usage}</p>}
        <div className="mt-3 grid gap-3">{accents.map(accent => {const recordings=records.filter(row=>regionKey(row)===accent);return <div key={accent} className="grid gap-2"><div className="flex flex-wrap items-center gap-3">{accent && <span className="text-xs font-medium">{accent.split("/").map(region => t(`dictionary.accents.${region}`, { defaultValue: region.replaceAll("-", " ") })).join(" · ")}</span>}<PronunciationRecording sound={recordings[0]} /><Button size="icon-sm" variant="ghost" aria-label={t('dictionary.editPronunciation')} tooltip={t('dictionary.editPronunciation')} aria-expanded={editing===recordings[0].id} onClick={()=>setEditing(editing===recordings[0].id?'':recordings[0].id)}><RiEditLine aria-hidden="true" /></Button></div>{recordings.length>1 && <details><summary className="cursor-pointer text-xs text-muted-foreground">{t('dictionary.otherRecordings',{count:recordings.length-1})}</summary><div className="mt-2 grid gap-3">{recordings.slice(1).map(sound=><div key={sound.id} className="flex gap-2"><PronunciationRecording sound={sound} /><Button size="icon-sm" variant="ghost" aria-label={t('dictionary.editPronunciation')} tooltip={t('dictionary.editPronunciation')} onClick={()=>setEditing(sound.id)}><RiEditLine aria-hidden="true" /></Button></div>)}</div></details>}</div>;})}</div>
        {records.filter(sound=>sound.id===editing).map(sound=><div key={sound.id} className="mt-4 grid gap-4 border-t pt-4"><p className="text-xs text-muted-foreground">{t('dictionary.sourceTags')}: {sound.tags.join(', ') || '—'}</p><div className="grid gap-3 md:grid-cols-3"><LibrarySelect label={t('dictionary.pronunciationScope')} value={sound.itemId ?? 'ENTRY'} options={scopes} onValueChange={itemId=>update(sound.id,{itemId:itemId==='ENTRY'?null:itemId})} /><TextField label={t('library.accent')} value={sound.accent} onChange={accent=>update(sound.id,{accent:accent||null})} /><TextField label={t('dictionary.sourceNotation')} value={sound.ipa} onChange={ipa=>update(sound.id,{ipa:ipa||null})} /></div><div className="grid gap-3 md:grid-cols-3"><TextField label={t('dictionary.spokenForm')} value={sound.pronunciationContext?.form ?? value.title} onChange={form=>update(sound.id,{pronunciationContext:{...sound.pronunciationContext,form}})} /><TextField label={t('dictionary.usageLabel')} value={sound.pronunciationContext?.usage ?? ''} onChange={usage=>update(sound.id,{pronunciationContext:{...sound.pronunciationContext,usage}})} /><LibrarySelect label={t('dictionary.notationSystem')} value={sound.pronunciationContext?.notation_system ?? 'SOURCE'} options={[{value:'IPA',label:'IPA'},{value:'SOURCE',label:t('dictionary.sourceNotation')}]} onValueChange={notation_system=>update(sound.id,{pronunciationContext:{...sound.pronunciationContext,notation_system}})} /></div><MediaEditor kind="audio" value={[]} onChange={media=>{const assetId=media[0]?.assetId;if(assetId) update(sound.id,{assetId,audioUrl:null});}} /><div className="flex items-center justify-between gap-2"><span className="min-w-0 truncate text-xs text-muted-foreground">{sound.sourceUrl || sound.audioFile}</span><Button size="icon-sm" variant="ghost" aria-label={t('library.remove')} tooltip={t('library.help.remove')} onClick={()=>onChange({...value,pronunciations:value.pronunciations.filter(row=>row.id!==sound.id)})}><RiDeleteBinLine aria-hidden="true" /></Button></div></div>)}
      </section>;
    })}{sounds.length===0 && <p className="py-4 text-sm text-muted-foreground">{t('dictionary.noPronunciations')}</p>}</div>
    <LexiconAssistant key={scope} document={document} dirty={dirty} adopt={adopt} context={{ itemId: ['ALL','ENTRY'].includes(scope) ? null : scope }} />
  </div>;
}
let currentRecording: HTMLAudioElement | undefined;

export function PronunciationRecording({ sound, label }: { sound: Sound; label?: string }) {
  const { t } = useTranslation('admin');
  if (!sound.assetId && !sound.audioUrl) return <span className="text-xs text-muted-foreground">{t('dictionary.noRecording')}</span>;
  return <div className="flex flex-wrap items-center gap-2">
    {sound.tags.includes('synthetic') && <Badge variant="outline">{t('library.syntheticAudio')}</Badge>}
    <RecordingButton key={sound.assetId ?? sound.audioUrl} sound={sound} label={label} />
  </div>;
}

function RecordingButton({ sound, label }: { sound: Sound; label?: string }) {
  const { t } = useTranslation('admin');
  const [state, setState] = useState<'idle' | 'loading' | 'playing' | 'error'>('idle');
  const player = useRef<HTMLAudioElement>(null);
  const pending = useRef<AbortController | null>(null);
  const objectUrl = useRef<string | undefined>(undefined);

  useEffect(() => {
    const audio = player.current;
    return () => {
      pending.current?.abort();
      if (audio && !audio.paused) audio.pause();
      if (currentRecording === audio) currentRecording = undefined;
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    };
  }, []);

  async function play() {
    const audio = player.current;
    if (!audio) return;
    if (state === 'playing') { audio.pause(); return; }
    if (currentRecording !== audio && currentRecording && !currentRecording.paused) currentRecording.pause();
    currentRecording = audio;
    const controller = new AbortController();
    pending.current = controller;
    setState('loading');
    try {
      if (!audio.getAttribute('src')) {
        if (sound.assetId) {
          const { data } = await http.get<Blob>(`/api/v1/admin/content-media/${sound.assetId}/file`, { responseType: 'blob', signal: controller.signal });
          if (controller.signal.aborted) return;
          objectUrl.current = URL.createObjectURL(data);
          audio.src = objectUrl.current;
        } else if (sound.audioUrl) audio.src = sound.audioUrl;
      }
      if (currentRecording !== audio) { setState('idle'); return; }
      audio.currentTime = 0;
      await audio.play();
    } catch {
      if (!controller.signal.aborted) setState('error');
    }
  }

  const action = t(state === 'playing' ? 'waveform.pause' : 'dictionary.listenNow');
  const accessibleLabel = label ? `${action} · ${label}` : action;
  return <div className="flex items-center gap-2">
    <audio hidden preload="none" ref={player} onPlaying={() => setState('playing')} onPause={() => setState('idle')}
      onEnded={() => setState('idle')} onError={() => setState('error')} />
    <Button size="icon-sm" type="button" variant="outline" tooltip={accessibleLabel}
      aria-label={accessibleLabel} disabled={state === 'loading'} onClick={() => void play()}>
      {state === 'loading' ? <RiLoader4Line className="animate-spin" aria-hidden="true" /> : state === 'playing' ? <RiStopLine aria-hidden="true" /> : <RiVolumeUpLine aria-hidden="true" />}
    </Button>
    {state === 'error' && <span role="status" className="text-xs text-destructive">{t('audioPlayer.failed')}</span>}
  </div>;
}
