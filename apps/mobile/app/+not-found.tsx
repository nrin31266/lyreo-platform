import { Link } from 'expo-router';
import { View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useSession } from '@/auth/use-session';
import { Button } from '@/components/ui/button';
import { Text } from '@/components/ui/text';

export default function NotFoundScreen() {
  const { status } = useSession();
  const { t } = useTranslation('mobile');
  return (
    <View className="flex-1 items-start justify-center gap-3 bg-background px-7">
      <Text className="text-3xl font-extrabold text-foreground">{t('notFound.title')}</Text>
      <Text className="leading-6 text-muted-foreground">{t('notFound.message')}</Text>
      <Link className="mt-2" href={status === 'authenticated' ? '/' : '/sign-in'} asChild>
        <Button>{t('notFound.action')}</Button>
      </Link>
    </View>
  );
}
