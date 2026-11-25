import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { RiArrowRightUpLine, RiBroadcastLine, RiNotification3Line } from '@remixicon/react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useJobConnection } from '@/lib/realtime/JobRealtimeProvider';
import { fetchJobs } from '@/pages/jobs/jobs.api';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchEntitlementAudit } from '@/pages/overview/admin-ops.api';

export function NotificationsPage() {
  const { t } = useTranslation('admin');
  const connectionStatus = useJobConnection();

  const jobsQuery = useQuery({
    queryKey: ['jobs', 'notifications'],
    queryFn: ({ signal }) => fetchJobs(new URLSearchParams({ page: '0', size: '15' }), signal),
  });

  const auditQuery = useQuery({
    queryKey: ['admin-entitlement-audit', 'notifications'],
    queryFn: ({ signal }) => fetchEntitlementAudit({ limit: 15 }, signal),
  });

  return (
    <div className="space-y-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{t('notificationsCenter.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('notificationsCenter.subtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant={connectionStatus === 'connected' ? 'default' : 'outline'}>
            <RiBroadcastLine className="mr-1 size-3.5" aria-hidden="true" />
            {t(`notificationsCenter.streamStatus.${connectionStatus}`)}
          </Badge>
          <Button asChild variant="outline" size="sm">
            <Link to="/jobs">
              <RiArrowRightUpLine aria-hidden="true" />
              {t('navigation.jobs')}
            </Link>
          </Button>
        </div>
      </div>

      <Alert>
        <RiNotification3Line className="size-4" aria-hidden="true" />
        <AlertTitle>{t('notificationsCenter.scopeTitle')}</AlertTitle>
        <AlertDescription>{t('notificationsCenter.scopeDescription')}</AlertDescription>
      </Alert>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>{t('notificationsCenter.jobsFeedTitle')}</CardTitle>
            <CardDescription>{t('notificationsCenter.jobsFeedHelp')}</CardDescription>
          </CardHeader>
          <CardContent>
            {jobsQuery.error ? (
              <CatalogError error={jobsQuery.error} retry={() => void jobsQuery.refetch()} />
            ) : jobsQuery.isPending ? (
              <Skeleton className="h-40 w-full" />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('notificationsCenter.columns.type')}</TableHead>
                    <TableHead>{t('notificationsCenter.columns.status')}</TableHead>
                    <TableHead className="text-right">{t('notificationsCenter.columns.progress')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(jobsQuery.data?.items ?? []).map((job) => (
                    <TableRow key={job.id}>
                      <TableCell>
                        <p className="font-medium">{job.jobType}</p>
                        <p className="text-xs text-muted-foreground">
                          {job.currentStep ?? job.ownerReferenceId ?? job.id}
                        </p>
                      </TableCell>
                      <TableCell>
                        <Badge variant={job.status === 'FAILED' ? 'destructive' : 'outline'}>
                          {job.status}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{job.progressPercent}%</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t('notificationsCenter.securityFeedTitle')}</CardTitle>
            <CardDescription>{t('notificationsCenter.securityFeedHelp')}</CardDescription>
          </CardHeader>
          <CardContent>
            {auditQuery.error ? (
              <CatalogError error={auditQuery.error} retry={() => void auditQuery.refetch()} />
            ) : auditQuery.isPending ? (
              <Skeleton className="h-40 w-full" />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('notificationsCenter.columns.time')}</TableHead>
                    <TableHead>{t('notificationsCenter.columns.feature')}</TableHead>
                    <TableHead>{t('notificationsCenter.columns.decision')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {(auditQuery.data ?? []).map((entry) => (
                    <TableRow key={entry.id}>
                      <TableCell className="text-xs tabular-nums">
                        {new Date(entry.occurredAt).toLocaleString()}
                      </TableCell>
                      <TableCell className="font-mono text-xs">{entry.featureKey}</TableCell>
                      <TableCell>
                        <Badge variant="outline">{entry.decision}</Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
