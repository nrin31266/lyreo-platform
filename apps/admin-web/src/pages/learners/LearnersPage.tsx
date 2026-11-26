import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Fragment, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useParams, useSearchParams } from 'react-router-dom';
import {
  RiArrowLeftLine,
  RiAwardLine,
  RiLineChartLine,
  RiSearchLine,
  RiUser3Line,
  RiVipCrownLine,
} from '@remixicon/react';
import { AdminPagination } from '@/components/AdminPagination';
import { CefrLevelBadge } from '@/components/ContentBadges';
import { statusBadgeVariant } from '@/components/ContentBadges';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/ui/input-group';
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useDebouncedInput } from '@/hooks/useDebouncedInput';
import { ApiError } from '@/lib/api';
import { cn } from '@/lib/utils';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { LearnerAvatarDialog, LearnerNameEditor } from './LearnerAppearanceEditor';
import { LearnerAvatar } from './LearnerAvatar';
import { LearnerAccessAndRewards } from './LearnerAccessAndRewards';
import { LearnerStudyProgress } from './LearnerStudyProgress';
import { fetchLearner, fetchLearners, type OnboardingStatus } from './learners.api';

type InspectorTab = 'overview' | 'plan' | 'rewards';

function OnboardingBadge({ status }: { status: OnboardingStatus }) {
  const { t } = useTranslation('admin');
  return (
    <Badge variant={statusBadgeVariant(status)}>
      {t(`learnerProfiles.status.${status}`)}
    </Badge>
  );
}

function LearnerInspectorPanel({
  learnerId,
  backSearch,
}: {
  learnerId: string;
  backSearch?: string;
}) {
  const { t, i18n } = useTranslation('admin');
  const [inspectorTab, setInspectorTab] = useState<InspectorTab>('overview');
  const learner = useQuery({
    queryKey: ['learner-directory', learnerId],
    queryFn: ({ signal }) => fetchLearner(learnerId, signal),
    enabled: Boolean(learnerId),
  });
  const entry = learner.data;
  const missing = t('learnerProfiles.missing');
  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });
  const localized = (field: 'goals' | 'levels' | 'focus', value: string | null) =>
    value ? t(`learnerProfiles.${field}.${value}`, { defaultValue: value }) : missing;
  const notFound = learner.error instanceof ApiError && learner.error.status === 404;

  if (!learnerId) {
    return (
      <Card className="border-dashed">
        <CardContent className="py-12">
          <Empty>
            <EmptyHeader>
              <EmptyTitle>{t('learnerProfiles.inspectorEmptyTitle')}</EmptyTitle>
              <EmptyDescription>{t('learnerProfiles.inspectorEmptyHelp')}</EmptyDescription>
            </EmptyHeader>
          </Empty>
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="space-y-4">
      {backSearch !== undefined && (
        <Button variant="ghost" size="sm" className="-ml-2" asChild>
          <Link to={{ pathname: '/learners', search: backSearch }}>
            <RiArrowLeftLine data-icon="inline-start" aria-hidden="true" />
            {t('learnerProfiles.back')}
          </Link>
        </Button>
      )}

      {learner.isPending && <CatalogSkeleton />}

      {notFound ? (
        <Empty className="border">
          <EmptyHeader>
            <EmptyTitle>{t('learnerProfiles.notFound')}</EmptyTitle>
          </EmptyHeader>
        </Empty>
      ) : (
        <CatalogError
          error={learner.error}
          retry={() => void learner.refetch()}
          retrying={learner.isFetching}
        />
      )}

      {entry && (
        <Card className="min-w-0">
          <CardContent className="grid gap-5 pt-5">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b pb-4">
              <div className="flex min-w-0 flex-wrap items-center gap-3">
                <Fragment key={entry.learnerId}>
                  <LearnerAvatarDialog entry={entry} />
                  <div className="min-w-0 space-y-0.5">
                    <LearnerNameEditor entry={entry} />
                    <p className="truncate text-xs text-muted-foreground">
                      {entry.email || missing}
                    </p>
                  </div>
                </Fragment>
              </div>
              <OnboardingBadge status={entry.onboardingStatus} />
            </div>

            <Tabs
              value={inspectorTab}
              onValueChange={(value) => setInspectorTab(value as InspectorTab)}
            >
              <TabsList className="w-full justify-start overflow-x-auto">
                <TabsTrigger value="overview" className="gap-1.5">
                  <RiLineChartLine className="size-4" aria-hidden="true" />
                  {t('learnerProfiles.inspectorTabs.overview')}
                </TabsTrigger>
                <TabsTrigger value="plan" className="gap-1.5">
                  <RiVipCrownLine className="size-4" aria-hidden="true" />
                  {t('learnerProfiles.inspectorTabs.plan')}
                </TabsTrigger>
                <TabsTrigger value="rewards" className="gap-1.5">
                  <RiAwardLine className="size-4" aria-hidden="true" />
                  {t('learnerProfiles.inspectorTabs.rewards')}
                </TabsTrigger>
              </TabsList>

              <TabsContent value="overview" className="mt-4 space-y-4">
                <dl className="grid gap-x-6 gap-y-4 rounded-xl border bg-muted/20 p-4 sm:grid-cols-2">
                  {(
                    [
                      ['email', entry.email || missing],
                      ['firstSeen', date.format(new Date(entry.firstSeenAt))],
                      [
                        'completedAt',
                        entry.onboardingCompletedAt
                          ? date.format(new Date(entry.onboardingCompletedAt))
                          : missing,
                      ],
                      ['goal', localized('goals', entry.goal)],
                      ['level', localized('levels', entry.currentLevel)],
                      [
                        'minutes',
                        entry.dailyMinutes == null
                          ? missing
                          : t('learnerProfiles.perDay', { minutes: entry.dailyMinutes }),
                      ],
                      ['focusArea', localized('focus', entry.focusArea)],
                    ] as const
                  ).map(([key, value]) => (
                    <div key={key} className="min-w-0">
                      <dt className="text-xs text-muted-foreground">{t(`learnerProfiles.${key}`)}</dt>
                      <dd className="mt-0.5 break-words text-sm font-medium">
                        {key === 'level' && entry.currentLevel ? (
                          <CefrLevelBadge level={entry.currentLevel} />
                        ) : (
                          value
                        )}
                      </dd>
                    </div>
                  ))}
                </dl>
                <LearnerStudyProgress learnerId={entry.learnerId} />
              </TabsContent>

              <TabsContent value="plan" className="mt-4">
                <LearnerAccessAndRewards learnerId={entry.learnerId} section="plan" />
              </TabsContent>

              <TabsContent value="rewards" className="mt-4">
                <LearnerAccessAndRewards learnerId={entry.learnerId} section="rewards" />
              </TabsContent>
            </Tabs>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export function LearnersPage({ initialSelectedId }: { initialSelectedId?: string } = {}) {
  const { t, i18n } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const { search } = useLocation();
  const query = (params.get('query') ?? '').trim().slice(0, 120);
  const statusParam = params.get('status');
  const status = statusParam === 'PENDING' || statusParam === 'COMPLETED' ? statusParam : '';
  const pageParam = Number(params.get('page'));
  const page = Number.isInteger(pageParam) && pageParam >= 0 && pageParam <= 2_147_483_647 ? pageParam : 0;

  function changeFilter(key: 'query' | 'status', value: string) {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.delete('page');
        if (value) next.set(key, value);
        else next.delete(key);
        return next;
      },
      { replace: key === 'query' },
    );
  }

  const [input, setInput] = useDebouncedInput(query, (value) => changeFilter('query', value.trim()));
  const learners = useQuery({
    queryKey: ['learner-directory', query, status, page],
    queryFn: ({ signal }) => fetchLearners(page, query, status, signal),
    placeholderData: keepPreviousData,
  });

  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' });
  const missing = t('learnerProfiles.missing');
  const hasFilters = Boolean(query || status || page);

  const selectedParam = params.get('selected');
  const activeLearnerId =
    initialSelectedId ?? selectedParam ?? learners.data?.items[0]?.learnerId ?? '';

  function selectLearner(id: string) {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.set('selected', id);
      return next;
    });
  }

  return (
    <section className="grid gap-5">
      <header className="space-y-1">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="space-y-1">
            <h1 className="text-2xl font-semibold tracking-tight">{t('navigation.profiles')}</h1>
            <p className="text-sm text-muted-foreground">{t('learnerProfiles.workspaceHelp')}</p>
          </div>
          {learners.data && (
            <Badge variant="secondary" className="gap-1.5 tabular-nums">
              <RiUser3Line className="size-3.5" aria-hidden="true" />
              {t('learnerProfiles.totalCount', { count: learners.data.total })}
            </Badge>
          )}
        </div>
      </header>

      <div className="grid items-start gap-5 lg:grid-cols-12">
        <div className="grid gap-3 lg:col-span-5">
          <div className="flex flex-wrap items-center gap-2">
            <InputGroup className="min-w-0 flex-1">
              <InputGroupInput
                type="search"
                aria-label={t('learnerProfiles.search')}
                placeholder={t('learnerProfiles.search')}
                maxLength={120}
                value={input}
                onChange={(event) => setInput(event.target.value)}
              />
              <InputGroupAddon>
                <RiSearchLine aria-hidden="true" />
              </InputGroupAddon>
            </InputGroup>
            <Select
              value={status || 'all'}
              onValueChange={(value) => changeFilter('status', value === 'all' ? '' : value)}
            >
              <SelectTrigger className="w-full sm:w-44" aria-label={t('learnerProfiles.onboarding')}>
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

          <CatalogError
            error={learners.error}
            retry={() => void learners.refetch()}
            retrying={learners.isFetching}
          />

          {learners.isPending && <CatalogSkeleton />}

          {learners.data && (
            <div className="overflow-hidden rounded-xl border bg-background">
              {learners.data.items.length ? (
                <Table aria-label={t('navigation.profiles')} aria-busy={learners.isFetching}>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('learnerProfiles.name')}</TableHead>
                      <TableHead>{t('learnerProfiles.onboarding')}</TableHead>
                      <TableHead className="text-right">{t('learnerProfiles.firstSeen')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {learners.data.items.map((entry) => {
                      const isSelected = activeLearnerId === entry.learnerId;
                      return (
                        <TableRow
                          key={entry.learnerId}
                          data-state={isSelected ? 'selected' : undefined}
                          className={cn(
                            'cursor-pointer transition-colors',
                            isSelected && 'bg-muted/70',
                          )}
                          onClick={() => selectLearner(entry.learnerId)}
                        >
                          <TableCell>
                            <div className="flex items-center gap-2.5">
                              <LearnerAvatar entry={entry} />
                              <div className="min-w-0 max-w-52">
                                <button
                                  type="button"
                                  aria-pressed={isSelected}
                                  className="block w-full truncate text-left font-medium hover:underline focus-visible:outline-none focus-visible:underline"
                                  onClick={(event) => {
                                    event.stopPropagation();
                                    selectLearner(entry.learnerId);
                                  }}
                                >
                                  {entry.displayName || t('learnerProfiles.openProfile')}
                                </button>
                                <p className="truncate text-xs text-muted-foreground">
                                  {entry.email || missing}
                                </p>
                              </div>
                            </div>
                          </TableCell>
                          <TableCell>
                            <OnboardingBadge status={entry.onboardingStatus} />
                          </TableCell>
                          <TableCell className="text-right text-xs tabular-nums text-muted-foreground">
                            {date.format(new Date(entry.firstSeenAt))}
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              ) : (
                <div className="p-4">
                  <Empty>
                    <EmptyHeader>
                      <EmptyTitle>{t('learnerProfiles.empty')}</EmptyTitle>
                      <EmptyDescription>
                        {t(hasFilters ? 'learnerProfiles.emptyFiltered' : 'learnerProfiles.emptyHelp')}
                      </EmptyDescription>
                    </EmptyHeader>
                    {hasFilters && (
                      <Button variant="outline" onClick={() => setParams({})}>
                        {t('learnerProfiles.clearFilters')}
                      </Button>
                    )}
                  </Empty>
                </div>
              )}

              {(page > 0 || learners.data.total > learners.data.size) && (
                <div className="border-t bg-muted/15 px-3 py-2">
                  <AdminPagination
                    page={page}
                    pageCount={Math.ceil(learners.data.total / learners.data.size)}
                    disabled={learners.isFetching}
                    onPageChange={(nextPage) =>
                      setParams((previous) => {
                        const next = new URLSearchParams(previous);
                        if (nextPage === 0) next.delete('page');
                        else next.set('page', String(nextPage));
                        return next;
                      })
                    }
                  />
                </div>
              )}
            </div>
          )}
        </div>

        <div className="lg:sticky lg:top-4 lg:col-span-7">
          <LearnerInspectorPanel
            learnerId={activeLearnerId}
            backSearch={initialSelectedId ? search : undefined}
          />
        </div>
      </div>
    </section>
  );
}

export function LearnerDetailPage() {
  const { learnerId = '' } = useParams();
  return <LearnersPage initialSelectedId={learnerId} />;
}
