import { useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  RiAddLine,
  RiDeleteBinLine,
  RiLoader4Line,
  RiStopMiniFill,
  RiVolumeUpLine,
} from '@remixicon/react';
import { AccessTierOption } from '@/components/AccessTierOption';
import { CefrLevelBadge, PosTag } from '@/components/ContentBadges';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { MediaPreview } from '@/pages/library/ContentMedia';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { useVocabularyAudio, type ClipPlaybackState } from './useVocabularyAudio';
import { cefrLevels, fetchVocabulary, saveVocabulary, vocabularyKey, type Entry, type EntrySummary, type Pack, type Unit } from './vocabulary-api';

type EditorActions = { onSaved: () => void; onClose: () => void };

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function supportsClozeExample(term: string, examples: string[]) {
  const normalized = term.replaceAll('’', "'").trim().toLowerCase();
  if (!normalized) return false;
  const pattern = new RegExp(`(?<![\\w'-])${escapeRegExp(normalized)}(?![\\w'-])`, 'gi');
  return examples.some((example) => {
    const matches = example.replaceAll('’', "'").toLowerCase().match(pattern);
    return matches?.length === 1;
  });
}

function useSave(path: string, onSaved: () => void) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (updates: object) => saveVocabulary(path, updates),
    onSuccess: () => {
      onSaved();
      void client.invalidateQueries({ queryKey: vocabularyKey });
    },
  });
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="grid gap-1.5">
    <span className="text-xs font-medium text-muted-foreground">{label}</span>
    {children}
  </label>;
}

function Visibility({ hidden, onChange }: { hidden: boolean; onChange: (hidden: boolean) => void }) {
  const { t } = useTranslation('admin');
  return <label className="inline-flex items-center gap-2 text-sm font-medium">
    <Switch size="sm" checked={hidden} onCheckedChange={onChange} />
    {t('vocabulary.hideItem')}
  </label>;
}

function SaveActions({ saving, onClose }: { saving: boolean; onClose: () => void }) {
  const { t } = useTranslation('admin');
  return <DialogFooter>
    <Button type="button" variant="outline" disabled={saving} onClick={onClose}>{t('vocabulary.cancel')}</Button>
    <Button type="submit" disabled={saving}>{t(saving ? 'vocabulary.saving' : 'vocabulary.save')}</Button>
  </DialogFooter>;
}

function ClipButton({ assetId, label, compact = false, speech }: {
  assetId: string | null;
  label: string;
  compact?: boolean;
  speech: ClipPlaybackState;
}) {
  if (!assetId) return null;
  const playing = speech.activeId === assetId;
  const loading = speech.loadingId === assetId;
  const Icon = loading ? RiLoader4Line : playing ? RiStopMiniFill : RiVolumeUpLine;
  const iconNode = <Icon className={loading ? 'animate-spin motion-reduce:animate-none' : undefined} aria-hidden="true" />;

  if (compact) {
    return <Button type="button" size="icon-sm" variant={playing ? 'secondary' : 'outline'}
      aria-label={label} aria-pressed={playing} onClick={() => speech.toggle(assetId)}>
      {iconNode}
    </Button>;
  }
  return <Button type="button" size="sm" variant={playing ? 'secondary' : 'outline'}
    aria-pressed={playing} onClick={() => speech.toggle(assetId)}>
    {iconNode}{label}
  </Button>;
}

export function VocabularyNameDialog({ value, onSaved, onClose }: EditorActions & { value: Pack | Unit }) {
  const { t } = useTranslation('admin');
  const pack = 'titleEn' in value;
  const [name, setName] = useState(pack ? value.titleEn : value.title);
  const [vi, setVi] = useState(pack ? value.titleVi : '');
  const [level, setLevel] = useState(pack ? '' : value.level);
  const [hidden, setHidden] = useState(value.hidden);
  const [accessMode, setAccessMode] = useState<'PUBLIC' | 'FEATURE'>(
    pack && value.accessMode === 'FEATURE' ? 'FEATURE' : 'PUBLIC',
  );
  const save = useSave(`/${pack ? 'packs' : 'units'}/${value.id}`, onSaved);
  const heading = t(pack ? 'vocabulary.editPack' : 'vocabulary.editUnit');

  return <Dialog open onOpenChange={(open) => { if (!open && !save.isPending) onClose(); }}>
    <DialogContent showCloseButton={!save.isPending}>
      <DialogHeader>
        <DialogTitle>{heading}</DialogTitle>
        <DialogDescription>{t('vocabulary.editNameHelp')}</DialogDescription>
      </DialogHeader>
      <form className="grid gap-4" onSubmit={(event) => {
        event.preventDefault();
        const edit = { revision: value.revision, hidden };
        save.mutate(
          pack
            ? {
                ...edit,
                titleEn: name.trim(),
                titleVi: vi.trim(),
                accessMode,
                requiredFeatureKey: accessMode === 'FEATURE' ? 'vocabulary.plus-packs' : null,
              }
            : { ...edit, title: name.trim(), level },
        );
      }}>
        <Field label={t(pack ? 'vocabulary.titleEn' : 'vocabulary.unitName')}>
          <Input required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} />
        </Field>
        {pack && <Field label={t('vocabulary.titleVi')}>
          <Input required maxLength={120} value={vi} onChange={(event) => setVi(event.target.value)} />
        </Field>}
        {pack && (
          <AccessTierOption
            value={accessMode}
            onChange={setAccessMode}
            disabled={save.isPending}
          />
        )}
        {!pack && <div className="grid gap-1.5">
          <Label htmlFor="vocabulary-cefr" className="text-xs font-medium text-muted-foreground">{t('vocabulary.cefrLevel')}</Label>
          <Select value={level} onValueChange={setLevel}>
            <SelectTrigger id="vocabulary-cefr" className="w-full"><SelectValue /></SelectTrigger>
            <SelectContent>{cefrLevels.map((value) => <SelectItem value={value} key={value}>{value}</SelectItem>)}</SelectContent>
          </Select>
        </div>}
        <Visibility hidden={hidden} onChange={setHidden} />
        <CatalogError error={save.error} />
        <SaveActions saving={save.isPending} onClose={onClose} />
      </form>
    </DialogContent>
  </Dialog>;
}

export function EntryDialog({ summary, onSaved, onClose }: EditorActions & { summary: EntrySummary }) {
  const { t } = useTranslation('admin');
  const save = useSave(`/entries/${summary.id}`, onSaved);
  const entry = useQuery({
    queryKey: [...vocabularyKey, 'entry', summary.id],
    refetchOnMount: 'always',
    queryFn: ({ signal }) => fetchVocabulary<Entry>(`/entries/${summary.id}`, signal),
  });
  return <Dialog open onOpenChange={(open) => { if (!open && !save.isPending) onClose(); }}>
    <DialogContent className="flex max-h-[calc(100dvh-2rem)] flex-col gap-3 overflow-hidden sm:max-w-3xl" showCloseButton={!save.isPending}>
      <DialogHeader className="shrink-0 pr-10">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <div className="flex flex-wrap items-baseline gap-2">
            <DialogTitle className="text-xl font-semibold"><span lang="en">{summary.term}</span></DialogTitle>
            <CefrLevelBadge level={summary.level} />
            <span className="text-xs text-muted-foreground">{t('vocabulary.entryContext', { title: summary.unitTitle })}</span>
          </div>
          <div className="flex flex-wrap items-center gap-3 text-xs">
            <Link to={`/lexicon?${new URLSearchParams({ q: summary.term })}`} target="_blank" rel="noreferrer"
              className="font-medium text-muted-foreground underline underline-offset-4 hover:text-foreground">
              {t('vocabulary.lookupDictionary')}
            </Link>
            <a href="https://en-word.net/" target="_blank" rel="noreferrer"
              className="font-medium text-muted-foreground underline underline-offset-4 hover:text-foreground">
              {t('vocabulary.referenceWordNet')}
            </a>
          </div>
        </div>
        <DialogDescription className="sr-only">{t('vocabulary.entryContext', { title: summary.unitTitle })}</DialogDescription>
      </DialogHeader>
      <CatalogError error={entry.error} retry={() => void entry.refetch()} />
      {!entry.isFetchedAfterMount && !entry.isError && <CatalogSkeleton />}
      {entry.isFetchedAfterMount && entry.isSuccess && <EntryForm entry={entry.data} onClose={onClose} save={save} />}
    </DialogContent>
  </Dialog>;
}

const wordLists = ['family', 'synonyms', 'antonyms'] as const;

function EntryForm({ entry, onClose, save }: Pick<EditorActions, 'onClose'> & { entry: Entry; save: ReturnType<typeof useSave> }) {
  const { t } = useTranslation('admin');
  const speech = useVocabularyAudio();
  const [original] = useState(entry);
  const [content, setContent] = useState(original.content);
  const [wordListsText, setWordListsText] = useState(() => ({
    family: original.content.family.join('\n'),
    synonyms: original.content.synonyms.join('\n'),
    antonyms: original.content.antonyms.join('\n'),
  }));
  const [collocations, setCollocations] = useState(() => original.content.collocations.map((en, index) => ({
    en, vi: original.content.collocationsVi?.[index] ?? '',
  })));
  const [hidden, setHidden] = useState(original.hidden);
  const [needsReview, setNeedsReview] = useState(Boolean(original.content.needsReview));

  const hasVietnamese = content.senses.every((sense) => Boolean(sense.meaningVi?.trim()));
  const clozeReady = supportsClozeExample(original.term, content.examples);
  const parseList = (text: string) => text.split('\n').map((item) => item.trim()).filter(Boolean);
  const relatedCount = collocations.length
    + parseList(wordListsText.family).length
    + parseList(wordListsText.synonyms).length
    + parseList(wordListsText.antonyms).length;

  const exampleTracks = [original.media.exampleAudio, original.media.secondExampleAudio];

  const [tab, setTab] = useState('meanings');
  const [invalid, setInvalid] = useState<string | null>(null);

  return <form className="flex min-h-0 flex-1 flex-col gap-3" noValidate onSubmit={(event) => {
    event.preventDefault();
    speech.stop();
    const lists = Object.fromEntries(wordLists.map((key) => [key, parseList(wordListsText[key])]));
    // Inactive tabs are unmounted, so browser `required` checks cannot see them: validate here.
    const problem: [string, string] | null =
      !content.ipaUk.trim() || !content.ipaUs.trim() ? ['meanings', 'ipaRequired']
      : content.senses.some((sense) => !sense.pos.trim() || !sense.definitionEn.trim()) ? ['meanings', 'senseRequired']
      : content.examples.some((example) => !example.trim()) ? ['examples', 'exampleRequired']
      : collocations.some(({ en }) => !en.trim()) ? ['related', 'collocationRequired']
      : wordLists.some((key) => lists[key].length > 12 || lists[key].some((item) => item.length > 200)) ? ['related', 'listLimit']
      : null;
    if (problem) {
      setTab(problem[0]);
      setInvalid(problem[1]);
      return;
    }
    setInvalid(null);
    save.mutate({ revision: original.revision, hidden, content: {
      ...content, ...lists, needsReview,
      ipaUk: content.ipaUk.trim(), ipaUs: content.ipaUs.trim(),
      senses: content.senses.map((sense) => ({
        pos: sense.pos.trim(), definitionEn: sense.definitionEn.trim(), meaningVi: sense.meaningVi?.trim() || null,
      })),
      examples: content.examples.map((example) => example.trim()),
      examplesVi: content.examplesVi?.map((example) => example?.trim() || null) ?? null,
      collocations: collocations.map(({ en }) => en.trim()),
      collocationsVi: collocations.map(({ vi }) => vi.trim() || null),
    } });
  }}>
    <div className="grid shrink-0 items-center gap-3 rounded-xl border bg-muted/30 p-3 sm:grid-cols-[4.5rem_minmax(0,1fr)]">
      <div className="size-18 overflow-hidden rounded-lg border bg-muted [&_img]:size-full [&_img]:object-cover">
        {original.media.image && <MediaPreview assetId={original.media.image} role="image" compact />}
      </div>
      <div className="grid min-w-0 gap-2">
        <div className="grid gap-2 sm:grid-cols-2">
          <div className="flex items-center gap-1.5">
            <ClipButton assetId={original.media.ukAudio} label={t('vocabulary.ukAudio')} compact speech={speech} />
            <span className="text-xs font-medium text-muted-foreground">UK</span>
            <Input lang="en-GB" required maxLength={120} aria-label={t('vocabulary.ipaUk')} value={content.ipaUk}
              onChange={(event) => setContent({ ...content, ipaUk: event.target.value })} />
          </div>
          <div className="flex items-center gap-1.5">
            <ClipButton assetId={original.media.usAudio} label={t('vocabulary.usAudio')} compact speech={speech} />
            <span className="text-xs font-medium text-muted-foreground">US</span>
            <Input lang="en-US" required maxLength={120} aria-label={t('vocabulary.ipaUs')} value={content.ipaUs}
              onChange={(event) => setContent({ ...content, ipaUs: event.target.value })} />
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-1.5 text-xs" aria-label={t('vocabulary.studyReadiness')}>
          <Badge variant={clozeReady ? 'secondary' : 'warning'}>
            {t(clozeReady ? 'vocabulary.clozeReady' : 'vocabulary.clozeMissing')}
          </Badge>
          {!hasVietnamese && <Badge variant="warning">{t('vocabulary.reviews.MISSING_VI')}</Badge>}
          {needsReview && <Badge variant="warning">{t('vocabulary.reviews.NEEDS_REVIEW')}</Badge>}
          {speech.failedId && <span role="alert" className="text-xs text-destructive">{t('audioPlayer.failed')}</span>}
        </div>
      </div>
    </div>

    <Tabs value={tab} onValueChange={setTab} className="min-h-0 flex-1">
      <TabsList className="w-full justify-start sm:w-fit">
        <TabsTrigger value="meanings">
          {t('vocabulary.tabMeanings')}
          <span className="text-xs tabular-nums opacity-65">{content.senses.length}</span>
        </TabsTrigger>
        <TabsTrigger value="examples">
          {t('vocabulary.tabExamples')}
          <span className="text-xs tabular-nums opacity-65">{content.examples.length}</span>
        </TabsTrigger>
        <TabsTrigger value="related">
          {t('vocabulary.tabRelated')}
          <span className="text-xs tabular-nums opacity-65">{relatedCount}</span>
        </TabsTrigger>
      </TabsList>

      <TabsContent value="meanings" className="min-h-0 overflow-y-auto pr-1">
        <div className="grid gap-3 py-1">
          {!!content.contextNotes?.length && <section className="grid gap-1.5 rounded-lg border bg-muted/35 p-3">
            <h3 className="text-xs font-semibold text-muted-foreground">{t('vocabulary.toeicContext')}</h3>
            <ul className="grid gap-1">
              {content.contextNotes.map((note) => <li key={note.pos + note.meaningVi} className="flex flex-wrap items-baseline gap-2 text-sm">
                <PosTag pos={note.pos} />
                <span lang="vi" className="font-medium">{note.meaningVi}</span>
              </li>)}
            </ul>
          </section>}

          {content.senses.map((sense, index) => {
            const change = (value: Partial<typeof sense>) => setContent({
              ...content, senses: content.senses.map((item, i) => i === index ? { ...item, ...value } : item),
            });
            return <div key={index} className="grid gap-2.5 rounded-xl border p-3 sm:grid-cols-[5.5rem_minmax(0,1fr)_minmax(0,1fr)]">
              <Field label={t('vocabulary.pos')}>
                <Input lang="en" required maxLength={24} value={sense.pos} onChange={(event) => change({ pos: event.target.value })} />
              </Field>
              <Field label={t('vocabulary.meaningEn')}>
                <Textarea lang="en" rows={2} required maxLength={500} value={sense.definitionEn}
                  onChange={(event) => change({ definitionEn: event.target.value })} />
              </Field>
              <Field label={t('vocabulary.meaningVi')}>
                <Textarea lang="vi" rows={2} maxLength={500} value={sense.meaningVi ?? ''}
                  onChange={(event) => change({ meaningVi: event.target.value })} />
              </Field>
            </div>;
          })}
        </div>
      </TabsContent>

      <TabsContent value="examples" className="min-h-0 overflow-y-auto pr-1">
        <div className="grid gap-3 py-1">
          {content.examples.map((example, index) => {
            const trackId = exampleTracks[index];
            const detached = Boolean(trackId && example.trim() !== original.content.examples[index]);
            return <div className="grid gap-2.5 rounded-xl border p-3" key={index}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-xs font-semibold text-muted-foreground">{t('vocabulary.example', { index: index + 1 })}</span>
                <div className="flex items-center gap-2">
                  {detached && <span className="text-xs text-warning">{t('vocabulary.detachedAudio')}</span>}
                  {trackId && !detached && <ClipButton
                    assetId={trackId}
                    label={t('vocabulary.exampleAudio', { index: index + 1 })}
                    speech={speech}
                  />}
                </div>
              </div>
              <div className="grid gap-2.5 sm:grid-cols-2">
                <Field label={t('vocabulary.exampleEn', { index: index + 1 })}>
                  <Textarea lang="en" rows={2} required maxLength={500} value={example} onChange={(event) => setContent({
                    ...content, examples: content.examples.map((item, i) => i === index ? event.target.value : item),
                  })} />
                </Field>
                <Field label={t('vocabulary.exampleVi', { index: index + 1 })}>
                  <Textarea lang="vi" rows={2} maxLength={500} value={content.examplesVi?.[index] ?? ''} onChange={(event) => setContent({
                    ...content, examplesVi: content.examples.map((_, i) => i === index ? event.target.value : content.examplesVi?.[i] ?? null),
                  })} />
                </Field>
              </div>
            </div>;
          })}
        </div>
      </TabsContent>

      <TabsContent value="related" className="min-h-0 overflow-y-auto pr-1">
        <div className="grid gap-4 py-1">
          <fieldset className="grid gap-2 rounded-xl border p-3">
            <legend className="px-1 text-xs font-semibold text-muted-foreground">{t('vocabulary.collocations')}</legend>
            {collocations.length > 0 && <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_2.25rem] gap-2 text-xs text-muted-foreground">
              <span>{t('vocabulary.phraseEn')}</span><span>{t('vocabulary.meaningVi')}</span><span />
            </div>}
            {collocations.map(({ en, vi }, index) => <div key={index} className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] gap-2">
              <Input lang="en" required maxLength={200} value={en} aria-label={t('vocabulary.collocationEn', { index: index + 1 })}
                onChange={(event) => setCollocations((current) => current.map((row, i) => i === index ? { ...row, en: event.target.value } : row))} />
              <Input lang="vi" maxLength={500} value={vi} aria-label={t('vocabulary.collocationVi', { index: index + 1 })}
                onChange={(event) => setCollocations((current) => current.map((row, i) => i === index ? { ...row, vi: event.target.value } : row))} />
              <Button type="button" variant="ghost" size="icon-sm" aria-label={t('vocabulary.removeCollocation', { index: index + 1 })}
                onClick={() => setCollocations((current) => current.filter((_, i) => i !== index))}>
                <RiDeleteBinLine aria-hidden="true" />
              </Button>
            </div>)}
            <Button type="button" size="sm" variant="outline" disabled={collocations.length >= 12} className="justify-self-start"
              onClick={() => setCollocations((current) => [...current, { en: '', vi: '' }])}>
              <RiAddLine aria-hidden="true" />{t('vocabulary.addCollocation')}
            </Button>
          </fieldset>

          <div className="grid gap-3 sm:grid-cols-3">
            {wordLists.map((key) => <Field key={key}
              label={`${t(`vocabulary.${key}`)} (${parseList(wordListsText[key]).length})`}>
              <Textarea lang="en" rows={3} value={wordListsText[key]} maxLength={2400}
                placeholder={t('vocabulary.onePerLine')}
                onChange={(event) => setWordListsText({ ...wordListsText, [key]: event.target.value })} />
            </Field>)}
          </div>
        </div>
      </TabsContent>
    </Tabs>

    <div className="grid shrink-0 gap-2.5 border-t pt-3">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex flex-wrap items-center gap-5">
          <label className="inline-flex items-center gap-2 text-sm font-medium">
            <Switch size="sm" checked={needsReview} onCheckedChange={setNeedsReview} />
            {t('vocabulary.flagReview')}
          </label>
          <Visibility hidden={hidden} onChange={setHidden} />
        </div>
        <SaveActions saving={save.isPending} onClose={() => { speech.stop(); onClose(); }} />
      </div>
      {invalid && <p role="alert" className="text-sm text-destructive">{t(`vocabulary.validation.${invalid}`)}</p>}
      <CatalogError error={save.error} />
    </div>
  </form>;
}
