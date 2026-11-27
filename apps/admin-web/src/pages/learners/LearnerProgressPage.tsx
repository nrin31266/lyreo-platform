import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  RiArrowRightUpLine,
  RiBarChart2Line,
  RiSearchLine,
  RiTableLine,
} from '@remixicon/react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Input } from '@/components/ui/input';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchAdminAnalyticsSummary, fetchLearnerAnalytics } from '@/pages/overview/admin-ops.api';
import { fetchLearners } from './learners.api';
import { LearnerWeaknesses } from './LearnerWeaknesses';
import { MetricHelp } from './MetricHelp';

function formatMinutes(seconds: number) {
  return `${Math.round(seconds / 60)}m`;
}

const tooltipStyle = {
  backgroundColor: 'var(--popover)',
  borderColor: 'var(--border)',
  color: 'var(--popover-foreground)',
  borderRadius: 'var(--radius)',
  fontSize: '12px',
};

export function LearnerProgressPage() {
  const { t } = useTranslation('admin');
  const [search, setSearch] = useState('');
  const [selectedLearnerId, setSelectedLearnerId] = useState<string>('');
  const [viewMode, setViewMode] = useState<'chart' | 'table'>('chart');

  const summaryQuery = useQuery({
    queryKey: ['admin-analytics-summary'],
    queryFn: ({ signal }) => fetchAdminAnalyticsSummary(signal),
  });

  const learnersQuery = useQuery({
    queryKey: ['admin-learners', 'progress', search],
    queryFn: ({ signal }) => fetchLearners(0, search.trim(), '', signal),
  });

  const activeLearnerId = selectedLearnerId || learnersQuery.data?.items[0]?.learnerId || '';
  const selectedLearner = learnersQuery.data?.items.find(
    (item) => item.learnerId === activeLearnerId,
  );

  const learnerAnalyticsQuery = useQuery({
    queryKey: ['admin-learner-analytics', activeLearnerId],
    queryFn: ({ signal }) => fetchLearnerAnalytics(activeLearnerId, signal),
    enabled: Boolean(activeLearnerId),
  });

  const dailyChartData = (summaryQuery.data?.dailySeries ?? []).map((row) => ({
    date: row.activityDate.slice(5),
    lessons: row.lessonCount,
    vocab: row.vocabularyReviewCount,
    grammar: row.grammarAnswerCount,
    toeic: row.toeicQuestionCount,
    studyMinutes: Math.round(row.studySeconds / 60),
    activeLearners: row.activeLearners,
  }));

  const skillChartData = (learnerAnalyticsQuery.data?.skills ?? []).map((s) => ({
    skill: t(`learnerProgress.skills.${s.skillCode}`, { defaultValue: s.skillCode }),
    score: Number(Number(s.score).toFixed(1)),
  }));

  return (
    <div className="w-full space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{t('learnerProgress.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('learnerProgress.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant={viewMode === 'chart' ? 'default' : 'outline'}
            onClick={() => setViewMode('chart')}
          >
            <RiBarChart2Line data-icon="inline-start" aria-hidden="true" />
            {t('learnerProgress.chartView')}
          </Button>
          <Button
            type="button"
            size="sm"
            variant={viewMode === 'table' ? 'default' : 'outline'}
            onClick={() => setViewMode('table')}
          >
            <RiTableLine data-icon="inline-start" aria-hidden="true" />
            {t('learnerProgress.tableView')}
          </Button>
        </div>
      </div>

      {summaryQuery.error ? (
        <CatalogError error={summaryQuery.error} retry={() => void summaryQuery.refetch()} />
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <Card>
          <CardHeader className="pb-2">
            <CardDescription className="flex items-center gap-1">
              {t('learnerProgress.metrics.active7d')}
              <MetricHelp label={t('learnerProgress.metrics.active7d')} calculation="active7d" />
            </CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {summaryQuery.isPending ? (
              <Skeleton className="h-7 w-16" />
            ) : (
              summaryQuery.data?.activeLearners7d ?? 0
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardDescription className="flex items-center gap-1">
              {t('learnerProgress.metrics.studyMinutes7d')}
              <MetricHelp label={t('learnerProgress.metrics.studyMinutes7d')} calculation="studyMinutes7d" />
            </CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {summaryQuery.isPending ? (
              <Skeleton className="h-7 w-16" />
            ) : (
              formatMinutes(summaryQuery.data?.studySeconds7d ?? 0)
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardDescription className="flex items-center gap-1">
              {t('learnerProgress.metrics.lessons7d')}
              <MetricHelp label={t('learnerProgress.metrics.lessons7d')} calculation="lessons7d" />
            </CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {summaryQuery.isPending ? (
              <Skeleton className="h-7 w-16" />
            ) : (
              summaryQuery.data?.lessonsCompleted7d ?? 0
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardDescription className="flex items-center gap-1">
              {t('learnerProgress.metrics.vocab7d')}
              <MetricHelp label={t('learnerProgress.metrics.vocab7d')} calculation="vocab7d" />
            </CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {summaryQuery.isPending ? (
              <Skeleton className="h-7 w-16" />
            ) : (
              summaryQuery.data?.vocabularyReviews7d ?? 0
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2">
            <CardDescription className="flex items-center gap-1">
              {t('learnerProgress.metrics.grammarToeic7d')}
              <MetricHelp label={t('learnerProgress.metrics.grammarToeic7d')} calculation="grammarToeic7d" />
            </CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {summaryQuery.isPending ? (
              <Skeleton className="h-7 w-20" />
            ) : (
              `${summaryQuery.data?.grammarAnswers7d ?? 0} / ${summaryQuery.data?.toeicQuestions7d ?? 0}`
            )}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-1">
            {t('overviewDashboard.dailyActivityTitle')}
            <MetricHelp label={t('overviewDashboard.dailyActivityTitle')} calculation="platformDaily" />
          </CardTitle>
          <CardDescription>{t('overviewDashboard.dailyActivityHelp')}</CardDescription>
        </CardHeader>
        <CardContent>
          {summaryQuery.isPending ? (
            <Skeleton className="h-64 w-full" />
          ) : dailyChartData.length === 0 ? (
            <Empty className="border py-8">
              <EmptyHeader>
                <EmptyTitle>{t('overviewDashboard.noDailyTitle')}</EmptyTitle>
                <EmptyDescription>{t('overviewDashboard.noDailyHelp')}</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : viewMode === 'chart' ? (
            <div className="h-64 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={dailyChartData}>
                  <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="date" stroke="var(--muted-foreground)" fontSize={12} />
                  <YAxis stroke="var(--muted-foreground)" fontSize={12} />
                  <Tooltip contentStyle={tooltipStyle} />
                  <Legend wrapperStyle={{ fontSize: '12px' }} />
                  <Bar
                    dataKey="lessons"
                    name={t('overviewDashboard.columns.lessons')}
                    fill="var(--chart-1)"
                    radius={[4, 4, 0, 0]}
                  />
                  <Bar
                    dataKey="vocab"
                    name={t('overviewDashboard.columns.vocab')}
                    fill="var(--chart-2)"
                    radius={[4, 4, 0, 0]}
                  />
                  <Bar
                    dataKey="grammar"
                    name={t('overviewDashboard.columns.grammar')}
                    fill="var(--chart-3)"
                    radius={[4, 4, 0, 0]}
                  />
                  <Bar
                    dataKey="toeic"
                    name={t('overviewDashboard.columns.toeic')}
                    fill="var(--chart-4)"
                    radius={[4, 4, 0, 0]}
                  />
                </BarChart>
              </ResponsiveContainer>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('overviewDashboard.columns.date')}</TableHead>
                  <TableHead className="text-right">
                    {t('overviewDashboard.columns.learners')}
                  </TableHead>
                  <TableHead className="text-right">
                    {t('overviewDashboard.columns.lessons')}
                  </TableHead>
                  <TableHead className="text-right">
                    {t('overviewDashboard.columns.vocab')}
                  </TableHead>
                  <TableHead className="text-right">
                    {t('overviewDashboard.columns.grammar')}
                  </TableHead>
                  <TableHead className="text-right">
                    {t('overviewDashboard.columns.toeic')}
                  </TableHead>
                  <TableHead className="text-right">
                    {t('overviewDashboard.columns.duration')}
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {dailyChartData.map((row) => (
                  <TableRow key={row.date}>
                    <TableCell className="font-mono text-xs">{row.date}</TableCell>
                    <TableCell className="text-right tabular-nums">{row.activeLearners}</TableCell>
                    <TableCell className="text-right tabular-nums">{row.lessons}</TableCell>
                    <TableCell className="text-right tabular-nums">{row.vocab}</TableCell>
                    <TableCell className="text-right tabular-nums">{row.grammar}</TableCell>
                    <TableCell className="text-right tabular-nums">{row.toeic}</TableCell>
                    <TableCell className="text-right tabular-nums">{row.studyMinutes}m</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <div className="grid gap-6 lg:grid-cols-12">
        <Card className="lg:col-span-5">
          <CardHeader>
            <CardTitle>{t('learnerProgress.rosterTitle')}</CardTitle>
            <CardDescription>{t('learnerProgress.rosterHelp')}</CardDescription>
            <div className="relative pt-1">
              <RiSearchLine
                className="pointer-events-none absolute top-3.5 left-3 size-4 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder={t('learnerProgress.searchPlaceholder')}
                className="pl-9"
              />
            </div>
          </CardHeader>
          <CardContent className="space-y-2">
            {learnersQuery.isPending ? (
              <div className="space-y-2">
                <Skeleton className="h-12 w-full" />
                <Skeleton className="h-12 w-full" />
              </div>
            ) : !learnersQuery.data || learnersQuery.data.items.length === 0 ? (
              <Empty className="border py-8">
                <EmptyHeader>
                  <EmptyTitle>{t('learnerProgress.noLearners')}</EmptyTitle>
                </EmptyHeader>
              </Empty>
            ) : (
              learnersQuery.data.items.map((learner) => {
                const selected = learner.learnerId === activeLearnerId;
                return (
                  <button
                    key={learner.learnerId}
                    type="button"
                    onClick={() => setSelectedLearnerId(learner.learnerId)}
                    className={`flex w-full items-center justify-between gap-2 rounded-xl border px-3 py-2 text-left transition-colors ${
                      selected ? 'border-primary bg-muted/40' : 'hover:bg-muted/25'
                    }`}
                  >
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium">
                        {learner.displayName || learner.email || learner.learnerId}
                      </p>
                      <p className="truncate text-xs text-muted-foreground">
                        {learner.email || learner.learnerId}
                      </p>
                    </div>
                    <Badge variant="outline" className="shrink-0">
                      {learner.currentLevel || learner.onboardingStatus}
                    </Badge>
                  </button>
                );
              })
            )}
          </CardContent>
        </Card>

        <Card className="lg:col-span-7">
          <CardHeader className="flex flex-row items-start justify-between gap-3">
            <div>
              <CardTitle>
                {selectedLearner?.displayName ||
                  selectedLearner?.email ||
                  t('learnerProgress.inspectorTitle')}
              </CardTitle>
              <CardDescription>{t('learnerProgress.inspectorHelp')}</CardDescription>
            </div>
            {activeLearnerId ? (
              <Button asChild variant="outline" size="sm">
                <Link to={`/learners/${encodeURIComponent(activeLearnerId)}`}>
                  <RiArrowRightUpLine aria-hidden="true" />
                  {t('learnerProgress.openProfile')}
                </Link>
              </Button>
            ) : null}
          </CardHeader>
          <CardContent className="space-y-5">
            {learnerAnalyticsQuery.error ? (
              <CatalogError
                error={learnerAnalyticsQuery.error}
                retry={() => void learnerAnalyticsQuery.refetch()}
              />
            ) : learnerAnalyticsQuery.isPending ? (
              <div className="space-y-3">
                <Skeleton className="h-20 w-full" />
                <Skeleton className="h-24 w-full" />
              </div>
            ) : !learnerAnalyticsQuery.data?.hasHistory ? (
              <Empty className="border py-8">
                <EmptyHeader>
                  <RiBarChart2Line className="size-8 text-muted-foreground" aria-hidden="true" />
                  <EmptyTitle>{t('learnerProgress.noHistoryTitle')}</EmptyTitle>
                  <EmptyDescription>{t('learnerProgress.noHistoryHelp')}</EmptyDescription>
                </EmptyHeader>
              </Empty>
            ) : (
              <>
                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="rounded-xl border bg-muted/20 p-3">
                    <p className="flex items-center gap-1 text-xs text-muted-foreground">
                      {t('learnerProgress.todayWindow')}
                      <MetricHelp label={t('learnerProgress.todayWindow')} calculation="window" />
                    </p>
                    <p className="mt-1 text-lg font-semibold tabular-nums">
                      {formatMinutes(learnerAnalyticsQuery.data.today.studySeconds)} ·{' '}
                      {t('learnerProgress.windowSummary', {
                        lessons: learnerAnalyticsQuery.data.today.lessonCount,
                        vocab: learnerAnalyticsQuery.data.today.vocabularyReviewCount,
                      })}
                    </p>
                  </div>
                  <div className="rounded-xl border bg-muted/20 p-3">
                    <p className="flex items-center gap-1 text-xs text-muted-foreground">
                      {t('learnerProgress.weekWindow')}
                      <MetricHelp label={t('learnerProgress.weekWindow')} calculation="window" />
                    </p>
                    <p className="mt-1 text-lg font-semibold tabular-nums">
                      {formatMinutes(learnerAnalyticsQuery.data.week.studySeconds)} ·{' '}
                      {t('learnerProgress.windowSummary', {
                        lessons: learnerAnalyticsQuery.data.week.lessonCount,
                        vocab: learnerAnalyticsQuery.data.week.vocabularyReviewCount,
                      })}
                    </p>
                  </div>
                </div>

                <div>
                  <h2 className="mb-2 flex items-center gap-1 text-sm font-medium">
                    {t('learnerProgress.skillsTitle')}
                    <MetricHelp label={t('learnerProgress.skillsTitle')} calculation="skills" />
                  </h2>
                  {learnerAnalyticsQuery.data.skills.length === 0 ? (
                    <p className="text-sm text-muted-foreground">{t('learnerProgress.noSkills')}</p>
                  ) : viewMode === 'chart' ? (
                    <div className="h-52 w-full">
                      <ResponsiveContainer width="100%" height="100%">
                        <BarChart data={skillChartData} layout="vertical">
                          <CartesianGrid
                            stroke="var(--border)"
                            strokeDasharray="3 3"
                            horizontal={false}
                          />
                          <XAxis
                            type="number"
                            domain={[0, 100]}
                            stroke="var(--muted-foreground)"
                            fontSize={12}
                          />
                          <YAxis
                            type="category"
                            dataKey="skill"
                            width={110}
                            stroke="var(--muted-foreground)"
                            fontSize={11}
                          />
                          <Tooltip contentStyle={tooltipStyle} />
                          <Bar dataKey="score" fill="var(--chart-2)" radius={[0, 4, 4, 0]} />
                        </BarChart>
                      </ResponsiveContainer>
                    </div>
                  ) : (
                    <div className="space-y-3">
                      {learnerAnalyticsQuery.data.skills.map((skill) => (
                        <div key={skill.skillCode} className="space-y-1">
                          <div className="flex items-center justify-between text-xs">
                            <span className="font-medium">{skill.skillCode}</span>
                            <span className="text-muted-foreground tabular-nums">
                              {Number(skill.score).toFixed(1)}% ({skill.sampleCount})
                            </span>
                          </div>
                          <Progress value={Math.min(100, Math.max(0, Number(skill.score)))} />
                        </div>
                      ))}
                    </div>
                  )}
                </div>

                <div>
                  <h2 className="mb-2 flex items-center gap-1 text-sm font-medium">
                    {t('learnerProgress.weaknessesTitle')}
                    <MetricHelp label={t('learnerProgress.weaknessesTitle')} calculation="weaknesses" />
                  </h2>
                  <LearnerWeaknesses items={learnerAnalyticsQuery.data.weaknesses} />
                </div>
              </>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
