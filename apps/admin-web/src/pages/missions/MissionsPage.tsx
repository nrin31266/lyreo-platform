import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiAddLine, RiEditLine, RiFocus3Line } from '@remixicon/react';
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
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import {
  createAdminMission,
  fetchAdminMissions,
  fetchRewardPolicy,
  missionDifficulties,
  missionMetrics,
  missionRecurrences,
  updateAdminMission,
  updateRewardPolicy,
  type AdminMissionDefinition,
  type RewardPolicyConfig,
} from '@/pages/overview/admin-ops.api';

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
  ruleVersion: 'dev-v1',
  enabled: true,
});

const missionCategories = ['GENERAL', 'VOCAB', 'LESSON', 'GRAMMAR', 'TOEIC'] as const;

export function MissionsPage() {
  const { t } = useTranslation('admin');
  const queryClient = useQueryClient();

  const [categoryFilter, setCategoryFilter] = useState('ALL');
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<MissionForm>(emptyMission);
  const [policyDraft, setPolicyDraft] = useState<RewardPolicyConfig | null>(null);

  const missionsQuery = useQuery({
    queryKey: ['admin-gamification-missions'],
    queryFn: ({ signal }) => fetchAdminMissions(signal),
  });

  const policyQuery = useQuery({
    queryKey: ['admin-gamification-policy'],
    queryFn: async ({ signal }) => {
      const data = await fetchRewardPolicy(signal);
      setPolicyDraft((current) => current ?? data);
      return data;
    },
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
        ruleVersion: state.ruleVersion.trim() || 'dev-v1',
        enabled: state.enabled,
      };
      return state.id ? updateAdminMission(state.id, payload) : createAdminMission(payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-gamification-missions'] });
      setDialogOpen(false);
    },
  });

  const policyMutation = useMutation({
    mutationFn: (cfg: RewardPolicyConfig) => updateRewardPolicy(cfg),
    onSuccess: (saved) => {
      setPolicyDraft(saved);
      void queryClient.invalidateQueries({ queryKey: ['admin-gamification-policy'] });
    },
  });

  function openNew() {
    setForm(emptyMission());
    saveMissionMutation.reset();
    setDialogOpen(true);
  }

  function openEdit(m: AdminMissionDefinition) {
    setForm({
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
      ruleVersion: m.ruleVersion || 'dev-v1',
      enabled: m.enabled,
    });
    saveMissionMutation.reset();
    setDialogOpen(true);
  }

  const missions = (missionsQuery.data ?? []).filter(
    (m) => categoryFilter === 'ALL' || (m.category || 'GENERAL') === categoryFilter,
  );

  return (
    <section className="mx-auto grid w-full max-w-7xl gap-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">{t('missionsConsole.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('missionsConsole.subtitle')}</p>
        </div>
        <Button type="button" onClick={openNew}>
          <RiAddLine data-icon="inline-start" aria-hidden="true" />
          {t('rewardsConsole.addMission')}
        </Button>
      </header>

      <div className="flex flex-wrap gap-2">
        {(['ALL', ...missionCategories] as const).map((cat) => (
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

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <RiFocus3Line className="size-4 text-muted-foreground" aria-hidden="true" />
            {t('rewardsConsole.missionsTitle')}
          </CardTitle>
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
                  <TableHead className="text-right">{t('rewardsConsole.columns.target')}</TableHead>
                  <TableHead className="text-right">{t('rewardsConsole.columns.rewards')}</TableHead>
                  <TableHead>{t('rewardsConsole.columns.recurrence')}</TableHead>
                  <TableHead>{t('rewardsConsole.columns.status')}</TableHead>
                  <TableHead className="text-right">{t('rewardsConsole.columns.actions')}</TableHead>
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
                          {m.category || 'GENERAL'} · {m.metric}
                        </p>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={
                          m.difficulty === 'HARD'
                            ? 'destructive'
                            : m.difficulty === 'EASY'
                              ? 'success'
                              : 'secondary'
                        }
                      >
                        {m.difficulty || 'NORMAL'}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{m.target}</TableCell>
                    <TableCell className="text-right text-xs tabular-nums">
                      +{m.xpReward} XP · +{m.diamondReward} ◆
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline">{m.recurrence}</Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant={m.enabled ? 'success' : 'secondary'}>
                        {t(m.enabled ? 'rewardsConsole.enabled' : 'rewardsConsole.disabled')}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        onClick={() => openEdit(m)}
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

      <Card>
        <CardHeader>
          <CardTitle>{t('rewardsConsole.policyTitle')}</CardTitle>
          <CardDescription>{t('rewardsConsole.policyHelp')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <CatalogError error={policyQuery.error || policyMutation.error} />
          {policyDraft ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.ruleVersion')}</Label>
                <Input
                  value={policyDraft.ruleVersion}
                  onChange={(e) =>
                    setPolicyDraft({ ...policyDraft, ruleVersion: e.target.value })
                  }
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.xpPerLevel')}</Label>
                <Input
                  type="number"
                  min={10}
                  value={policyDraft.xpPerLevel}
                  onChange={(e) =>
                    setPolicyDraft({
                      ...policyDraft,
                      xpPerLevel: Math.max(10, Number(e.target.value) || 100),
                    })
                  }
                />
              </div>
              <label className="sm:col-span-2 flex items-center justify-between rounded-xl border bg-muted/20 px-4 py-3 text-sm">
                <div>
                  <p className="font-medium">{t('rewardsConsole.directDiamonds')}</p>
                  <p className="text-xs text-muted-foreground">
                    {t('rewardsConsole.directDiamondsHelp')}
                  </p>
                </div>
                <Switch
                  checked={policyDraft.directLessonActivityDiamondsEnabled}
                  onCheckedChange={(checked) =>
                    setPolicyDraft({
                      ...policyDraft,
                      directLessonActivityDiamondsEnabled: checked,
                    })
                  }
                />
              </label>
              <div className="sm:col-span-2 flex justify-end">
                <Button
                  type="button"
                  disabled={policyMutation.isPending}
                  onClick={() => policyMutation.mutate(policyDraft)}
                >
                  {t(
                    policyMutation.isPending
                      ? 'rewardsConsole.saving'
                      : 'rewardsConsole.savePolicy',
                  )}
                </Button>
              </div>
            </div>
          ) : (
            <Skeleton className="h-28 w-full" />
          )}
        </CardContent>
      </Card>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {form.id ? t('rewardsConsole.editMissionTitle') : t('rewardsConsole.addMission')}
            </DialogTitle>
            <DialogDescription>{t('rewardsConsole.missionDialogHelp')}</DialogDescription>
          </DialogHeader>
          <CatalogError error={saveMissionMutation.error} />
          <form
            id="mission-form"
            className="grid gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              saveMissionMutation.mutate(form);
            }}
          >
            <div className="grid gap-1">
              <Label htmlFor="mission-code">{t('rewardsConsole.columns.code')}</Label>
              <Input
                id="mission-code"
                required
                className="font-mono text-xs"
                value={form.code}
                onChange={(e) => setForm({ ...form, code: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label htmlFor="mission-title">{t('rewardsConsole.columns.title')}</Label>
              <Input
                id="mission-title"
                required
                value={form.title}
                onChange={(e) => setForm({ ...form, title: e.target.value })}
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
                    {missionMetrics.map((m) => (
                      <SelectItem key={m} value={m}>
                        {m}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.recurrence')}</Label>
                <Select
                  value={form.recurrence}
                  onValueChange={(v) => setForm({ ...form, recurrence: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {missionRecurrences.map((r) => (
                      <SelectItem key={r} value={r}>
                        {r}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('missionsConsole.category')}</Label>
                <Select value={form.category} onValueChange={(v) => setForm({ ...form, category: v })}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {missionCategories.map((c) => (
                      <SelectItem key={c} value={c}>
                        {c}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('missionsConsole.difficulty')}</Label>
                <Select
                  value={form.difficulty}
                  onValueChange={(v) => setForm({ ...form, difficulty: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {missionDifficulties.map((d) => (
                      <SelectItem key={d} value={d}>
                        {d}
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
            <Button type="submit" form="mission-form" disabled={saveMissionMutation.isPending}>
              {t('rewardsConsole.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
