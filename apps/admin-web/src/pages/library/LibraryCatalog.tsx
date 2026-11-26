import { useEffect, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { RiSearchLine, RiBook2Line, RiQuestionLine, RiFileList3Line, RiPencilLine, RiFilterOffLine, RiListCheck2, RiEyeLine, RiEyeOffLine } from '@remixicon/react';
import { api, ApiError } from '@/lib/api';
import { AccessTierBadge, DifficultyBadge, PosTag, statusBadgeVariant } from '@/components/ContentBadges';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Empty, EmptyTitle, EmptyDescription } from '@/components/ui/empty';
import { AdminPagination } from '@/components/AdminPagination';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { BulkStatus } from './BulkStatus';
import { LibrarySelect } from './LibrarySelect';
import { useDebouncedInput } from '@/hooks/useDebouncedInput';
import { lexiconPageParams } from './lexicon-pagination';
import { lexiconPartsOfSpeech } from "./library.types";
import type { CatalogPage, Document, Summary } from './library.types';

export function LibraryList({ endpoint, route, title, extra }: { endpoint: string; route: string; title: string; extra?: ReactNode }) {
  const { t, i18n } = useTranslation('admin');
  const cache = useQueryClient();
  const [search, setSearch] = useSearchParams();
  const query = search.get('q') ?? '';
  const lexicon = route === '/lexicon', toeic = route === '/toeic', questions = route.endsWith('questions');
  const kind = route.split('/').at(-1)!;
  const mode = lexicon ? 'lexicon' : toeic ? 'toeic' : questions ? 'questions' : kind;
  const titleOf = (row: Summary) => i18n.language.startsWith('en') ? (row.titleEn || row.title) : (row.title || row.titleEn || '');
  const page = Math.max(0, Math.min(100000, Math.floor(Number(search.get('page')) || 0)));
  const [input, setInput] = useDebouncedInput(query, value => update({ q: value.trim(), page: null, after: null, before: null, tail: null, skip: null, take: null }, true));
  const [bulk, setBulk] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  useEffect(() => setSelected([]), [search.toString()]);
  function update(patch: Record<string, string | null>, replace = false) {
    setSearch(previous => { const next = new URLSearchParams(previous); for (const [key, value] of Object.entries(patch)) { if (value === null || value === '') next.delete(key); else next.set(key, value); } return next; }, { replace });
  }
  const params = new URLSearchParams({ q: query, size: lexicon ? search.get('take') || '30' : '30', page: String(page) });
  if (lexicon) for (const key of ['after', 'before', 'tail', 'skip']) if (search.get(key) && !(lexicon && key === 'status' && search.get(key) === 'EDITED')) params.set(key, search.get(key)!);
  const filterKeys = ['status', 'year', 'type', 'translation', 'pos', 'access', 'quality', 'difficulty'];
  if (kind === 'subtopic') filterKeys.push('parentId');
  for (const key of filterKeys) if (search.get(key) && !(lexicon && key === 'status' && search.get(key) === 'EDITED')) params.set(lexicon && key === 'status' ? 'state' : key, search.get(key)!);
  const list = useQuery({ queryKey: ['library', endpoint, params.toString()], queryFn: async ({ signal }) => {
    if (!lexicon) return api<CatalogPage<Summary>>(`${endpoint}/page?${params}`, { signal });
    const rows = await api<Summary[]>(`${endpoint}/browse?${params}`, { signal });
    return { items: rows, total: undefined, page, size: 30 };
  } });
  const visibility = useMutation({
    mutationFn: async (row: Summary) => {
      const current=await api<Document<unknown>>(`${endpoint}/${row.id}`);
      if(current.status !== row.status) throw new ApiError(409,"Visibility changed","CONFLICT",t('library.visibilityChanged'));
      return api(`${endpoint}/${row.id}/status`, {method:'PUT',body:JSON.stringify({revision:current.revision,status:row.status === 'PUBLISHED' ? 'HIDDEN' : 'PUBLISHED'})});
    },
    onSuccess: () => cache.invalidateQueries({queryKey:['library',endpoint]}),
  });
  function visibilityAction(row: Summary) {
    if(row.status === 'DRAFT') return null;
    return <Button size="sm" variant="ghost" tooltip={t(row.status === 'PUBLISHED' ? 'library.help.hide' : 'library.help.show')} aria-label={t(row.status === 'PUBLISHED' ? 'library.hideItem' : 'library.showItem',{title:titleOf(row)})} disabled={visibility.isPending} onClick={()=>visibility.mutate(row)}>{row.status === 'PUBLISHED' ? <RiEyeOffLine aria-hidden="true" /> : <RiEyeLine aria-hidden="true" />}{t(row.status === 'PUBLISHED' ? 'library.hide' : 'library.showShort')}</Button>;
  }
  const parents = useQuery({queryKey:['library','/api/v1/admin/grammar/topic','parents'],enabled:kind === 'subtopic',queryFn:async({signal})=>(await api<CatalogPage<Summary>>('/api/v1/admin/grammar/topic/page?size=100&page=0',{signal})).items});
  const rows = list.data?.items;
  const countParams = new URLSearchParams(params);
  for (const key of ['page', 'size', 'after', 'before', 'tail', 'skip']) countParams.delete(key);
  const count = useQuery({ queryKey: ['library', endpoint, 'count', countParams.toString()], enabled: lexicon, queryFn: ({ signal }) => api<number>(`${endpoint}/count?${countParams}`, { signal }), staleTime: 60000 });
  const total = lexicon ? count.data : list.data?.total;
  useEffect(() => {
    if (total === undefined || list.isFetching || lexicon && count.isFetching) return;
    const last = Math.max(0, Math.ceil(total / 30) - 1);
    if (page > last) update(lexicon ? lexiconPageParams(last, page, total) : {page:last ? String(last) : null}, true);
  }, [total, page, lexicon, list.isFetching, count.isFetching]);
  function goToPage(target: number) {
    update(lexicon ? lexiconPageParams(target, page, total, rows?.[0]?.cursor, rows?.at(-1)?.cursor) : { page: target ? String(target) : null });
  }
  const Icon = lexicon ? RiBook2Line : questions ? RiQuestionLine : RiFileList3Line;
  function filter(key: string, label: string, options: { value: string; label: string }[]) {
    return <LibrarySelect label={label} value={search.get(key) ?? ''} onValueChange={value => update({ [key]: value, page: null, after: null, before: null, tail: null, skip: null, take: null })} placeholder={t('library.all')} options={options} />;
  }
  function toggle(id: string, checked: boolean) { setSelected(previous => checked ? [...new Set([...previous, id])].slice(0, 100) : previous.filter(value => value !== id)); }
  function visibilityLabel(value: string) {
    return t(value === 'PUBLISHED' ? 'library.learnerVisible' : value === 'HIDDEN' ? 'library.learnerHidden' : 'library.learnerUnavailable');
  }
  function status(row: Summary) {
    return <Badge variant={statusBadgeVariant(row.status)}>
      {lexicon ? visibilityLabel(row.status) : t(`library.states.${row.status}`, { defaultValue: row.status })}
    </Badge>;
  }
  function meaning(row: Summary) {
    const vietnamese = i18n.language.startsWith('vi');
    const primary = vietnamese ? row.translationVi : row.definition;
    const alternative = vietnamese ? row.definition : row.translationVi;
    return <div className="grid gap-1">
      <p className="line-clamp-2 text-sm">{primary || alternative || '—'}</p>
      {!primary && alternative && <span className="text-xs text-muted-foreground">{t(vietnamese ? 'dictionary.mode.en' : 'dictionary.mode.vi')}</span>}
    </div>;
  }
  function check(row: Summary) { return <Checkbox aria-label={t('library.selectItem', { title: titleOf(row) })} checked={selected.includes(row.id)} disabled={!selected.includes(row.id) && selected.length >= 100} onCheckedChange={checked => toggle(row.id, checked === true)} />; }
  const filtered = !!query || filterKeys.some(key => search.get(key));
  return <section className="library-workspace">
    <header className="flex flex-wrap items-start justify-between gap-4">
      <div className="min-w-0 flex-1"><h1 className="flex items-center gap-3"><Icon className="size-6 shrink-0 text-primary" aria-hidden="true" />{title}</h1><p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted-foreground">{t(`library.catalogs.${mode}.help`)}</p></div>
    </header>
    {extra}
    <div className="library-filters" role="search" aria-label={t('library.search')}>
      <label className="library-search"><span className="text-sm font-medium">{t('library.searchLabel')}</span><div className="relative"><RiSearchLine className="pointer-events-none absolute left-3 top-2.5 size-4 text-muted-foreground" aria-hidden="true" /><Input aria-label={t('library.search')} className="pl-9" placeholder={t(`library.catalogs.${mode}.search`)} value={input} onChange={event => setInput(event.target.value)} /></div></label>
        {filter('status', t(lexicon ? 'library.learnerVisibility' : 'library.state'), ['PUBLISHED', 'HIDDEN', 'DRAFT'].map(value => ({ value, label: lexicon ? visibilityLabel(value) : t(`library.states.${value}`) })))}
        {lexicon && <>{filter('type', t('library.entryType'), ['WORD', 'PHRASE', 'PHRASAL_VERB', 'IDIOM', 'COLLOCATION'].map(value => ({ value, label: t(`library.types.${value}`) })))}{filter('pos', t('library.partOfSpeech'), lexiconPartsOfSpeech.map(row => ({...row,label:t(`library.partsOfSpeech.${row.value}`, {defaultValue:row.label})})))}{filter('translation', t('library.translationState'), ['AVAILABLE', 'MISSING', 'NEEDS_REVIEW', 'AI_GENERATED', 'VERIFIED'].map(value => ({ value, label: t(`library.translationStates.${value}`) })))}</>}
        {!lexicon && !questions && filter('access', t('library.access'), [{ value: 'PUBLIC', label: t('library.public') }, { value: 'FEATURE', label: t('library.restricted') }])}
        {toeic && <>{filter('year', t('library.year'), Array.from({ length: 31 }, (_, index) => String(new Date().getFullYear() + 1 - index)).map(value => ({ value, label: value })))}{filter('quality', t('library.qualityFilter'), ['UNCHECKED','RUNNING','BLOCKED','WARNING','PASSED','STALE','FAILED'].map(value=>({value,label:t(`quality.status.${value}`)})))}</>}
        {kind === 'subtopic' && filter('parentId',t('library.parentTopic'),parents.data?.map(row=>({value:row.id,label:titleOf(row)}))??[])}
        {questions && filter('difficulty', t('library.difficulty'), [1, 2, 3, 4, 5].map(value => ({ value: String(value), label: t('library.difficultyNumber', { number: value }) })))}
      {filtered && <Button className="self-end" variant="ghost" size="sm" onClick={() => { setInput(''); const patch = Object.fromEntries([...filterKeys, 'q', 'page', 'after', 'before', 'tail', 'skip', 'take'].map(key => [key, null])); update(patch); }}><RiFilterOffLine aria-hidden="true" />{t('jobs.clearFilters')}</Button>}
    </div>
    <CatalogError error={list.error || count.error || visibility.error || parents.error} retry={() => {
      if (count.error) void count.refetch();
      if (parents.error) void parents.refetch();
      if (visibility.error) visibility.reset();
      void list.refetch();
    }} />
    <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-base font-semibold">{t(`library.catalogs.${mode}.list`)}</h2><Button variant={bulk ? 'secondary' : 'ghost'} size="sm" onClick={() => { setBulk(!bulk); setSelected([]); }}><RiListCheck2 aria-hidden="true" />{t(bulk ? 'library.finishSelection' : 'library.bulkSelect')}</Button>{selected.length > 0 && <div className="flex flex-wrap items-center gap-2"><BulkStatus endpoint={endpoint} selected={selected} clear={() => setSelected([])} /><Button variant="ghost" size="sm" onClick={() => setSelected([])}>{t('library.clearSelection')}</Button></div>}</div>
    {list.isPending ? <CatalogSkeleton /> : !rows?.length && !list.isError ? <Empty><Icon className="size-8 text-muted-foreground" aria-hidden="true" /><EmptyTitle>{t('library.empty')}</EmptyTitle><EmptyDescription>{t(filtered ? 'library.noFilterMatches' : `library.catalogs.${mode}.help`)}</EmptyDescription></Empty> : toeic ?
      <div className="overflow-hidden rounded-xl border"><Table><TableHeader><TableRow>
        {bulk && <TableHead className="w-12">{t('library.choose')}</TableHead>}
        <TableHead>{t('library.title')}</TableHead><TableHead>{t('library.year')}</TableHead><TableHead>{t('library.content')}</TableHead><TableHead>{t('library.access')}</TableHead><TableHead>{t('library.state')}</TableHead><TableHead>{t('library.qualityFilter')}</TableHead><TableHead className="text-right">{t('catalog.actions')}</TableHead>
      </TableRow></TableHeader><TableBody>{rows?.map(row => <TableRow key={row.id}>
        {bulk && <TableCell>{check(row)}</TableCell>}<TableCell><Link className="font-medium hover:underline" to={`${route}/${row.id}?tab=preview`}>{titleOf(row)}</Link></TableCell>
        <TableCell className="tabular-nums">{row.year ?? '—'}</TableCell><TableCell>{t('library.questionCount', { count: row.questionCount ?? 0 })}</TableCell><TableCell><AccessTierBadge accessMode={row.accessMode} /></TableCell><TableCell>{status(row)}</TableCell>
        <TableCell><Badge variant={statusBadgeVariant(row.qualityStatus ?? 'UNCHECKED')} asChild><Link className="underline underline-offset-2" to={`${route}/${row.id}?tab=quality`}>{t(`quality.status.${row.qualityStatus ?? 'UNCHECKED'}`)}</Link></Badge></TableCell><TableCell className="text-right"><Button tooltip={t("library.help.editTest")} variant="ghost" size="sm" asChild><Link to={`${route}/${row.id}`}><RiPencilLine aria-hidden="true" />{t('library.editTest')}</Link></Button>{visibilityAction(row)}</TableCell>
      </TableRow>)}</TableBody></Table></div> :
      <div className="overflow-hidden rounded-xl border"><Table className="library-table"><TableHeader><TableRow>
        {bulk && <TableHead className="w-12"><Checkbox aria-label={t('library.selectPage')} checked={!!rows?.length && rows.every(row => selected.includes(row.id))} onCheckedChange={checked => setSelected(previous => checked === true ? [...new Set([...previous, ...(rows?.map(row => row.id) ?? [])])].slice(0, 100) : previous.filter(id => !rows?.some(row => row.id === id)) )} /></TableHead>}
        <TableHead className={lexicon || questions ? 'w-[38%]' : 'w-[35%]'}>{t(lexicon ? 'library.headword' : questions ? 'library.questionText' : 'library.title')}</TableHead>
        <TableHead>{t(lexicon ? 'library.meaningPreview' : questions ? 'library.difficulty' : 'library.content')}</TableHead>
        <TableHead>{t(lexicon ? 'library.learnerVisibility' : questions ? 'library.state' : 'library.access')}</TableHead>
        {!lexicon && !questions && <TableHead>{t('library.state')}</TableHead>}
        <TableHead className="w-44 text-right">{t('catalog.actions')}</TableHead>
      </TableRow></TableHeader><TableBody>{rows?.map(row => <TableRow key={row.id}>
        {bulk && <TableCell>{check(row)}</TableCell>}<TableCell><Link className="font-medium leading-relaxed hover:underline" to={`${route}/${row.id}`}>{titleOf(row) || t(row.type === 'CLOZE' ? 'library.clozeQuestion' : 'library.untitledQuestion')}</Link>{lexicon && <><p className="mt-1 flex flex-wrap items-center gap-1 text-xs text-muted-foreground"><span>{t(`library.types.${row.type}`, {defaultValue:row.type})}</span>{row.partOfSpeech && <><span>·</span>{row.partOfSpeech.split(",").map(pos => <PosTag key={pos.trim()} pos={pos.trim()} label={t(`library.partsOfSpeech.${pos.trim()}`, {defaultValue:pos.trim()})} />)}</>}<span>· {t('library.senseCount', { count: row.senseCount ?? 0 })}</span></p></>}</TableCell>
        <TableCell>{lexicon ? meaning(row) : questions ? <DifficultyBadge difficulty={row.difficultyLevel} /> : <div className="grid gap-2"><Link className="hover:underline" to={`${route}/${row.id}?tab=questions`}>{t('library.questionCount', { count: row.questionCount ?? 0 })}</Link>{kind === 'topic' ? <Link className="text-xs underline underline-offset-4" to={`/grammar/subtopic?parentId=${row.id}`}>{t('library.viewSubtopics')}</Link> : kind === 'subtopic' && row.parentId ? <Link className="text-xs text-muted-foreground hover:underline" to={`/grammar/topic/${row.parentId}`}>{parents.data?.find(parent=>parent.id===row.parentId) ? titleOf(parents.data.find(parent=>parent.id===row.parentId)!) : t('library.parentTopic')}</Link> : null}</div>}</TableCell>
        <TableCell>{lexicon || questions ? status(row) : <AccessTierBadge accessMode={row.accessMode} />}</TableCell>
        {!lexicon && !questions && <TableCell>{status(row)}</TableCell>}
        <TableCell className="text-right"><Button tooltip={t("library.help.openEditor")} variant="ghost" size="sm" asChild><Link to={`${route}/${row.id}`}><RiPencilLine aria-hidden="true" />{t('catalog.editMetadataShort')}</Link></Button>{visibilityAction(row)}</TableCell>
      </TableRow>)}</TableBody></Table></div>}
    {list.data && <footer className="flex flex-wrap items-center justify-between gap-3"><span className="text-sm text-muted-foreground">{total !== undefined ? t('pagination.total', { count: total }) : t('pagination.cursorHelp')}</span><AdminPagination page={page} pageCount={total === undefined ? undefined : Math.ceil(total / 30)} hasNext={rows?.length === 30} disabled={list.isFetching || list.isError} onPageChange={goToPage} /></footer>}
  </section>;
}
