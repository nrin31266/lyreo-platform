import { ScrollView } from 'react-native';
import { useTranslation } from 'react-i18next';
import { BackButton } from '@/components/navigation/back-button';
import { Text } from '@/components/ui/text';
import { LearnerProgress } from '@/features/progress/learner-progress';

export default function ProgressScreen() {
  const { t } = useTranslation('mobile');
  return (
    <ScrollView className="flex-1 bg-background" contentContainerStyle={{ padding: 24, gap: 16 }}>
      <BackButton label={t('progress.back')} />
      <Text accessibilityRole="header" className="text-3xl font-semibold tracking-tight text-foreground">
        {t('progress.title')}
      </Text>
      <LearnerProgress />
    </ScrollView>
  );
}
