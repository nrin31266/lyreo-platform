import {
  RiAlertLine,
  RiArrowDownLine,
  RiArrowUpLine,
  RiCheckboxCircleLine,
  RiErrorWarningLine,
  RiPulseLine,
  RiSubtractLine,
  RiTimeLine,
  RiUserHeartLine,
  RiVipCrownLine,
} from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import type { AdminDashboardSummary } from './admin-ops.api';

type SparklineProps = {
  values: number[];
  strokeColor: string;
};

function MiniSparkline({ values, strokeColor }: SparklineProps) {
  if (values.length < 2) {
    return null;
  }
  const width = 88;
  const height = 28;
  const pad = 4;
  const max = Math.max(...values, 1);
  const points = values.map((val, idx) => {
    const x = pad + (idx / (values.length - 1)) * (width - pad * 2);
    const y = height - pad - (val / max) * (height - pad * 2);
    return { x, y };
  });
  const path = points
    .map((pt, idx) => `${idx === 0 ? 'M' : 'L'} ${pt.x.toFixed(1)} ${pt.y.toFixed(1)}`)
    .join(' ');
  const last = points[points.length - 1];

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className="shrink-0 overflow-visible"
      aria-hidden="true"
    >
      <path
        d={path}
        fill="none"
        stroke="currentColor"
        strokeWidth={1.5}
        strokeLinecap="round"
        strokeLinejoin="round"
        className="text-muted-foreground/45"
      />
      <circle
        cx={last.x}
        cy={last.y}
        r={3}
        fill={strokeColor}
        stroke="var(--card)"
        strokeWidth={1.5}
      />
    </svg>
  );
}

function DeltaPill({
  current,
  previous,
  days,
}: {
  current: number;
  previous: number;
  days: number;
}) {
  const { t } = useTranslation('admin');
  if (previous === 0 && current === 0) {
    return (
      <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
        <RiSubtractLine className="size-3.5" aria-hidden="true" />
        {t('overviewDashboard.delta.flat')}
      </span>
    );
  }
  const pct =
    previous === 0
      ? 100
      : Math.round(((current - previous) / previous) * 100);
  const isUp = pct > 0;
  const isDown = pct < 0;
  const Icon = isUp ? RiArrowUpLine : isDown ? RiArrowDownLine : RiSubtractLine;
  const toneClass = isUp
    ? 'text-[var(--success)]'
    : isDown
      ? 'text-[var(--warning)]'
      : 'text-muted-foreground';

  return (
    <span className={`inline-flex items-center gap-1 text-xs font-medium ${toneClass}`}>
      <Icon className="size-3.5" aria-hidden="true" />
      <span className="tabular-nums">
        {pct > 0 ? `+${pct}%` : `${pct}%`}
      </span>
      <span className="font-normal text-muted-foreground">
        {t('overviewDashboard.delta.vsPrevious', { days })}
      </span>
    </span>
  );
}

export function formatStudyDuration(
  seconds: number,
  t: (key: string, options?: Record<string, unknown>) => string,
) {
  const totalMinutes = Math.max(0, Math.round(seconds / 60));
  if (totalMinutes < 60) {
    return t('overviewDashboard.minutesValue', { count: totalMinutes });
  }
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (minutes === 0) {
    return t('overviewDashboard.hoursShort', { hours });
  }
  return t('overviewDashboard.hoursMinutes', { hours, minutes });
}

export function OverviewKpiRow({ data }: { data: AdminDashboardSummary }) {
  const { t, i18n } = useTranslation('admin');
  const fmt = new Intl.NumberFormat(i18n.language);
  const { kpis, operations, dailySeries, period } = data;

  const plusShare =
    kpis.totalLearners > 0
      ? Math.min(100, Math.round((kpis.activePlusLearners * 100) / kpis.totalLearners))
      : 0;

  const hasCritical = operations.jobsFailed24h > 0 || operations.aiProvidersUnhealthy > 0;
  const hasAttention = !hasCritical && (operations.aiErrors24h > 0 || operations.pendingAccountSync > 0);
  const healthState = hasCritical ? 'critical' : hasAttention ? 'attention' : 'healthy';

  const HealthIcon =
    healthState === 'critical'
      ? RiErrorWarningLine
      : healthState === 'attention'
        ? RiAlertLine
        : RiCheckboxCircleLine;
  const healthBadgeClass =
    healthState === 'critical'
      ? 'border-destructive/30 bg-destructive/10 text-destructive'
      : healthState === 'attention'
        ? 'border-[var(--warning)]/35 bg-[var(--warning)]/10 text-[var(--warning)]'
        : 'border-[var(--success)]/35 bg-[var(--success)]/10 text-[var(--success)]';

  const learnerSpark = dailySeries.map((d) => d.activeLearners);
  const practiceSpark = dailySeries.map(
    (d) =>
      d.lessonCount +
      d.vocabularyReviewCount +
      d.grammarAnswerCount +
      d.toeicQuestionCount,
  );

  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Card>
        <CardContent className="space-y-3 pt-5">
          <div className="flex items-start justify-between gap-2">
            <span className="text-sm font-medium text-muted-foreground">
              {t('overviewDashboard.kpis.activeLearners')}
            </span>
            <RiUserHeartLine className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          </div>
          <div className="flex items-end justify-between gap-3">
            <div className="min-w-0">
              <p className="text-3xl font-semibold tracking-tight text-foreground">
                {fmt.format(kpis.activeLearners)}
              </p>
              <div className="mt-1">
                <DeltaPill
                  current={kpis.activeLearners}
                  previous={kpis.previousActiveLearners}
                  days={period.days}
                />
              </div>
            </div>
            <MiniSparkline values={learnerSpark} strokeColor="var(--chart-1)" />
          </div>
          <p className="border-t pt-2.5 text-xs text-muted-foreground">
            {t('overviewDashboard.kpis.activeTodaySub', {
              dau: fmt.format(kpis.activeLearnersToday),
              total: fmt.format(kpis.totalLearners),
            })}
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-3 pt-5">
          <div className="flex items-start justify-between gap-2">
            <span className="text-sm font-medium text-muted-foreground">
              {t('overviewDashboard.kpis.plusMembers')}
            </span>
            <RiVipCrownLine className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          </div>
          <div className="space-y-2.5">
            <div className="flex items-baseline justify-between gap-2">
              <p className="text-3xl font-semibold tracking-tight text-foreground">
                {fmt.format(kpis.activePlusLearners)}
              </p>
              <span className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground tabular-nums">
                {plusShare}%
              </span>
            </div>
            <div
              className="h-2 w-full overflow-hidden rounded-full bg-primary/15"
              role="progressbar"
              aria-valuenow={plusShare}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-label={t('overviewDashboard.kpis.plusMembers')}
            >
              <div
                className="h-full rounded-full bg-primary transition-[width] duration-300"
                style={{ width: `${plusShare}%` }}
              />
            </div>
          </div>
          <p className="border-t pt-2.5 text-xs text-muted-foreground">
            {t('overviewDashboard.kpis.plusSub', {
              share: plusShare,
              plusNew: fmt.format(kpis.plusStartedInPeriod),
              newUsers: fmt.format(kpis.newLearners),
            })}
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-3 pt-5">
          <div className="flex items-start justify-between gap-2">
            <span className="text-sm font-medium text-muted-foreground">
              {t('overviewDashboard.kpis.studyVolume')}
            </span>
            <RiTimeLine className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          </div>
          <div className="flex items-end justify-between gap-3">
            <div className="min-w-0">
              <p className="text-3xl font-semibold tracking-tight text-foreground">
                {fmt.format(kpis.practiceTotal)}
              </p>
              <div className="mt-1">
                <DeltaPill
                  current={kpis.practiceTotal}
                  previous={kpis.previousPracticeTotal}
                  days={period.days}
                />
              </div>
            </div>
            <MiniSparkline values={practiceSpark} strokeColor="var(--chart-2)" />
          </div>
          <p className="border-t pt-2.5 text-xs text-muted-foreground">
            {t('overviewDashboard.kpis.studyVolumeSub', {
              time: formatStudyDuration(kpis.studySeconds, t),
              lessons: fmt.format(kpis.lessonsCompleted),
              vocab: fmt.format(kpis.vocabularyReviews),
            })}
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-3 pt-5">
          <div className="flex items-start justify-between gap-2">
            <span className="text-sm font-medium text-muted-foreground">
              {t('overviewDashboard.kpis.systemHealth')}
            </span>
            <RiPulseLine className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          </div>
          <div className="space-y-2">
            <Badge
              variant="outline"
              className={`inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-semibold ${healthBadgeClass}`}
            >
              <HealthIcon className="size-3.5" aria-hidden="true" />
              {t(`overviewDashboard.healthStatus.${healthState}`)}
            </Badge>
            <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground tabular-nums">
              <span>{t('overviewDashboard.opsRows.jobsFailedBadge', { count: operations.jobsFailed24h })}</span>
              <span>{t('overviewDashboard.opsRows.aiErrorBadge', { count: operations.aiErrors24h })}</span>
            </div>
          </div>
          <p className="border-t pt-2.5 text-xs text-muted-foreground">
            {t('overviewDashboard.kpis.systemHealthSub', {
              jobsRunning: fmt.format(operations.jobsRunning),
              aiCalls: fmt.format(operations.aiInvocations24h),
            })}
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
