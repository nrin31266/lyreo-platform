import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiAddLine,
  RiBookOpenLine,
  RiCoupon3Line,
  RiDeleteBinLine,
  RiEditLine,
  RiGiftLine,
  RiHistoryLine,
  RiMegaphoneLine,
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
import { NotificationActivity, activityKey } from './NotificationActivity';
import { NotificationRecipientPicker, type NotificationRecipient } from './NotificationRecipientPicker';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
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
  targetAudience: NotificationAudience | '';
  status: NotificationStatus;
  originalStatus: NotificationStatus;
  expiresAt: string | null;
  publishedAt: string | null;
  recipients: NotificationRecipient[];
};

function emptyForm(): FormState {
  return {
    id: null,
    title: '',
    body: '',
    category: 'SYSTEM',
    targetAudience: '',
    status: 'DRAFT',
    originalStatus: 'DRAFT',
    expiresAt: null,
    publishedAt: null,
    recipients: [],
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
    originalStatus: item.status,
    expiresAt: item.expiresAt,
    publishedAt: item.publishedAt ?? (item.status === 'PUBLISHED' ? item.createdAt : null),
    recipients: (item.recipientIds ?? []).map((id, index) => ({ id, label: item.recipientLabels?.[index] || id })),
  };
}

function categoryVariant(cat: NotificationCategory): 'default' | 'secondary' | 'success' | 'warning' {
  if (cat === 'REWARD') return 'success';
  if (cat === 'PROMO') return 'warning';
  if (cat === 'STUDY_REMINDER') return 'default';
  return 'secondary';
}

const categoryIcons = {
  SYSTEM: RiMegaphoneLine,
  STUDY_REMINDER: RiBookOpenLine,
  REWARD: RiGiftLine,
  PROMO: RiCoupon3Line,
};

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

  const saveMutation = useMutation({
    mutationFn: (state: FormState) => {
      const payload: SaveNotificationInput = {
        title: state.title.trim(),
        body: state.body.trim(),
        category: state.category,
        targetAudience: state.targetAudience || undefined,
        status: state.status,
        expiresAt: state.expiresAt,
        recipientIds: state.targetAudience === 'SELECTED' ? state.recipients.map(item => item.id) : [],
      };
      return state.id
        ? updateAdminNotification(state.id, payload)
        : createAdminNotification(payload);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: notificationsKey }),
        queryClient.invalidateQueries({ queryKey: activityKey }),
      ]);
      setDialogOpen(false);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteAdminNotification(id),
    onSuccess: async () => {
      setDeleteTarget(null);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: notificationsKey }),
        queryClient.invalidateQueries({ queryKey: activityKey }),
      ]);
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

  const audienceLocked = form.publishedAt !== null && Date.parse(form.publishedAt) <= Date.now();
  const publishing = form.status === 'PUBLISHED' && form.originalStatus !== 'PUBLISHED';
  const invalidAudience = (publishing && !form.targetAudience)
    || (form.targetAudience === 'SELECTED' && form.recipients.length === 0)
    || (form.category === 'STUDY_REMINDER' && form.targetAudience === 'ALL' && form.status === 'PUBLISHED');
  let saveLabel = t('catalog.save');
  if (publishing) {
    saveLabel = t('notificationsCenter.publish');
    if (form.targetAudience === 'SELECTED' && form.recipients.length > 0) {
      saveLabel = form.recipients.length === 1 ? t('notificationsCenter.publishToOne')
        : t('notificationsCenter.publishToMany', { count: form.recipients.length });
    } else if (form.targetAudience && form.targetAudience !== 'SELECTED') {
      saveLabel = t('notificationsCenter.publishTo', {
        audience: t(`notificationsCenter.audiences.${form.targetAudience}`),
      });
    }
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
            <RiHistoryLine className="size-4" aria-hidden="true" />
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
            <div className="divide-y rounded-lg border">
              {notificationsQuery.data.map((item) => {
                const published = item.status === 'PUBLISHED' && item.publishedAt;
                const expired = item.status === 'PUBLISHED' && item.expiresAt && Date.parse(item.expiresAt) <= Date.now();
                const scheduled = published && Date.parse(published) > Date.now();
                const Icon = categoryIcons[item.category];
                return (
                <Card key={item.id} size="sm" className="rounded-none bg-transparent shadow-none ring-0">
                  <CardHeader>
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="space-y-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge variant={categoryVariant(item.category)}>
                            <Icon aria-hidden="true" />
                            {t(`notificationsCenter.categories.${item.category}`, {
                              defaultValue: item.category,
                            })}
                          </Badge>
                          <Badge variant="outline">
                            {item.targetAudience === 'SELECTED'
                              ? (item.recipientLabels?.length === 1 ? item.recipientLabels[0]
                                : t('notificationsCenter.recipientCount', { count: item.recipientIds?.length ?? 0 }))
                              : t(`notificationsCenter.audiences.${item.targetAudience}`)}
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
                          onClick={() => { deleteMutation.reset(); setDeleteTarget(item); }}
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

        <TabsContent value="activity" className="mt-4" forceMount hidden={tab !== 'activity'}>
          <NotificationActivity active={tab === 'activity'} />
        </TabsContent>
      </Tabs>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-2xl">
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
              if (!invalidAudience && !saveMutation.isPending) saveMutation.mutate(form);
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
                <Label htmlFor="announcement-category">{t('notificationsCenter.fields.category')}</Label>
                <Select
                  value={form.category}
                  onValueChange={(v) => setForm({ ...form, category: v as NotificationCategory })}
                >
                  <SelectTrigger id="announcement-category" className="w-full">
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
                <Label htmlFor="announcement-audience">{t('notificationsCenter.fields.audience')}</Label>
                <Select
                  value={form.targetAudience}
                  disabled={audienceLocked}
                  onValueChange={(v) =>
                    setForm({ ...form, targetAudience: v as NotificationAudience })
                  }
                >
                  <SelectTrigger id="announcement-audience" className="w-full">
                    <SelectValue placeholder={t('notificationsCenter.chooseAudience')} />
                  </SelectTrigger>
                  <SelectContent>
                    {(['ALL', 'FREE', 'PLUS', 'SELECTED'] as const).map((aud) => (
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
                  <SelectTrigger id="announcement-status" className="w-full">
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
            {audienceLocked && <p className="text-xs text-muted-foreground">{t('notificationsCenter.audienceLocked')}</p>}
            {publishing && !form.targetAudience &&
              <p className="text-sm text-muted-foreground">{t('notificationsCenter.publishAudienceRequired')}</p>}
            {form.category === 'STUDY_REMINDER' && form.targetAudience === 'ALL' &&
              <p className="text-sm text-muted-foreground">{t('notificationsCenter.reminderAudience')}</p>}
            {form.targetAudience === 'SELECTED' && <NotificationRecipientPicker
              selected={form.recipients} readOnly={audienceLocked}
              onChange={recipients => setForm(current => ({ ...current, recipients }))} />}
          </form>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
              {t('catalog.cancel')}
            </Button>
            <Button type="submit" form="announcement-form" disabled={saveMutation.isPending || invalidAudience}>
              {saveMutation.isPending ? t('catalog.saving') : saveLabel}
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
          <CatalogError error={deleteMutation.error} />
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
