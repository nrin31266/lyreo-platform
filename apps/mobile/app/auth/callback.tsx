import { Link, Redirect, useLocalSearchParams } from 'expo-router';
import { useEffect } from 'react';
import { View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useSession } from '@/auth/use-session';
import { ErrorState } from '@/components/states/error-state';
import { LoadingState } from '@/components/states/loading-state';
import { Button } from '@/components/ui/button';

export default function AuthCallbackScreen() {
  const session = useSession();
  const params = useLocalSearchParams<{
    code?: string | string[];
    error?: string | string[];
    state?: string | string[];
  }>();
  const { t } = useTranslation('mobile');
  const code = firstParam(params.code);
  const error = firstParam(params.error);
  const state = firstParam(params.state);

  useEffect(() => {
    if (!code && !error) return;
    void session.completeAuthorizationCallback({ code, error, state });
  }, [code, error, session.completeAuthorizationCallback, state]);

  if (session.error) {
    return (
      <View className="flex-1 justify-center bg-background px-7">
        <ErrorState message={t(`auth.${session.error}`)} />
        <Link className="mt-4" href="/sign-in" asChild>
          <Button variant="outline">{t('auth.signIn')}</Button>
        </Link>
      </View>
    );
  }

  if (session.status === 'authenticated') {
    return <Redirect href="/" />;
  }

  const hasAuthorizationResponse = Boolean(code || error);
  if (session.status === 'unauthenticated' && !hasAuthorizationResponse) {
    return <Redirect href="/sign-in" />;
  }

  return (
    <View className="flex-1 justify-center bg-background">
      <LoadingState label={t('auth.preparing')} />
    </View>
  );
}

function firstParam(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}
