import {
  RiGiftLine,
  RiHistoryLine,
  RiMegaphoneLine,
  RiSettings3Line,
  RiShieldKeyholeLine,
  RiVipCrownLine,
} from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import type { ActivityCategory } from '@/pages/notifications/notifications.api';

type ActivityEntry = {
  id: string;
  category: ActivityCategory;
  action: string;
  learnerId: string | null;
  learnerEmail: string | null;
  actor: string | null;
  detail: string | null;
  amount: number | null;
  occurredAt: string;
  scheduledFor: string | null;
};

const categoryIcons: Record<ActivityCategory, typeof RiHistoryLine> = {
  MEMBERSHIP: RiVipCrownLine,
  ACCOUNT: RiShieldKeyholeLine,
  NOTIFICATION: RiMegaphoneLine,
  REWARD: RiGiftLine,
  OTHER: RiSettings3Line,
};

export function OverviewRecentActivity({
  items,
  isPending,
}: {
  items: ActivityEntry[];
  isPending: boolean;
}) {
  const { t, i18n } = useTranslation('admin');
  const dateFmt = new Intl.DateTimeFormat(i18n.language, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  const recent = items.slice(0, 8);

  return (
    <Card>
      <CardHeader className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <CardTitle>{t('overviewDashboard.recentActivity.title')}</CardTitle>
          <CardDescription>{t('overviewDashboard.recentActivity.help')}</CardDescription>
        </div>
        <Button asChild variant="outline" size="sm">
          <Link to="/notifications?tab=activity">
            {t('overviewDashboard.recentActivity.viewAll')}
          </Link>
        </Button>
      </CardHeader>
      <CardContent>
        {isPending && recent.length === 0 ? (
          <Skeleton className="h-44 w-full" />
        ) : recent.length === 0 ? (
          <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
            {t('overviewDashboard.recentActivity.empty')}
          </div>
        ) : (
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('notificationsCenter.columns.event')}</TableHead>
                  <TableHead>{t('notificationsCenter.columns.account')}</TableHead>
                  <TableHead className="text-right">{t('notificationsCenter.columns.time')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {recent.map((entry) => {
                  const Icon = categoryIcons[entry.category] ?? RiHistoryLine;
                  const detail =
                    entry.action === 'QUOTA_UPDATED' && entry.detail
                      ? t(`plansConsole.features.${entry.detail}`, { defaultValue: entry.detail })
                      : entry.detail;
                  return (
                    <TableRow key={entry.id}>
                      <TableCell className="whitespace-normal">
                        <div className="flex items-start gap-2">
                          <Icon
                            className="mt-0.5 size-4 shrink-0 text-muted-foreground"
                            aria-hidden="true"
                          />
                          <div className="min-w-0">
                            <p className="font-medium text-foreground">
                              {t(`notificationsCenter.activityActions.${entry.action}`, {
                                defaultValue: entry.action,
                                amount: entry.amount,
                              })}
                            </p>
                            {detail && (
                              <p className="max-w-2xl truncate text-xs text-muted-foreground">
                                {detail}
                              </p>
                            )}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>
                        {entry.learnerId ? (
                          <Link
                            className="text-sm underline underline-offset-2"
                            to={`/learners/${encodeURIComponent(entry.learnerId)}`}
                          >
                            {entry.learnerEmail ||
                              t('notificationsCenter.learnerLink', {
                                id: entry.learnerId.slice(-8),
                              })}
                          </Link>
                        ) : (
                          <span className="text-xs text-muted-foreground">
                            {entry.actor || '—'}
                          </span>
                        )}
                      </TableCell>
                      <TableCell className="text-right text-xs text-muted-foreground tabular-nums">
                        <time dateTime={entry.occurredAt}>
                          {dateFmt.format(new Date(entry.occurredAt))}
                        </time>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
