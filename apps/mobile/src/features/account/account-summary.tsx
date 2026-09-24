import { useCallback, useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useApiClient } from '@/api/api-provider';
import { EmptyState } from '@/components/states/empty-state';
import { ErrorState } from '@/components/states/error-state';
import { LoadingState } from '@/components/states/loading-state';
import { Text } from '@/components/ui/text';
import { getCurrentUser, type CurrentUser } from './api';

type AccountState =
  | { status: 'loading' }
  | { status: 'loaded'; user: CurrentUser }
  | { status: 'error' };

export function AccountSummary() {
  const client = useApiClient();
  const { t } = useTranslation('mobile');
  const [state, setState] = useState<AccountState>({ status: 'loading' });
  const requestController = useRef<AbortController | null>(null);

  const load = useCallback(() => {
    requestController.current?.abort();
    const controller = new AbortController();
    requestController.current = controller;
    setState({ status: 'loading' });
    void getCurrentUser(client, controller.signal)
      .then(user => {
        if (controller.signal.aborted) return;
        setState({ status: 'loaded', user });
      })
      .catch(() => {
        if (controller.signal.aborted) return;
        setState({ status: 'error' });
      });
  }, [client]);

  useEffect(() => {
    load();
    return () => requestController.current?.abort();
  }, [load]);

  if (state.status === 'loading') return <LoadingState label={t('account.loading')} />;
  if (state.status === 'error') {
    return <ErrorState message={t('account.loadFailed')} onRetry={load} />;
  }
  if (!state.user.email) {
    return <EmptyState title={t('account.title')} message={t('account.noEmail')} />;
  }

  return (
    <View className="rounded-lg border border-border bg-surface p-5">
      <Text className="text-xs font-bold tracking-[1.2px] text-primary">{t('account.eyebrow')}</Text>
      <Text className="mt-1 font-extrabold text-foreground">{state.user.email}</Text>
      <Text className="mt-1 text-xs text-muted-foreground">{t('account.coreVerified')}</Text>
    </View>
  );
}
