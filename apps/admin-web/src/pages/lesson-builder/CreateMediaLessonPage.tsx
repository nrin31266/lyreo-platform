import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { createMetadataSchema, type Metadata } from '@/pages/lessons/catalog.api';
import { CreateMetadataFields } from './CreateMetadataFields';
import { Field, FieldGroup, FieldLabel, FieldDescription } from '@/components/ui/field';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { useCategories, catalogKeys } from '@/pages/lessons/catalog.queries';
import { SourceRangeEditor } from './SourceRangeEditor';
import { RiLoader4Line } from '@remixicon/react';
import { SourceInspectionSkeleton } from './AuthoringSkeleton';
import { ProcessingStatus } from './ProcessingStatus';
import { ContentOptionsPanel,defaultContentOptions } from './ContentEditor';
import { VoiceSelect } from './VoiceSelect';
import { validSelection } from './timeline-view';
import * as authoring from './lesson-builder.api';

export function CreateMediaLessonPage({ type, onDirtyChange, onBusyChange }: { type: 'text' | 'audio'; onDirtyChange?: (dirty: boolean) => void; onBusyChange?: (busy: boolean) => void }) {
  const { t } = useTranslation('admin');
  const navigate = useNavigate();
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const metadata = useForm<Metadata>({ resolver: zodResolver(createMetadataSchema), defaultValues: { title: '', description: '', imageUrl: '', categoryIds: [] } });
  const [options,setOptions]=useState(defaultContentOptions);
  const [text, setText] = useState('');
  const [voice, setVoice] = useState('');
  const [rangeValid, setRangeValid] = useState(true);
  const [uploadError, setUploadError] = useState('');
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const categories = useCategories();
  const sourceId = params.get('source') || '';
  useEffect(() => { onDirtyChange?.(!!text.trim() || !!sourceId || metadata.formState.isDirty || JSON.stringify(options) !== JSON.stringify(defaultContentOptions)); }, [text, sourceId, metadata.formState.isDirty, options, onDirtyChange]);
  const source = useQuery({ queryKey: ['audio-source', sourceId], queryFn: ({ signal }) => authoring.fetchAudioSource(sourceId, signal), enabled: type === 'audio' && !!sourceId });
  const startMs = Number(params.get('start') || 0);
  const endMs = params.has('end') ? (params.get('end') === '' ? null : Number(params.get('end'))) : Math.min(120_000, source.data?.durationMs ?? 0);
  const upload = useMutation({ mutationFn: authoring.uploadAudio, onSuccess: data => {
    client.setQueryData(['audio-source', data.id], data);
    if (mounted.current) setParams({ type: 'audio', source: data.id });
  } });
  const create = useMutation({ mutationFn: async (synthesize: boolean) => type === 'text'
    ? authoring.createTextDraft({ ...metadata.getValues(), text, voice, options, startProcessing: synthesize }).then(draft => {
        client.setQueryData(['authoring', draft.id], draft);
        return { id: draft.id, preparationFailed: false };
      })
    : authoring.createAudioDraft({ ...metadata.getValues(), sourceId, startMs, endMs: endMs ?? 0,options }).then(result => ({ id: result.lessonId, preparationFailed: false })),
    onSuccess: async result => { await client.invalidateQueries({ queryKey: catalogKeys.all }); navigate(`/lessons/new?draft=${result.id}`, { state: { preparationFailed: result.preparationFailed } }); },
  });
  const submitting = useRef(false);
  useEffect(() => { onBusyChange?.(create.isPending || upload.isPending); return () => onBusyChange?.(false); }, [create.isPending, upload.isPending, onBusyChange]);
  async function submit(synthesize: boolean) {
    if (submitting.current || !valid) return;
    submitting.current = true;
    try { if (await metadata.trigger()) await create.mutateAsync(synthesize); }
    catch { /* Mutation state retains the error and all form input. */ }
    finally { submitting.current = false; }
  }
  const valid = !uploadError && !upload.isError && !upload.isPending && !categories.isPending && !categories.isError && (type === 'text'
    ? text.trim().length > 0 && text.length <= 10000 && !!voice
    : !!source.data && endMs !== null && rangeValid && validSelection(startMs, endMs, source.data.durationMs));
  return <form className="authoring-page source-page" onSubmit={event => { event.preventDefault(); void submit(type === "text"); }} aria-busy={create.isPending || upload.isPending}>
    {upload.isPending && <ProcessingStatus label={t("twoStep.uploading")} />}
    {create.isPending && <ProcessingStatus label={t("finish.starting")} />}
    <fieldset disabled={create.isPending || upload.isPending} className="source-workspace">
    <div className="source-media-pane">
    <h2 className="source-section-title">{t("sourceWorkspace.sourceHeading")}</h2>
    <CatalogError error={categories.error || source.error || upload.error || create.error} />
    <FieldGroup>
      {type === 'text' ? <>
        <Field><FieldLabel htmlFor="source-text">{t('media.text')}</FieldLabel><Textarea className="min-h-64" id="source-text" value={text} maxLength={10000} required rows={10} onChange={event => setText(event.target.value)} /><FieldDescription>{t('media.textHelp')}</FieldDescription></Field>
        <VoiceSelect value={voice} onChange={setVoice} disabled={create.isPending} />
      </> : <Field><FieldLabel htmlFor="audio-file">{t('media.upload')}</FieldLabel><Input id="audio-file" type="file" accept=".mp3,.wav,.m4a,audio/mpeg,audio/wav,audio/mp4" disabled={upload.isPending || create.isPending} onChange={event => {
        const file = event.target.files?.[0]; if (!file) return;
        setUploadError('');
        if (!file.size || file.size > 30 * 1024 * 1024) { setUploadError(t('media.uploadLimit')); return; }
        setParams({ type: 'audio' });
        upload.mutate(file);
      }} /><FieldDescription>{t(upload.isPending ? 'media.uploading' : 'media.uploadHelp')}</FieldDescription>{uploadError && <p role="alert" className="text-destructive">{uploadError}</p>}</Field>}
    </FieldGroup>
    {type === 'audio' && sourceId && source.isPending && <SourceInspectionSkeleton />}
    {type === 'audio' && source.data && <SourceRangeEditor key={source.data.id} audioUrl={`/api/v1/admin/lesson-authoring/audio-sources/${source.data.id}/audio`} durationMs={source.data.durationMs} initialPositionMs={startMs} startMs={startMs} endMs={endMs} onValidityChange={setRangeValid} onRangeChange={(start, end) => setParams({ type: 'audio', source: sourceId, start: String(start ?? 0), end: end === null ? '' : String(end) }, { replace: true })} />}
    </div>
    <aside className="source-metadata-pane"><h2 className="source-section-title">{t('reviewWorkspace.lessonInformation')}</h2><CreateMetadataFields form={metadata} titleHint={type === 'audio' ? source.data?.originalFilename || undefined : text.trim().split(/(?<=[.!?])\s/)[0]?.slice(0, 120)} /><ContentOptionsPanel draft={{options}} disabled={create.isPending} onChange={setOptions} /></aside>
    </fieldset>
    <footer className="authoring-actionbar"><span className="text-xs text-muted-foreground tabular-nums">{type === 'text' ? `${text.length.toLocaleString()} / 10.000` : t('media.uploadHelp')}</span><div className="authoring-action-buttons">{type === "text" && <Button type="button" variant="ghost" disabled={!valid || create.isPending} onClick={() => void submit(false)}>{t("media.saveText")}</Button>}<Button type="submit" disabled={!valid || create.isPending || upload.isPending}>{create.isPending && <RiLoader4Line className="animate-spin" aria-hidden="true" />}{t(create.isPending ? 'finish.starting' : 'finish.create')}</Button></div></footer>
  </form>;
}
