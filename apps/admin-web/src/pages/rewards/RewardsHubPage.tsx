import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import {
  RiAddLine,
  RiAwardLine,
  RiEditLine,
  RiFocus3Line,
  RiMedalLine,
  RiSettings3Line,
} from '@remixicon/react';
import { AuthenticatedImage } from '@/components/AuthenticatedImage';
import { AchievementTierBadge, DifficultyBadge, statusBadgeVariant } from '@/components/ContentBadges';
import { ImageAssetEditor } from '@/components/ImageAssetEditor';
import { RewardPill } from '@/components/RewardIcons';
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { RewardPolicyPanel } from './RewardPolicyPanel';
import {
  achievementMetrics,
  badgeTiers,
  createAdminAchievement,
  createAdminMission,
  fetchAdminAchievements,
  fetchAdminMissions,
  fetchRewardPolicy,
  missionDifficulties,
  missionMetrics,
  missionRecurrences,
  updateAdminAchievement,
  updateAdminMission,
  type AdminAchievementDefinition,
  type AdminMissionDefinition,
} from '@/pages/overview/admin-ops.api';

type HubTab = 'missions' | 'achievements' | 'policy';

type MissionForm = {
  id: string | null;
  code: string;
  title: string;
  metric: string;
  target: string;
  xpReward: string;
  diamondReward: string;
  recurrence: string;
  category: string;
  difficulty: string;
  ruleVersion: string;
  enabled: boolean;
};

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

const missionCategories = ['GENERAL', 'VOCAB', 'LESSON', 'GRAMMAR', 'TOEIC'] as const;
const achievementCategories = [
  'GENERAL',
  'STREAK',
  'LESSONS',
  'VOCABULARY',
  'TOEIC',
  'MASTERY',
] as const;

const emptyMission = (): MissionForm => ({
  id: null,
  code: '',
  title: '',
  metric: 'LESSON_COMPLETED',
  target: '1',
  xpReward: '25',
  diamondReward: '3',
  recurrence: 'DAILY',
  category: 'LESSON',
  difficulty: 'NORMAL',
  ruleVersion: 'v1',
  enabled: true,
});

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
  ruleVersion: 'v1',
  enabled: true,
});

export function RewardsHubPage() {
  const { t } = useTranslation('admin');
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();

  const tabParam = params.get('tab');
  const activeTab: HubTab =
    tabParam === 'achievements' || tabParam === 'policy' ? tabParam : 'missions';

  function selectTab(next: string) {
    const updated = new URLSearchParams(params);
    if (next === 'missions') updated.delete('tab');
    else updated.set('tab', next);
    setParams(updated, { replace: true });
  }

  const [missionCat, setMissionCat] = useState('ALL');
  const [missionRecurrence, setMissionRecurrence] = useState('ALL');
  const [missionDialogOpen, setMissionDialogOpen] = useState(false);
  const [missionForm, setMissionForm] = useState<MissionForm>(emptyMission);

  const [achCat, setAchCat] = useState('ALL');
  const [achDialogOpen, setAchDialogOpen] = useState(false);
  const [achForm, setAchForm] = useState<AchievementForm>(emptyAchievement);

  const missionsQuery = useQuery({
    queryKey: ['admin-gamification-missions'],
    queryFn: ({ signal }) => fetchAdminMissions(signal),
  });

  const achievementsQuery = useQuery({
    queryKey: ['admin-gamification-achievements'],
    queryFn: ({ signal }) => fetchAdminAchievements(signal),
  });

  const policyQuery = useQuery({
    queryKey: ['admin-gamification-policy'],
    queryFn: ({ signal }) => fetchRewardPolicy(signal),
  });

  const saveMissionMutation = useMutation({
    mutationFn: (state: MissionForm) => {
      const payload = {
        code: state.code.trim(),
        title: state.title.trim(),
        metric: state.metric,
        target: Math.max(1, Number(state.target) || 1),
        xpReward: Math.max(0, Number(state.xpReward) || 0),
        diamondReward: Math.max(0, Number(state.diamondReward) || 0),
        recurrence: state.recurrence,
        category: state.category,
        difficulty: state.difficulty,
        ruleVersion: state.ruleVersion.trim() || 'v1',
        enabled: state.enabled,
      };
      return state.id ? updateAdminMission(state.id, payload) : createAdminMission(payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-gamification-missions'] });
      setMissionDialogOpen(false);
    },
  });

  const saveAchMutation = useMutation({
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
        ruleVersion: state.ruleVersion.trim() || 'v1',
        enabled: state.enabled,
      };
      return state.id
        ? updateAdminAchievement(state.id, payload)
        : createAdminAchievement(payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-gamification-achievements'] });
      setAchDialogOpen(false);
    },
  });

  function openNewMission() {
    setMissionForm(emptyMission());
    saveMissionMutation.reset();
    setMissionDialogOpen(true);
  }

  function openEditMission(m: AdminMissionDefinition) {
    setMissionForm({
      id: m.id,
      code: m.code,
      title: m.title,
      metric: m.metric,
      target: String(m.target),
      xpReward: String(m.xpReward),
      diamondReward: String(m.diamondReward),
      recurrence: m.recurrence,
      category: m.category || 'GENERAL',
      difficulty: m.difficulty || 'NORMAL',
      ruleVersion: m.ruleVersion || 'v1',
      enabled: m.enabled,
    });
    saveMissionMutation.reset();
    setMissionDialogOpen(true);
  }

  function openNewAchievement() {
    setAchForm(emptyAchievement());
    saveAchMutation.reset();
    setAchDialogOpen(true);
  }

  function openEditAchievement(ach: AdminAchievementDefinition) {
    setAchForm({
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
      ruleVersion: ach.ruleVersion || 'v1',
      enabled: ach.enabled,
    });
    saveAchMutation.reset();
    setAchDialogOpen(true);
  }

  const missions = (missionsQuery.data ?? [])
    .filter((m) => missionCat === 'ALL' || (m.category || 'GENERAL') === missionCat)
    .filter((m) => missionRecurrence === 'ALL' || m.recurrence === missionRecurrence);

  const achievements = (achievementsQuery.data ?? []).filter(
    (ach) => achCat === 'ALL' || (ach.category || 'GENERAL') === achCat,
  );

  const activeMissionsCount = (missionsQuery.data ?? []).filter((m) => m.enabled).length;
  const activeAchievementsCount = (achievementsQuery.data ?? []).filter((a) => a.enabled).length;

  return (
    <section className="grid w-full gap-5">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">{t('rewardsHub.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('rewardsHub.subtitle')}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {activeTab === 'achievements' ? (
            <Button type="button" onClick={openNewAchievement}>
              <RiAddLine data-icon="inline-start" aria-hidden="true" />
              {t('rewardsConsole.addAchievement')}
            </Button>
          ) : activeTab === 'missions' ? (
            <Button type="button" onClick={openNewMission}>
              <RiAddLine data-icon="inline-start" aria-hidden="true" />
              {t('rewardsConsole.addMission')}
            </Button>
          ) : null}
        </div>
      </header>

      <div className="grid gap-4 sm:grid-cols-3">
        <Card size="sm">
          <CardHeader className="pb-1">
            <CardDescription>{t('rewardsHub.kpis.activeMissions')}</CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {missionsQuery.isPending ? (
              <Skeleton className="h-7 w-16" />
            ) : (
              `${activeMissionsCount} / ${(missionsQuery.data ?? []).length}`
            )}
          </CardContent>
        </Card>
        <Card size="sm">
          <CardHeader className="pb-1">
            <CardDescription>{t('rewardsHub.kpis.badgesCatalog')}</CardDescription>
          </CardHeader>
          <CardContent className="text-2xl font-semibold tabular-nums">
            {achievementsQuery.isPending ? (
              <Skeleton className="h-7 w-16" />
            ) : (
              `${activeAchievementsCount} / ${(achievementsQuery.data ?? []).length}`
            )}
          </CardContent>
        </Card>
        <Card size="sm">
          <CardHeader className="pb-1">
            <CardDescription>{t('rewardsHub.kpis.xpPolicy')}</CardDescription>
          </CardHeader>
          <CardContent className="text-lg font-semibold tabular-nums">
            {policyQuery.isPending ? (
              <Skeleton className="h-7 w-28" />
            ) : policyQuery.data ? (
              `${policyQuery.data.ruleVersion} · ${policyQuery.data.xpPerLevel}+${policyQuery.data.xpGrowthPerLevel ?? 15} XP · ×${(policyQuery.data.xpLevelMultiplier ?? 1.02).toFixed(2)} · Lv.${policyQuery.data.maxLevel ?? 99}`
            ) : (
              '—'
            )}
          </CardContent>
        </Card>
      </div>

      <Tabs value={activeTab} onValueChange={selectTab}>
        <TabsList>
          <TabsTrigger value="missions">
            <RiFocus3Line className="size-4" aria-hidden="true" />
            {t('rewardsConsole.tabs.missions')}
          </TabsTrigger>
          <TabsTrigger value="achievements">
            <RiMedalLine className="size-4" aria-hidden="true" />
            {t('rewardsConsole.tabs.achievements')}
          </TabsTrigger>
          <TabsTrigger value="policy">
            <RiSettings3Line className="size-4" aria-hidden="true" />
            {t('rewardsConsole.tabs.policy')}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="missions" className="mt-4 space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap gap-1.5">
              {(['ALL', ...missionCategories] as const).map((cat) => (
                <Button
                  key={cat}
                  type="button"
                  size="sm"
                  variant={missionCat === cat ? 'default' : 'outline'}
                  onClick={() => setMissionCat(cat)}
                >
                  {t(`rewardsHub.categories.${cat}`, { defaultValue: cat })}
                </Button>
              ))}
            </div>
            <div className="flex flex-wrap gap-1.5">
              {(['ALL', ...missionRecurrences] as const).map((rec) => (
                <Button
                  key={rec}
                  type="button"
                  size="sm"
                  variant={missionRecurrence === rec ? 'secondary' : 'ghost'}
                  onClick={() => setMissionRecurrence(rec)}
                >
                  {t(`rewardsHub.recurrences.${rec}`, { defaultValue: rec })}
                </Button>
              ))}
            </div>
          </div>

          <Card>
            <CardHeader>
              <CardTitle>{t('rewardsConsole.missionsTitle')}</CardTitle>
              <CardDescription>{t('rewardsConsole.missionsHelp')}</CardDescription>
            </CardHeader>
            <CardContent>
              <CatalogError
                error={missionsQuery.error}
                retry={() => void missionsQuery.refetch()}
                retrying={missionsQuery.isFetching}
              />
              {missionsQuery.isPending ? (
                <Skeleton className="h-44 w-full" />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('rewardsConsole.columns.code')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.title')}</TableHead>
                      <TableHead>{t('missionsConsole.difficulty')}</TableHead>
                      <TableHead className="text-right">
                        {t('rewardsConsole.columns.target')}
                      </TableHead>
                      <TableHead className="text-right">
                        {t('rewardsConsole.columns.rewards')}
                      </TableHead>
                      <TableHead>{t('rewardsConsole.columns.recurrence')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.status')}</TableHead>
                      <TableHead className="text-right">
                        {t('rewardsConsole.columns.actions')}
                      </TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {missions.map((m) => (
                      <TableRow key={m.id}>
                        <TableCell className="font-mono text-xs">{m.code}</TableCell>
                        <TableCell>
                          <div className="space-y-0.5">
                            <p className="font-medium">{m.title}</p>
                            <p className="text-xs text-muted-foreground">
                              {t(`rewardsHub.categories.${m.category || 'GENERAL'}`, {
                                defaultValue: m.category || 'GENERAL',
                              })}{' '}
                              ·{' '}
                              {t(`rewardsHub.metrics.${m.metric}`, { defaultValue: m.metric })}
                            </p>
                          </div>
                        </TableCell>
                        <TableCell>
                          <DifficultyBadge difficulty={m.difficulty || 'NORMAL'} />
                        </TableCell>
                        <TableCell className="text-right tabular-nums">{m.target}</TableCell>
                        <TableCell className="text-right tabular-nums">
                          <div className="inline-flex items-center justify-end gap-1.5">
                            <RewardPill kind="xp" value={m.xpReward} />
                            <RewardPill kind="diamond" value={m.diamondReward} />
                          </div>
                        </TableCell>
                        <TableCell>
                          <Badge variant="outline">
                            {t(`rewardsHub.recurrences.${m.recurrence}`, {
                              defaultValue: m.recurrence,
                            })}
                          </Badge>
                        </TableCell>
                        <TableCell>
                          <Badge variant={statusBadgeVariant(m.enabled ? 'ENABLED' : 'DISABLED')}>
                            {t(m.enabled ? 'rewardsConsole.enabled' : 'rewardsConsole.disabled')}
                          </Badge>
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            onClick={() => openEditMission(m)}
                          >
                            <RiEditLine data-icon="inline-start" aria-hidden="true" />
                            {t('rewardsConsole.edit')}
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="achievements" className="mt-4 space-y-4">
          <div className="flex flex-wrap gap-1.5">
            {(['ALL', ...achievementCategories] as const).map((cat) => (
              <Button
                key={cat}
                type="button"
                size="sm"
                variant={achCat === cat ? 'default' : 'outline'}
                onClick={() => setAchCat(cat)}
              >
                {t(`rewardsHub.categories.${cat}`, { defaultValue: cat })}
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
          ) : achievements.length === 0 ? (
            <Empty className="border py-12">
              <EmptyHeader>
                <RiMedalLine className="size-8 text-muted-foreground" aria-hidden="true" />
                <EmptyTitle>{t('achievementsConsole.emptyTitle')}</EmptyTitle>
                <EmptyDescription>{t('achievementsConsole.emptyHelp')}</EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <div className="grid gap-x-6 gap-y-8 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 2xl:grid-cols-5">
              {achievements.map((ach) => {
                const badgeUrl = ach.badgeAssetId
                  ? `/api/v1/admin/content-media/${encodeURIComponent(ach.badgeAssetId)}/file`
                  : null;
                return (
                  <article
                    key={ach.id}
                    className="group relative flex flex-col items-center rounded-2xl px-3 py-4 text-center transition-colors hover:bg-muted/30"
                  >
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      className="absolute top-2 right-2 opacity-70 transition-opacity group-hover:opacity-100"
                      aria-label={t('rewardsConsole.edit')}
                      onClick={() => openEditAchievement(ach)}
                    >
                      <RiEditLine className="size-4" aria-hidden="true" />
                    </Button>

                    <AuthenticatedImage
                      src={badgeUrl}
                      alt={ach.title}
                      className="size-28 sm:size-32 object-contain drop-shadow-md transition-transform duration-200 group-hover:scale-105"
                      fallback={
                        <RiAwardLine
                          className="size-16 text-muted-foreground"
                          aria-hidden="true"
                        />
                      }
                    />

                    <div className="mt-3 flex flex-wrap items-center justify-center gap-1.5">
                      <AchievementTierBadge tier={ach.badgeTier} className="text-[11px]" />
                      <Badge variant={statusBadgeVariant(ach.enabled ? 'ENABLED' : 'DISABLED')} className="text-[11px]">
                        {t(ach.enabled ? 'rewardsConsole.enabled' : 'rewardsConsole.disabled')}
                      </Badge>
                    </div>

                    <h3 className="mt-2 text-base font-semibold leading-snug">{ach.title}</h3>
                    <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                      {ach.description}
                    </p>

                    <span className="mt-2 text-xs font-medium text-muted-foreground tabular-nums">
                      {t(`rewardsHub.metrics.${ach.metric}`, { defaultValue: ach.metric })} ≥{' '}
                      {ach.target}
                    </span>

                    <div className="mt-2 inline-flex items-center gap-1.5">
                      <RewardPill kind="xp" value={ach.xpReward} />
                      <RewardPill kind="diamond" value={ach.diamondReward} />
                    </div>
                  </article>
                );
              })}
            </div>
          )}
        </TabsContent>

        <TabsContent value="policy" className="mt-4">
          <RewardPolicyPanel />
        </TabsContent>
      </Tabs>

      <Dialog open={missionDialogOpen} onOpenChange={setMissionDialogOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {missionForm.id
                ? t('rewardsConsole.editMissionTitle')
                : t('rewardsConsole.addMission')}
            </DialogTitle>
            <DialogDescription>{t('rewardsConsole.missionDialogHelp')}</DialogDescription>
          </DialogHeader>
          <CatalogError error={saveMissionMutation.error} />
          <form
            id="mission-form"
            className="grid gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              saveMissionMutation.mutate(missionForm);
            }}
          >
            <div className="grid gap-1">
              <Label htmlFor="mission-code">{t('rewardsConsole.columns.code')}</Label>
              <Input
                id="mission-code"
                required
                className="font-mono text-xs"
                value={missionForm.code}
                onChange={(e) => setMissionForm({ ...missionForm, code: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label htmlFor="mission-title">{t('rewardsConsole.columns.title')}</Label>
              <Input
                id="mission-title"
                required
                value={missionForm.title}
                onChange={(e) => setMissionForm({ ...missionForm, title: e.target.value })}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.metric')}</Label>
                <Select
                  value={missionForm.metric}
                  onValueChange={(v) => setMissionForm({ ...missionForm, metric: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {missionMetrics.map((m) => (
                      <SelectItem key={m} value={m}>
                        {t(`rewardsHub.metrics.${m}`, { defaultValue: m })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.recurrence')}</Label>
                <Select
                  value={missionForm.recurrence}
                  onValueChange={(v) => setMissionForm({ ...missionForm, recurrence: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {missionRecurrences.map((r) => (
                      <SelectItem key={r} value={r}>
                        {t(`rewardsHub.recurrences.${r}`, { defaultValue: r })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('missionsConsole.category')}</Label>
                <Select
                  value={missionForm.category}
                  onValueChange={(v) => setMissionForm({ ...missionForm, category: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {missionCategories.map((c) => (
                      <SelectItem key={c} value={c}>
                        {t(`rewardsHub.categories.${c}`, { defaultValue: c })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('missionsConsole.difficulty')}</Label>
                <Select
                  value={missionForm.difficulty}
                  onValueChange={(v) => setMissionForm({ ...missionForm, difficulty: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {missionDifficulties.map((d) => (
                      <SelectItem key={d} value={d}>
                        {t(`rewardsHub.difficulties.${d}`, { defaultValue: d })}
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
                  value={missionForm.target}
                  onChange={(e) => setMissionForm({ ...missionForm, target: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>XP</Label>
                <Input
                  type="number"
                  min={0}
                  value={missionForm.xpReward}
                  onChange={(e) => setMissionForm({ ...missionForm, xpReward: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('rewardsHub.diamondsLabel')}</Label>
                <Input
                  type="number"
                  min={0}
                  value={missionForm.diamondReward}
                  onChange={(e) =>
                    setMissionForm({ ...missionForm, diamondReward: e.target.value })
                  }
                />
              </div>
            </div>
            <label className="flex items-center justify-between rounded-xl border bg-muted/20 px-3 py-2 text-sm">
              <span>{t('rewardsConsole.enabled')}</span>
              <Switch
                checked={missionForm.enabled}
                onCheckedChange={(checked) => setMissionForm({ ...missionForm, enabled: checked })}
              />
            </label>
          </form>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setMissionDialogOpen(false)}>
              {t('rewardsConsole.cancel')}
            </Button>
            <Button
              type="submit"
              form="mission-form"
              disabled={saveMissionMutation.isPending}
            >
              {t('rewardsConsole.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={achDialogOpen} onOpenChange={setAchDialogOpen}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {achForm.id
                ? t('rewardsConsole.editAchievementTitle')
                : t('rewardsConsole.addAchievement')}
            </DialogTitle>
            <DialogDescription>{t('rewardsConsole.achievementDialogHelp')}</DialogDescription>
          </DialogHeader>
          <CatalogError error={saveAchMutation.error} />
          <form
            id="achievement-form"
            className="grid gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              saveAchMutation.mutate(achForm);
            }}
          >
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.code')}</Label>
              <Input
                required
                className="font-mono text-xs"
                value={achForm.code}
                onChange={(e) => setAchForm({ ...achForm, code: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.title')}</Label>
              <Input
                required
                value={achForm.title}
                onChange={(e) => setAchForm({ ...achForm, title: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.description')}</Label>
              <Textarea
                rows={2}
                value={achForm.description}
                onChange={(e) => setAchForm({ ...achForm, description: e.target.value })}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.metric')}</Label>
                <Select
                  value={achForm.metric}
                  onValueChange={(v) => setAchForm({ ...achForm, metric: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {achievementMetrics.map((m) => (
                      <SelectItem key={m} value={m}>
                        {t(`rewardsHub.metrics.${m}`, { defaultValue: m })}
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
                  value={achForm.target}
                  onChange={(e) => setAchForm({ ...achForm, target: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('achievementsConsole.tier')}</Label>
                <Select
                  value={achForm.badgeTier}
                  onValueChange={(v) => setAchForm({ ...achForm, badgeTier: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {badgeTiers.map((tier) => (
                      <SelectItem key={tier} value={tier}>
                        {t(`rewardsHub.tiers.${tier}`, { defaultValue: tier })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('missionsConsole.category')}</Label>
                <Select
                  value={achForm.category}
                  onValueChange={(v) => setAchForm({ ...achForm, category: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {achievementCategories.map((cat) => (
                      <SelectItem key={cat} value={cat}>
                        {t(`rewardsHub.categories.${cat}`, { defaultValue: cat })}
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
                  value={achForm.xpReward}
                  onChange={(e) => setAchForm({ ...achForm, xpReward: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('rewardsHub.diamondsLabel')}</Label>
                <Input
                  type="number"
                  min={0}
                  value={achForm.diamondReward}
                  onChange={(e) => setAchForm({ ...achForm, diamondReward: e.target.value })}
                />
              </div>
            </div>

            <ImageAssetEditor
              mode="asset"
              aspect={1}
              outputWidth={192}
              outputHeight={192}
              value={achForm.badgeAssetId}
              onChange={(badgeAssetId) => setAchForm({ ...achForm, badgeAssetId })}
              label={t('achievementsConsole.badgeAsset')}
              alt={achForm.title || 'Badge'}
            />

            <label className="flex items-center justify-between rounded-xl border bg-muted/20 px-3 py-2 text-sm">
              <span>{t('rewardsConsole.enabled')}</span>
              <Switch
                checked={achForm.enabled}
                onCheckedChange={(checked) => setAchForm({ ...achForm, enabled: checked })}
              />
            </label>
          </form>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setAchDialogOpen(false)}>
              {t('rewardsConsole.cancel')}
            </Button>
            <Button type="submit" form="achievement-form" disabled={saveAchMutation.isPending}>
              {t('rewardsConsole.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
