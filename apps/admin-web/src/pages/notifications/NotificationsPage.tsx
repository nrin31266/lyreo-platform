import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import {
  RiAddLine,
  RiCheckDoubleLine,
  RiDeleteBinLine,
  RiEditLine,
  RiMegaphoneLine,
  RiShieldKeyholeLine,
} from '@remixicon/react';
import { statusBadgeVariant } from '@/components/ContentBadges';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchEntitlementAudit } from '@/pages/overview/admin-ops.api';
import {
  createAdminNotification,
  deleteAdminNotification,
  fetchAdminNotifications,
  notificationsKey,
  updateAdminNotification,
  type LearnerNotification,
  type NotificationAudience,
  type NotificationCategory,
  type NotificationStatus,
  type SaveNotificationInput,
} from './notifications.api';

type FormState = {
  id: string | null;
  title: string;
  body: string;
  category: NotificationCategory;
  targetAudience: NotificationAudience;
  status: NotificationStatus;
  expiresAt: string | null;
};

function emptyForm(): FormState {
  return {
    id: null,
    title: '',
    body: '',
    category: 'SYSTEM',
    targetAudience: 'ALL',
    status: 'DRAFT',
    expiresAt: null,
  };
}

function toForm(item: LearnerNotification): FormState {
  return {
    id: item.id,
    title: item.title,
    body: item.body,
    category: item.category,
    targetAudience: item.targetAudience,
    status: item.status,
    expiresAt: item.expiresAt,
  };
}

function categoryVariant(cat: NotificationCategory): 'default' | 'secondary' | 'success' | 'warning' {
  if (cat === 'REWARD') return 'success';
  if (cat === 'PROMO') return 'warning';
  if (cat === 'STUDY_REMINDER') return 'default';
  return 'secondary';
}

function auditVariant(decision: string) {
  if (decision.startsWith('DENIED') || decision === 'GRANT_REVOKED') return 'destructive';
  if (['ALLOWED', 'GRANT_CREATED', 'SETTLED'].includes(decision)) return 'success';
  return 'secondary';
}

export function NotificationsPage() {
  const { t, i18n } = useTranslation('admin');
  const queryClient = useQueryClient();

  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [deleteTarget, setDeleteTarget] = useState<LearnerNotification | null>(null);
  const [tab, setTab] = useState('announcements');

  const notificationsQuery = useQuery({
    queryKey: notificationsKey,
    queryFn: ({ signal }) => fetchAdminNotifications(signal),
  });

  const auditQuery = useQuery({
    queryKey: ['admin-notifications-activity-feed'],
    enabled: tab === 'activity',
    queryFn: ({ signal }) => fetchEntitlementAudit({ limit: 30 }, signal),
  });

  const saveMutation = useMutation({
    mutationFn: (state: FormState) => {
      const payload: SaveNotificationInput = {
        title: state.title.trim(),
        body: state.body.trim(),
        category: state.category,
        targetAudience: state.targetAudience,
        status: state.status,
        expiresAt: state.expiresAt,
      };
      return state.id
        ? updateAdminNotification(state.id, payload)
        : createAdminNotification(payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: notificationsKey });
      setDialogOpen(false);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteAdminNotification(id),
    onSuccess: async () => {
      setDeleteTarget(null);
      await queryClient.invalidateQueries({ queryKey: notificationsKey });
    },
  });

  function openNew() {
    setForm(emptyForm());
    saveMutation.reset();
    setDialogOpen(true);
  }

  function openEdit(item: LearnerNotification) {
    setForm(toForm(item));
    saveMutation.reset();
    setDialogOpen(true);
  }

  function formatFriendlyTime(iso: string | null) {
    if (!iso) return '—';
    return new Date(iso).toLocaleString(i18n.language, {
      dateStyle: 'medium',
      timeStyle: 'short',
    });
  }

  return (
    <section className="grid w-full gap-5">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">{t('notificationsCenter.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('notificationsCenter.subtitle')}</p>
        </div>
        {tab === 'announcements' && <Button type="button" onClick={openNew}>
          <RiAddLine data-icon="inline-start" aria-hidden="true" />
          {t('notificationsCenter.createButton')}
        </Button>}
      </header>

      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="announcements">
            <RiMegaphoneLine className="size-4" aria-hidden="true" />
            {t('notificationsCenter.tabs.announcements')}
          </TabsTrigger>
          <TabsTrigger value="activity">
            <RiShieldKeyholeLine className="size-4" aria-hidden="true" />
            {t('notificationsCenter.tabs.activity')}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="announcements" className="mt-4 space-y-4">
          <p className="text-sm text-muted-foreground">{t('notificationsCenter.deliveryHelp')}</p>
          <CatalogError
            error={notificationsQuery.error}
            retry={() => void notificationsQuery.refetch()}
            retrying={notificationsQuery.isFetching}
          />
          {notificationsQuery.isPending ? (
            <Skeleton className="h-48 w-full" />
          ) : !notificationsQuery.data || notificationsQuery.data.length === 0 ? (
            <Empty className="border py-12">
              <EmptyHeader>
                <RiMegaphoneLine className="size-8 text-muted-foreground" aria-hidden="true" />
                <EmptyTitle>{t('notificationsCenter.emptyTitle')}</EmptyTitle>
                <EmptyDescription>{t('notificationsCenter.emptyHelp')}</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <div className="grid gap-4 lg:grid-cols-2">
              {notificationsQuery.data.map((item) => {
                const published = item.status === 'PUBLISHED' && item.publishedAt;
                const expired = item.status === 'PUBLISHED' && item.expiresAt && Date.parse(item.expiresAt) <= Date.now();
                const scheduled = published && Date.parse(published) > Date.now();
                return (
                <Card key={item.id} size="sm">
                  <CardHeader>
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge variant={categoryVariant(item.category)}>
                            {t(`notificationsCenter.categories.${item.category}`, {
                              defaultValue: item.category,
                            })}
                          </Badge>
                          <Badge variant="outline">
                            {t(`notificationsCenter.audiences.${item.targetAudience}`, {
                              defaultValue: item.targetAudience,
                            })}
                          </Badge>
                          <Badge variant={statusBadgeVariant(expired ? 'EXPIRED' : scheduled ? 'SCHEDULED' : item.status)}>
                            {expired ? t('notificationsCenter.expired') : scheduled ? t('notificationsCenter.scheduled')
                              : t(`curriculumWorkspace.statuses.${item.status}`, { defaultValue: item.status })}
                          </Badge>
                        </div>
                        <CardTitle className="text-base">{item.title}</CardTitle>
                        <CardDescription className="text-xs">
                          {t(published ? 'notificationsCenter.publishedTimestamp' : 'notificationsCenter.updatedTimestamp', {
                            time: formatFriendlyTime(published || item.updatedAt),
                          })}
                        </CardDescription>
                      </div>
                      <div className="flex items-center gap-1">
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          onClick={() => openEdit(item)}
                        >
                          <RiEditLine data-icon="inline-start" aria-hidden="true" />
                          {t('rewardsConsole.edit')}
                        </Button>
                        <Button
                          type="button"
                          size="icon-sm"
                          variant="ghost"
                          className="text-destructive"
                          aria-label={t('notificationsCenter.deleteButton')}
                          onClick={() => setDeleteTarget(item)}
                        >
                          <RiDeleteBinLine aria-hidden="true" />
                        </Button>
                      </div>
                    </div>
                  </CardHeader>
                  <CardContent>
                    <p className="text-sm leading-relaxed whitespace-pre-wrap wrap-anywhere text-foreground/90">{item.body}</p>
                  </CardContent>
                </Card>
                );
              })}
            </div>
          )}
        </TabsContent>

        <TabsContent value="activity" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>{t('notificationsCenter.activityTitle')}</CardTitle>
              <CardDescription>{t('notificationsCenter.activityHelp')}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <CatalogError error={auditQuery.error} retry={() => void auditQuery.refetch()} />
              {auditQuery.isPending ? (
                <Skeleton className="h-40 w-full" />
              ) : !auditQuery.data || auditQuery.data.length === 0 ? (
                <Empty className="border py-8">
                  <EmptyHeader>
                    <RiCheckDoubleLine className="size-8 text-muted-foreground" aria-hidden="true" />
                    <EmptyTitle>{t('notificationsCenter.activityEmpty')}</EmptyTitle>
                  </EmptyHeader>
                </Empty>
              ) : (
                <div className="overflow-hidden rounded-xl border">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t('notificationsCenter.columns.time')}</TableHead>
                        <TableHead>{t('notificationsCenter.columns.feature')}</TableHead>
                        <TableHead>{t('notificationsCenter.columns.decision')}</TableHead>
                        <TableHead>{t('notificationsCenter.columns.account')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {auditQuery.data.map((entry) => (
                        <TableRow key={entry.id}>
                          <TableCell className="text-xs tabular-nums text-muted-foreground">
                            <time dateTime={entry.occurredAt}>{formatFriendlyTime(entry.occurredAt)}</time>
                          </TableCell>
                          <TableCell className="whitespace-normal">
                            {t(`plansConsole.features.${entry.featureKey}`, { defaultValue: entry.featureKey })}
                          </TableCell>
                          <TableCell>
                            <Badge variant={auditVariant(entry.decision)}>
                              {t(`notificationsCenter.decisions.${entry.decision}`, { defaultValue: entry.decision })}
                            </Badge>
                            {typeof entry.detail?.limit === 'number' &&
                              <p className="mt-1 text-xs text-muted-foreground">{t('notificationsCenter.auditLimit', { limit: entry.detail.limit })}</p>}
                          </TableCell>
                          <TableCell>
                            {entry.decision === 'QUOTA_UPDATED' ? '—' : (
                              <Link className="text-xs underline underline-offset-2" to={`/learners/${encodeURIComponent(entry.userId)}`}>
                                {t('notificationsCenter.learnerLink', { id: entry.userId.slice(-8) })}
                              </Link>
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {form.id
                ? t('notificationsCenter.editTitle')
                : t('notificationsCenter.createTitle')}
            </DialogTitle>
            <DialogDescription>{t('notificationsCenter.dialogHelp')}</DialogDescription>
          </DialogHeader>
          <CatalogError error={saveMutation.error} />
          <form
            id="announcement-form"
            className="grid gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              saveMutation.mutate(form);
            }}
          >
            <div className="grid gap-1">
              <Label htmlFor="announcement-title">{t('notificationsCenter.fields.title')}</Label>
              <Input
                required
                maxLength={200}
                value={form.title}
                id="announcement-title"
                onChange={(e) => setForm({ ...form, title: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label htmlFor="announcement-body">{t('notificationsCenter.fields.body')}</Label>
              <Textarea
                required
                rows={4}
                maxLength={2000}
                value={form.body}
                id="announcement-body"
                onChange={(e) => setForm({ ...form, body: e.target.value })}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="grid gap-1">
                <Label>{t('notificationsCenter.fields.category')}</Label>
                <Select
                  value={form.category}
                  onValueChange={(v) => setForm({ ...form, category: v as NotificationCategory })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(['SYSTEM', 'STUDY_REMINDER', 'REWARD', 'PROMO'] as const).map((cat) => (
                      <SelectItem key={cat} value={cat}>
                        {t(`notificationsCenter.categories.${cat}`, { defaultValue: cat })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('notificationsCenter.fields.audience')}</Label>
                <Select
                  value={form.targetAudience}
                  onValueChange={(v) =>
                    setForm({ ...form, targetAudience: v as NotificationAudience })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(['ALL', 'FREE', 'PLUS'] as const).map((aud) => (
                      <SelectItem key={aud} value={aud}>
                        {t(`notificationsCenter.audiences.${aud}`, { defaultValue: aud })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label htmlFor="announcement-status">{t('curriculumWorkspace.status')}</Label>
                <Select
                  value={form.status}
                  onValueChange={(v) => setForm({ ...form, status: v as NotificationStatus })}
                >
                  <SelectTrigger id="announcement-status">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(['DRAFT', 'PUBLISHED', 'ARCHIVED'] as const).map((st) => (
                      <SelectItem key={st} value={st}>
                        {t(`curriculumWorkspace.statuses.${st}`, { defaultValue: st })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
          </form>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
              {t('catalog.cancel')}
            </Button>
            <Button type="submit" form="announcement-form" disabled={saveMutation.isPending}>
              {t(saveMutation.isPending ? 'catalog.saving' : 'catalog.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('notificationsCenter.deleteButton')}</DialogTitle>
            <DialogDescription>
              {t('notificationsCenter.deleteConfirm', { title: deleteTarget?.title ?? '' })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDeleteTarget(null)}>
              {t('catalog.cancel')}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={deleteMutation.isPending || !deleteTarget}
              onClick={() => deleteTarget && deleteMutation.mutate(deleteTarget.id)}
            >
              {t('curriculumWorkspace.confirmDelete')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
