import { weaknessMessageKey } from '@lyreo/i18n';
import { View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useCoreResource } from '@/api/use-core-resource';
import { EmptyState } from '@/components/states/empty-state';
import { ErrorState } from '@/components/states/error-state';
import { LoadingState } from '@/components/states/loading-state';
import { Card } from '@/components/ui/card';
import { Text } from '@/components/ui/text';

type Weakness = {
  weaknessType: string;
  weaknessKey: string;
  severity: number;
  sampleCount: number;
  label?: string | null;
  labelEn?: string | null;
};

type Overview = {
  hasHistory: boolean;
  weaknesses: Weakness[];
  week: { activeDays: number; studySeconds: number };
  skills: { skillCode: string; score: number }[];
};

const skillLabels = {
  GENERAL: 'progress.general',
  LISTENING: 'progress.listening',
  SPEAKING: 'progress.speaking',
  VOCABULARY: 'progress.vocabulary',
  GRAMMAR: 'progress.grammar',
  TOEIC_LISTENING: 'progress.toeicListening',
  TOEIC_READING: 'progress.toeicReading',
} as const;

export function LearnerProgress({ summary = false }: { summary?: boolean }) {
  const { t, i18n } = useTranslation(['mobile', 'common']);
  const { resource, reload } = useCoreResource<Overview>('/api/v1/analytics/overview');
  if (resource.status === 'loading') return <LoadingState />;
  if (resource.status === 'error') return <ErrorState onRetry={reload} />;

  const { data } = resource;
  const language = i18n.resolvedLanguage;
  const percent = new Intl.NumberFormat(language, { style: 'percent', maximumFractionDigits: 0 });
  const weaknesses = summary ? data.weaknesses.slice(0, 3) : data.weaknesses;
  return <>
    {!summary && data.hasHistory && <Card className="gap-3 p-4">
      <Text accessibilityRole="header" className="text-base font-semibold text-foreground">{t('progress.week')}</Text>
      <Text className="text-sm text-muted-foreground">{t('progress.activitySummary', {
        minutes: Math.round(data.week.studySeconds / 60),
        days: data.week.activeDays,
      })}</Text>
      {data.skills.map(({ skillCode, score }) => {
        const key = skillLabels[skillCode as keyof typeof skillLabels];
        return key ? <View key={skillCode} className="flex-row justify-between gap-3">
          <Text className="flex-1 text-foreground">{t(key)}</Text>
          <Text className="font-semibold text-foreground">{percent.format(score / 100)}</Text>
        </View> : null;
      })}
    </Card>}
    {weaknesses.length === 0 ? (
      <EmptyState title={t('common:weakness.title')} message={t('common:weakness.empty')} />
    ) : (
      <Card className="gap-3 p-4">
        <Text accessibilityRole="header" className="text-base font-semibold text-foreground">{t('common:weakness.title')}</Text>
        <Text className="text-sm text-muted-foreground">{t('common:weakness.help')}</Text>
        {weaknesses.map((item) => {
          const label = language?.startsWith('vi') ? item.label : item.labelEn ?? item.label;
          const title = label && label !== item.weaknessKey ? label : t(
            `common:${weaknessMessageKey(item.weaknessType, item.weaknessKey)}`,
            { part: item.weaknessKey },
          );
          return <View key={`${item.weaknessType}:${item.weaknessKey}`} className="flex-row items-start justify-between gap-3">
            <View className="min-w-0 flex-1 gap-0.5">
              <Text className="font-medium text-foreground">{title}</Text>
              <Text className="text-xs text-muted-foreground">{t('common:weakness.samples', { count: item.sampleCount })}</Text>
            </View>
            <Text className={item.severity >= 50 ? 'font-semibold text-destructive' : 'font-semibold text-warning'}>
              {percent.format(item.severity / 100)}
            </Text>
          </View>;
        })}
      </Card>
    )}
  </>;
}
