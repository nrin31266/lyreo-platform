import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { RiGiftLine, RiHistoryLine, RiMegaphoneLine, RiSettings3Line, RiShieldKeyholeLine, RiVipCrownLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { AdminPagination } from '@/components/AdminPagination';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { activityCategories, fetchAdminActivity, type ActivityCategory } from './notifications.api';

const icons = {
  MEMBERSHIP: RiVipCrownLine,
  ACCOUNT: RiShieldKeyholeLine,
  NOTIFICATION: RiMegaphoneLine,
  REWARD: RiGiftLine,
  OTHER: RiSettings3Line,
};

export const activityKey = ['admin-activity'] as const;

export function NotificationActivity({ active }: { active: boolean }) {
  const { t, i18n } = useTranslation('admin');
  const [category, setCategory] = useState<ActivityCategory | 'ALL'>('ALL');
  const [page, setPage] = useState(0);
  const audit = useQuery({
    queryKey: [...activityKey, category, page],
    queryFn: ({ signal }) => fetchAdminActivity(category, page, signal),
    enabled: active,
  });
  const date = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Label htmlFor="activity-category">{t('notificationsCenter.fields.category')}</Label>
        <Select value={category} onValueChange={value => { setCategory(value as typeof category); setPage(0); }}>
          <SelectTrigger id="activity-category"><SelectValue /></SelectTrigger>
          <SelectContent>
            {(['ALL', ...activityCategories] as const).map(value =>
              <SelectItem key={value} value={value}>{t(`notificationsCenter.activityCategories.${value}`)}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      <CatalogError error={audit.error} retry={() => void audit.refetch()} />
      {audit.isPending ? <Skeleton className="h-36 w-full" /> : audit.data?.items.length ? (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('notificationsCenter.columns.event')}</TableHead>
                <TableHead>{t('notificationsCenter.columns.account')}</TableHead>
                <TableHead>{t('notificationsCenter.columns.time')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {audit.data.items.map(entry => {
                const Icon = icons[entry.category] ?? RiHistoryLine;
                const detail = entry.action === 'QUOTA_UPDATED' && entry.detail
                  ? t(`plansConsole.features.${entry.detail}`, { defaultValue: entry.detail })
                  : entry.detail;
                return (
                  <TableRow key={entry.id}>
                    <TableCell className="whitespace-normal">
                      <div className="flex items-start gap-2">
                        <Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                        <div className="min-w-0">
                          <p className="font-medium">{t(`notificationsCenter.activityActions.${entry.action}`, {
                            defaultValue: entry.action, amount: entry.amount,
                          })}</p>
                          {detail && <p className="max-w-xl wrap-anywhere text-xs text-muted-foreground">{detail}</p>}
                          {entry.scheduledFor && <p className="text-xs text-muted-foreground">
                            {t('notificationsCenter.scheduledFor', { time: date.format(new Date(entry.scheduledFor)) })}
                          </p>}
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>{entry.learnerId ? (
                      <Link className="underline underline-offset-2" to={`/learners/${encodeURIComponent(entry.learnerId)}`}>
                        {entry.learnerEmail || t('notificationsCenter.learnerLink', { id: entry.learnerId.slice(-8) })}
                      </Link>
                    ) : '—'}</TableCell>
                    <TableCell className="text-xs tabular-nums text-muted-foreground">
                      <time dateTime={entry.occurredAt}>{date.format(new Date(entry.occurredAt))}</time>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      ) : audit.isSuccess && <p className="rounded-lg border p-4 text-sm text-muted-foreground">{t('notificationsCenter.activityEmpty')}</p>}
      {(page > 0 || audit.data?.hasNext) && <AdminPagination page={page} hasNext={audit.data?.hasNext} onPageChange={setPage} />}
    </div>
  );
}
