import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom';
import { RiArrowLeftLine, RiArrowRightLine, RiSearchLine } from '@remixicon/react';
import { AdminPagination } from '@/components/AdminPagination';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/ui/input-group';
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useDebouncedInput } from '@/hooks/useDebouncedInput';
import { ApiError } from '@/lib/api';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { LearnerAvatarDialog, LearnerNameEditor } from './LearnerAppearanceEditor';
import { LearnerAvatar } from './LearnerAvatar';
import { LearnerAccessAndRewards } from './LearnerAccessAndRewards';
import { LearnerStudyProgress } from './LearnerStudyProgress';
import { fetchLearner, fetchLearners, type OnboardingStatus } from './learners.api';

function OnboardingBadge({ status }: { status: OnboardingStatus }) {
  const { t } = useTranslation('admin');
  return <Badge variant={status === 'COMPLETED' ? 'secondary' : 'outline'}>{t(`learnerProfiles.status.${status}`)}</Badge>;
}

export function LearnersPage() {
  const { t, i18n } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const { search } = useLocation();
  const query = (params.get('query') ?? '').trim().slice(0, 120);
  const statusParam = params.get('status');
  const status = statusParam === 'PENDING' || statusParam === 'COMPLETED' ? statusParam : '';
  const pageParam = Number(params.get('page'));
  const page = Number.isInteger(pageParam) && pageParam >= 0 && pageParam <= 2_147_483_647 ? pageParam : 0;

  function changeFilter(key: 'query' | 'status', value: string) {
    setParams(previous => {
      const next = new URLSearchParams(previous);
      next.delete('page');
      if (value) next.set(key, value);
      else next.delete(key);
      return next;
    }, { replace: key === 'query' });
  }
  const [input, setInput] = useDebouncedInput(query, value => changeFilter('query', value.trim()));
  const learners = useQuery({
    queryKey: ['learner-directory', query, status, page],
    queryFn: ({ signal }) => fetchLearners(page, query, status, signal),
    placeholderData: keepPreviousData,
  });
  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' });
  const missing = t('learnerProfiles.missing');
  const hasFilters = Boolean(query || status || page);

  return (
    <section className="grid gap-4">
      <h1>{t('navigation.profiles')}</h1>
      <div className="flex flex-wrap items-center gap-2">
        <InputGroup className="w-full sm:w-80">
          <InputGroupInput
            type="search" aria-label={t('learnerProfiles.search')} placeholder={t('learnerProfiles.search')}
            maxLength={120} value={input} onChange={event => setInput(event.target.value)}
          />
          <InputGroupAddon><RiSearchLine aria-hidden="true" /></InputGroupAddon>
        </InputGroup>
        <Select value={status || 'all'} onValueChange={value => changeFilter('status', value === 'all' ? '' : value)}>
          <SelectTrigger className="w-full sm:w-48" aria-label={t('learnerProfiles.onboarding')}>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectGroup>
              <SelectItem value="all">{t('learnerProfiles.all')}</SelectItem>
              <SelectItem value="PENDING">{t('learnerProfiles.status.PENDING')}</SelectItem>
              <SelectItem value="COMPLETED">{t('learnerProfiles.status.COMPLETED')}</SelectItem>
            </SelectGroup>
          </SelectContent>
        </Select>
      </div>
      <CatalogError error={learners.error} retry={() => void learners.refetch()} retrying={learners.isFetching} />
      {learners.isPending && <CatalogSkeleton />}
      {learners.data && (
        <>
          {learners.data.items.length ? (
            <Table aria-label={t('navigation.profiles')} aria-busy={learners.isFetching}>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('learnerProfiles.name')}</TableHead>
                  <TableHead>{t('learnerProfiles.email')}</TableHead>
                  <TableHead>{t('learnerProfiles.onboarding')}</TableHead>
                  <TableHead>{t('learnerProfiles.firstSeen')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {learners.data.items.map(entry => (
                  <TableRow key={entry.learnerId}>
                    <TableCell>
                      <div className="flex items-center gap-2">
                        <LearnerAvatar entry={entry} />
                        <div className="min-w-0 max-w-64">
                          {!entry.displayName && <div className="text-muted-foreground">{missing}</div>}
                          <Button variant="link" asChild className="h-auto max-w-full p-0 text-left">
                            <Link to={{ pathname: `/learners/${entry.learnerId}`, search }} title={entry.displayName ?? undefined}>
                              <span className="truncate">{entry.displayName || t('learnerProfiles.openProfile')}</span>
                              <RiArrowRightLine data-icon="inline-end" aria-hidden="true" />
                            </Link>
                          </Button>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>{entry.email || missing}</TableCell>
                    <TableCell><OnboardingBadge status={entry.onboardingStatus} /></TableCell>
                    <TableCell className="tabular-nums">{date.format(new Date(entry.firstSeenAt))}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <Empty className="border">
              <EmptyHeader>
                <EmptyTitle>{t('learnerProfiles.empty')}</EmptyTitle>
                <EmptyDescription>{t(hasFilters ? 'learnerProfiles.emptyFiltered' : 'learnerProfiles.emptyHelp')}</EmptyDescription>
              </EmptyHeader>
              {hasFilters && (
                <Button variant="outline" onClick={() => setParams({})}>{t('learnerProfiles.clearFilters')}</Button>
              )}
            </Empty>
          )}
          {(page > 0 || learners.data.total > learners.data.size) && (
            <AdminPagination
              page={page} pageCount={Math.ceil(learners.data.total / learners.data.size)} disabled={learners.isFetching}
              onPageChange={nextPage => setParams(previous => {
                const next = new URLSearchParams(previous);
                if (nextPage === 0) next.delete('page');
                else next.set('page', String(nextPage));
                return next;
              })}
            />
          )}
        </>
      )}
    </section>
  );
}

export function LearnerDetailPage() {
  const { t, i18n } = useTranslation('admin');
  const { learnerId = '' } = useParams();
  const { search } = useLocation();
  const learner = useQuery({
    queryKey: ['learner-directory', learnerId],
    queryFn: ({ signal }) => fetchLearner(learnerId, signal),
  });
  const entry = learner.data;
  const missing = t('learnerProfiles.missing');
  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });
  const localized = (field: 'goals' | 'levels' | 'focus', value: string | null) =>
    value ? t(`learnerProfiles.${field}.${value}`, { defaultValue: value }) : missing;
  const notFound = learner.error instanceof ApiError && learner.error.status === 404;

  return (
    <section className="grid gap-5">
      <Button variant="ghost" size="sm" className="justify-self-start" asChild>
        <Link to={{ pathname: '/learners', search }}>
          <RiArrowLeftLine data-icon="inline-start" aria-hidden="true" />
          {t('learnerProfiles.back')}
        </Link>
      </Button>
      <div className="flex flex-wrap items-center gap-3">
        {entry ? (
          <Fragment key={entry.learnerId}>
            <LearnerAvatarDialog entry={entry} />
            <LearnerNameEditor entry={entry} />
            <OnboardingBadge status={entry.onboardingStatus} />
          </Fragment>
        ) : <h1>{t('navigation.profiles')}</h1>}
      </div>
      {learner.isPending && <CatalogSkeleton />}
      {notFound ? (
        <Empty className="border"><EmptyHeader><EmptyTitle>{t('learnerProfiles.notFound')}</EmptyTitle></EmptyHeader></Empty>
      ) : (
        <CatalogError error={learner.error} retry={() => void learner.refetch()} retrying={learner.isFetching} />
      )}
      {entry && (
        <>
          <dl className="grid gap-x-8 gap-y-5 rounded-lg border bg-card p-5 sm:grid-cols-2 xl:grid-cols-3">
            {([
              ['email', entry.email || missing],
              ['firstSeen', date.format(new Date(entry.firstSeenAt))],
              ['completedAt', entry.onboardingCompletedAt ? date.format(new Date(entry.onboardingCompletedAt)) : missing],
              ['goal', localized('goals', entry.goal)],
              ['level', localized('levels', entry.currentLevel)],
              ['minutes', entry.dailyMinutes == null ? missing : t('learnerProfiles.perDay', { minutes: entry.dailyMinutes })],
              ['focusArea', localized('focus', entry.focusArea)],
            ] as const).map(([key, value]) => (
              <div key={key} className="min-w-0">
                <dt className="text-sm text-muted-foreground">{t(`learnerProfiles.${key}`)}</dt>
                <dd className="mt-1 break-words font-medium">{value}</dd>
              </div>
            ))}
          </dl>
          <LearnerStudyProgress learnerId={entry.learnerId} />
          <LearnerAccessAndRewards learnerId={entry.learnerId} />
        </>
      )}
    </section>
  );
}
