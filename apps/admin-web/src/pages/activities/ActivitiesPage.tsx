import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { RiArrowRightUpLine, RiBookOpenLine, RiMicLine, RiQuestionnaireLine, RiTranslate2 } from '@remixicon/react';
import { api } from '@/lib/api';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchLessons } from '@/pages/lessons/catalog.api';
import type { CatalogPage, Summary } from '@/pages/library/library.types';
import { fetchAdminAnalyticsSummary } from '@/pages/overview/admin-ops.api';

type PackSummary = {
  id: string;
  titleEn: string;
  titleVi: string;
  accessMode: string;
  hidden: boolean;
};

export function ActivitiesPage() {
  const { t } = useTranslation('admin');

  const analyticsQuery = useQuery({
    queryKey: ['admin-analytics-summary'],
    queryFn: ({ signal }) => fetchAdminAnalyticsSummary(signal),
  });
  const lessonsQuery = useQuery({
    queryKey: ['admin-lessons', 'activities'],
    queryFn: ({ signal }) => fetchLessons(new URLSearchParams({ page: '0', size: '12' }), signal),
  });
  const grammarQuery = useQuery({
    queryKey: ['admin-grammar', 'activities'],
    queryFn: ({ signal }) =>
      api<CatalogPage<Summary>>('/api/v1/admin/grammar/topics/page?page=0&size=5', { signal }),
  });
  const toeicQuery = useQuery({
    queryKey: ['admin-toeic', 'activities'],
    queryFn: ({ signal }) =>
      api<CatalogPage<Summary>>('/api/v1/admin/toeic/page?page=0&size=5', { signal }),
  });
  const packsQuery = useQuery({
    queryKey: ['admin-vocabulary-packs', 'activities'],
    queryFn: ({ signal }) => api<PackSummary[]>('/api/v1/admin/vocabulary/packs', { signal }),
  });

  const primaryError = lessonsQuery.error ?? analyticsQuery.error;

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{t('activitiesHub.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('activitiesHub.subtitle')}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button asChild variant="outline" size="sm">
            <Link to="/lessons/new">
              <RiBookOpenLine aria-hidden="true" />
              {t('navigation.createLesson')}
            </Link>
          </Button>
          <Button asChild size="sm">
            <Link to="/curriculum">
              <RiArrowRightUpLine aria-hidden="true" />
              {t('navigation.curriculum')}
            </Link>
          </Button>
        </div>
      </div>

      {primaryError ? (
        <CatalogError
          error={primaryError}
          retry={() => {
            void lessonsQuery.refetch();
            void analyticsQuery.refetch();
          }}
        />
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardDescription>{t('activitiesHub.cards.lessonActivities')}</CardDescription>
            <RiMicLine className="size-4 text-muted-foreground" aria-hidden="true" />
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold tabular-nums">
              {lessonsQuery.isPending ? '—' : lessonsQuery.data?.total ?? 0}
            </p>
            <p className="mt-1 text-xs text-muted-foreground tabular-nums">
              {t('activitiesHub.cards.lessons7d', {
                count: analyticsQuery.data?.lessonsCompleted7d ?? 0,
              })}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardDescription>{t('activitiesHub.cards.vocabularyPacks')}</CardDescription>
            <RiTranslate2 className="size-4 text-muted-foreground" aria-hidden="true" />
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold tabular-nums">
              {packsQuery.isPending ? '—' : packsQuery.data?.length ?? 0}
            </p>
            <p className="mt-1 text-xs text-muted-foreground tabular-nums">
              {t('activitiesHub.cards.reviews7d', {
                count: analyticsQuery.data?.vocabularyReviews7d ?? 0,
              })}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardDescription>{t('activitiesHub.cards.grammarTopics')}</CardDescription>
            <RiBookOpenLine className="size-4 text-muted-foreground" aria-hidden="true" />
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold tabular-nums">
              {grammarQuery.isPending ? '—' : grammarQuery.data?.total ?? 0}
            </p>
            <p className="mt-1 text-xs text-muted-foreground tabular-nums">
              {t('activitiesHub.cards.answers7d', {
                count: analyticsQuery.data?.grammarAnswers7d ?? 0,
              })}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardDescription>{t('activitiesHub.cards.toeicTests')}</CardDescription>
            <RiQuestionnaireLine className="size-4 text-muted-foreground" aria-hidden="true" />
          </CardHeader>
          <CardContent>
            <p className="text-2xl font-semibold tabular-nums">
              {toeicQuery.isPending ? '—' : toeicQuery.data?.total ?? 0}
            </p>
            <p className="mt-1 text-xs text-muted-foreground tabular-nums">
              {t('activitiesHub.cards.questions7d', {
                count: analyticsQuery.data?.toeicQuestions7d ?? 0,
              })}
            </p>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>{t('activitiesHub.coverageTitle')}</CardTitle>
          <CardDescription>{t('activitiesHub.coverageHelp')}</CardDescription>
        </CardHeader>
        <CardContent>
          {lessonsQuery.isPending ? (
            <Skeleton className="h-44 w-full" />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('activitiesHub.columns.lesson')}</TableHead>
                  <TableHead>{t('activitiesHub.columns.sourceType')}</TableHead>
                  <TableHead>{t('activitiesHub.columns.status')}</TableHead>
                  <TableHead>{t('activitiesHub.columns.activities')}</TableHead>
                  <TableHead className="text-right">{t('activitiesHub.columns.manage')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(lessonsQuery.data?.items ?? []).map((lesson) => (
                  <TableRow key={lesson.id}>
                    <TableCell className="font-medium">{lesson.title}</TableCell>
                    <TableCell>{lesson.sourceType}</TableCell>
                    <TableCell>
                      <Badge variant={lesson.status === 'PUBLISHED' ? 'default' : 'outline'}>
                        {lesson.status}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {(lesson.activities && lesson.activities.length > 0
                          ? lesson.activities
                          : ['WATCH', 'DICTATION', 'SHADOWING']
                        ).map((act) => (
                          <Badge key={act} variant="secondary" className="text-xs">
                            {act}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button asChild size="sm" variant="outline">
                        <Link to={`/lessons/${encodeURIComponent(lesson.id)}`}>
                          {t('activitiesHub.openLesson')}
                        </Link>
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
