import { View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { Text } from '@/components/ui/text';

export function EmptyState({ title, message }: { title?: string; message?: string }) {
  const { t } = useTranslation('mobile');
  return (
    <View className="items-start gap-2 rounded-lg border border-border bg-surface p-5">
      <Text className="text-lg font-extrabold text-foreground">
        {title ?? t('states.empty.title')}
      </Text>
      <Text className="leading-5 text-muted-foreground">
        {message ?? t('states.empty.message')}
      </Text>
    </View>
  );
}
