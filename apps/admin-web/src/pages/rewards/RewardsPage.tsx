import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiAddLine, RiEditLine } from '@remixicon/react';
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
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import {
  achievementMetrics,
  createAdminAchievement,
  createAdminMission,
  fetchAdminAchievements,
  fetchAdminMissions,
  fetchEntitlementAudit,
  fetchEntitlementFeatures,
  fetchEntitlementQuotas,
  fetchRewardPolicy,
  missionMetrics,
  missionRecurrences,
  quotaLimitTypes,
  quotaPeriodUnits,
  updateAdminAchievement,
  updateAdminMission,
  updateEntitlementQuota,
  updateRewardPolicy,
  type AdminAchievementDefinition,
  type AdminMissionDefinition,
  type QuotaPolicy,
  type RewardPolicyConfig,
} from '@/pages/overview/admin-ops.api';

type MissionFormState = {
  id: string | null;
  code: string;
  title: string;
  metric: string;
  target: string;
  xpReward: string;
  diamondReward: string;
  recurrence: string;
  ruleVersion: string;
  enabled: boolean;
};

type AchievementFormState = {
  id: string | null;
  code: string;
  title: string;
  description: string;
  metric: string;
  target: string;
  xpReward: string;
  diamondReward: string;
  ruleVersion: string;
  enabled: boolean;
};

const emptyMission = (): MissionFormState => ({
  id: null,
  code: '',
  title: '',
  metric: 'LESSON_COMPLETED',
  target: '1',
  xpReward: '20',
  diamondReward: '2',
  recurrence: 'DAILY',
  ruleVersion: 'dev-v1',
  enabled: true,
});

const emptyAchievement = (): AchievementFormState => ({
  id: null,
  code: '',
  title: '',
  description: '',
  metric: 'DISTINCT_LESSON_COMPLETED',
  target: '5',
  xpReward: '50',
  diamondReward: '10',
  ruleVersion: 'dev-v1',
  enabled: true,
});

export function RewardsPage() {
  const { t } = useTranslation('admin');
  const queryClient = useQueryClient();

  const [missionDialogOpen, setMissionDialogOpen] = useState(false);
  const [missionForm, setMissionForm] = useState<MissionFormState>(emptyMission);
  const [achievementDialogOpen, setAchievementDialogOpen] = useState(false);
  const [achievementForm, setAchievementForm] = useState<AchievementFormState>(emptyAchievement);
  const [policyDraft, setPolicyDraft] = useState<RewardPolicyConfig | null>(null);
  const [quotaEditing, setQuotaEditing] = useState<QuotaPolicy | null>(null);
  const [quotaMaxAmount, setQuotaMaxAmount] = useState('0');
  const [quotaLimitType, setQuotaLimitType] = useState('PERIOD_USAGE');
  const [quotaPeriodUnit, setQuotaPeriodUnit] = useState('DAY');
  const [auditFeatureFilter, setAuditFeatureFilter] = useState('ALL');

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
    queryFn: async ({ signal }) => {
      const data = await fetchRewardPolicy(signal);
      setPolicyDraft((current) => current ?? data);
      return data;
    },
  });
  const featuresQuery = useQuery({
    queryKey: ['admin-entitlement-features'],
    queryFn: ({ signal }) => fetchEntitlementFeatures(signal),
  });
  const quotasQuery = useQuery({
    queryKey: ['admin-entitlement-quotas'],
    queryFn: ({ signal }) => fetchEntitlementQuotas(signal),
  });
  const auditQuery = useQuery({
    queryKey: ['admin-entitlement-audit', auditFeatureFilter],
    queryFn: ({ signal }) =>
      fetchEntitlementAudit(
        {
          featureKey: auditFeatureFilter === 'ALL' ? undefined : auditFeatureFilter,
          limit: 50,
        },
        signal,
      ),
  });

  const missionMutation = useMutation({
    mutationFn: (form: MissionFormState) => {
      const payload = {
        code: form.code.trim(),
        title: form.title.trim(),
        metric: form.metric,
        target: Number(form.target) || 1,
        xpReward: Number(form.xpReward) || 0,
        diamondReward: Number(form.diamondReward) || 0,
        recurrence: form.recurrence,
        ruleVersion: form.ruleVersion.trim() || 'dev-v1',
        enabled: form.enabled,
      };
      return form.id ? updateAdminMission(form.id, payload) : createAdminMission(payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-gamification-missions'] });
      setMissionDialogOpen(false);
    },
  });

  const achievementMutation = useMutation({
    mutationFn: (form: AchievementFormState) => {
      const payload = {
        code: form.code.trim(),
        title: form.title.trim(),
        description: form.description.trim(),
        metric: form.metric,
        target: Number(form.target) || 1,
        xpReward: Number(form.xpReward) || 0,
        diamondReward: Number(form.diamondReward) || 0,
        ruleVersion: form.ruleVersion.trim() || 'dev-v1',
        enabled: form.enabled,
      };
      return form.id ? updateAdminAchievement(form.id, payload) : createAdminAchievement(payload);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-gamification-achievements'] });
      setAchievementDialogOpen(false);
    },
  });

  const policyMutation = useMutation({
    mutationFn: (next: RewardPolicyConfig) => updateRewardPolicy(next),
    onSuccess: async (saved) => {
      setPolicyDraft(saved);
      await queryClient.invalidateQueries({ queryKey: ['admin-gamification-policy'] });
    },
  });

  const quotaMutation = useMutation({
    mutationFn: (item: QuotaPolicy) =>
      updateEntitlementQuota({
        featureKey: item.featureKey,
        tier: item.tier,
        limitType: quotaLimitType,
        periodUnit: quotaPeriodUnit,
        maxAmount: Math.max(0, Number(quotaMaxAmount) || 0),
        policyVersion: item.policyVersion,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-entitlement-quotas'] });
      setQuotaEditing(null);
    },
  });

  function openMissionEdit(item: AdminMissionDefinition) {
    setMissionForm({
      id: item.id,
      code: item.code,
      title: item.title,
      metric: item.metric,
      target: String(item.target),
      xpReward: String(item.xpReward),
      diamondReward: String(item.diamondReward),
      recurrence: item.recurrence,
      ruleVersion: item.ruleVersion,
      enabled: item.enabled,
    });
    setMissionDialogOpen(true);
  }

  function openAchievementEdit(item: AdminAchievementDefinition) {
    setAchievementForm({
      id: item.id,
      code: item.code,
      title: item.title,
      description: item.description,
      metric: item.metric,
      target: String(item.target),
      xpReward: String(item.xpReward),
      diamondReward: String(item.diamondReward),
      ruleVersion: item.ruleVersion,
      enabled: item.enabled,
    });
    setAchievementDialogOpen(true);
  }

  const activePolicy = policyDraft ?? policyQuery.data;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{t('rewardsConsole.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('rewardsConsole.subtitle')}</p>
      </div>

      <Tabs defaultValue="missions">
        <TabsList>
          <TabsTrigger value="missions">{t('rewardsConsole.tabs.missions')}</TabsTrigger>
          <TabsTrigger value="achievements">{t('rewardsConsole.tabs.achievements')}</TabsTrigger>
          <TabsTrigger value="policy">{t('rewardsConsole.tabs.policy')}</TabsTrigger>
          <TabsTrigger value="quotas">{t('rewardsConsole.tabs.quotas')}</TabsTrigger>
          <TabsTrigger value="audit">{t('rewardsConsole.tabs.audit')}</TabsTrigger>
        </TabsList>

        <TabsContent value="missions" className="mt-4">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between gap-3">
              <div>
                <CardTitle>{t('rewardsConsole.missionsTitle')}</CardTitle>
                <CardDescription>{t('rewardsConsole.missionsHelp')}</CardDescription>
              </div>
              <Button
                type="button"
                size="sm"
                onClick={() => {
                  setMissionForm(emptyMission());
                  setMissionDialogOpen(true);
                }}
              >
                <RiAddLine aria-hidden="true" />
                {t('rewardsConsole.addMission')}
              </Button>
            </CardHeader>
            <CardContent>
              {missionsQuery.error ? (
                <CatalogError error={missionsQuery.error} retry={() => void missionsQuery.refetch()} />
              ) : missionsQuery.isPending ? (
                <Skeleton className="h-32 w-full" />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('rewardsConsole.columns.code')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.title')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.metric')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.target')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.rewards')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.recurrence')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.status')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.actions')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(missionsQuery.data ?? []).map((mission) => (
                      <TableRow key={mission.id}>
                        <TableCell className="font-mono text-xs">{mission.code}</TableCell>
                        <TableCell className="font-medium">{mission.title}</TableCell>
                        <TableCell>{mission.metric}</TableCell>
                        <TableCell className="text-right tabular-nums">{mission.target}</TableCell>
                        <TableCell className="text-right tabular-nums">
                          {mission.xpReward} XP · {mission.diamondReward} 💎
                        </TableCell>
                        <TableCell>{mission.recurrence}</TableCell>
                        <TableCell>
                          <Badge variant={mission.enabled ? 'default' : 'outline'}>
                            {mission.enabled
                              ? t('rewardsConsole.enabled')
                              : t('rewardsConsole.disabled')}
                          </Badge>
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            onClick={() => openMissionEdit(mission)}
                          >
                            <RiEditLine aria-hidden="true" />
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

        <TabsContent value="achievements" className="mt-4">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between gap-3">
              <div>
                <CardTitle>{t('rewardsConsole.achievementsTitle')}</CardTitle>
                <CardDescription>{t('rewardsConsole.achievementsHelp')}</CardDescription>
              </div>
              <Button
                type="button"
                size="sm"
                onClick={() => {
                  setAchievementForm(emptyAchievement());
                  setAchievementDialogOpen(true);
                }}
              >
                <RiAddLine aria-hidden="true" />
                {t('rewardsConsole.addAchievement')}
              </Button>
            </CardHeader>
            <CardContent>
              {achievementsQuery.error ? (
                <CatalogError
                  error={achievementsQuery.error}
                  retry={() => void achievementsQuery.refetch()}
                />
              ) : achievementsQuery.isPending ? (
                <Skeleton className="h-32 w-full" />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('rewardsConsole.columns.code')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.title')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.metric')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.target')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.rewards')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.status')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.actions')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(achievementsQuery.data ?? []).map((item) => (
                      <TableRow key={item.id}>
                        <TableCell className="font-mono text-xs">{item.code}</TableCell>
                        <TableCell>
                          <p className="font-medium">{item.title}</p>
                          <p className="text-xs text-muted-foreground">{item.description}</p>
                        </TableCell>
                        <TableCell>{item.metric}</TableCell>
                        <TableCell className="text-right tabular-nums">{item.target}</TableCell>
                        <TableCell className="text-right tabular-nums">
                          {item.xpReward} XP · {item.diamondReward} 💎
                        </TableCell>
                        <TableCell>
                          <Badge variant={item.enabled ? 'default' : 'outline'}>
                            {item.enabled
                              ? t('rewardsConsole.enabled')
                              : t('rewardsConsole.disabled')}
                          </Badge>
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            onClick={() => openAchievementEdit(item)}
                          >
                            <RiEditLine aria-hidden="true" />
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

        <TabsContent value="policy" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>{t('rewardsConsole.policyTitle')}</CardTitle>
              <CardDescription>{t('rewardsConsole.policyHelp')}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {policyQuery.error ? (
                <CatalogError error={policyQuery.error} retry={() => void policyQuery.refetch()} />
              ) : policyQuery.isPending || !activePolicy ? (
                <Skeleton className="h-32 w-full" />
              ) : (
                <form
                  className="grid max-w-xl gap-4"
                  onSubmit={(event) => {
                    event.preventDefault();
                    policyMutation.mutate(activePolicy);
                  }}
                >
                  <div className="grid gap-1.5">
                    <Label htmlFor="reward-rule-version">{t('rewardsConsole.ruleVersion')}</Label>
                    <Input
                      id="reward-rule-version"
                      value={activePolicy.ruleVersion}
                      onChange={(event) =>
                        setPolicyDraft({ ...activePolicy, ruleVersion: event.target.value })
                      }
                    />
                  </div>
                  <div className="grid gap-1.5">
                    <Label htmlFor="reward-xp-level">{t('rewardsConsole.xpPerLevel')}</Label>
                    <Input
                      id="reward-xp-level"
                      type="number"
                      min={1}
                      value={activePolicy.xpPerLevel}
                      onChange={(event) =>
                        setPolicyDraft({
                          ...activePolicy,
                          xpPerLevel: Math.max(1, Number(event.target.value) || 100),
                        })
                      }
                    />
                  </div>
                  <div className="flex items-center justify-between gap-3 rounded-xl border p-3">
                    <div>
                      <Label htmlFor="direct-diamonds-switch">
                        {t('rewardsConsole.directDiamonds')}
                      </Label>
                      <p className="text-xs text-muted-foreground">
                        {t('rewardsConsole.directDiamondsHelp')}
                      </p>
                    </div>
                    <Switch
                      id="direct-diamonds-switch"
                      checked={activePolicy.directLessonActivityDiamondsEnabled}
                      onCheckedChange={(checked) =>
                        setPolicyDraft({
                          ...activePolicy,
                          directLessonActivityDiamondsEnabled: checked,
                        })
                      }
                    />
                  </div>
                  {policyMutation.error ? <CatalogError error={policyMutation.error} /> : null}
                  <div>
                    <Button type="submit" disabled={policyMutation.isPending}>
                      {policyMutation.isPending
                        ? t('rewardsConsole.saving')
                        : t('rewardsConsole.savePolicy')}
                    </Button>
                  </div>
                </form>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="quotas" className="mt-4 space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>{t('rewardsConsole.featuresTitle')}</CardTitle>
              <CardDescription>{t('rewardsConsole.featuresHelp')}</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {(featuresQuery.data ?? []).map((feature) => (
                <div key={feature.featureKey} className="rounded-xl border p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs font-medium">{feature.featureKey}</span>
                    <Badge variant="outline">{feature.status}</Badge>
                  </div>
                  <p className="mt-1 text-sm font-medium">{feature.displayName}</p>
                  <p className="mt-0.5 text-xs text-muted-foreground">{feature.description}</p>
                </div>
              ))}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>{t('rewardsConsole.quotasTitle')}</CardTitle>
              <CardDescription>{t('rewardsConsole.quotasHelp')}</CardDescription>
            </CardHeader>
            <CardContent>
              {quotasQuery.error ? (
                <CatalogError error={quotasQuery.error} retry={() => void quotasQuery.refetch()} />
              ) : quotasQuery.isPending ? (
                <Skeleton className="h-32 w-full" />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('rewardsConsole.columns.feature')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.tier')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.limitType')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.period')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.maxAmount')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.actions')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(quotasQuery.data ?? []).map((policy) => (
                      <TableRow key={`${policy.featureKey}:${policy.tier}`}>
                        <TableCell className="font-mono text-xs">{policy.featureKey}</TableCell>
                        <TableCell>
                          <Badge variant={policy.tier === 'PLUS' ? 'default' : 'outline'}>
                            {policy.tier}
                          </Badge>
                        </TableCell>
                        <TableCell>{policy.limitType}</TableCell>
                        <TableCell>{policy.periodUnit}</TableCell>
                        <TableCell className="text-right tabular-nums">{policy.maxAmount}</TableCell>
                        <TableCell className="text-right">
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            onClick={() => {
                              setQuotaEditing(policy);
                              setQuotaMaxAmount(String(policy.maxAmount));
                              setQuotaLimitType(policy.limitType);
                              setQuotaPeriodUnit(policy.periodUnit);
                            }}
                          >
                            <RiEditLine aria-hidden="true" />
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

        <TabsContent value="audit" className="mt-4">
          <Card>
            <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <div>
                <CardTitle>{t('rewardsConsole.auditTitle')}</CardTitle>
                <CardDescription>{t('rewardsConsole.auditHelp')}</CardDescription>
              </div>
              <Select value={auditFeatureFilter} onValueChange={setAuditFeatureFilter}>
                <SelectTrigger className="w-56">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    <SelectItem value="ALL">{t('rewardsConsole.allFeatures')}</SelectItem>
                    {(featuresQuery.data ?? []).map((f) => (
                      <SelectItem key={f.featureKey} value={f.featureKey}>
                        {f.featureKey}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </CardHeader>
            <CardContent>
              {auditQuery.error ? (
                <CatalogError error={auditQuery.error} retry={() => void auditQuery.refetch()} />
              ) : auditQuery.isPending ? (
                <Skeleton className="h-32 w-full" />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('rewardsConsole.columns.occurredAt')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.feature')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.decision')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.learnerId')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(auditQuery.data ?? []).map((entry) => (
                      <TableRow key={entry.id}>
                        <TableCell className="tabular-nums text-xs">
                          {new Date(entry.occurredAt).toLocaleString()}
                        </TableCell>
                        <TableCell className="font-mono text-xs">{entry.featureKey}</TableCell>
                        <TableCell>
                          <Badge variant="outline">{entry.decision}</Badge>
                        </TableCell>
                        <TableCell className="font-mono text-xs">{entry.userId}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <Dialog open={missionDialogOpen} onOpenChange={setMissionDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {missionForm.id
                ? t('rewardsConsole.editMissionTitle')
                : t('rewardsConsole.addMission')}
            </DialogTitle>
            <DialogDescription>{t('rewardsConsole.missionDialogHelp')}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="grid gap-1">
              <Label htmlFor="mission-code">{t('rewardsConsole.columns.code')}</Label>
              <Input
                id="mission-code"
                value={missionForm.code}
                onChange={(e) => setMissionForm({ ...missionForm, code: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label htmlFor="mission-title">{t('rewardsConsole.columns.title')}</Label>
              <Input
                id="mission-title"
                value={missionForm.title}
                onChange={(e) => setMissionForm({ ...missionForm, title: e.target.value })}
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.metric')}</Label>
                <Select
                  value={missionForm.metric}
                  onValueChange={(value) => setMissionForm({ ...missionForm, metric: value })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {missionMetrics.map((m) => (
                        <SelectItem key={m} value={m}>
                          {m}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.recurrence')}</Label>
                <Select
                  value={missionForm.recurrence}
                  onValueChange={(value) => setMissionForm({ ...missionForm, recurrence: value })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {missionRecurrences.map((r) => (
                        <SelectItem key={r} value={r}>
                          {r}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div className="grid gap-1">
                <Label htmlFor="mission-target">{t('rewardsConsole.columns.target')}</Label>
                <Input
                  id="mission-target"
                  type="number"
                  min={1}
                  value={missionForm.target}
                  onChange={(e) => setMissionForm({ ...missionForm, target: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="mission-xp">XP</Label>
                <Input
                  id="mission-xp"
                  type="number"
                  min={0}
                  value={missionForm.xpReward}
                  onChange={(e) => setMissionForm({ ...missionForm, xpReward: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="mission-diamonds">💎</Label>
                <Input
                  id="mission-diamonds"
                  type="number"
                  min={0}
                  value={missionForm.diamondReward}
                  onChange={(e) =>
                    setMissionForm({ ...missionForm, diamondReward: e.target.value })
                  }
                />
              </div>
            </div>
            <div className="flex items-center justify-between rounded-xl border p-3">
              <Label htmlFor="mission-enabled">{t('rewardsConsole.enabled')}</Label>
              <Switch
                id="mission-enabled"
                checked={missionForm.enabled}
                onCheckedChange={(checked) => setMissionForm({ ...missionForm, enabled: checked })}
              />
            </div>
            {missionMutation.error ? <CatalogError error={missionMutation.error} /> : null}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setMissionDialogOpen(false)}>
              {t('rewardsConsole.cancel')}
            </Button>
            <Button
              type="button"
              disabled={missionMutation.isPending}
              onClick={() => missionMutation.mutate(missionForm)}
            >
              {t('rewardsConsole.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={achievementDialogOpen} onOpenChange={setAchievementDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {achievementForm.id
                ? t('rewardsConsole.editAchievementTitle')
                : t('rewardsConsole.addAchievement')}
            </DialogTitle>
            <DialogDescription>{t('rewardsConsole.achievementDialogHelp')}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="grid gap-1">
              <Label htmlFor="ach-code">{t('rewardsConsole.columns.code')}</Label>
              <Input
                id="ach-code"
                value={achievementForm.code}
                onChange={(e) => setAchievementForm({ ...achievementForm, code: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label htmlFor="ach-title">{t('rewardsConsole.columns.title')}</Label>
              <Input
                id="ach-title"
                value={achievementForm.title}
                onChange={(e) => setAchievementForm({ ...achievementForm, title: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label htmlFor="ach-description">{t('rewardsConsole.description')}</Label>
              <Textarea
                id="ach-description"
                rows={2}
                value={achievementForm.description}
                onChange={(e) =>
                  setAchievementForm({ ...achievementForm, description: e.target.value })
                }
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.metric')}</Label>
              <Select
                value={achievementForm.metric}
                onValueChange={(value) => setAchievementForm({ ...achievementForm, metric: value })}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {achievementMetrics.map((m) => (
                      <SelectItem key={m} value={m}>
                        {m}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
            <div className="grid grid-cols-3 gap-3">
              <div className="grid gap-1">
                <Label htmlFor="ach-target">{t('rewardsConsole.columns.target')}</Label>
                <Input
                  id="ach-target"
                  type="number"
                  min={1}
                  value={achievementForm.target}
                  onChange={(e) =>
                    setAchievementForm({ ...achievementForm, target: e.target.value })
                  }
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="ach-xp">XP</Label>
                <Input
                  id="ach-xp"
                  type="number"
                  min={0}
                  value={achievementForm.xpReward}
                  onChange={(e) =>
                    setAchievementForm({ ...achievementForm, xpReward: e.target.value })
                  }
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="ach-diamonds">💎</Label>
                <Input
                  id="ach-diamonds"
                  type="number"
                  min={0}
                  value={achievementForm.diamondReward}
                  onChange={(e) =>
                    setAchievementForm({ ...achievementForm, diamondReward: e.target.value })
                  }
                />
              </div>
            </div>
            <div className="flex items-center justify-between rounded-xl border p-3">
              <Label htmlFor="ach-enabled">{t('rewardsConsole.enabled')}</Label>
              <Switch
                id="ach-enabled"
                checked={achievementForm.enabled}
                onCheckedChange={(checked) =>
                  setAchievementForm({ ...achievementForm, enabled: checked })
                }
              />
            </div>
            {achievementMutation.error ? <CatalogError error={achievementMutation.error} /> : null}
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setAchievementDialogOpen(false)}
            >
              {t('rewardsConsole.cancel')}
            </Button>
            <Button
              type="button"
              disabled={achievementMutation.isPending}
              onClick={() => achievementMutation.mutate(achievementForm)}
            >
              {t('rewardsConsole.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(quotaEditing)} onOpenChange={(open) => !open && setQuotaEditing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('rewardsConsole.editQuotaTitle')}</DialogTitle>
            <DialogDescription>
              {quotaEditing ? `${quotaEditing.featureKey} · ${quotaEditing.tier}` : ''}
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.limitType')}</Label>
              <Select value={quotaLimitType} onValueChange={setQuotaLimitType}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {quotaLimitTypes.map((lt) => (
                      <SelectItem key={lt} value={lt}>
                        {lt}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.period')}</Label>
              <Select value={quotaPeriodUnit} onValueChange={setQuotaPeriodUnit}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {quotaPeriodUnits.map((pu) => (
                      <SelectItem key={pu} value={pu}>
                        {pu}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-1">
              <Label htmlFor="quota-max-amount">{t('rewardsConsole.columns.maxAmount')}</Label>
              <Input
                id="quota-max-amount"
                type="number"
                min={0}
                value={quotaMaxAmount}
                onChange={(e) => setQuotaMaxAmount(e.target.value)}
              />
            </div>
            {quotaMutation.error ? <CatalogError error={quotaMutation.error} /> : null}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setQuotaEditing(null)}>
              {t('rewardsConsole.cancel')}
            </Button>
            <Button
              type="button"
              disabled={quotaMutation.isPending || !quotaEditing}
              onClick={() => quotaEditing && quotaMutation.mutate(quotaEditing)}
            >
              {t('rewardsConsole.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
