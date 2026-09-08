import { Link, Redirect } from 'expo-router';
import { ActivityIndicator, Pressable, ScrollView, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { Card } from '../src/components/ui/card';
import { Text } from '../src/components/ui/text';
import { useAuth } from '../src/auth';
import { useAppTheme } from '../src/providers/AppThemeProvider';

export default function HomeScreen() {
  const auth = useAuth();
  const { colors } = useAppTheme();
  const { t } = useTranslation('mobile');

  if (auth.loading) {
    return (
      <View className="flex-1 items-center justify-center bg-background">
        <ActivityIndicator color={colors.primary} />
      </View>
    );
  }

  if (!auth.authenticated) return <Redirect href="/login" />;

  return (
    <ScrollView className="flex-1 bg-background" contentContainerStyle={{ padding: 24, paddingTop: 56, paddingBottom: 60, gap: 14 }}>
      <View className="flex-row items-start justify-between gap-4">
        <View className="flex-1">
          <Text className="text-[11px] font-bold tracking-[2px] text-primary">{t('home.eyebrow')}</Text>
          <Text className="mt-1.5 max-w-[300px] text-[38px] font-bold leading-[42px] tracking-[-1.5px] text-foreground">
            {t('home.title')}
          </Text>
        </View>
        <View className="rounded-full bg-secondary px-3 py-2.5">
          <Text className="font-bold text-secondary-foreground">💎 240</Text>
        </View>
      </View>

      <View className="mt-4 flex-row items-end justify-between">
        <View>
          <Text className="text-xs font-semibold text-muted-foreground">{t('home.level')}</Text>
          <Text className="text-xl font-bold text-foreground">{t('home.levelName')}</Text>
        </View>
        <Text className="font-extrabold text-warning">82%</Text>
      </View>
      <View className="h-1.5 overflow-hidden rounded-full bg-muted">
        <View className="h-1.5 w-[82%] rounded-full bg-primary" />
      </View>

      <Link href="/lesson" asChild>
        <Pressable className="mt-3 rounded-lg bg-primary p-6 shadow-lg active:opacity-90">
          <Text className="text-[10px] font-bold tracking-[1.4px] text-primary-foreground opacity-70">{t('home.continueEyebrow')}</Text>
          <Text className="mt-2 text-[27px] font-bold text-primary-foreground">{t('home.continueTitle')}</Text>
          <Text className="mt-2 text-primary-foreground opacity-80">{t('home.continueMeta')}</Text>
          <Text className="mt-6 font-bold text-secondary">{t('home.continueAction')}</Text>
        </Pressable>
      </Link>

      <Text className="mt-3 text-[15px] font-extrabold text-foreground">{t('home.today')}</Text>
      <View className="flex-row gap-3">
        <SummaryCard label={t('home.vocabulary')} value="18" note={t('home.wordsDue')} />
        <SummaryCard label={t('home.studyTime')} value="25m" note={t('home.activities')} />
      </View>

      <Text className="mt-3 text-[15px] font-extrabold text-foreground">{t('home.needsAttention')}</Text>
      <Card className="p-[18px]">
        <Text className="text-xl font-bold text-foreground">{t('home.weakTopic')}</Text>
        <Text className="mt-1 leading-5 text-muted-foreground">{t('home.weakCopy')}</Text>
        <Text className="mt-2 font-bold text-primary">{t('home.review')}</Text>
      </Card>

      <View className="flex-row justify-between pt-4">
        <Link href="/progress" asChild>
          <Pressable><Text className="font-bold text-primary">{t('home.progress')}</Text></Pressable>
        </Link>
        <Link href="/settings" asChild>
          <Pressable><Text className="font-bold text-primary">{t('home.settings')}</Text></Pressable>
        </Link>
      </View>
    </ScrollView>
  );
}

function SummaryCard({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <Card className="flex-1 p-[18px]">
      <Text className="text-muted-foreground">{label}</Text>
      <Text className="my-1 text-3xl font-extrabold text-primary">{value}</Text>
      <Text className="text-muted-foreground">{note}</Text>
    </Card>
  );
}
