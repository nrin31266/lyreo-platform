import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  RiArrowLeftLine,
  RiCloseLine,
  RiEditLine,
  RiLoader4Line,
  RiSearchLine,
  RiStopMiniFill,
  RiVolumeUpLine,
} from '@remixicon/react';
import { AdminPagination } from '@/components/AdminPagination';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { cn } from '@/lib/utils';
import { useDebouncedInput } from '@/hooks/useDebouncedInput';
import { MediaPreview } from '@/pages/library/ContentMedia';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { useVocabularyAudio } from './useVocabularyAudio';
import { EntryDialog, VocabularyNameDialog } from './VocabularyEditors';
import { fetchVocabulary, reviewFilters, vocabularyKey, type EntrySummary, type Pack, type Unit } from './vocabulary-api';

const ALL = 'all';

export function VocabularyPage() {
  const { t, i18n } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const [editingName, setEditingName] = useState<Pack | Unit | null>(null);
  const [editingEntry, setEditingEntry] = useState<EntrySummary | null>(null);
  const [saved, setSaved] = useState(false);
  const speech = useVocabularyAudio();
  const q = params.get('q') ?? '';
  const review = reviewFilters.find((value) => value === params.get('review')) ?? 'ALL';
  const rawPage = Number(params.get('page'));
  const page = Number.isInteger(rawPage) && rawPage >= 0 && rawPage <= 10000 ? rawPage : 0;
  const number = new Intl.NumberFormat(i18n.resolvedLanguage);
  const packs = useQuery({
    queryKey: [...vocabularyKey, 'packs'],
    queryFn: ({ signal }) => fetchVocabulary<Pack[]>('/packs', signal),
  });
  const pack = packs.data?.find((value) => value.id === params.get('pack'));
  const units = useQuery({
    queryKey: [...vocabularyKey, 'units', pack?.id],
    enabled: Boolean(pack),
    queryFn: ({ signal }) => fetchVocabulary<Unit[]>(`/packs/${pack?.id}/units`, signal),
  });
  const unit = units.data?.find((value) => value.id === params.get('unit'));
  const update = (changes: Record<string, string>) => {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    setParams(next, { replace: true });
  };
  const [input, setInput] = useDebouncedInput(q, (value) => update({ q: value.trim(), page: '' }));
  const search = new URLSearchParams({ q, page: String(page) });
  if (review !== 'ALL') search.set('review', review);
  if (pack) search.set('packId', pack.id);
  if (unit) search.set('unitId', unit.id);
  const entries = useQuery({
    queryKey: [...vocabularyKey, 'entries', search.toString()],
    enabled: Boolean(packs.data?.length) && (!pack || units.isSuccess),
    queryFn: ({ signal }) => fetchVocabulary<{ items: EntrySummary[]; total: number }>(`/entries?${search}`, signal),
  });
  const total = entries.data?.total ?? 0;
  useEffect(() => {
    if (!entries.data || entries.isFetching) return;
    const last = Math.max(0, Math.ceil(total / 30) - 1);
    if (page > last) update({ page: last ? String(last) : '' });
  }, [entries.data, entries.isFetching, total, page]);

  const vietnamese = i18n.resolvedLanguage?.startsWith('vi');
  const localeTitle = (value: Pack) => vietnamese ? value.titleVi : value.titleEn;
  const packTitle = (id: string) => {
    const value = packs.data?.find((candidate) => candidate.id === id);
    return value ? localeTitle(value) : '';
  };
  const openPackOrUnitEditor = (value: Pack | Unit) => {
    speech.stop();
    setSaved(false);
    setEditingName(value);
  };
  const openEntryEditor = (value: EntrySummary) => {
    speech.stop();
    setSaved(false);
    setEditingEntry(value);
  };
  const chooseUnit = (id: string) => update({ unit: id === ALL ? '' : id, page: '' });
  const savedEditor = () => {
    setEditingEntry(null);
    setEditingName(null);
    setSaved(true);
  };
  const hasPacks = Boolean(packs.data?.length);
  const hasActiveFilters = Boolean(q || review !== 'ALL' || pack || unit);

  function renderStatus(entry: EntrySummary) {
    if (entry.hidden) return <Badge variant="secondary">{t('vocabulary.hidden')}</Badge>;
    if (entry.missingVietnamese) return <Badge variant="warning">{t('vocabulary.reviews.MISSING_VI')}</Badge>;
    if (entry.needsReview) return <Badge variant="warning">{t('vocabulary.reviews.NEEDS_REVIEW')}</Badge>;
    return <span className="text-xs text-muted-foreground">{t('vocabulary.statusReady')}</span>;
  }

  return <section className="mx-auto grid w-full max-w-7xl gap-4">
    <header className="flex flex-wrap items-baseline justify-between gap-3">
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">{t('vocabulary.title')}</h1>
        {hasPacks && <p className="text-sm tabular-nums text-muted-foreground">{t('vocabulary.catalogCount', {
          packs: number.format(packs.data!.length),
          units: number.format(packs.data!.reduce((sum, item) => sum + item.unitCount, 0)),
          entries: number.format(packs.data!.reduce((sum, item) => sum + item.entryCount, 0)),
        })}</p>}
      </div>
      <div className="text-sm font-medium text-success" aria-live="polite">{saved && t('vocabulary.saved')}</div>
    </header>

    <CatalogError error={packs.error} retry={() => void packs.refetch()} />
    {packs.isPending && <CatalogSkeleton />}
    {packs.data?.length === 0 && <div className="grid justify-items-center gap-1.5 rounded-xl border border-dashed p-12 text-center">
      <h2 className="font-medium">{t('vocabulary.emptyTitle')}</h2>
      <p className="text-sm text-muted-foreground">{t('vocabulary.emptyHelp')}</p>
    </div>}

    {hasPacks && <div className="grid items-start gap-5 lg:grid-cols-[16rem_minmax(0,1fr)]">
      <aside aria-label={t('vocabulary.catalogNavigation')} className="grid min-w-0 gap-3 rounded-xl border bg-card p-3 lg:sticky lg:top-4">
        <div className="grid gap-2.5 lg:hidden">
          <div className="grid gap-1.5">
            <Label htmlFor="vocabulary-pack">{t('vocabulary.pack')}</Label>
            <div className="flex items-center gap-1.5">
              <Select value={pack?.id ?? ALL} onValueChange={(id) => update({ pack: id === ALL ? '' : id, unit: '', page: '' })}>
                <SelectTrigger id="vocabulary-pack" className="min-w-0 flex-1"><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL}>{t('vocabulary.allPacks')}</SelectItem>
                  {packs.data?.map((item) => <SelectItem key={item.id} value={item.id}>
                    {localeTitle(item)}{item.hidden ? ` · ${t('vocabulary.hidden')}` : ''}
                  </SelectItem>)}
                </SelectContent>
              </Select>
              {pack && <Button type="button" size="icon" variant="outline" aria-label={t('vocabulary.editPack')}
                onClick={() => openPackOrUnitEditor(pack)}><RiEditLine aria-hidden="true" /></Button>}
            </div>
          </div>
          {pack && <div className="grid gap-1.5">
            <Label htmlFor="vocabulary-unit">{t('vocabulary.unit')}</Label>
            <Select value={unit?.id ?? ALL} disabled={units.isPending} onValueChange={chooseUnit}>
              <SelectTrigger id="vocabulary-unit" className="w-full"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>{t('vocabulary.allUnits')}</SelectItem>
                {units.data?.map((item) => <SelectItem key={item.id} value={item.id}>
                  {item.title}{item.hidden ? ` · ${t('vocabulary.hidden')}` : ''}
                </SelectItem>)}
              </SelectContent>
            </Select>
          </div>}
        </div>

        {!pack ? <div className="hidden gap-1.5 lg:grid">
          <div className="flex items-center justify-between px-2 py-0.5 text-xs font-medium text-muted-foreground">
            <span>{t('vocabulary.pack')}</span>
            <span className="tabular-nums">{number.format(packs.data!.length)}</span>
          </div>
          <div className="max-h-[calc(100dvh-10rem)] space-y-0.5 overflow-y-auto pr-0.5">
            <button type="button" aria-pressed={!pack} onClick={() => update({ pack: '', unit: '', page: '' })}
              className="flex min-h-9 w-full items-center justify-between rounded-lg bg-muted px-2.5 py-1.5 text-left text-sm font-medium focus-visible:outline-2 focus-visible:outline-ring">
              <span>{t('vocabulary.allPacks')}</span>
              <span className="text-xs tabular-nums text-muted-foreground">
                {number.format(packs.data!.reduce((sum, item) => sum + item.entryCount, 0))}
              </span>
            </button>
            {packs.data?.map((item) => <button type="button" key={item.id} aria-pressed={false}
              onClick={() => update({ pack: item.id, unit: '', page: '' })}
              className="grid w-full grid-cols-[2.25rem_minmax(0,1fr)_auto] items-center gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors hover:bg-muted/70 focus-visible:outline-2 focus-visible:outline-ring">
              <span className="size-9 overflow-hidden rounded-md border bg-muted [&_img]:h-full [&_img]:w-full [&_img]:object-cover">
                {item.coverImage && <MediaPreview assetId={item.coverImage} role="image" compact />}
              </span>
              <span className="min-w-0">
                <span className="block truncate text-sm leading-snug">{localeTitle(item)}</span>
                {item.hidden && <span className="block text-[11px] text-muted-foreground">{t('vocabulary.hidden')}</span>}
              </span>
              <span className="text-xs tabular-nums text-muted-foreground">{number.format(item.entryCount)}</span>
            </button>)}
          </div>
        </div> : <div className="hidden gap-2.5 lg:grid">
          <div className="grid gap-2 rounded-lg bg-muted/45 p-2.5">
            <div className="flex items-center justify-between gap-1">
              <button type="button" onClick={() => update({ pack: '', unit: '', page: '' })}
                className="inline-flex items-center gap-1 rounded-sm text-xs font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-2 focus-visible:outline-ring">
                <RiArrowLeftLine className="size-3.5" aria-hidden="true" />{t('vocabulary.allPacks')}
              </button>
              <Button type="button" size="icon-sm" variant="ghost" aria-label={t('vocabulary.editPack')}
                onClick={() => openPackOrUnitEditor(pack)}><RiEditLine aria-hidden="true" /></Button>
            </div>
            <div className="grid grid-cols-[2.5rem_minmax(0,1fr)] items-center gap-2.5">
              <span className="size-10 overflow-hidden rounded-md border bg-muted [&_img]:h-full [&_img]:w-full [&_img]:object-cover">
                {pack.coverImage && <MediaPreview assetId={pack.coverImage} role="image" compact />}
              </span>
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{localeTitle(pack)}</p>
                <p className="text-xs tabular-nums text-muted-foreground">
                  {number.format(pack.unitCount)} {t('vocabulary.unit').toLowerCase()} · {t('vocabulary.wordCount', { count: pack.entryCount })}
                </p>
              </div>
            </div>
          </div>
          <div className="max-h-[calc(100dvh-15rem)] space-y-0.5 overflow-y-auto pr-0.5">
            <button type="button" aria-pressed={!unit} onClick={() => chooseUnit(ALL)}
              className={cn('flex min-h-9 w-full items-center justify-between gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm transition-colors hover:bg-muted/70 focus-visible:outline-2 focus-visible:outline-ring',
                !unit && 'bg-muted font-medium')}>
              <span>{t('vocabulary.allUnits')}</span>
              <span className="text-xs tabular-nums text-muted-foreground">{number.format(pack.entryCount)}</span>
            </button>
            {units.data?.map((item) => {
              const titleHasLevel = item.title.startsWith(item.level);
              return <button type="button" key={item.id} aria-pressed={item.id === unit?.id}
                onClick={() => chooseUnit(item.id)} className={cn('flex min-h-9 w-full items-center justify-between gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm transition-colors hover:bg-muted/70 focus-visible:outline-2 focus-visible:outline-ring',
                  item.id === unit?.id && 'bg-muted font-medium')}>
                <span className="min-w-0 truncate leading-snug">
                  {!titleHasLevel && <span className="mr-1.5 text-xs font-medium text-muted-foreground">{item.level}</span>}
                  {item.title}
                  {item.hidden && <span className="ml-1.5 text-xs font-normal text-muted-foreground">· {t('vocabulary.hidden')}</span>}
                </span>
                <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{number.format(item.entryCount)}</span>
              </button>;
            })}
          </div>
        </div>}
      </aside>

      <div className="grid min-w-0 gap-3">
        <div className="grid gap-2.5 rounded-xl border bg-card p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex min-w-0 flex-wrap items-baseline gap-x-2.5 gap-y-1">
              <h2 className="truncate text-base font-semibold">
                {unit ? `${localeTitle(pack!)} · ${unit.title}` : (pack ? localeTitle(pack) : t('vocabulary.allPacks'))}
              </h2>
              {unit && <Badge variant="outline">{unit.level}</Badge>}
              {(unit?.hidden || pack?.hidden) && <Badge variant="secondary">{t('vocabulary.hidden')}</Badge>}
              <span className="text-xs tabular-nums text-muted-foreground">
                {t('vocabulary.resultCount', { number: number.format(total) })}
              </span>
            </div>
            <div className="flex flex-wrap items-center gap-1.5">
              {pack && <Button type="button" size="sm" variant="ghost" className="hidden lg:inline-flex" onClick={() => openPackOrUnitEditor(pack)}>
                {t('vocabulary.editPack')}
              </Button>}
              {unit && <Button type="button" size="sm" variant="outline" onClick={() => openPackOrUnitEditor(unit)}>
                {t('vocabulary.editUnit')}
              </Button>}
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <div className="relative min-w-[14rem] flex-1">
              <RiSearchLine className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input type="search" id="vocabulary-search" aria-label={t('vocabulary.search')} value={input} maxLength={120}
                className="pl-9 pr-8" placeholder={t('vocabulary.searchPlaceholder')} onChange={(event) => setInput(event.target.value)} />
              {input && <button type="button" aria-label={t('vocabulary.clearSearch')}
                onClick={() => { setInput(''); update({ q: '', page: '' }); }}
                className="absolute top-1/2 right-2.5 -translate-y-1/2 rounded-sm p-0.5 text-muted-foreground hover:text-foreground">
                <RiCloseLine className="size-4" aria-hidden="true" />
              </button>}
            </div>
            <div className="min-w-[12rem]">
              <Select value={review} onValueChange={(value) => update({ review: value === 'ALL' ? '' : value, page: '' })}>
                <SelectTrigger id="vocabulary-review" aria-label={t('vocabulary.reviewFilter')} className="w-full"><SelectValue /></SelectTrigger>
                <SelectContent>{reviewFilters.map((value) => <SelectItem value={value} key={value}>{t(`vocabulary.reviews.${value}`)}</SelectItem>)}</SelectContent>
              </Select>
            </div>
            {hasActiveFilters && <Button type="button" variant="ghost" size="sm" onClick={() => {
              setInput('');
              update({ q: '', review: '', pack: '', unit: '', page: '' });
            }}>
              {t('jobs.clearFilters')}
            </Button>}
          </div>
        </div>

        {speech.failedId && <p role="alert" className="text-sm text-destructive">{t('audioPlayer.failed')}</p>}
        <CatalogError error={units.error ?? entries.error} retry={() => {
          if (units.error) void units.refetch();
          else void entries.refetch();
        }} />
        {(entries.isLoading || (pack && units.isPending)) && <CatalogSkeleton />}
        {entries.data && <div aria-busy={entries.isFetching}>
          <div className="overflow-hidden rounded-xl border bg-card">
            <Table>
              <TableHeader className="bg-muted/40">
                <TableRow>
                  <TableHead className="w-[27%]">{t('vocabulary.term')}</TableHead>
                  <TableHead>{t(vietnamese ? 'vocabulary.meaningVi' : 'vocabulary.meaningEn')}</TableHead>
                  <TableHead className="w-[20%]">{t('vocabulary.unit')}</TableHead>
                  <TableHead className="w-[15%] text-right">{t('vocabulary.reviewFilter')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {entries.data.items.map((entry) => {
                  const playing = Boolean(entry.audioId && speech.activeId === entry.audioId);
                  const loadingAudio = Boolean(entry.audioId && speech.loadingId === entry.audioId);
                  return <TableRow key={entry.id} className="align-middle">
                    <TableCell className="whitespace-normal py-2.5">
                      <div className="flex items-start gap-1.5">
                        {entry.audioId && <Button type="button" size="icon-sm" variant={playing ? 'secondary' : 'ghost'}
                          className="mt-0.5 shrink-0 text-muted-foreground hover:text-foreground"
                          aria-label={t('vocabulary.listenWord', { term: entry.term })}
                          aria-pressed={playing}
                          onClick={() => speech.toggle(entry.audioId)}>
                          {loadingAudio ? <RiLoader4Line className="animate-spin motion-reduce:animate-none" aria-hidden="true" />
                            : playing ? <RiStopMiniFill aria-hidden="true" />
                            : <RiVolumeUpLine aria-hidden="true" />}
                        </Button>}
                        <div className="min-w-0">
                          <Button type="button" variant="link" className="h-auto px-0 text-left text-[15px] font-semibold leading-snug"
                            onClick={() => openEntryEditor(entry)}>
                            <span lang="en">{entry.term}</span>
                          </Button>
                          <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                            <span lang="en" className="font-medium text-foreground/75">{entry.pos}</span>
                            {entry.ipa && <span lang="en" className="truncate">{entry.ipa}</span>}
                          </div>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell className="max-w-md whitespace-normal py-2.5">
                      <div className="flex flex-wrap items-baseline gap-1.5">
                        <span lang={vietnamese && entry.meaningVi ? 'vi' : 'en'} className="text-sm font-medium leading-snug text-foreground">
                          {vietnamese ? entry.meaningVi ?? entry.definitionEn : entry.definitionEn}
                        </span>
                        {entry.senseCount > 1 && <span className="text-[11px] tabular-nums text-muted-foreground">
                          {t('vocabulary.moreSenses', { count: entry.senseCount - 1 })}
                        </span>}
                      </div>
                      {vietnamese && entry.meaningVi && <p lang="en" className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">
                        {entry.definitionEn}
                      </p>}
                    </TableCell>
                    <TableCell className="whitespace-normal py-2.5">
                      <div className="flex items-baseline gap-1.5">
                        <span className="text-xs font-medium text-muted-foreground">{entry.level}</span>
                        {unit?.id === entry.unitId ? <span className="truncate text-sm">{entry.unitTitle}</span>
                          : <Button type="button" variant="link" className="h-auto truncate px-0 text-left text-sm"
                            onClick={() => update({ pack: entry.packId, unit: entry.unitId, page: '' })}>{entry.unitTitle}</Button>}
                      </div>
                      {!pack && <span className="mt-0.5 block truncate text-xs text-muted-foreground">{packTitle(entry.packId)}</span>}
                    </TableCell>
                    <TableCell className="whitespace-nowrap py-2.5 text-right">
                      <div className="inline-flex items-center justify-end gap-2">
                        {renderStatus(entry)}
                        <Button type="button" size="icon-sm" variant="ghost" aria-label={t('vocabulary.editEntry', { term: entry.term })}
                          onClick={() => openEntryEditor(entry)}>
                          <RiEditLine aria-hidden="true" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>;
                })}
                {!entries.data.items.length && <TableRow><TableCell colSpan={4} className="py-10 text-center text-muted-foreground">
                  {t('vocabulary.noMatches')}
                </TableCell></TableRow>}
              </TableBody>
            </Table>
          </div>
          <footer className="flex flex-wrap items-center justify-between gap-2 pt-3">
            <p className="text-sm tabular-nums text-muted-foreground" aria-live="polite">
              {t('vocabulary.resultCount', { number: number.format(total) })}
            </p>
            {total > 30 && <AdminPagination page={page} pageCount={Math.ceil(total / 30)} disabled={entries.isFetching}
              onPageChange={(nextPage) => update({ page: nextPage ? String(nextPage) : '' })} />}
            {page > 0 && total <= 30 && <Button variant="outline" onClick={() => update({ page: '' })}>
              {t('vocabulary.firstPage')}
            </Button>}
          </footer>
        </div>}
      </div>
    </div>}

    <footer className="border-t pt-3 text-xs text-muted-foreground">
      <a href="https://github.com/designfuchen/word-atlas" className="underline underline-offset-2" target="_blank" rel="noreferrer">
        Word Atlas contributors
      </a>{' · '}
      <a href="https://creativecommons.org/licenses/by/4.0/" className="underline underline-offset-2" target="_blank" rel="noreferrer">CC BY 4.0</a>
      {' · '}{t('vocabulary.attribution')}
    </footer>
    {editingName && <VocabularyNameDialog value={editingName} onClose={() => setEditingName(null)} onSaved={savedEditor} />}
    {editingEntry && <EntryDialog summary={editingEntry} onClose={() => setEditingEntry(null)} onSaved={savedEditor} />}
  </section>;
}
