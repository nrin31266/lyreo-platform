import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import {
  RiArrowRightUpLine,
  RiBookOpenLine,
  RiDiscussLine,
  RiGroupLine,
  RiImage2Line,
  RiListCheck3,
  RiStackLine,
  RiTimeLine,
  RiTrophyLine,
} from '@remixicon/react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { fetchAdminCurriculumPaths } from '@/pages/curriculum/curriculum.api';
import { fetchJobs } from '@/pages/jobs/jobs.api';
import { fetchLearners } from '@/pages/learners/learners.api';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchLessons } from '@/pages/lessons/catalog.api';
import { fetchAdminAnalyticsSummary, fetchAdminChatSummary } from './admin-ops.api';

function formatStudyMinutes(totalSeconds: number, t: (key: string, opts?: Record<string, unknown>) => string) {
  const minutes = Math.round(totalSeconds / 60);
  return t('overviewDashboard.minutesValue', { count: minutes });
}

export function OverviewPage() {
  const { t } = useTranslation('admin');

  const analyticsQuery = useQuery({
    queryKey: ['admin-analytics-summary'],
    queryFn: ({ signal }) => fetchAdminAnalyticsSummary(signal),
  });
  const pathsQuery = useQuery({
    queryKey: ['admin-curriculum', 'overview'],
    queryFn: ({ signal }) => fetchAdminCurriculumPaths(signal),
  });
  const lessonsQuery = useQuery({
    queryKey: ['admin-lessons', 'overview'],
    queryFn: ({ signal }) => fetchLessons(new URLSearchParams({ page: '0', size: '5' }), signal),
  });
  const learnersQuery = useQuery({
    queryKey: ['admin-learners', 'overview'],
    queryFn: ({ signal }) => fetchLearners(0, '', '', signal),
  });
  const jobsQuery = useQuery({
    queryKey: ['jobs', 'overview'],
    queryFn: ({ signal }) => fetchJobs(new URLSearchParams({ page: '0', size: '5' }), signal),
  });
  const chatQuery = useQuery({
    queryKey: ['admin-chat-summary', 'overview'],
    queryFn: ({ signal }) => fetchAdminChatSummary(signal),
  });

  const summary = analyticsQuery.data;
  const publishedPathsCount =
    pathsQuery.data?.filter((item) => item.status === 'PUBLISHED').length ?? 0;
  const primaryError = analyticsQuery.error ?? pathsQuery.error ?? learnersQuery.error;

  const kpis = [
    {
      key: 'activeLearners',
      label: t('overviewDashboard.kpis.activeLearners7d'),
      value: summary ? String(summary.activeLearners7d) : '—',
      detail: summary
        ? t('overviewDashboard.kpis.activeTodaySub', { count: summary.activeLearnersToday })
        : '',
      icon: RiGroupLine,
    },
    {
      key: 'lessonsCompleted',
      label: t('overviewDashboard.kpis.lessonsCompleted7d'),
      value: summary ? String(summary.lessonsCompleted7d) : '—',
      detail: lessonsQuery.data
        ? t('overviewDashboard.kpis.catalogLessonsSub', { count: lessonsQuery.data.total })
        : '',
      icon: RiBookOpenLine,
    },
    {
      key: 'vocabularyReviews',
      label: t('overviewDashboard.kpis.vocabularyReviews7d'),
      value: summary ? String(summary.vocabularyReviews7d) : '—',
      detail: summary
        ? t('overviewDashboard.kpis.studyTimeSub', {
            minutes: formatStudyMinutes(summary.studySeconds7d, t),
          })
        : '',
      icon: RiTimeLine,
    },
    {
      key: 'tutorTurns',
      label: t('overviewDashboard.kpis.tutorTurns7d'),
      value: chatQuery.data ? String(chatQuery.data.userTurns7d) : '—',
      detail: pathsQuery.data
        ? t('overviewDashboard.kpis.publishedPathsSub', {
            published: publishedPathsCount,
            total: pathsQuery.data.length,
          })
        : '',
      icon: RiDiscussLine,
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{t('overviewDashboard.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('overviewDashboard.subtitle')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button asChild variant="outline" size="sm">
            <Link to="/learners/progress">
              <RiArrowRightUpLine aria-hidden="true" />
              {t('overviewDashboard.actions.viewProgress')}
            </Link>
          </Button>
          <Button asChild size="sm">
            <Link to="/lessons/new">
              <RiBookOpenLine aria-hidden="true" />
              {t('overviewDashboard.actions.newLesson')}
            </Link>
          </Button>
        </div>
      </div>

      {primaryError ? (
        <CatalogError
          error={primaryError}
          retry={() => {
            void analyticsQuery.refetch();
            void pathsQuery.refetch();
            void learnersQuery.refetch();
          }}
        />
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {kpis.map((item) => {
          const Icon = item.icon;
          return (
            <Card key={item.key}>
              <CardHeader className="flex flex-row items-center justify-between gap-2 pb-2">
                <CardDescription>{item.label}</CardDescription>
                <Icon className="size-4 text-muted-foreground" aria-hidden="true" />
              </CardHeader>
              <CardContent>
                {analyticsQuery.isPending ? (
                  <Skeleton className="h-8 w-20" />
                ) : (
                  <p className="text-2xl font-semibold tabular-nums">{item.value}</p>
                )}
                <p className="mt-1 text-xs text-muted-foreground tabular-nums">{item.detail}</p>
              </CardContent>
            </Card>
          );
        })}
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>{t('overviewDashboard.dailyActivityTitle')}</CardTitle>
            <CardDescription>{t('overviewDashboard.dailyActivityHelp')}</CardDescription>
          </CardHeader>
          <CardContent>
            {analyticsQuery.isPending ? (
              <div className="space-y-2">
                <Skeleton className="h-8 w-full" />
                <Skeleton className="h-8 w-full" />
                <Skeleton className="h-8 w-full" />
              </div>
            ) : !summary || summary.dailySeries.length === 0 ? (
              <Alert>
                <AlertTitle>{t('overviewDashboard.noDailyTitle')}</AlertTitle>
                <AlertDescription>{t('overviewDashboard.noDailyHelp')}</AlertDescription>
              </Alert>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('overviewDashboard.columns.date')}</TableHead>
                    <TableHead className="text-right">{t('overviewDashboard.columns.learners')}</TableHead>
                    <TableHead className="text-right">{t('overviewDashboard.columns.lessons')}</TableHead>
                    <TableHead className="text-right">{t('overviewDashboard.columns.vocab')}</TableHead>
                    <TableHead className="text-right">{t('overviewDashboard.columns.grammar')}</TableHead>
                    <TableHead className="text-right">{t('overviewDashboard.columns.toeic')}</TableHead>
                    <TableHead className="text-right">{t('overviewDashboard.columns.duration')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {summary.dailySeries.map((day) => (
                    <TableRow key={day.activityDate}>
                      <TableCell className="font-medium tabular-nums">{day.activityDate}</TableCell>
                      <TableCell className="text-right tabular-nums">{day.activeLearners}</TableCell>
                      <TableCell className="text-right tabular-nums">{day.lessonCount}</TableCell>
                      <TableCell className="text-right tabular-nums">{day.vocabularyReviewCount}</TableCell>
                      <TableCell className="text-right tabular-nums">{day.grammarAnswerCount}</TableCell>
                      <TableCell className="text-right tabular-nums">{day.toeicQuestionCount}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        {formatStudyMinutes(day.studySeconds, t)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t('overviewDashboard.weaknessesTitle')}</CardTitle>
            <CardDescription>{t('overviewDashboard.weaknessesHelp')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {analyticsQuery.isPending ? (
              <div className="space-y-2">
                <Skeleton className="h-12 w-full" />
                <Skeleton className="h-12 w-full" />
              </div>
            ) : !summary || summary.topWeaknesses.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('overviewDashboard.noWeaknesses')}</p>
            ) : (
              summary.topWeaknesses.slice(0, 6).map((item) => (
                <div
                  key={`${item.weaknessType}:${item.weaknessKey}`}
                  className="flex items-center justify-between gap-3 rounded-xl border bg-muted/25 px-3 py-2"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">{item.weaknessKey}</p>
                    <p className="text-xs text-muted-foreground">
                      {item.weaknessType} ·{' '}
                      {t('overviewDashboard.learnersAffected', { count: item.affectedLearners })}
                    </p>
                  </div>
                  <Badge variant="outline" className="shrink-0 tabular-nums">
                    {Number(item.averageSeverity).toFixed(1)}%
                  </Badge>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between gap-2">
            <div>
              <CardTitle>{t('overviewDashboard.recentJobsTitle')}</CardTitle>
              <CardDescription>{t('overviewDashboard.recentJobsHelp')}</CardDescription>
            </div>
            <Button asChild variant="ghost" size="sm">
              <Link to="/jobs">
                <RiListCheck3 aria-hidden="true" />
                {t('overviewDashboard.actions.allJobs')}
              </Link>
            </Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {jobsQuery.isPending ? (
              <Skeleton className="h-24 w-full" />
            ) : !jobsQuery.data || jobsQuery.data.items.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('overviewDashboard.noJobs')}</p>
            ) : (
              jobsQuery.data.items.map((job) => (
                <div
                  key={job.id}
                  className="flex items-center justify-between gap-3 rounded-xl border px-3 py-2"
                >
                  <div className="min-w-0">
                    <p className="truncate text-sm font-medium">{job.jobType}</p>
                    <p className="truncate text-xs text-muted-foreground">
                      {job.currentStep ?? job.ownerModule ?? job.id}
                    </p>
                  </div>
                  <Badge variant="outline" className="shrink-0 tabular-nums">
                    {job.status} · {job.progressPercent}%
                  </Badge>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t('overviewDashboard.quickLinksTitle')}</CardTitle>
            <CardDescription>{t('overviewDashboard.quickLinksHelp')}</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-2 sm:grid-cols-2">
            <Button asChild variant="outline" className="justify-start">
              <Link to="/curriculum">
                <RiStackLine aria-hidden="true" />
                {t('navigation.curriculum')}
              </Link>
            </Button>
            <Button asChild variant="outline" className="justify-start">
              <Link to="/rewards">
                <RiTrophyLine aria-hidden="true" />
                {t('navigation.rewards')}
              </Link>
            </Button>
            <Button asChild variant="outline" className="justify-start">
              <Link to="/learners">
                <RiGroupLine aria-hidden="true" />
                {t('overviewDashboard.actions.learnersCount', {
                  count: learnersQuery.data?.total ?? 0,
                })}
              </Link>
            </Button>
            <Button asChild variant="outline" className="justify-start">
              <Link to="/media">
                <RiImage2Line aria-hidden="true" />
                {t('navigation.media')}
              </Link>
            </Button>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
