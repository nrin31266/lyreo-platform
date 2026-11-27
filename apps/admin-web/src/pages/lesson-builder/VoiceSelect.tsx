import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiPlayLine, RiPauseLine, RiCheckLine } from '@remixicon/react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogTrigger } from '@/components/ui/dialog';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { Progress } from '@/components/ui/progress';
import { Field, FieldDescription, FieldLabel } from '@/components/ui/field';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchVoices, fetchVoiceSample, prepareVoiceSamples, type Voice } from './lesson-builder.api';

export function VoiceSelect({ value, onChange, disabled = false }: { value: string; onChange: (voice: string) => void; disabled?: boolean }) {
  const { t } = useTranslation('admin');
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [playing, setPlaying] = useState<string | null>(null);
  const [loading, setLoading] = useState<string | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const audio = useRef<HTMLAudioElement>(null);
  const request = useRef(0);
  const objectUrl = useRef<string | null>(null);
  const controller = useRef<AbortController | null>(null);
  const query = useQuery({ queryKey: ['tts-voices'], queryFn: ({ signal }) => fetchVoices(signal), staleTime: 300_000,
    refetchInterval: query => ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED'].includes(query.state.data?.sampleStatus || '') ? 2000 : false,
  });
  const prepare = useMutation({ mutationFn: prepareVoiceSamples, onSuccess: () => client.invalidateQueries({ queryKey: ['tts-voices'] }) });
  useEffect(() => { if (!value && query.data) onChange(query.data.defaultVoice); }, [value, query.data, onChange]);
  function stop() {
    request.current++; controller.current?.abort(); audio.current?.pause();
    if (audio.current) audio.current.removeAttribute('src');
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = null; setPlaying(null); setLoading(null);
  }
  useEffect(() => { if (!open || disabled) stop(); }, [open, disabled]);
  useEffect(() => () => { request.current++; controller.current?.abort(); audio.current?.pause(); if (objectUrl.current) URL.revokeObjectURL(objectUrl.current); }, []);
  async function listen(voice: Voice) {
    if (playing === voice.id) { stop(); return; }
    stop(); setError(null); setLoading(voice.id);
    const version = request.current;
    controller.current = new AbortController();
    try {
      const blob = await fetchVoiceSample(voice.id, controller.current.signal);
      if (version !== request.current || !audio.current) return;
      objectUrl.current = URL.createObjectURL(blob); audio.current.src = objectUrl.current;
      await audio.current.play();
      if (version === request.current) setPlaying(voice.id);
    } catch (cause) { if (version === request.current) setError(cause instanceof Error ? cause : new Error(t('media.sampleFailed'))); }
    finally { if (version === request.current) setLoading(null); }
  }
  const label = (voice: Voice) => `${voice.name} · ${t(voice.gender === 'FEMALE' ? 'media.female' : 'media.male')} · ${t(voice.accent === 'US' ? 'media.us' : 'media.uk')}`;
  const selected = query.data?.voices.find(voice => voice.id === value);
  const busy = prepare.isPending || ['QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCEL_REQUESTED'].includes(query.data?.sampleStatus || '');
  return <Field><FieldLabel htmlFor="tts-voice">{t('media.voice')}</FieldLabel>
    <CatalogError error={query.error} retry={() => void query.refetch()} />
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button id="tts-voice" type="button" variant="outline" className="justify-between" disabled={disabled || query.isPending || query.isError}>{selected ? label(selected) : t('media.voice')}</Button></DialogTrigger>
      <DialogContent className="sm:max-w-xl"><DialogHeader><DialogTitle>{t('media.voice')}</DialogTitle><DialogDescription>{t('media.voiceHelp')}</DialogDescription></DialogHeader>
        <audio ref={audio} onEnded={() => setPlaying(null)} onError={() => { setPlaying(null); setError(new Error(t('media.sampleFailed'))); }} />
        {error && <p role="alert" className="text-sm text-destructive">{t("media.sampleFailed")}</p>}
        <CatalogError error={prepare.error} />
        {query.data?.sampleError && <p role="alert" className="text-sm text-destructive">{query.data.sampleError}</p>}
        <p className="text-sm text-muted-foreground" lang="en">{query.data?.sampleText}</p>
        {(error || query.data?.voices.some(voice => !voice.sampleAvailable)) && <div className="grid gap-2">
          <Button type="button" variant="secondary" disabled={busy || disabled} onClick={() => prepare.mutate()}>{t(busy ? 'media.samplesPreparing' : 'media.prepareSamples')}</Button>
          {busy && <><Progress value={query.data?.sampleProgress || 0} aria-label={t('media.samplesPreparing')} /><p role="status" className="text-sm">{query.data?.sampleProgress || 0}%</p></>}
        </div>}
        <Tabs defaultValue={selected?.accent || 'US'} onValueChange={stop}>
          <TabsList aria-label={t('media.accent')}><TabsTrigger value="US">{t('media.usVoices')}</TabsTrigger><TabsTrigger value="UK">{t('media.ukVoices')}</TabsTrigger></TabsList>
          {['US', 'UK'].map(accent => <TabsContent key={accent} value={accent} className="max-h-[50vh] overflow-y-auto p-1">
            {['FEMALE', 'MALE'].map(gender => <section key={gender} className="mb-4 grid gap-1" aria-label={t(gender === 'FEMALE' ? 'media.female' : 'media.male')}>
              <h3 className="py-2 text-sm font-medium">{t(gender === 'FEMALE' ? 'media.female' : 'media.male')}</h3>
              {query.data?.voices.filter(voice => voice.accent === accent && voice.gender === gender).map(voice => <div key={voice.id} className="flex items-center justify-between gap-3 rounded-md border p-2">
                <span className="text-sm">{label(voice)}</span><div className="flex shrink-0 gap-1">
                  <Button type="button" size="sm" variant="ghost" disabled={!voice.sampleAvailable || loading === voice.id} aria-label={`${t(playing === voice.id ? 'media.stopSample' : 'media.listen')} ${voice.name}`} onClick={() => void listen(voice)}>{playing === voice.id ? <RiPauseLine /> : <RiPlayLine />}{t(loading === voice.id ? 'loading' : playing === voice.id ? 'media.stopSample' : 'media.listen')}</Button>
                  <Button type="button" size="sm" variant={value === voice.id ? 'secondary' : 'outline'} aria-pressed={value === voice.id} aria-label={`${t('media.chooseVoice')} ${voice.name}`} onClick={() => { stop(); onChange(voice.id); setOpen(false); }}>{value === voice.id && <RiCheckLine />}{t('media.chooseVoice')}</Button>
                </div>
              </div>)}
            </section>)}
          </TabsContent>)}
        </Tabs>
      </DialogContent>
    </Dialog>
  </Field>;
}

const TTS_SPEED_PRESETS = [
  { value: '0.75', key: 'media.ttsSpeedVerySlow' },
  { value: '0.8', key: 'media.ttsSpeedSlow' },
  { value: '0.9', key: 'media.ttsSpeedGentle' },
  { value: '1', key: 'media.ttsSpeedNormal' },
  { value: '1.15', key: 'media.ttsSpeedBrisk' },
] as const;

/**
 * Reading pace selector for text-to-speech lesson synthesis (Kokoro speed).
 */
export function TtsSpeedSelect({
  value,
  onChange,
  disabled = false,
}: {
  value?: number;
  onChange: (speed: number) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation('admin');
  const numeric = typeof value === 'number' && Number.isFinite(value) ? Number(value.toFixed(2)) : 1;
  const normalized = String(numeric);
  const hasPreset = TTS_SPEED_PRESETS.some((preset) => Number(preset.value) === numeric);
  const current = hasPreset
    ? TTS_SPEED_PRESETS.find((preset) => Number(preset.value) === numeric)!.value
    : normalized;
  return (
    <Field>
      <FieldLabel htmlFor="tts-speed">{t('media.ttsSpeed')}</FieldLabel>
      <Select
        value={current}
        disabled={disabled}
        onValueChange={(next) => onChange(Number(next))}
      >
        <SelectTrigger id="tts-speed" aria-label={t('media.ttsSpeed')}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {!hasPreset && (
            <SelectItem value={normalized}>{`${normalized}×`}</SelectItem>
          )}
          {TTS_SPEED_PRESETS.map((preset) => (
            <SelectItem key={preset.value} value={preset.value}>
              {t(preset.key)}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <FieldDescription>{t('media.ttsSpeedHelp')}</FieldDescription>
    </Field>
  );
}
