import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiCheckLine,
  RiCoinsLine,
  RiShieldCheckLine,
  RiVipCrownLine,
} from '@remixicon/react';
import { AccessTierBadge } from '@/components/ContentBadges';
import { DiamondIcon } from '@/components/RewardIcons';
import { Badge } from '@/components/ui/badge';
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
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import {
  adjustLearnerDiamonds,
  createLearnerGrant,
  entitlementFeatureKeys,
  fetchLearnerGamification,
  fetchLearnerGrants,
  revokeLearnerGrant,
} from './learners.api';

type PlusDurationPreset = 'UNLIMITED' | '30D' | '90D' | '365D';

function computeValidUntil(preset: PlusDurationPreset): string | null {
  if (preset === 'UNLIMITED') return null;
  const days = preset === '30D' ? 30 : preset === '90D' ? 90 : 365;
  return new Date(Date.now() + days * 86_400_000).toISOString();
}

export function LearnerAccessAndRewards({
  learnerId,
  section = 'all',
}: {
  learnerId: string;
  section?: 'all' | 'plan' | 'rewards';
}) {
  const { t, i18n } = useTranslation('admin');
  const client = useQueryClient();
  const dateFormatter = new Intl.DateTimeFormat(i18n.language, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
  const numberFormatter = new Intl.NumberFormat(i18n.language);

  const grants = useQuery({
    queryKey: ['learner-entitlement-grants', learnerId],
    queryFn: ({ signal }) => fetchLearnerGrants(learnerId, signal),
  });
  const gamification = useQuery({
    queryKey: ['learner-gamification', learnerId],
    queryFn: ({ signal }) => fetchLearnerGamification(learnerId, signal),
  });

  const [upgradeOpen, setUpgradeOpen] = useState(false);
  const [durationPreset, setDurationPreset] = useState<PlusDurationPreset>('UNLIMITED');
  const [upgradeNote, setUpgradeNote] = useState('');

  const [downgradeOpen, setDowngradeOpen] = useState(false);
  const [downgradeReason, setDowngradeReason] = useState('');

  const [diamondOpen, setDiamondOpen] = useState(false);
  const [diamondAmount, setDiamondAmount] = useState('');
  const [diamondReason, setDiamondReason] = useState('');
  const [diamondError, setDiamondError] = useState<string | null>(null);

  const activeGrants = (grants.data ?? []).filter((g) => g.status === 'ACTIVE');
  const isPlus = activeGrants.length > 0;
  const expiryDate = activeGrants.find((g) => g.validUntil)?.validUntil ?? null;

  const upgradeMutation = useMutation({
    mutationFn: async () => {
      const existing = grants.data ?? [];
      const activeKeys = new Set(
        existing.filter((g) => g.status === 'ACTIVE').map((g) => g.featureKey),
      );
      const missingKeys = entitlementFeatureKeys.filter((key) => !activeKeys.has(key));
      const validUntil = computeValidUntil(durationPreset);
      const sourceReference = upgradeNote.trim() || 'Admin Plus Membership';
      await Promise.all(
        missingKeys.map((key) =>
          createLearnerGrant({
            userId: learnerId,
            featureKey: key,
            validUntil,
            sourceType: 'ADMIN_MANUAL',
            sourceReference,
          }),
        ),
      );
    },
    onSuccess: () => {
      setUpgradeOpen(false);
      setUpgradeNote('');
      setDurationPreset('UNLIMITED');
      void client.invalidateQueries({ queryKey: ['learner-entitlement-grants', learnerId] });
    },
  });

  const downgradeMutation = useMutation({
    mutationFn: async () => {
      const reason =
        downgradeReason.trim() || t('learnerOperations.defaultDowngradeReason');
      await Promise.all(activeGrants.map((grant) => revokeLearnerGrant(grant.id, reason)));
    },
    onSuccess: () => {
      setDowngradeOpen(false);
      setDowngradeReason('');
      void client.invalidateQueries({ queryKey: ['learner-entitlement-grants', learnerId] });
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
    <div className={section === 'all' ? 'grid gap-5 xl:grid-cols-12' : 'grid gap-5'}>
      {section !== 'rewards' && (
      <Card size="sm" className={section === 'all' ? 'xl:col-span-5' : undefined}>
        <CardHeader className="border-b">
          <div className="flex items-center gap-2">
            <RiShieldCheckLine className="size-4 text-muted-foreground" aria-hidden="true" />
            <CardTitle>{t('learnerOperations.membershipTitle')}</CardTitle>
          </div>
          <CardDescription>{t('learnerOperations.membershipHelp')}</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-4 pt-4">
          <CatalogError
            error={grants.error}
            retry={() => void grants.refetch()}
            retrying={grants.isFetching}
          />

          <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-muted/25 p-4">
            <div className="space-y-1">
              <div className="flex items-center gap-2">
                <AccessTierBadge accessMode={isPlus ? 'FEATURE' : 'PUBLIC'} />
              </div>
              <p className="text-sm text-muted-foreground">
                {isPlus
                  ? expiryDate
                    ? t('learnerOperations.plusActiveUntil', {
                        date: dateFormatter.format(new Date(expiryDate)),
                      })
                    : t('learnerOperations.plusActiveUnlimited')
                  : t('learnerOperations.freePlanSummary')}
              </p>
            </div>

            {isPlus ? (
              <Button
                type="button"
                size="sm"
                variant="destructive"
                onClick={() => {
                  downgradeMutation.reset();
                  setDowngradeReason('');
                  setDowngradeOpen(true);
                }}
              >
                {t('learnerOperations.downgradeToFree')}
              </Button>
            ) : (
              <Button
                type="button"
                size="sm"
                onClick={() => {
                  upgradeMutation.reset();
                  setDurationPreset('UNLIMITED');
                  setUpgradeNote('');
                  setUpgradeOpen(true);
                }}
              >
                <RiVipCrownLine data-icon="inline-start" aria-hidden="true" />
                {t('learnerOperations.upgradeToPlus')}
              </Button>
            )}
          </div>

          <div className="space-y-1.5 text-xs text-muted-foreground">
            <p className="flex items-center gap-2">
              <RiCheckLine className="size-3.5 text-success" aria-hidden="true" />
              <span>{t('learnerOperations.plusPerkLessons')}</span>
            </p>
            <p className="flex items-center gap-2">
              <RiCheckLine className="size-3.5 text-success" aria-hidden="true" />
              <span>{t('learnerOperations.plusPerkExams')}</span>
            </p>
            <p className="flex items-center gap-2">
              <RiCheckLine className="size-3.5 text-success" aria-hidden="true" />
              <span>{t('learnerOperations.plusPerkAi')}</span>
            </p>
          </div>

          {grants.data && grants.data.length > 0 && (
            <details className="rounded-xl border bg-muted/15 px-3 py-2 text-xs">
              <summary className="cursor-pointer font-medium text-muted-foreground">
                {t('learnerOperations.historyToggle', { count: grants.data.length })}
              </summary>
              <div className="mt-2 divide-y">
                {grants.data.slice(0, 8).map((grant) => (
                  <div
                    key={grant.id}
                    className="flex flex-wrap items-center justify-between gap-2 py-1.5"
                  >
                    <span className="font-medium">
                      {t(`plansConsole.features.${grant.featureKey}`, {
                        defaultValue: grant.featureKey,
                      })}
                    </span>
                    <div className="flex items-center gap-2">
                      <Badge
                        variant={
                          grant.status === 'ACTIVE'
                            ? 'success'
                            : grant.status === 'REVOKED'
                              ? 'destructive'
                              : 'secondary'
                        }
                      >
                        {t(`learnerOperations.statuses.${grant.status}`, {
                          defaultValue: grant.status,
                        })}
                      </Badge>
                      <span className="text-muted-foreground tabular-nums">
                        {dateFormatter.format(new Date(grant.validFrom))}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </details>
          )}
        </CardContent>
      </Card>
      )}

      {section !== 'plan' && (
      <Card size="sm" className={section === 'all' ? 'xl:col-span-7' : undefined}>
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
                  <dt className="text-xs text-muted-foreground">{t('learnerOperations.level')}</dt>
                  <dd className="mt-1 text-lg font-semibold tabular-nums">
                    {t('learnerOperations.levelValue', {
                      level: summary.level,
                      xpIntoLevel: numberFormatter.format(summary.xpIntoLevel),
                      xpForNextLevel: numberFormatter.format(summary.xpForNextLevel),
                    })}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-muted-foreground">{t('learnerOperations.totalXp')}</dt>
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
                  <dt className="text-xs text-muted-foreground">{t('learnerOperations.streak')}</dt>
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
        open={upgradeOpen}
        onOpenChange={(open) => {
          if (!upgradeMutation.isPending) setUpgradeOpen(open);
        }}
      >
        <DialogContent showCloseButton={!upgradeMutation.isPending}>
          <DialogHeader>
            <DialogTitle>{t('learnerOperations.upgradeDialogTitle')}</DialogTitle>
            <DialogDescription>{t('learnerOperations.upgradeDialogHelp')}</DialogDescription>
          </DialogHeader>
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              upgradeMutation.mutate();
            }}
          >
            <div className="grid gap-1.5">
              <Label htmlFor="plus-duration-preset">{t('learnerOperations.durationLabel')}</Label>
              <Select
                value={durationPreset}
                onValueChange={(v) => setDurationPreset(v as PlusDurationPreset)}
              >
                <SelectTrigger id="plus-duration-preset">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="UNLIMITED">
                    {t('learnerOperations.durations.UNLIMITED')}
                  </SelectItem>
                  <SelectItem value="30D">{t('learnerOperations.durations.30D')}</SelectItem>
                  <SelectItem value="90D">{t('learnerOperations.durations.90D')}</SelectItem>
                  <SelectItem value="365D">{t('learnerOperations.durations.365D')}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="plus-upgrade-note">{t('learnerOperations.grantReference')}</Label>
              <Input
                id="plus-upgrade-note"
                maxLength={250}
                value={upgradeNote}
                onChange={(event) => setUpgradeNote(event.target.value)}
              />
            </div>
            <CatalogError error={upgradeMutation.error} />
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={upgradeMutation.isPending}
                onClick={() => setUpgradeOpen(false)}
              >
                {t('catalog.cancel')}
              </Button>
              <Button type="submit" disabled={upgradeMutation.isPending}>
                {t('learnerOperations.confirmUpgrade')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog
        open={downgradeOpen}
        onOpenChange={(open) => {
          if (!downgradeMutation.isPending) setDowngradeOpen(open);
        }}
      >
        <DialogContent showCloseButton={!downgradeMutation.isPending}>
          <DialogHeader>
            <DialogTitle>{t('learnerOperations.downgradeDialogTitle')}</DialogTitle>
            <DialogDescription>{t('learnerOperations.downgradeDialogHelp')}</DialogDescription>
          </DialogHeader>
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              downgradeMutation.mutate();
            }}
          >
            <div className="grid gap-1.5">
              <Label htmlFor="plus-downgrade-reason">{t('learnerOperations.reasonOptional')}</Label>
              <Input
                id="plus-downgrade-reason"
                maxLength={250}
                value={downgradeReason}
                onChange={(event) => setDowngradeReason(event.target.value)}
              />
            </div>
            <CatalogError error={downgradeMutation.error} />
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={downgradeMutation.isPending}
                onClick={() => setDowngradeOpen(false)}
              >
                {t('catalog.cancel')}
              </Button>
              <Button
                type="submit"
                variant="destructive"
                disabled={downgradeMutation.isPending}
              >
                {t('learnerOperations.confirmDowngrade')}
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
    </div>
  );
}
