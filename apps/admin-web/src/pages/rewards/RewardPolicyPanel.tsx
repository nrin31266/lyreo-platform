import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiAwardLine, RiSparklingLine } from '@remixicon/react';
import { RewardPill } from '@/components/RewardIcons';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import {
  fetchRewardPolicy,
  updateRewardPolicy,
  type RewardPolicyConfig,
} from '@/pages/overview/admin-ops.api';

type LevelCurveRow = {
  level: number;
  stepXp: number;
  totalXp: number;
  milestoneDiamonds: number;
  isCap: boolean;
};

function sanitizeMultiplier(raw: number | undefined): number {
  if (typeof raw !== 'number' || !Number.isFinite(raw) || raw < 1) return 1;
  return Math.min(1.25, raw);
}

export function xpForStep(
  level: number,
  baseXp: number,
  growthPerLevel: number,
  multiplier: number,
): number {
  const linear = baseXp + (level - 1) * growthPerLevel;
  return Math.max(1, Math.round(linear * Math.pow(multiplier, level - 1)));
}

export function milestoneDiamondsForLevel(
  level: number,
  interval: number,
  baseDiamonds: number,
  growthDiamonds: number,
  maxLevel: number,
): number {
  if (level <= 1 || interval <= 0 || (baseDiamonds <= 0 && growthDiamonds <= 0)) return 0;
  if (level > maxLevel) return 0;
  const isPeriodic = level % interval === 0;
  const isCap = level === maxLevel;
  if (!isPeriodic && !isCap) return 0;
  const tierIndex = Math.max(0, Math.floor(level / interval) - (isPeriodic ? 1 : 0));
  return Math.max(0, baseDiamonds) + tierIndex * Math.max(0, growthDiamonds);
}

export function computeLevelCurveRows(policy: RewardPolicyConfig): LevelCurveRow[] {
  const safeBase = Math.max(1, policy.xpPerLevel || 100);
  const safeGrowth = Math.max(0, policy.xpGrowthPerLevel ?? 15);
  const safeMult = sanitizeMultiplier(policy.xpLevelMultiplier ?? 1.02);
  const safeMax = Math.max(2, Math.min(99, policy.maxLevel ?? 99));
  const safeInterval = Math.max(0, policy.milestoneInterval ?? 5);
  const safeDiaBase = Math.max(0, policy.milestoneDiamondBase ?? 15);
  const safeDiaGrowth = Math.max(0, policy.milestoneDiamondGrowth ?? 10);

  const sortedTargets = Array.from({ length: safeMax }, (_, index) => index + 1);

  const cumulativeByLevel = new Map<number, number>();
  let runningTotal = 0;
  cumulativeByLevel.set(1, 0);
  for (let lvl = 1; lvl < safeMax; lvl++) {
    runningTotal += xpForStep(lvl, safeBase, safeGrowth, safeMult);
    cumulativeByLevel.set(lvl + 1, runningTotal);
  }

  return sortedTargets.map((lvl) => {
    const isCap = lvl >= safeMax;
    return {
      level: lvl,
      stepXp: isCap ? 0 : xpForStep(lvl, safeBase, safeGrowth, safeMult),
      totalXp: cumulativeByLevel.get(lvl) ?? 0,
      milestoneDiamonds: milestoneDiamondsForLevel(
        lvl,
        safeInterval,
        safeDiaBase,
        safeDiaGrowth,
        safeMax,
      ),
      isCap,
    };
  });
}

export function RewardPolicyPanel() {
  const { t } = useTranslation('admin');
  const queryClient = useQueryClient();
  const [policyDraft, setPolicyDraft] = useState<RewardPolicyConfig | null>(null);
  const [policySaved, setPolicySaved] = useState(false);

  const policyQuery = useQuery({
    queryKey: ['admin-gamification-policy'],
    queryFn: ({ signal }) => fetchRewardPolicy(signal),
  });

  useEffect(() => {
    if (policyQuery.data) {
      setPolicyDraft((current) => current ?? policyQuery.data);
    }
  }, [policyQuery.data]);

  const policyMutation = useMutation({
    mutationFn: (cfg: RewardPolicyConfig) => updateRewardPolicy(cfg),
    onSuccess: (saved) => {
      setPolicyDraft(saved);
      setPolicySaved(true);
      void queryClient.invalidateQueries({ queryKey: ['admin-gamification-policy'] });
    },
  });

  const rows = policyDraft ? computeLevelCurveRows(policyDraft) : [];
  const capRow = rows[rows.length - 1];
  const totalToCap = capRow?.totalXp ?? 0;
  const dailyXpScenarios = [100, 200, 350] as const;

  return (
    <div className="grid items-start gap-5 xl:grid-cols-12">
      <Card className="xl:col-span-6">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <RiSparklingLine className="size-4 text-primary" aria-hidden="true" />
            {t('rewardsConsole.policyTitle')}
          </CardTitle>
          <CardDescription>{t('rewardsConsole.policyHelp')}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <CatalogError error={policyQuery.error || policyMutation.error} />
          {policyDraft ? (
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label htmlFor="policy-rule-version">{t('rewardsConsole.ruleVersion')}</Label>
                <Input
                  id="policy-rule-version"
                  value={policyDraft.ruleVersion}
                  onChange={(e) => {
                    setPolicyDraft({ ...policyDraft, ruleVersion: e.target.value });
                    setPolicySaved(false);
                  }}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="policy-xp-base">{t('rewardsConsole.xpPerLevel')}</Label>
                <Input
                  id="policy-xp-base"
                  type="number"
                  min={10}
                  max={100000}
                  value={policyDraft.xpPerLevel}
                  onChange={(e) => {
                    setPolicyDraft({
                      ...policyDraft,
                      xpPerLevel: Math.max(10, Number(e.target.value) || 100),
                    });
                    setPolicySaved(false);
                  }}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="policy-xp-growth">{t('rewardsConsole.xpGrowthPerLevel')}</Label>
                <Input
                  id="policy-xp-growth"
                  type="number"
                  min={0}
                  max={10000}
                  value={policyDraft.xpGrowthPerLevel ?? 15}
                  onChange={(e) => {
                    const raw = Number(e.target.value);
                    setPolicyDraft({
                      ...policyDraft,
                      xpGrowthPerLevel: Number.isFinite(raw) ? Math.max(0, raw) : 15,
                    });
                    setPolicySaved(false);
                  }}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="policy-xp-multiplier">
                  {t('rewardsConsole.xpLevelMultiplier')}
                </Label>
                <Input
                  id="policy-xp-multiplier"
                  type="number"
                  step="0.01"
                  min={1}
                  max={1.25}
                  value={policyDraft.xpLevelMultiplier ?? 1.02}
                  onChange={(e) => {
                    const raw = Number(e.target.value);
                    const next = Number.isFinite(raw) ? Math.min(1.25, Math.max(1, raw)) : 1.02;
                    setPolicyDraft({
                      ...policyDraft,
                      xpLevelMultiplier: next,
                    });
                    setPolicySaved(false);
                  }}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="policy-max-level">{t('rewardsConsole.maxLevel')}</Label>
                <Input
                  id="policy-max-level"
                  type="number"
                  min={1}
                  max={99}
                  value={policyDraft.maxLevel ?? 99}
                  onChange={(e) => {
                    const raw = Math.min(99, Math.max(1, Number(e.target.value) || 99));
                    setPolicyDraft({
                      ...policyDraft,
                      maxLevel: raw,
                    });
                    setPolicySaved(false);
                  }}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="policy-milestone-interval">
                  {t('rewardsConsole.milestoneInterval')}
                </Label>
                <Input
                  id="policy-milestone-interval"
                  type="number"
                  min={0}
                  max={50}
                  value={policyDraft.milestoneInterval ?? 5}
                  onChange={(e) => {
                    const raw = Number(e.target.value);
                    setPolicyDraft({
                      ...policyDraft,
                      milestoneInterval: Number.isFinite(raw)
                        ? Math.min(50, Math.max(0, Math.trunc(raw)))
                        : 5,
                    });
                    setPolicySaved(false);
                  }}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="policy-milestone-base">
                  {t('rewardsConsole.milestoneDiamondBase')}
                </Label>
                <Input
                  id="policy-milestone-base"
                  type="number"
                  min={0}
                  max={1000}
                  value={policyDraft.milestoneDiamondBase ?? 15}
                  onChange={(e) => {
                    const raw = Number(e.target.value);
                    setPolicyDraft({
                      ...policyDraft,
                      milestoneDiamondBase: Number.isFinite(raw)
                        ? Math.max(0, Math.trunc(raw))
                        : 15,
                    });
                    setPolicySaved(false);
                  }}
                />
              </div>
              <div className="grid gap-1">
                <Label htmlFor="policy-milestone-growth">
                  {t('rewardsConsole.milestoneDiamondGrowth')}
                </Label>
                <Input
                  id="policy-milestone-growth"
                  type="number"
                  min={0}
                  max={500}
                  value={policyDraft.milestoneDiamondGrowth ?? 10}
                  onChange={(e) => {
                    const raw = Number(e.target.value);
                    setPolicyDraft({
                      ...policyDraft,
                      milestoneDiamondGrowth: Number.isFinite(raw)
                        ? Math.max(0, Math.trunc(raw))
                        : 10,
                    });
                    setPolicySaved(false);
                  }}
                />
              </div>
              <p className="sm:col-span-2 text-xs text-muted-foreground">
                {t('rewardsConsole.maxLevelHint')}
              </p>
              <label className="sm:col-span-2 flex items-center justify-between gap-3 rounded-xl border bg-muted/20 px-4 py-3 text-sm">
                <div>
                  <p className="font-medium">{t('rewardsConsole.directDiamonds')}</p>
                  <p className="text-xs text-muted-foreground">
                    {t('rewardsConsole.directDiamondsHelp')}
                  </p>
                </div>
                <Switch
                  checked={policyDraft.directLessonActivityDiamondsEnabled}
                  onCheckedChange={(checked) => {
                    setPolicyDraft({
                      ...policyDraft,
                      directLessonActivityDiamondsEnabled: checked,
                    });
                    setPolicySaved(false);
                  }}
                />
              </label>
              <div className="sm:col-span-2 flex items-center justify-end gap-3">
                {policySaved && (
                  <span className="text-sm font-medium text-success">{t('catalog.save')}</span>
                )}
                <Button
                  type="button"
                  disabled={policyMutation.isPending}
                  onClick={() =>
                    policyMutation.mutate({
                      ...policyDraft,
                      xpGrowthPerLevel: policyDraft.xpGrowthPerLevel ?? 15,
                      xpLevelMultiplier: policyDraft.xpLevelMultiplier ?? 1.02,
                      maxLevel: Math.min(99, policyDraft.maxLevel ?? 99),
                      milestoneInterval: policyDraft.milestoneInterval ?? 5,
                      milestoneDiamondBase: policyDraft.milestoneDiamondBase ?? 15,
                      milestoneDiamondGrowth: policyDraft.milestoneDiamondGrowth ?? 10,
                    })
                  }
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
            <Skeleton className="h-44 w-full" />
          )}
        </CardContent>
      </Card>

      <Card className="xl:col-span-6">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <RiAwardLine className="size-4 text-warning" aria-hidden="true" />
            {t('rewardsConsole.levelCurveTitle')}
          </CardTitle>
          <CardDescription>{t('rewardsConsole.levelCurveHelp')}</CardDescription>
        </CardHeader>
        <CardContent>
          {policyDraft ? (
            <>
              <div className="mb-3 grid gap-2 sm:grid-cols-3">
                {dailyXpScenarios.map((daily) => (
                  <div key={daily} className="rounded-xl border bg-muted/20 px-3 py-2">
                    <p className="text-[11px] text-muted-foreground tabular-nums">
                      {t('rewardsConsole.paceScenario', { xp: daily })}
                    </p>
                    <p className="text-sm font-semibold tabular-nums">
                      {t('rewardsConsole.paceYears', {
                        years: (totalToCap / daily / 365).toFixed(1),
                      })}
                    </p>
                  </div>
                ))}
              </div>
              <div className="[&_[data-slot=table-container]]:max-h-[560px] [&_[data-slot=table-container]]:overflow-y-auto rounded-xl border">
            <Table>
              <TableHeader className="sticky top-0 z-10 bg-card">
                <TableRow>
                  <TableHead>{t('rewardsConsole.levelStep')}</TableHead>
                  <TableHead className="text-right">{t('rewardsConsole.stepXp')}</TableHead>
                  <TableHead className="text-right">{t('rewardsConsole.cumulativeXp')}</TableHead>
                  <TableHead className="text-right">
                    {t('rewardsConsole.milestoneRewardColumn')}
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow
                    key={row.level}
                    className={row.milestoneDiamonds > 0 ? 'bg-muted/25' : undefined}
                  >
                    <TableCell className="font-medium tabular-nums">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant={row.isCap ? 'warning' : 'outline'}>
                          Lv. {row.level}
                        </Badge>
                        {row.isCap ? (
                          <span className="text-xs text-muted-foreground">
                            {t('rewardsConsole.capReachedNote', {
                              max: policyDraft.maxLevel ?? 99,
                            })}
                          </span>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {row.isCap ? '—' : <RewardPill kind="xp" value={row.stepXp} />}
                    </TableCell>
                    <TableCell className="text-right font-semibold tabular-nums">
                      {row.totalXp.toLocaleString()} XP
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {row.milestoneDiamonds > 0 ? (
                        <RewardPill kind="diamond" value={row.milestoneDiamonds} />
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
              </div>
            </>
          ) : (
            <Skeleton className="h-44 w-full" />
          )}
        </CardContent>
      </Card>
    </div>
  );
}
