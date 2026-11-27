import {
  RiArrowRightSLine,
  RiChat3Line,
  RiCpuLine,
  RiListCheck3,
  RiNotification3Line,
  RiShieldUserLine,
} from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import type { AdminDashboardSummary } from './admin-ops.api';

export function OverviewOperationsPanel({ data }: { data: AdminDashboardSummary }) {
  const { t, i18n } = useTranslation('admin');
  const fmt = new Intl.NumberFormat(i18n.language);
  const { operations, kpis, period } = data;

  return (
    <Card className="lg:col-span-4">
      <CardHeader>
        <CardTitle>{t('overviewDashboard.operationsTitle')}</CardTitle>
        <CardDescription>{t('overviewDashboard.operationsHelp')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="divide-y rounded-lg border">
          <Link
            to={operations.jobsFailed24h > 0 ? '/jobs?status=FAILED' : '/jobs'}
            className="flex items-center justify-between gap-3 px-3 py-2.5 text-sm transition-colors hover:bg-muted/50"
          >
            <div className="flex min-w-0 items-center gap-2.5">
              <RiListCheck3 className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <div className="min-w-0">
                <p className="font-medium text-foreground">{t('overviewDashboard.opsRows.jobs')}</p>
                <p className="truncate text-xs text-muted-foreground tabular-nums">
                  {t('overviewDashboard.opsRows.jobsSummary', {
                    running: fmt.format(operations.jobsRunning),
                    succeeded: fmt.format(operations.jobsSucceeded24h),
                  })}
                </p>
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-1.5">
              {operations.jobsFailed24h > 0 && (
                <Badge variant="destructive" className="tabular-nums">
                  {t('overviewDashboard.opsRows.jobsFailedBadge', {
                    count: operations.jobsFailed24h,
                  })}
                </Badge>
              )}
              <RiArrowRightSLine className="size-4 text-muted-foreground" aria-hidden="true" />
            </div>
          </Link>

          <Link
            to="/settings"
            className="flex items-center justify-between gap-3 px-3 py-2.5 text-sm transition-colors hover:bg-muted/50"
          >
            <div className="flex min-w-0 items-center gap-2.5">
              <RiCpuLine className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <div className="min-w-0">
                <p className="font-medium text-foreground">{t('overviewDashboard.opsRows.ai')}</p>
                <p className="truncate text-xs text-muted-foreground tabular-nums">
                  {t('overviewDashboard.opsRows.aiSummary', {
                    healthy: fmt.format(operations.aiProvidersHealthy),
                    enabled: fmt.format(operations.aiProvidersEnabled),
                    calls: fmt.format(operations.aiInvocations24h),
                    latency: fmt.format(operations.aiAvgLatencyMs24h),
                  })}
                </p>
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-1.5">
              {operations.aiProvidersUnhealthy > 0 && (
                <Badge variant="destructive" className="tabular-nums">
                  {t('overviewDashboard.opsRows.aiOfflineBadge', {
                    count: operations.aiProvidersUnhealthy,
                  })}
                </Badge>
              )}
              <RiArrowRightSLine className="size-4 text-muted-foreground" aria-hidden="true" />
            </div>
          </Link>

          <Link
            to="/learners"
            className="flex items-center justify-between gap-3 px-3 py-2.5 text-sm transition-colors hover:bg-muted/50"
          >
            <div className="flex min-w-0 items-center gap-2.5">
              <RiShieldUserLine className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <div className="min-w-0">
                <p className="font-medium text-foreground">{t('overviewDashboard.opsRows.accounts')}</p>
                <p className="truncate text-xs text-muted-foreground tabular-nums">
                  {t('overviewDashboard.opsRows.accountsSummary', {
                    locked: fmt.format(operations.lockedAccounts),
                  })}
                </p>
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-1.5">
              {operations.pendingAccountSync > 0 && (
                <Badge
                  variant="outline"
                  className="border-[var(--warning)]/40 bg-[var(--warning)]/10 text-[var(--warning)] tabular-nums"
                >
                  {t('overviewDashboard.opsRows.syncPendingBadge', {
                    count: operations.pendingAccountSync,
                  })}
                </Badge>
              )}
              <RiArrowRightSLine className="size-4 text-muted-foreground" aria-hidden="true" />
            </div>
          </Link>

          <Link
            to="/notifications"
            className="flex items-center justify-between gap-3 px-3 py-2.5 text-sm transition-colors hover:bg-muted/50"
          >
            <div className="flex min-w-0 items-center gap-2.5">
              <RiNotification3Line className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              <div className="min-w-0">
                <p className="font-medium text-foreground">
                  {t('overviewDashboard.opsRows.notifications')}
                </p>
                <p className="truncate text-xs text-muted-foreground tabular-nums">
                  {t('overviewDashboard.opsRows.notificationsSummary', {
                    count: operations.scheduledNotifications,
                  })}
                </p>
              </div>
            </div>
            <RiArrowRightSLine className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          </Link>
        </div>

        <div className="space-y-2 border-t pt-3">
          <div className="flex items-center justify-between gap-2">
            <span className="inline-flex items-center gap-1.5 text-sm font-medium text-foreground">
              <RiChat3Line className="size-4 text-muted-foreground" aria-hidden="true" />
              {t('overviewDashboard.tutorSection.title')}
            </span>
            <span className="text-sm font-semibold text-foreground tabular-nums">
              {t('overviewDashboard.tutorSection.turnsInPeriod', {
                count: fmt.format(kpis.aiTutorTurns),
                days: period.days,
              })}
            </span>
          </div>
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground tabular-nums">
            <span>
              {t('overviewDashboard.tutorSection.turnsToday', {
                count: fmt.format(kpis.aiTutorTurnsToday),
              })}
            </span>
            <span>
              {t('overviewDashboard.tutorSection.activeLearners', {
                count: fmt.format(kpis.aiTutorActiveLearners),
              })}
            </span>
            <span>
              {t('overviewDashboard.tutorSection.conversations', {
                count: fmt.format(kpis.aiTutorTotalConversations),
              })}
            </span>
          </div>
          <p className="text-xs text-muted-foreground">
            {t('overviewDashboard.tutorSection.privacyNote')}
          </p>
        </div>
      </CardContent>
    </Card>
  );
}
