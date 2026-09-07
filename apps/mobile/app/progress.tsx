import { Link } from 'expo-router';
import { Pressable, ScrollView, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { Text } from '../src/components/ui/text';

const skills = [
  ['listening', 72],
  ['speaking', 64],
  ['vocabulary', 81],
  ['grammar', 69],
] as const;

export default function ProgressScreen() {
  const { t } = useTranslation('mobile');
  return (
    <ScrollView className="flex-1 bg-background" contentContainerStyle={{ padding: 24, paddingTop: 54, gap: 16, paddingBottom: 60 }}>
      <Link href="/" asChild>
        <Pressable><Text className="font-bold text-primary">{t('progress.back')}</Text></Pressable>
      </Link>
      <Text className="mt-[18px] text-[40px] font-extrabold tracking-[-1.5px] text-foreground">{t('progress.title')}</Text>
      <Text className="leading-[22px] text-muted-foreground">{t('progress.lead')}</Text>

      {skills.map(([name, value]) => (
        <View className="rounded-lg border border-border bg-surface p-[18px]" key={name}>
          <View className="flex-row justify-between">
            <Text className="font-extrabold text-foreground">{t(`progress.${name}`)}</Text>
            <Text className="font-extrabold text-primary">{value}</Text>
          </View>
          <View className="mt-3 h-1.5 overflow-hidden rounded-full bg-muted">
            <View className="h-1.5 rounded-full bg-primary" style={{ width: `${value}%` }} />
          </View>
        </View>
      ))}

      <View className="mt-2 rounded-lg bg-secondary p-5">
        <Text className="font-extrabold text-secondary-foreground">{t('progress.week')}</Text>
        <Text className="my-1.5 text-2xl font-extrabold text-secondary-foreground">{t('progress.weekValue')}</Text>
        <Text className="leading-[22px] text-secondary-foreground opacity-75">{t('progress.weekMeta')}</Text>
      </View>
    </ScrollView>
  );
}
