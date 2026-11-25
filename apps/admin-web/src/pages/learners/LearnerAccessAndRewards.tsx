import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiAddLine, RiCoinsLine, RiShieldCheckLine } from '@remixicon/react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
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
  type EntitlementGrantEntry,
} from './learners.api';

function grantBadgeVariant(status: string): 'success' | 'destructive' | 'secondary' {
  if (status === 'ACTIVE') return 'success';
  if (status === 'REVOKED') return 'destructive';
  return 'secondary';
}

function toIsoOrUndefined(localValue: string): string | undefined {
  if (!localValue.trim()) return undefined;
  const parsed = new Date(localValue);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

export function LearnerAccessAndRewards({ learnerId }: { learnerId: string }) {
  const { t, i18n } = useTranslation('admin');
  const client = useQueryClient();
  const dateFormatter = new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' });
  const numberFormatter = new Intl.NumberFormat(i18n.language);

  const grants = useQuery({
    queryKey: ['learner-entitlement-grants', learnerId],
    queryFn: ({ signal }) => fetchLearnerGrants(learnerId, signal),
  });
  const gamification = useQuery({
    queryKey: ['learner-gamification', learnerId],
    queryFn: ({ signal }) => fetchLearnerGamification(learnerId, signal),
  });

  const [grantOpen, setGrantOpen] = useState(false);
  const [featureKey, setFeatureKey] = useState<string>(entitlementFeatureKeys[0]);
  const [validFrom, setValidFrom] = useState('');
  const [validUntil, setValidUntil] = useState('');
  const [sourceReference, setSourceReference] = useState('');

  const [revokingGrant, setRevokingGrant] = useState<EntitlementGrantEntry | null>(null);
  const [revokeReason, setRevokeReason] = useState('');
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const [diamondOpen, setDiamondOpen] = useState(false);
  const [diamondAmount, setDiamondAmount] = useState('');
  const [diamondReason, setDiamondReason] = useState('');
  const [diamondError, setDiamondError] = useState<string | null>(null);

  const createGrantMutation = useMutation({
    mutationFn: () =>
      createLearnerGrant({
        userId: learnerId,
        featureKey,
        validFrom: toIsoOrUndefined(validFrom),
        validUntil: toIsoOrUndefined(validUntil) ?? null,
        sourceType: 'ADMIN_MANUAL',
        sourceReference: sourceReference.trim() || undefined,
      }),
    onSuccess: () => {
      setGrantOpen(false);
      setValidFrom('');
      setValidUntil('');
      setSourceReference('');
      void client.invalidateQueries({ queryKey: ['learner-entitlement-grants', learnerId] });
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (input: { grantId: string; reason: string }) => revokeLearnerGrant(input.grantId, input.reason),
    onSuccess: () => {
      setRevokingGrant(null);
      setRevokeReason('');
      setRevokeError(null);
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
    <div className="grid gap-5">
      <Card size="sm">
        <CardHeader className="border-b">
          <div className="flex items-center gap-2">
            <RiShieldCheckLine className="size-4 text-muted-foreground" aria-hidden="true" />
            <CardTitle>{t('learnerOperations.entitlementsTitle')}</CardTitle>
          </div>
          <CardDescription>{t('learnerOperations.entitlementsHelp')}</CardDescription>
          <CardAction>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={() => {
                createGrantMutation.reset();
                setGrantOpen(true);
              }}
            >
              <RiAddLine data-icon="inline-start" aria-hidden="true" />
              {t('learnerOperations.grantFeature')}
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="grid gap-3">
          <CatalogError error={grants.error} retry={() => void grants.refetch()} retrying={grants.isFetching} />
          {grants.data && grants.data.length === 0 && (
            <p className="text-sm text-muted-foreground">{t('learnerOperations.noGrants')}</p>
          )}
          {grants.data && grants.data.length > 0 && (
            <Table aria-label={t('learnerOperations.entitlementsTitle')}>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('learnerOperations.featureKey')}</TableHead>
                  <TableHead>{t('learnerOperations.grantStatus')}</TableHead>
                  <TableHead>{t('learnerOperations.validity')}</TableHead>
                  <TableHead>{t('learnerOperations.note')}</TableHead>
                  <TableHead className="text-right">{t('catalog.actions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {grants.data.map(grant => (
                  <TableRow key={grant.id}>
                    <TableCell className="font-mono text-xs">{grant.featureKey}</TableCell>
                    <TableCell>
                      <Badge variant={grantBadgeVariant(grant.status)}>
                        {t(`learnerOperations.statuses.${grant.status}`, { defaultValue: grant.status })}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground tabular-nums">
                      {dateFormatter.format(new Date(grant.validFrom))} →{' '}
                      {grant.validUntil
                        ? dateFormatter.format(new Date(grant.validUntil))
                        : t('learnerOperations.noExpiry')}
                    </TableCell>
                    <TableCell className="max-w-xs whitespace-normal text-xs">
                      {grant.revocationReason || grant.sourceReference || t('learnerProfiles.missing')}
                    </TableCell>
                    <TableCell className="text-right">
                      {grant.status === 'ACTIVE' && (
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            revokeMutation.reset();
                            setRevokeReason('');
                            setRevokeError(null);
                            setRevokingGrant(grant);
                          }}
                        >
                          {t('learnerOperations.revoke')}
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Card size="sm">
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
        <CardContent className="grid gap-4">
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
                  <dd className="mt-1 text-lg font-semibold tabular-nums">{numberFormatter.format(summary.xp)}</dd>
                </div>
                <div>
                  <dt className="text-xs text-muted-foreground">{t('learnerOperations.diamondBalance')}</dt>
                  <dd className="mt-1 text-lg font-semibold tabular-nums">
                    {numberFormatter.format(summary.diamondBalance)}
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
                      <TableHead className="text-right">{t('learnerOperations.recordedAt')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {summary.transactions.map(tx => (
                      <TableRow key={tx.id}>
                        <TableCell className="font-semibold tabular-nums">
                          {tx.amount > 0 ? `+${numberFormatter.format(tx.amount)}` : numberFormatter.format(tx.amount)}
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
                <p className="text-sm text-muted-foreground">{t('learnerOperations.noTransactions')}</p>
              )}
            </>
          )}
        </CardContent>
      </Card>

      <Dialog open={grantOpen} onOpenChange={open => { if (!createGrantMutation.isPending) setGrantOpen(open); }}>
        <DialogContent showCloseButton={!createGrantMutation.isPending}>
          <DialogHeader>
            <DialogTitle>{t('learnerOperations.grantDialogTitle')}</DialogTitle>
            <DialogDescription>{t('learnerOperations.grantDialogHelp')}</DialogDescription>
          </DialogHeader>
          <form
            className="grid gap-4"
            onSubmit={event => {
              event.preventDefault();
              createGrantMutation.mutate();
            }}
          >
            <div className="grid gap-1.5">
              <Label htmlFor="grant-feature-key">{t('learnerOperations.featureKey')}</Label>
              <Select value={featureKey} onValueChange={setFeatureKey}>
                <SelectTrigger id="grant-feature-key">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {entitlementFeatureKeys.map(key => (
                    <SelectItem key={key} value={key}>
                      {key}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1.5">
                <Label htmlFor="grant-valid-from">{t('learnerOperations.validFrom')}</Label>
                <Input
                  id="grant-valid-from"
                  type="datetime-local"
                  value={validFrom}
                  onChange={event => setValidFrom(event.target.value)}
                />
              </div>
              <div className="grid gap-1.5">
                <Label htmlFor="grant-valid-until">{t('learnerOperations.validUntil')}</Label>
                <Input
                  id="grant-valid-until"
                  type="datetime-local"
                  value={validUntil}
                  onChange={event => setValidUntil(event.target.value)}
                />
              </div>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="grant-reason">{t('learnerOperations.grantReference')}</Label>
              <Input
                id="grant-reason"
                maxLength={500}
                value={sourceReference}
                onChange={event => setSourceReference(event.target.value)}
              />
            </div>
            <CatalogError error={createGrantMutation.error} />
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={createGrantMutation.isPending}
                onClick={() => setGrantOpen(false)}
              >
                {t('catalog.cancel')}
              </Button>
              <Button type="submit" disabled={createGrantMutation.isPending}>
                {t('learnerOperations.confirmGrant')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog
        open={Boolean(revokingGrant)}
        onOpenChange={open => { if (!open && !revokeMutation.isPending) setRevokingGrant(null); }}
      >
        <DialogContent showCloseButton={!revokeMutation.isPending}>
          <DialogHeader>
            <DialogTitle>{t('learnerOperations.revokeDialogTitle')}</DialogTitle>
            <DialogDescription>
              {t('learnerOperations.revokeDialogHelp', { featureKey: revokingGrant?.featureKey ?? '' })}
            </DialogDescription>
          </DialogHeader>
          <form
            noValidate
            className="grid gap-4"
            onSubmit={event => {
              event.preventDefault();
              if (!revokingGrant) return;
              if (!revokeReason.trim()) {
                setRevokeError(t('learnerOperations.reasonRequired'));
                return;
              }
              setRevokeError(null);
              revokeMutation.mutate({ grantId: revokingGrant.id, reason: revokeReason.trim() });
            }}
          >
            <div className="grid gap-1.5">
              <Label htmlFor="revoke-grant-reason">{t('learnerOperations.reason')}</Label>
              <Input
                id="revoke-grant-reason"
                maxLength={500}
                value={revokeReason}
                onChange={event => setRevokeReason(event.target.value)}
              />
            </div>
            {revokeError && <p role="alert" className="text-sm text-destructive">{revokeError}</p>}
            <CatalogError error={revokeMutation.error} />
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                disabled={revokeMutation.isPending}
                onClick={() => setRevokingGrant(null)}
              >
                {t('catalog.cancel')}
              </Button>
              <Button type="submit" variant="destructive" disabled={revokeMutation.isPending}>
                {t('learnerOperations.confirmRevoke')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={diamondOpen} onOpenChange={open => { if (!adjustMutation.isPending) setDiamondOpen(open); }}>
        <DialogContent showCloseButton={!adjustMutation.isPending}>
          <DialogHeader>
            <DialogTitle>{t('learnerOperations.adjustDialogTitle')}</DialogTitle>
            <DialogDescription>{t('learnerOperations.adjustDialogHelp')}</DialogDescription>
          </DialogHeader>
          <form
            noValidate
            className="grid gap-4"
            onSubmit={event => {
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
                onChange={event => setDiamondAmount(event.target.value)}
              />
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="diamond-adjust-reason">{t('learnerOperations.reason')}</Label>
              <Input
                id="diamond-adjust-reason"
                maxLength={250}
                value={diamondReason}
                onChange={event => setDiamondReason(event.target.value)}
              />
            </div>
            {diamondError && <p role="alert" className="text-sm text-destructive">{diamondError}</p>}
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
