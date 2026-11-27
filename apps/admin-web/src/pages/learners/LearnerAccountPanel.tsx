import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { RiKey2Line, RiLockLine, RiLockUnlockLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import { fetchLearnerAccount, resetLearnerPassword, setLearnerAccountLock } from './learners.api';

type Action = 'password' | 'lock' | 'unlock';

export function LearnerAccountPanel({ learnerId, learnerName }: { learnerId: string; learnerName: string }) {
  const { t } = useTranslation('admin');
  const client = useQueryClient();
  const key = ['learner-account', learnerId] as const;
  const account = useQuery({ queryKey: key, queryFn: ({ signal }) => fetchLearnerAccount(learnerId, signal), retry: false });
  const [action, setAction] = useState<Action | null>(null);
  const [changeOnNextLogin, setChangeOnNextLogin] = useState(false);
  const [notice, setNotice] = useState<Action | null>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const reasonInput = useRef<HTMLTextAreaElement>(null);
  const data = account.data;
  const available = data?.management === 'AVAILABLE';
  const locked = !!data?.locked;

  const mutation = useMutation({
    mutationFn: async (next: Action) => {
      if (next === 'password') {
        await resetLearnerPassword(learnerId, passwordInput.current?.value ?? '', changeOnNextLogin);
        return undefined;
      }
      return setLearnerAccountLock(learnerId, next === 'lock', reasonInput.current?.value.trim() || undefined);
    },
    onSuccess: async (updated, next) => {
      if (updated) {
        await client.cancelQueries({ queryKey: key, exact: true });
        client.setQueryData(key, updated);
      }
      setNotice(updated?.syncPending ? null : next);
      setAction(null);
    },
    onSettled: () => {
      if (passwordInput.current) passwordInput.current.value = '';
    },
  });

  function open(next: Action) {
    mutation.reset();
    setChangeOnNextLogin(false);
    setNotice(null);
    setAction(next);
  }

  return (
    <section className="grid gap-3 rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold">{t('learnerAccount.title')}</h3>
        {available && <Badge variant={locked ? 'destructive' : 'success'}>{t(locked ? 'learnerAccount.locked' : 'learnerAccount.active')}</Badge>}
      </div>
      <CatalogError error={account.error} retry={() => void account.refetch()} retrying={account.isFetching} />
      {data && !available && (
        <p className="text-xs text-muted-foreground">
          {t(data.management === 'UNCONFIGURED' ? 'learnerAccount.unconfigured' : 'learnerAccount.unmanaged')}
        </p>
      )}
      {data?.syncPending && <p role="status" className="text-sm text-muted-foreground">{t('learnerAccount.syncPending')}</p>}
      {notice && <p role="status" className="text-sm text-success">{t(`learnerAccount.done.${notice}`)}</p>}
      {available && <div className="flex flex-wrap gap-2">
        {!locked && <Button variant="outline" size="sm" onClick={() => open('password')}>
          <RiKey2Line aria-hidden="true" />{t('learnerAccount.actions.password')}
        </Button>}
        {(!locked || data.syncPending) && <Button variant="outline" size="sm" onClick={() => open('lock')}>
          <RiLockLine aria-hidden="true" />{t(data.syncPending ? 'learnerAccount.retryLock' : 'learnerAccount.actions.lock')}
        </Button>}
        {locked && <Button variant="outline" size="sm" onClick={() => open('unlock')}>
          <RiLockUnlockLine aria-hidden="true" />{t('learnerAccount.actions.unlock')}
        </Button>}
      </div>}

      <Dialog open={action !== null} onOpenChange={(next) => { if (!next && !mutation.isPending) setAction(null); }}>
        {action && <DialogContent showCloseButton={!mutation.isPending}>
          <DialogHeader>
            <DialogTitle>{t(`learnerAccount.actions.${action}`)}</DialogTitle>
            <DialogDescription>{t(`learnerAccount.description.${action}`, { name: learnerName })}</DialogDescription>
          </DialogHeader>
          <form className="grid gap-3" onSubmit={(event) => { event.preventDefault(); if (!mutation.isPending) mutation.mutate(action); }}>
            {action === 'password' ? <>
              <Label htmlFor="learner-account-password">{t('learnerAccount.password')}</Label>
              <Input id="learner-account-password" ref={passwordInput} type="password" autoComplete="new-password" required maxLength={256} />
              <Label className="flex items-start gap-2">
                <Checkbox checked={changeOnNextLogin} onCheckedChange={(checked) => setChangeOnNextLogin(checked === true)} />
                {t('learnerAccount.changeOnNextLogin')}
              </Label>
            </> : <>
              <Label htmlFor="learner-account-reason">{t('learnerAccount.reason')}</Label>
              <Textarea id="learner-account-reason" ref={reasonInput} maxLength={500} rows={3} />
            </>}
            <CatalogError error={mutation.error} />
            <DialogFooter>
              <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => setAction(null)}>{t('catalog.cancel')}</Button>
              <Button type="submit" variant={action === 'lock' ? 'destructive' : 'default'} disabled={mutation.isPending}>{t(`learnerAccount.actions.${action}`)}</Button>
            </DialogFooter>
          </form>
        </DialogContent>}
      </Dialog>
    </section>
  );
}
