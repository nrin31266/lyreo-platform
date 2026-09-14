import { ActivityIndicator, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useAppTheme } from '@/providers/AppThemeProvider';
import { Text } from '@/components/ui/text';

export function LoadingState({ label }: { label?: string }) {
  const { colors } = useAppTheme();
  const { t } = useTranslation('common');
  const loadingLabel = label ?? t('common:status.loading');
  return (
    <View className="items-center justify-center gap-3 rounded-lg bg-background p-6">
      <ActivityIndicator accessibilityLabel={loadingLabel} color={colors.primary} />
      <Text className="text-center text-sm text-muted-foreground">{loadingLabel}</Text>
    </View>
  );
}
