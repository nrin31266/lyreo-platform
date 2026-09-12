import { Link } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Text } from '@/components/ui/text';

/**
 * Representative Shadowing screen, not the final player implementation.
 * Context notes remain support annotations; they do not silently become dedicated practice modes.
 */
export default function LessonScreen() {
  const [showIpa, setShowIpa] = useState(false);
  const [recorded, setRecorded] = useState(false);
  const { t } = useTranslation('mobile');

  return (
    <ScrollView className="flex-1 bg-background" contentContainerStyle={{ padding: 24, paddingTop: 54, paddingBottom: 60 }}>
      <Link href="/" asChild>
        <Pressable className="mb-9"><Text className="font-bold text-primary">{t('lesson.back')}</Text></Pressable>
      </Link>
      <Text className="text-[11px] font-extrabold tracking-[1.6px] text-primary">{t('lesson.eyebrow')}</Text>

      <View className="my-[18px] items-center rounded-lg bg-primary p-[22px]">
        <Text className="text-[26px] tracking-[3px] text-secondary">▂▄▆█▅▃▂▅▇▆▃▂</Text>
        <Text className="mt-1 text-[13px] text-primary-foreground opacity-70">{t('lesson.audioMeta')}</Text>
      </View>

      <Text className="mt-[18px] text-[34px] font-bold leading-[44px] tracking-[-1.2px] text-foreground">
        Would you like to grab a cup of coffee after work?
      </Text>
      <Text className="mt-3.5 text-[15px] font-bold leading-[23px] text-primary">
        Would you LIKE / to GRAB a CUP of COFFEE / after WORK?
      </Text>

      {showIpa ? (
        <View className="mt-[18px] rounded-lg border border-border bg-surface p-[18px]">
          <Text className="mb-1.5 font-extrabold text-foreground">{t('lesson.ipaTitle')}</Text>
          <Text className="text-[13px] leading-5 text-muted-foreground">{t('lesson.ipaCopy')}</Text>
        </View>
      ) : null}

      <Button className="mt-[22px]" variant="outline" onPress={() => setShowIpa(value => !value)}>
        {showIpa ? t('lesson.hideIpa') : t('lesson.showIpa')}
      </Button>

      <Pressable className="mt-3.5 items-center rounded-full bg-primary p-[18px] active:opacity-80" onPress={() => setRecorded(true)}>
        <Text className="font-extrabold text-primary-foreground">● {recorded ? t('lesson.recorded') : t('lesson.hold')}</Text>
      </Pressable>

      {recorded ? (
        <View className="mt-5 rounded-lg border border-border bg-surface p-5">
          <View className="border-b border-border pb-4">
            <Text className="text-[45px] font-extrabold text-primary">84</Text>
            <Text className="text-[13px] leading-5 text-muted-foreground">{t('lesson.scoreMeta')}</Text>
          </View>

          <Text className="mb-1.5 mt-3.5 font-extrabold text-foreground">{t('lesson.expressions')}</Text>
          <Text className="mb-1 text-foreground">grab a cup of coffee · đi uống một tách cà phê</Text>
          <Text className="mb-1 text-foreground">would you like to… · bạn có muốn…</Text>

          <Text className="mb-1.5 mt-3.5 font-extrabold text-foreground">{t('lesson.grammarNote')}</Text>
          <Text className="mb-2 text-foreground">would you like to + V · lời mời/lời đề nghị lịch sự</Text>

          <Text className="text-[13px] leading-5 text-muted-foreground">{t('lesson.contextRule')}</Text>
        </View>
      ) : null}
    </ScrollView>
  );
}
