import { LearnerWeaknesses } from './LearnerWeaknesses';
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchLearnerStudyProfile } from '@/pages/overview/admin-ops.api';

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
    lessons: pt.lessonCount,
    vocab: pt.vocabularyReviewCount,
  }));

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('learnerProgress.inspectorTitle')}</CardTitle>
        <CardDescription>{t('learnerProgress.inspectorHelp')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        {query.error ? (
          <CatalogError error={query.error} retry={() => void query.refetch()} />
        ) : query.isPending ? (
          <Skeleton className="h-44 w-full" />
        ) : !data?.hasHistory ? (
          <p className="text-sm text-muted-foreground">{t('learnerProgress.noHistoryHelp')}</p>
        ) : (
          <>
            {/* Today / 7d / 30d Study Windows */}
            <div className="grid gap-3 sm:grid-cols-3">
              {(
                [
                  ['todayWindow', data.today],
                  ['weekWindow', data.week],
                  ['monthWindow', data.month],
                ] as const
              ).map(([labelKey, win]) => (
                <div key={labelKey} className="rounded-xl border bg-muted/20 p-3">
                  <div className="flex items-center justify-between gap-2">
                    <p className="text-xs text-muted-foreground">
                      {t(`learnerProgress.${labelKey}`)}
                    </p>
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

            {/* Practice Breakdown Mini-KPIs */}
            <div className="space-y-2">
              <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                {t('learnerProgress.practiceBreakdown')}
              </h4>
              <div className="grid gap-2.5 sm:grid-cols-2 xl:grid-cols-4">
                <div className="rounded-xl border bg-card p-3">
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <RiBookOpenLine className="size-3.5 text-primary" aria-hidden="true" />
                    <span>{t('learnerProgress.lessonsDone')}</span>
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
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <RiTranslate2 className="size-3.5 text-primary" aria-hidden="true" />
                    <span>{t('learnerProgress.vocabMastered')}</span>
                  </div>
                  <p className="mt-1 text-lg font-semibold tabular-nums">
                    {data.breakdown.vocabCardsMastered} / {data.breakdown.vocabCardsTotal}
                  </p>
                </div>

                <div className="rounded-xl border bg-card p-3">
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <RiSparklingLine className="size-3.5 text-primary" aria-hidden="true" />
                    <span>{t('learnerProgress.grammarAccuracy')}</span>
                  </div>
                  <p className="mt-1 text-lg font-semibold tabular-nums">
                    {data.breakdown.grammarAccuracyPct}%
                  </p>
                  <p className="text-[11px] text-muted-foreground tabular-nums">
                    {t('learnerProgress.attemptsCount', {
                      count: data.breakdown.grammarAttempts,
                    })}
                  </p>
                </div>

                <div className="rounded-xl border bg-card p-3">
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <RiFileList3Line className="size-3.5 text-primary" aria-hidden="true" />
                    <span>{t('learnerProgress.toeicBest')}</span>
                  </div>
                  <p className="mt-1 text-lg font-semibold tabular-nums">
                    {data.breakdown.bestToeicScore != null
                      ? data.breakdown.bestToeicScore
                      : t('learnerProgress.noToeicYet')}
                  </p>
                  <p className="text-[11px] text-muted-foreground tabular-nums">
                    {t('learnerProgress.attemptsCount', {
                      count: data.breakdown.toeicAttempts,
                    })}
                  </p>
                </div>
              </div>
            </div>

            {/* 14-Day Study Activity BarChart */}
            {chartData.length > 0 ? (
              <div className="space-y-2">
                <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                  {t('learnerProgress.dailyChartTitle')}
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

            {/* Skill Mastery */}
            {data.skills.length > 0 ? (
              <div className="space-y-2.5">
                <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                  {t('learnerProgress.skillsTitle')}
                </h4>
                <div className="space-y-2">
                  {data.skills.map((skill) => (
                    <div key={skill.skillCode} className="space-y-1">
                      <div className="flex items-center justify-between text-xs">
                        <span className="font-medium">
                          {t(`learnerProgress.skills.${skill.skillCode}`, {
                            defaultValue: skill.skillCode,
                          })}
                        </span>
                        <span className="text-muted-foreground tabular-nums">
                          {Number(skill.score).toFixed(1)}%
                        </span>
                      </div>
                      <Progress value={Math.min(100, Math.max(0, Number(skill.score)))} />
                    </div>
                  ))}
                </div>
              </div>
            ) : null}

            {/* Enrolled Learning Paths */}
            <div className="space-y-2.5">
              <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                {t('learnerProgress.enrolledPathsTitle')}
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

            <div className="space-y-2">
              <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                {t('learnerProgress.weaknessesTitle')}
              </h4>
              <LearnerWeaknesses items={data.weaknesses} />
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
