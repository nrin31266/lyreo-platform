import { lyreoBrand } from '@lyreo/design-system';
import { View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useSession } from '@/auth/use-session';
import { ErrorState } from '@/components/states/error-state';
import { Button } from '@/components/ui/button';
import { Text } from '@/components/ui/text';

export default function SignInScreen() {
  const session = useSession();
  const { t } = useTranslation('mobile');
  const errorMessage = session.error ? t(`auth.${session.error}`) : null;

  return (
    <View className="flex-1 justify-center bg-background px-7 py-12">
      <View className="h-14 w-14 items-center justify-center rounded-[20px] bg-primary">
        <Text className="text-[34px] font-bold text-primary-foreground">L</Text>
      </View>
      <Text className="mt-[22px] text-[44px] font-extrabold tracking-[-1.5px] text-foreground">
        {lyreoBrand.name}
      </Text>
      <Text className="mt-1 text-[17px] font-bold text-primary">{t('auth.name')}</Text>
      <Text className="mt-6 max-w-[380px] text-base leading-6 text-muted-foreground">{t('auth.copy')}</Text>
      {errorMessage ? (
        <View className="mt-6">
          <ErrorState
            message={errorMessage}
            onRetry={session.error === 'identityUnavailable'
              ? () => void session.retryInitialization()
              : session.clearError}
          />
        </View>
      ) : null}
      <Button
        className="mt-[34px]"
        size="lg"
        disabled={!session.signInReady}
        onPress={() => void session.signIn()}
      >
        {session.signInReady ? t('auth.signIn') : t('auth.preparing')}
      </Button>
      <Text className="mt-[18px] text-xs leading-[18px] text-muted-foreground">{t('auth.note')}</Text>
    </View>
  );
}
