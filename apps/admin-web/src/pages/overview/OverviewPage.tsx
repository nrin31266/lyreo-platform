import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  RiAddLine,
  RiListCheck3,
  RiNotification3Line,
  RiRefreshLine,
  RiStackLine,
} from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchAdminActivity } from '@/pages/notifications/notifications.api';
import { fetchAdminDashboard, type DashboardPeriodDays } from './admin-ops.api';
import { OverviewActivityChart } from './OverviewActivityChart';
import { OverviewContentPanel } from './OverviewContentPanel';
import { OverviewKpiRow } from './OverviewKpiRow';
import { OverviewOperationsPanel } from './OverviewOperationsPanel';
import { OverviewRecentActivity } from './OverviewRecentActivity';

const PERIOD_OPTIONS: DashboardPeriodDays[] = [7, 14, 30];

export function OverviewPage() {
  const { t, i18n } = useTranslation('admin');
  const [days, setDays] = useState<DashboardPeriodDays>(14);

  const dashboard = useQuery({
    queryKey: ['admin-dashboard', days],
    queryFn: ({ signal }) => fetchAdminDashboard(days, signal),
    placeholderData: keepPreviousData,
  });

  const activity = useQuery({
    queryKey: ['admin-dashboard-activity'],
    queryFn: ({ signal }) => fetchAdminActivity('ALL', 0, signal),
  });

  const timeFmt = new Intl.DateTimeFormat(i18n.language, {
    hour: '2-digit',
    minute: '2-digit',
  });
  const isRefetching = Boolean(dashboard.data && (dashboard.isFetching || dashboard.isPlaceholderData));

  return (
    <section className="space-y-6">
      <header className="space-y-4">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-foreground">
              {t('overviewDashboard.title')}
            </h1>
            <p className="mt-1 text-sm text-muted-foreground">
              {t('overviewDashboard.subtitle')}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {dashboard.dataUpdatedAt > 0 && (
              <span className="text-xs text-muted-foreground tabular-nums">
                {t('overviewDashboard.updatedAt', {
                  time: timeFmt.format(new Date(dashboard.dataUpdatedAt)),
                })}
              </span>
            )}
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={dashboard.isFetching}
              onClick={() => {
                void dashboard.refetch();
                void activity.refetch();
              }}
            >
              <RiRefreshLine
                className={dashboard.isFetching ? 'size-4 animate-spin' : 'size-4'}
                data-icon="inline-start"
                aria-hidden="true"
              />
              {t('overviewDashboard.refresh')}
            </Button>
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-y py-2.5">
          <div
            className="inline-flex items-center rounded-lg border bg-muted/40 p-0.5"
            role="group"
            aria-label={t('overviewDashboard.dailyActivityTitle')}
          >
            {PERIOD_OPTIONS.map((option) => (
              <Button
                key={option}
                type="button"
                size="sm"
                variant={days === option ? 'default' : 'ghost'}
                className="h-7 px-3 text-xs"
                aria-pressed={days === option}
                onClick={() => setDays(option)}
              >
                {t(`overviewDashboard.periods.${option}d`)}
              </Button>
            ))}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button asChild size="sm">
              <Link to="/lessons/new">
                <RiAddLine className="size-3.5" data-icon="inline-start" aria-hidden="true" />
                {t('overviewDashboard.actions.newLesson')}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link to="/curriculum">
                <RiStackLine className="size-3.5" data-icon="inline-start" aria-hidden="true" />
                {t('overviewDashboard.actions.curriculum')}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link to="/notifications">
                <RiNotification3Line className="size-3.5" data-icon="inline-start" aria-hidden="true" />
                {t('overviewDashboard.actions.notifications')}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link to="/jobs">
                <RiListCheck3 className="size-3.5" data-icon="inline-start" aria-hidden="true" />
                {t('overviewDashboard.actions.allJobs')}
              </Link>
            </Button>
          </div>
        </div>
      </header>

      <CatalogError
        error={dashboard.error || activity.error}
        retry={() => {
          void dashboard.refetch();
          void activity.refetch();
        }}
      />

      {dashboard.isPending && !dashboard.data ? (
        <div className="space-y-6">
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            {Array.from({ length: 4 }).map((_, idx) => (
              <Skeleton key={idx} className="h-36 rounded-xl" />
            ))}
          </div>
          <div className="grid gap-4 lg:grid-cols-12">
            <Skeleton className="h-96 rounded-xl lg:col-span-8" />
            <Skeleton className="h-96 rounded-xl lg:col-span-4" />
          </div>
          <div className="grid gap-4 lg:grid-cols-12">
            <Skeleton className="h-80 rounded-xl lg:col-span-6" />
            <Skeleton className="h-80 rounded-xl lg:col-span-6" />
          </div>
        </div>
      ) : dashboard.data ? (
        <div
          className={`space-y-6 transition-opacity duration-200 ${isRefetching ? 'opacity-60' : 'opacity-100'}`}
        >
          <OverviewKpiRow data={dashboard.data} />
          <div className="grid gap-4 lg:grid-cols-12">
            <OverviewActivityChart data={dashboard.data} />
            <OverviewOperationsPanel data={dashboard.data} />
          </div>
          <OverviewContentPanel data={dashboard.data} />
          <OverviewRecentActivity
            items={activity.data?.items ?? []}
            isPending={activity.isPending}
          />
        </div>
      ) : null}
    </section>
  );
}
