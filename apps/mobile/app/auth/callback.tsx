import { Link, Redirect, useLocalSearchParams } from 'expo-router';
import { View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useSession } from '@/auth/use-session';
import { ErrorState } from '@/components/states/error-state';
import { LoadingState } from '@/components/states/loading-state';
import { Button } from '@/components/ui/button';

export default function AuthCallbackScreen() {
  const session = useSession();
  const params = useLocalSearchParams<{ code?: string; error?: string }>();
  const { t } = useTranslation('mobile');

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

  const hasAuthorizationResponse = typeof params.code === 'string' || typeof params.error === 'string';
  if (session.status === 'unauthenticated' && !hasAuthorizationResponse) {
    return <Redirect href="/sign-in" />;
  }

  return (
    <View className="flex-1 justify-center bg-background">
      <LoadingState label={t('auth.preparing')} />
    </View>
  );
}
