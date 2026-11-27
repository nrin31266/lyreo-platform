import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiCoinsLine,
  RiShieldCheckLine,
  RiVipCrownLine,
} from '@remixicon/react';
import { AccessTierBadge } from '@/components/ContentBadges';
import { DiamondIcon } from '@/components/RewardIcons';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { MetricHelp } from './MetricHelp';
import {
  adjustLearnerDiamonds,
  fetchLearnerGamification,
  fetchLearnerPlan,
  updateLearnerPlan,
  type LearnerPlanTier,
} from './learners.api';

const durations = { UNLIMITED: undefined, '30D': 30, '90D': 90, '365D': 365 } as const;
type PlusDurationPreset = keyof typeof durations;

export function LearnerAccessAndRewards({
  learnerId,
  section,
}: {
  learnerId: string;
  section: 'plan' | 'rewards';
}) {
  const { t, i18n } = useTranslation('admin');
  const client = useQueryClient();
  const dateFormatter = new Intl.DateTimeFormat(i18n.language, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  const numberFormatter = new Intl.NumberFormat(i18n.language);

  const planKey = ['learner-plan', learnerId] as const;
  const plan = useQuery({
    queryKey: planKey,
    queryFn: ({ signal }) => fetchLearnerPlan(learnerId, signal),
    enabled: section === 'plan',
  });
  const gamification = useQuery({
    queryKey: ['learner-gamification', learnerId],
    queryFn: ({ signal }) => fetchLearnerGamification(learnerId, signal),
    enabled: section === 'rewards',
  });

  const [targetTier, setTargetTier] = useState<LearnerPlanTier | null>(null);
  const [duration, setDuration] = useState<PlusDurationPreset>('UNLIMITED');

  const [diamondOpen, setDiamondOpen] = useState(false);
  const [diamondAmount, setDiamondAmount] = useState('');
  const [diamondReason, setDiamondReason] = useState('');
  const [diamondError, setDiamondError] = useState<string | null>(null);

  const isPlus = plan.data?.tier === 'PLUS';
  const upgrading = targetTier === 'PLUS';
  const planMutation = useMutation({
    mutationFn: (tier: LearnerPlanTier) =>
      updateLearnerPlan(learnerId, tier, tier === 'PLUS' ? durations[duration] : undefined),
    onSuccess: (updatedPlan) => {
      client.setQueryData(planKey, updatedPlan);
      setTargetTier(null);
    },
  });

  const adjustMutation = useMutation({
    mutationFn: (input: { amount: number; reason: string }) =>
      adjustLearnerDiamonds(learnerId, {
        amount: input.amount,
        reason: input.reason,
        idempotencyKey:
          typeof crypto !== 'undefined' && 'randomUUID' in crypto
            ? crypto.randomUUID()
            : `admin-${Date.now()}-${Math.random().toString(36).slice(2)}`,
      }),
    onSuccess: () => {
      setDiamondOpen(false);
      setDiamondAmount('');
      setDiamondReason('');
      setDiamondError(null);
      void client.invalidateQueries({ queryKey: ['learner-gamification', learnerId] });
    },
  });

  const summary = gamification.data;

  return (
    <>
      {section === 'plan' && (
        <Card size="sm" className="rounded-xl border shadow-none ring-0">
          <CardHeader>
            <div className="flex items-center gap-2">
              <RiShieldCheckLine className="size-4 text-muted-foreground" aria-hidden="true" />
              <CardTitle>{t('learnerOperations.membershipTitle')}</CardTitle>
            </div>
          </CardHeader>
          <CardContent className="grid gap-3">
            <CatalogError
              error={plan.error}
              retry={() => void plan.refetch()}
              retrying={plan.isFetching}
            />
            {plan.isPending && <Skeleton className="h-12 w-full" />}
            {plan.data && (
              <>
                <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <AccessTierBadge accessMode={isPlus ? 'FEATURE' : 'PUBLIC'} />
                    {isPlus && (
                      <span className="text-xs text-muted-foreground">
                        {plan.data.validUntil
                          ? t('learnerOperations.plusActiveUntil', {
                              date: dateFormatter.format(new Date(plan.data.validUntil)),
                            })
                          : t('learnerOperations.noExpiry')}
                      </span>
                    )}
                  </div>
                  <Button
                    size="sm"
                    variant={isPlus ? 'outline' : 'default'}
                    onClick={() => {
                      planMutation.reset();
                      setDuration('UNLIMITED');
                      setTargetTier(isPlus ? 'FREE' : 'PLUS');
                    }}
                  >
                    {!isPlus && <RiVipCrownLine data-icon="inline-start" aria-hidden="true" />}
                    {t(isPlus ? 'learnerOperations.downgradeToFree' : 'learnerOperations.upgradeToPlus')}
                  </Button>
                </div>
                {plan.data.history.length > 0 && (
                  <details className="rounded-lg border px-3 py-2">
                    <summary className="cursor-pointer text-sm font-medium">
                      {t('learnerOperations.historyTitle')}
                    </summary>
                    <ul className="mt-1 divide-y">
                      {plan.data.history.map(({ tier, changedAt }) => (
                        <li key={`${tier}-${changedAt}`} className="flex flex-wrap items-center justify-between gap-2 py-1.5">
                          <AccessTierBadge accessMode={tier === 'PLUS' ? 'FEATURE' : 'PUBLIC'} />
                          <time dateTime={changedAt} className="text-xs text-muted-foreground tabular-nums">
                            {dateFormatter.format(new Date(changedAt))}
                          </time>
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
              </>
            )}
          </CardContent>
        </Card>
      )}

      {section === 'rewards' && (
        <Card size="sm" className="rounded-xl border shadow-none ring-0">
          <CardHeader className="border-b">
            <div className="flex items-center gap-2">
              <RiCoinsLine className="size-4 text-muted-foreground" aria-hidden="true" />
              <CardTitle>{t('learnerOperations.rewardsTitle')}</CardTitle>
            </div>
            <CardDescription>{t('learnerOperations.rewardsHelp')}</CardDescription>
            <CardAction>
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => {
                  adjustMutation.reset();
                  setDiamondError(null);
                  setDiamondOpen(true);
                }}
              >
                {t('learnerOperations.adjustDiamonds')}
              </Button>
            </CardAction>
          </CardHeader>
          <CardContent className="grid gap-4 pt-4">
            <CatalogError
              error={gamification.error}
              retry={() => void gamification.refetch()}
              retrying={gamification.isFetching}
            />
            {summary && (
              <>
                <dl className="grid gap-4 rounded-xl border bg-muted/25 p-4 sm:grid-cols-2 lg:grid-cols-4">
                  <div>
                    <dt className="flex items-center gap-1 text-xs text-muted-foreground">
                      {t('learnerOperations.level')}
                      <MetricHelp label={t('learnerOperations.level')} calculation="level" />
                    </dt>
                    <dd className="mt-1 text-lg font-semibold tabular-nums">
                      {t('learnerOperations.levelValue', {
                        level: summary.level,
                        xpIntoLevel: numberFormatter.format(summary.xpIntoLevel),
                        xpForNextLevel: numberFormatter.format(summary.xpForNextLevel),
                      })}
                    </dd>
                  </div>
                  <div>
                    <dt className="flex items-center gap-1 text-xs text-muted-foreground">
                      {t('learnerOperations.totalXp')}
                      <MetricHelp label={t('learnerOperations.totalXp')} calculation="totalXp" />
                    </dt>
                    <dd className="mt-1 text-lg font-semibold tabular-nums">
                      {numberFormatter.format(summary.xp)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-muted-foreground">
                      {t('learnerOperations.diamondBalance')}
                    </dt>
                    <dd className="mt-1 inline-flex items-center gap-1.5 text-lg font-semibold tabular-nums">
                      <DiamondIcon className="size-4" />
                      <span>{numberFormatter.format(summary.diamondBalance)}</span>
                    </dd>
                  </div>
                  <div>
                    <dt className="flex items-center gap-1 text-xs text-muted-foreground">
                      {t('learnerOperations.streak')}
                      <MetricHelp label={t('learnerOperations.streak')} calculation="streak" />
                    </dt>
                    <dd className="mt-1 text-lg font-semibold tabular-nums">
                      {t('learnerOperations.streakValue', {
                        current: summary.currentStreak,
                        longest: summary.longestStreak,
                      })}
                    </dd>
                  </div>
                </dl>

                {summary.transactions.length > 0 ? (
                  <Table aria-label={t('learnerOperations.transactionsTitle')}>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t('learnerOperations.amount')}</TableHead>
                        <TableHead>{t('learnerOperations.reason')}</TableHead>
                        <TableHead>{t('learnerOperations.source')}</TableHead>
                        <TableHead className="text-right">
                          {t('learnerOperations.recordedAt')}
                        </TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {summary.transactions.map((tx) => (
                        <TableRow key={tx.id}>
                          <TableCell className="font-semibold tabular-nums">
                            <span className="inline-flex items-center gap-1">
                              <DiamondIcon className="size-3.5" />
                              <span>
                                {tx.amount > 0
                                  ? `+${numberFormatter.format(tx.amount)}`
                                  : numberFormatter.format(tx.amount)}
                              </span>
                            </span>
                          </TableCell>
                          <TableCell className="whitespace-normal">{tx.reason}</TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {tx.sourceType || t('learnerProfiles.missing')}
                          </TableCell>
                          <TableCell className="text-right text-xs text-muted-foreground tabular-nums">
                            {dateFormatter.format(new Date(tx.createdAt))}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    {t('learnerOperations.noTransactions')}
                  </p>
                )}
              </>
            )}
          </CardContent>
        </Card>
      )}

      <Dialog
        open={targetTier !== null}
        onOpenChange={(open) => {
          if (!open && !planMutation.isPending) setTargetTier(null);
        }}
      >
        <DialogContent showCloseButton={!planMutation.isPending}>
          <DialogHeader>
            <DialogTitle>
              {t(upgrading ? 'learnerOperations.upgradeDialogTitle' : 'learnerOperations.downgradeDialogTitle')}
            </DialogTitle>
            <DialogDescription>
              {t(upgrading ? 'learnerOperations.upgradeDialogHelp' : 'learnerOperations.downgradeDialogHelp')}
            </DialogDescription>
          </DialogHeader>
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              if (targetTier) planMutation.mutate(targetTier);
            }}
          >
            {upgrading && (
              <div className="grid gap-1.5">
                <Label htmlFor="plus-duration-preset">{t('learnerOperations.durationLabel')}</Label>
                <Select value={duration} onValueChange={(value) => setDuration(value as PlusDurationPreset)}>
                  <SelectTrigger id="plus-duration-preset"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {(Object.keys(durations) as PlusDurationPreset[]).map((preset) => (
                      <SelectItem key={preset} value={preset}>
                        {t(`learnerOperations.durations.${preset}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            )}
            <CatalogError error={planMutation.error} />
            <DialogFooter>
              <Button type="button" variant="outline" disabled={planMutation.isPending} onClick={() => setTargetTier(null)}>
                {t('catalog.cancel')}
              </Button>
              <Button type="submit" variant={upgrading ? 'default' : 'destructive'} disabled={planMutation.isPending}>
                {t(upgrading ? 'learnerOperations.confirmUpgrade' : 'learnerOperations.confirmDowngrade')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog
        open={diamondOpen}
        onOpenChange={(open) => {
          if (!adjustMutation.isPending) setDiamondOpen(open);
        }}
      >
        <DialogContent showCloseButton={!adjustMutation.isPending}>
          <DialogHeader>
            <DialogTitle>{t('learnerOperations.adjustDialogTitle')}</DialogTitle>
            <DialogDescription>{t('learnerOperations.adjustDialogHelp')}</DialogDescription>
          </DialogHeader>
          <form
            noValidate
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              const parsed = Number(diamondAmount);
              if (!Number.isInteger(parsed) || parsed === 0) {
                setDiamondError(t('learnerOperations.invalidAmount'));
                return;
              }
              if (!diamondReason.trim()) {
                setDiamondError(t('learnerOperations.reasonRequired'));
                return;
              }
              setDiamondError(null);
              adjustMutation.mutate({ amount: parsed, reason: diamondReason.trim() });
            }}
          >
            <div className="grid gap-1.5">
              <Label htmlFor="diamond-adjust-amount">{t('learnerOperations.amountDelta')}</Label>
              <Input
                id="diamond-adjust-amount"
                type="number"
                step={1}
                placeholder="25"
                value={diamondAmount}
                onChange={(event) => setDiamondAmount(event.target.value)}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="diamond-adjust-reason">{t('learnerOperations.reason')}</Label>
              <Input
                id="diamond-adjust-reason"
                maxLength={250}
                value={diamondReason}
                onChange={(event) => setDiamondReason(event.target.value)}
              />
            </div>
            {diamondError && (
              <p role="alert" className="text-sm text-destructive">
                {diamondError}
              </p>
            )}
            <CatalogError error={adjustMutation.error} />
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={adjustMutation.isPending}
                onClick={() => setDiamondOpen(false)}
              >
                {t('catalog.cancel')}
              </Button>
              <Button type="submit" disabled={adjustMutation.isPending}>
                {t('learnerOperations.confirmAdjust')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
