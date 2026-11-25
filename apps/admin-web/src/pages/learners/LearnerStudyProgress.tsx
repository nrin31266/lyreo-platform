import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchLearnerAnalytics } from '@/pages/overview/admin-ops.api';

export function LearnerStudyProgress({ learnerId }: { learnerId: string }) {
  const { t } = useTranslation('admin');

  const query = useQuery({
    queryKey: ['admin-learner-analytics', learnerId],
    queryFn: ({ signal }) => fetchLearnerAnalytics(learnerId, signal),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('learnerProgress.inspectorTitle')}</CardTitle>
        <CardDescription>{t('learnerProgress.inspectorHelp')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {query.error ? (
          <CatalogError error={query.error} retry={() => void query.refetch()} />
        ) : query.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : !query.data?.hasHistory ? (
          <p className="text-sm text-muted-foreground">{t('learnerProgress.noHistoryHelp')}</p>
        ) : (
          <>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="rounded-xl border bg-muted/20 p-3">
                <p className="text-xs text-muted-foreground">{t('learnerProgress.todayWindow')}</p>
                <p className="mt-1 text-base font-semibold tabular-nums">
                  {Math.round(query.data.today.studySeconds / 60)}m ·{' '}
                  {t('learnerProgress.windowSummary', {
                    lessons: query.data.today.lessonCount,
                    vocab: query.data.today.vocabularyReviewCount,
                  })}
                </p>
              </div>
              <div className="rounded-xl border bg-muted/20 p-3">
                <p className="text-xs text-muted-foreground">{t('learnerProgress.weekWindow')}</p>
                <p className="mt-1 text-base font-semibold tabular-nums">
                  {Math.round(query.data.week.studySeconds / 60)}m ·{' '}
                  {t('learnerProgress.windowSummary', {
                    lessons: query.data.week.lessonCount,
                    vocab: query.data.week.vocabularyReviewCount,
                  })}
                </p>
              </div>
            </div>

            {query.data.skills.length > 0 ? (
              <div className="space-y-2">
                {query.data.skills.map((skill) => (
                  <div key={skill.skillCode} className="space-y-1">
                    <div className="flex items-center justify-between text-xs">
                      <span className="font-medium">{skill.skillCode}</span>
                      <span className="text-muted-foreground tabular-nums">
                        {Number(skill.score).toFixed(1)}%
                      </span>
                    </div>
                    <Progress value={Math.min(100, Math.max(0, Number(skill.score)))} />
                  </div>
                ))}
              </div>
            ) : null}

            {query.data.weaknesses.length > 0 ? (
              <div className="flex flex-wrap gap-2">
                {query.data.weaknesses.map((item) => (
                  <Badge key={`${item.weaknessType}:${item.weaknessKey}`} variant="outline">
                    {item.weaknessKey} · {Number(item.severity).toFixed(0)}%
                  </Badge>
                ))}
              </div>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}
