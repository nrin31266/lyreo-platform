import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  RiAwardLine,
  RiBookOpenLine,
  RiFileList3Line,
  RiMapPinLine,
  RiSparklingLine,
  RiTranslate2,
} from '@remixicon/react';
import { AuthenticatedImage } from '@/components/AuthenticatedImage';
import { AchievementTierBadge, CefrLevelBadge } from '@/components/ContentBadges';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchLearnerStudyProfile } from '@/pages/overview/admin-ops.api';
import { LearnerWeaknesses } from './LearnerWeaknesses';
import { MetricHelp } from './MetricHelp';
import { LearnerSkillScores } from './LearnerSkillScores';

export function LearnerStudyProgress({ learnerId }: { learnerId: string }) {
  const { t } = useTranslation('admin');

  const query = useQuery({
    queryKey: ['admin-learner-study-profile', learnerId],
    queryFn: ({ signal }) => fetchLearnerStudyProfile(learnerId, signal),
  });

  const data = query.data;
  const chartData = (data?.dailySeries ?? []).map((pt) => ({
    date: pt.date.slice(5),
    minutes: Math.round(pt.studySeconds / 60),
  }));

  return (
    <Card size="sm" className="rounded-xl border shadow-none ring-0">
      <CardHeader>
        <CardTitle>{t('learnerProgress.inspectorTitle')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {query.error ? (
          <CatalogError error={query.error} retry={() => void query.refetch()} />
        ) : query.isPending ? (
          <Skeleton className="h-44 w-full" />
        ) : !data?.hasHistory ? (
          <>
            <LearnerSkillScores scores={[]} />
            <p className="text-sm text-muted-foreground">{t('learnerProgress.noHistoryHelp')}</p>
          </>
        ) : (
          <>
            <div className="grid gap-2 sm:grid-cols-3">
              {(
                [
                  ['todayWindow', data.today],
                  ['weekWindow', data.week],
                  ['monthWindow', data.month],
                ] as const
              ).map(([labelKey, win]) => (
                <div key={labelKey} className="rounded-lg border bg-muted/20 p-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <div className="inline-flex flex-wrap items-center text-xs text-muted-foreground">
                      {t(`learnerProgress.${labelKey}`)}
                      <MetricHelp label={t(`learnerProgress.${labelKey}`)} calculation="window" />
                    </div>
                    <span className="text-[11px] font-medium text-muted-foreground tabular-nums">
                      {t('learnerProgress.activeDaysCount', { days: win.activeDays })}
                    </span>
                  </div>
                  <p className="mt-1 text-base font-semibold tabular-nums">
                    {Math.round(win.studySeconds / 60)}m ·{' '}
                    {t('learnerProgress.windowSummary', {
                      lessons: win.lessonCount,
                      vocab: win.vocabularyReviewCount,
                    })}
                  </p>
                </div>
              ))}
            </div>

            <div className="space-y-2">
              <h4 className="flex items-center gap-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                {t('learnerProgress.weaknessesTitle')}
                <MetricHelp label={t('learnerProgress.weaknessesTitle')} calculation="weaknesses" />
              </h4>
              <LearnerWeaknesses items={data.weaknesses} />
            </div>
            <LearnerSkillScores scores={data.skills} />
            <div className="space-y-2">
              <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                {t('learnerProgress.practiceBreakdown')}
              </h4>
              <div className="grid gap-2.5 sm:grid-cols-2 2xl:grid-cols-4">
                <div className="rounded-xl border bg-card p-3">
                  <div className="flex items-start gap-1 text-xs text-muted-foreground [&>span]:pt-1 [&>svg]:mt-1 [&>svg]:shrink-0">
                    <RiBookOpenLine className="size-3.5 text-primary" aria-hidden="true" />
                    <span>{t('learnerProgress.lessonsDone')}</span>
                    <MetricHelp label={t('learnerProgress.lessonsDone')} calculation="lessons" />
                  </div>
                  <p className="mt-1 text-lg font-semibold tabular-nums">
                    {data.breakdown.lessonsCompleted}
                  </p>
                  <p className="text-[11px] text-muted-foreground tabular-nums">
                    {t('learnerProgress.lessonsInProgress', {
                      count: data.breakdown.lessonsInProgress,
                    })}
                  </p>
                </div>

                <div className="rounded-xl border bg-card p-3">
                  <div className="flex items-start gap-1 text-xs text-muted-foreground [&>span]:pt-1 [&>svg]:mt-1 [&>svg]:shrink-0">
                    <RiTranslate2 className="size-3.5 text-primary" aria-hidden="true" />
                    <span>{t('learnerProgress.vocabMastered')}</span>
                    <MetricHelp label={t('learnerProgress.vocabMastered')} calculation="mastered" />
                  </div>
                  <p className="mt-1 text-lg font-semibold tabular-nums">
                    {data.breakdown.vocabCardsMastered} / {data.breakdown.vocabCardsTotal}
                  </p>
                </div>

                <div className="rounded-xl border bg-card p-3">
                  <div className="flex items-start gap-1 text-xs text-muted-foreground [&>span]:pt-1 [&>svg]:mt-1 [&>svg]:shrink-0">
                    <RiSparklingLine className="size-3.5 text-primary" aria-hidden="true" />
                    <span>{t('learnerProgress.grammarAccuracy')}</span>
                    <MetricHelp label={t('learnerProgress.grammarAccuracy')} calculation="grammarAccuracy" />
                  </div>
                  <p className="mt-1 text-lg font-semibold tabular-nums">
                    {data.breakdown.grammarAttempts > 0 ? `${data.breakdown.grammarAccuracyPct}%` : '—'}
                  </p>
                  <p className="text-[11px] text-muted-foreground tabular-nums">
                    {t('learnerProgress.grammarAnswersCount', {
                      count: data.breakdown.grammarAttempts,
                    })}
                  </p>
                </div>

                <div className="rounded-xl border bg-card p-3">
                  <div className="flex items-start gap-1 text-xs text-muted-foreground [&>span]:pt-1 [&>svg]:mt-1 [&>svg]:shrink-0">
                    <RiFileList3Line className="size-3.5 text-primary" aria-hidden="true" />
                    <span>{t('learnerProgress.toeicSubmissions')}</span>
                    <MetricHelp label={t('learnerProgress.toeicSubmissions')} calculation="toeicBest" />
                  </div>
                  <p className="mt-1 text-lg font-semibold tabular-nums">{data.breakdown.toeicAttempts}</p>
                  {data.breakdown.bestToeicScore != null && (
                    <p className="text-[11px] text-muted-foreground tabular-nums">
                      {t('learnerProgress.bestScaledToeic', { score: data.breakdown.bestToeicScore })}
                    </p>
                  )}
                </div>
              </div>
            </div>

            {/* 14-Day Study Activity BarChart */}
            {chartData.length > 0 ? (
              <div className="space-y-2">
                <h4 className="flex items-center gap-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                  {t('learnerProgress.dailyChartTitle')}
                  <MetricHelp label={t('learnerProgress.dailyChartTitle')} calculation="dailyMinutes" />
                </h4>
                <div className="h-44 w-full rounded-xl border bg-muted/10 p-3">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={chartData}>
                      <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
                      <XAxis dataKey="date" stroke="var(--muted-foreground)" fontSize={11} />
                      <YAxis stroke="var(--muted-foreground)" fontSize={11} width={32} />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: 'var(--popover)',
                          borderColor: 'var(--border)',
                          borderRadius: '8px',
                          fontSize: '12px',
                        }}
                      />
                      <Bar
                        dataKey="minutes"
                        name={t('learnerProgress.minutesLabel')}
                        fill="var(--chart-1)"
                        radius={[4, 4, 0, 0]}
                      />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </div>
            ) : null}

            {/* Enrolled Learning Paths */}
            <div className="space-y-2.5">
              <h4 className="flex items-center gap-1 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                {t('learnerProgress.enrolledPathsTitle')}
                <MetricHelp label={t('learnerProgress.enrolledPathsTitle')} calculation="paths" />
              </h4>
              {data.enrolledPaths.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  {t('learnerProgress.noEnrolledPaths')}
                </p>
              ) : (
                <div className="grid gap-2.5 sm:grid-cols-2">
                  {data.enrolledPaths.map((path) => {
                    const pct =
                      path.totalItems > 0
                        ? Math.round((path.completedItems / path.totalItems) * 100)
                        : 0;
                    return (
                      <div
                        key={path.pathId}
                        className="space-y-2 rounded-xl border bg-muted/15 p-3"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="flex min-w-0 items-center gap-1.5">
                            <RiMapPinLine
                              className="size-3.5 shrink-0 text-primary"
                              aria-hidden="true"
                            />
                            <span className="truncate text-xs font-semibold">{path.pathName}</span>
                          </div>
                          <CefrLevelBadge level={path.level} className="h-5 px-1.5 text-[10px]" />
                        </div>
                        <Progress value={pct} />
                        <div className="flex items-center justify-between text-[11px] text-muted-foreground tabular-nums">
                          <span>
                            {t('learnerProgress.pathProgressCount', {
                              completed: path.completedItems,
                              total: path.totalItems,
                            })}
                          </span>
                          <span>{pct}%</span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* Unlocked Achievement Badges Showcase (no box/card wrapper around emblems) */}
            <div className="space-y-3">
              <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                {t('learnerProgress.unlockedBadgesTitle')}
              </h4>
              {data.unlockedAchievements.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  {t('learnerProgress.noBadgesUnlocked')}
                </p>
              ) : (
                <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4">
                  {data.unlockedAchievements.map((badge) => {
                    const badgeUrl = badge.badgeAssetId
                      ? `/api/v1/admin/content-media/${encodeURIComponent(badge.badgeAssetId)}/file`
                      : null;
                    return (
                      <div
                        key={badge.achievementId}
                        className="flex flex-col items-center gap-1.5 text-center"
                      >
                        <AuthenticatedImage
                          src={badgeUrl}
                          alt={badge.title}
                          className="size-20 object-contain drop-shadow-sm"
                          fallback={
                            <RiAwardLine
                              className="size-12 text-muted-foreground"
                              aria-hidden="true"
                            />
                          }
                        />
                        <p className="line-clamp-2 text-xs font-medium leading-tight">
                          {badge.title}
                        </p>
                        <AchievementTierBadge tier={badge.badgeTier} className="h-4 px-1.5 text-[10px]" />
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

          </>
        )}
      </CardContent>
    </Card>
  );
}
