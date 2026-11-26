import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiAddLine, RiAwardLine, RiEditLine, RiMedalLine } from '@remixicon/react';
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
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import {
  achievementMetrics,
  badgeTiers,
  createAdminAchievement,
  fetchAdminAchievements,
  fetchContentMediaList,
  updateAdminAchievement,
  type AdminAchievementDefinition,
} from '@/pages/overview/admin-ops.api';

type AchievementForm = {
  id: string | null;
  code: string;
  title: string;
  description: string;
  metric: string;
  target: string;
  xpReward: string;
  diamondReward: string;
  badgeAssetId: string | null;
  badgeTier: string;
  category: string;
  ruleVersion: string;
  enabled: boolean;
};

const achievementCategories = [
  'GENERAL',
  'STREAK',
  'LESSONS',
  'VOCABULARY',
  'TOEIC',
  'MASTERY',
] as const;

const tierBorderClass: Record<string, string> = {
  BRONZE: 'border-t-4 border-t-[color:var(--tier-bronze)]',
  SILVER: 'border-t-4 border-t-[color:var(--tier-silver)]',
  GOLD: 'border-t-4 border-t-[color:var(--tier-gold)]',
  PLATINUM: 'border-t-4 border-t-[color:var(--tier-platinum)]',
  LEGENDARY: 'border-t-4 border-t-[color:var(--tier-legendary)]',
};

const emptyAchievement = (): AchievementForm => ({
  id: null,
  code: '',
  title: '',
  description: '',
  metric: 'DISTINCT_LESSON_COMPLETED',
  target: '10',
  xpReward: '150',
  diamondReward: '15',
  badgeAssetId: null,
  badgeTier: 'SILVER',
  category: 'LESSONS',
  ruleVersion: 'dev-v1',
  enabled: true,
});

export function AchievementsPage() {
  const { t } = useTranslation('admin');
  const queryClient = useQueryClient();

  const [categoryFilter, setCategoryFilter] = useState('ALL');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<AchievementForm>(emptyAchievement);

  const achievementsQuery = useQuery({
    queryKey: ['admin-gamification-achievements'],
    queryFn: ({ signal }) => fetchAdminAchievements(signal),
  });

  const mediaQuery = useQuery({
    queryKey: ['admin-achievement-badge-media'],
    queryFn: ({ signal }) => fetchContentMediaList({ kind: 'image', size: 50 }, signal),
  });

  const saveMutation = useMutation({
    mutationFn: (state: AchievementForm) => {
      const payload = {
        code: state.code.trim(),
        title: state.title.trim(),
        description: state.description.trim(),
        metric: state.metric,
        target: Math.max(1, Number(state.target) || 1),
        xpReward: Math.max(0, Number(state.xpReward) || 0),
        diamondReward: Math.max(0, Number(state.diamondReward) || 0),
        badgeAssetId: state.badgeAssetId || null,
        badgeTier: state.badgeTier,
        category: state.category,
        ruleVersion: state.ruleVersion.trim() || 'dev-v1',
        enabled: state.enabled,
      };
      return state.id
        ? updateAdminAchievement(state.id, payload)
        : createAdminAchievement(payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-gamification-achievements'] });
      setDialogOpen(false);
    },
  });

  function openNew() {
    setForm(emptyAchievement());
    saveMutation.reset();
    setDialogOpen(true);
  }

  function openEdit(ach: AdminAchievementDefinition) {
    setForm({
      id: ach.id,
      code: ach.code,
      title: ach.title,
      description: ach.description,
      metric: ach.metric,
      target: String(ach.target),
      xpReward: String(ach.xpReward),
      diamondReward: String(ach.diamondReward),
      badgeAssetId: ach.badgeAssetId ?? null,
      badgeTier: ach.badgeTier || 'BRONZE',
      category: ach.category || 'GENERAL',
      ruleVersion: ach.ruleVersion || 'dev-v1',
      enabled: ach.enabled,
    });
    saveMutation.reset();
    setDialogOpen(true);
  }

  const items = (achievementsQuery.data ?? []).filter(
    (ach) => categoryFilter === 'ALL' || (ach.category || 'GENERAL') === categoryFilter,
  );

  return (
    <section className="mx-auto grid w-full max-w-7xl gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">
            {t('achievementsConsole.title')}
          </h1>
          <p className="text-sm text-muted-foreground">{t('achievementsConsole.subtitle')}</p>
        </div>
        <Button type="button" onClick={openNew}>
          <RiAddLine data-icon="inline-start" aria-hidden="true" />
          {t('rewardsConsole.addAchievement')}
        </Button>
      </header>

      <div className="flex flex-wrap gap-2">
        {(['ALL', ...achievementCategories] as const).map((cat) => (
          <Button
            key={cat}
            type="button"
            size="sm"
            variant={categoryFilter === cat ? 'default' : 'outline'}
            onClick={() => setCategoryFilter(cat)}
          >
            {cat}
          </Button>
        ))}
      </div>

      <CatalogError
        error={achievementsQuery.error}
        retry={() => void achievementsQuery.refetch()}
        retrying={achievementsQuery.isFetching}
      />

      {achievementsQuery.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : items.length === 0 ? (
        <Empty className="border py-12">
          <EmptyHeader>
            <RiMedalLine className="size-8 text-muted-foreground" aria-hidden="true" />
            <EmptyTitle>{t('achievementsConsole.emptyTitle')}</EmptyTitle>
            <EmptyDescription>{t('achievementsConsole.emptyHelp')}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((ach) => {
            const tier = ach.badgeTier || 'BRONZE';
            return (
              <Card key={ach.id} className={tierBorderClass[tier] ?? ''}>
                <CardHeader>
                  <div className="flex items-start gap-3">
                    {ach.badgeAssetId ? (
                      <img
                        src={`/api/v1/gamification/achievements/${encodeURIComponent(ach.id)}/badge`}
                        alt={ach.title}
                        className="size-16 shrink-0 rounded-xl border bg-muted/30 object-cover"
                      />
                    ) : (
                      <div className="flex size-16 shrink-0 items-center justify-center rounded-xl border bg-muted text-muted-foreground">
                        <RiAwardLine className="size-8" aria-hidden="true" />
                      </div>
                    )}
                    <div className="min-w-0 flex-1 space-y-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Badge variant="outline">{tier}</Badge>
                        <Badge variant={ach.enabled ? 'success' : 'secondary'}>
                          {t(ach.enabled ? 'rewardsConsole.enabled' : 'rewardsConsole.disabled')}
                        </Badge>
                      </div>
                      <CardTitle className="text-base leading-snug">{ach.title}</CardTitle>
                      <CardDescription className="font-mono text-xs">{ach.code}</CardDescription>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="space-y-3 text-sm">
                  <p className="text-muted-foreground">{ach.description}</p>
                  <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border bg-muted/20 px-3 py-2 text-xs">
                    <span className="font-medium tabular-nums">
                      {t('achievementsConsole.threshold', {
                        metric: ach.metric,
                        target: ach.target,
                      })}
                    </span>
                    <span className="font-semibold tabular-nums">
                      +{ach.xpReward} XP · +{ach.diamondReward} ◆
                    </span>
                  </div>
                  <div className="flex justify-end">
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => openEdit(ach)}
                    >
                      <RiEditLine data-icon="inline-start" aria-hidden="true" />
                      {t('rewardsConsole.edit')}
                    </Button>
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {form.id
                ? t('rewardsConsole.editAchievementTitle')
                : t('rewardsConsole.addAchievement')}
            </DialogTitle>
            <DialogDescription>{t('rewardsConsole.achievementDialogHelp')}</DialogDescription>
          </DialogHeader>
          <CatalogError error={saveMutation.error} />
          <form
            id="achievement-form"
            className="grid gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              saveMutation.mutate(form);
            }}
          >
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.code')}</Label>
              <Input
                required
                className="font-mono text-xs"
                value={form.code}
                onChange={(e) => setForm({ ...form, code: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.title')}</Label>
              <Input
                required
                value={form.title}
                onChange={(e) => setForm({ ...form, title: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.description')}</Label>
              <Textarea
                rows={2}
                value={form.description}
                onChange={(e) => setForm({ ...form, description: e.target.value })}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.metric')}</Label>
                <Select value={form.metric} onValueChange={(v) => setForm({ ...form, metric: v })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {achievementMetrics.map((m) => (
                      <SelectItem key={m} value={m}>
                        {m}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.target')}</Label>
                <Input
                  type="number"
                  min={1}
                  value={form.target}
                  onChange={(e) => setForm({ ...form, target: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('achievementsConsole.tier')}</Label>
                <Select
                  value={form.badgeTier}
                  onValueChange={(v) => setForm({ ...form, badgeTier: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {badgeTiers.map((tier) => (
                      <SelectItem key={tier} value={tier}>
                        {tier}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('missionsConsole.category')}</Label>
                <Select
                  value={form.category}
                  onValueChange={(v) => setForm({ ...form, category: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {achievementCategories.map((cat) => (
                      <SelectItem key={cat} value={cat}>
                        {cat}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>XP</Label>
                <Input
                  type="number"
                  min={0}
                  value={form.xpReward}
                  onChange={(e) => setForm({ ...form, xpReward: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>Diamonds</Label>
                <Input
                  type="number"
                  min={0}
                  value={form.diamondReward}
                  onChange={(e) => setForm({ ...form, diamondReward: e.target.value })}
                />
              </div>
            </div>

            <div className="grid gap-1">
              <Label>{t('achievementsConsole.badgeAsset')}</Label>
              <Select
                value={form.badgeAssetId || 'NONE'}
                onValueChange={(v) => setForm({ ...form, badgeAssetId: v === 'NONE' ? null : v })}
              >
                <SelectTrigger>
                  <SelectValue placeholder={t('achievementsConsole.noBadge')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="NONE">{t('achievementsConsole.noBadge')}</SelectItem>
                  {(mediaQuery.data?.items ?? []).map((asset) => (
                    <SelectItem key={asset.id} value={asset.id}>
                      {asset.filename || asset.id}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <label className="flex items-center justify-between rounded-xl border bg-muted/20 px-3 py-2 text-sm">
              <span>{t('rewardsConsole.enabled')}</span>
              <Switch
                checked={form.enabled}
                onCheckedChange={(checked) => setForm({ ...form, enabled: checked })}
              />
            </label>
          </form>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
              {t('rewardsConsole.cancel')}
            </Button>
            <Button type="submit" form="achievement-form" disabled={saveMutation.isPending}>
              {t('rewardsConsole.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
