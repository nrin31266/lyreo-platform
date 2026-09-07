import { Redirect } from 'expo-router';
import { ActivityIndicator, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { Button } from '../src/components/ui/button';
import { Text } from '../src/components/ui/text';
import { useAuth } from '../src/auth';
import { useAppTheme } from '../src/providers/AppThemeProvider';

export default function LoginScreen() {
  const auth = useAuth();
  const { colors } = useAppTheme();
  const { t } = useTranslation('mobile');

  if (auth.loading) {
    return <View className="flex-1 items-center justify-center bg-background"><ActivityIndicator color={colors.primary} /></View>;
  }
  if (auth.authenticated) return <Redirect href="/" />;

  return (
    <View className="flex-1 justify-center bg-background px-7 py-12">
      <View className="h-14 w-14 items-center justify-center rounded-[20px] bg-primary">
        <Text className="text-[34px] font-bold text-primary-foreground">L</Text>
      </View>
      <Text className="mt-[22px] text-[44px] font-extrabold tracking-[-1.5px] text-foreground">Lyreo</Text>
      <Text className="mt-1 text-[17px] font-bold text-primary">{t('auth.name')}</Text>
      <Text className="mt-6 max-w-[380px] text-base leading-6 text-muted-foreground">{t('auth.copy')}</Text>
      <Button className="mt-[34px]" size="lg" onPress={() => void auth.signIn()}>{t('auth.signIn')}</Button>
      <Text className="mt-[18px] text-xs leading-[18px] text-muted-foreground">{t('auth.note')}</Text>
    </View>
  );
}
