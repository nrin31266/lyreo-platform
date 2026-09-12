import { View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { Button } from '@/components/ui/button';
import { Text } from '@/components/ui/text';

type ErrorStateProps = {
  title?: string;
  message?: string;
  onRetry?: () => void;
};

export function ErrorState({ title, message, onRetry }: ErrorStateProps) {
  const { t } = useTranslation(['mobile', 'common']);
  return (
    <View className="items-start gap-2 rounded-lg border border-border bg-surface p-5">
      <Text accessibilityRole="alert" className="text-lg font-extrabold text-foreground">
        {title ?? t('mobile:states.error.title')}
      </Text>
      <Text className="leading-5 text-muted-foreground">
        {message ?? t('mobile:states.error.message')}
      </Text>
      {onRetry ? (
        <Button className="mt-2" variant="outline" onPress={onRetry}>
          {t('common:actions.retry')}
        </Button>
      ) : null}
    </View>
  );
}
