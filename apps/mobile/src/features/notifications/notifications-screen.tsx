import { FlatList, View } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useCoreResource } from '@/api/use-core-resource';
import { BackButton } from '@/components/navigation/back-button';
import { EmptyState } from '@/components/states/empty-state';
import { ErrorState } from '@/components/states/error-state';
import { LoadingState } from '@/components/states/loading-state';
import { Card } from '@/components/ui/card';
import { Text } from '@/components/ui/text';

type Announcement = {
  id: string;
  title: string;
  body: string;
  category: 'SYSTEM' | 'STUDY_REMINDER' | 'REWARD' | 'PROMO';
  publishedAt: string | null;
};

export function NotificationsScreen() {
  const { t, i18n } = useTranslation('mobile');
  const { resource, reload } = useCoreResource<Announcement[]>('/api/v1/notifications');
  const date = new Intl.DateTimeFormat(i18n.resolvedLanguage, { dateStyle: 'medium', timeStyle: 'short' });
  return <FlatList
    className="flex-1 bg-background"
    contentContainerStyle={{ padding: 24, gap: 12 }}
    data={resource.status === 'ready' ? resource.data : []}
    keyExtractor={(item) => item.id}
    refreshing={resource.status === 'loading'}
    onRefresh={reload}
    ListHeaderComponent={<View className="gap-2 pb-2">
      <BackButton label={t('progress.back')} />
      <Text accessibilityRole="header" className="text-3xl font-semibold tracking-tight text-foreground">{t('notifications.title')}</Text>
    </View>}
    ListEmptyComponent={resource.status === 'loading' ? <LoadingState />
      : resource.status === 'error' ? <ErrorState onRetry={reload} />
        : <EmptyState title={t('notifications.empty')} message={t('notifications.emptyHelp')} />}
    renderItem={({ item }) => <Card className="gap-2 p-4">
      <View className="flex-row flex-wrap justify-between gap-x-3 gap-y-1">
        <Text className="text-xs font-medium text-muted-foreground">{t(`notifications.categories.${item.category}`)}</Text>
        {item.publishedAt && <Text className="text-xs text-muted-foreground">{date.format(new Date(item.publishedAt))}</Text>}
      </View>
      <Text accessibilityRole="header" className="text-base font-semibold text-foreground">{item.title}</Text>
      <Text className="leading-5 text-foreground">{item.body}</Text>
    </Card>}
  />;
}
