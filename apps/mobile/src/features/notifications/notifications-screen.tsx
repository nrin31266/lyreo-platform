import { FlatList, Image, View, type ImageSourcePropType } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useCoreResource } from '@/api/use-core-resource';
import { BackButton } from '@/components/navigation/back-button';
import { EmptyState } from '@/components/states/empty-state';
import { ErrorState } from '@/components/states/error-state';
import { LoadingState } from '@/components/states/loading-state';
import { Card } from '@/components/ui/card';
import { Text } from '@/components/ui/text';
import { useAppTheme } from '@/providers/AppThemeProvider';

type Announcement = {
  id: string;
  title: string;
  body: string;
  category: 'SYSTEM' | 'STUDY_REMINDER' | 'REWARD' | 'PROMO';
  publishedAt: string | null;
  read?: boolean;
  readAt?: string | null;
};

type NotificationFeedResponse = {
  items: Announcement[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
  unreadCount: number;
};

const categoryIcons: Record<Announcement['category'], ImageSourcePropType> = {
  SYSTEM: require('./icons/system.png'),
  STUDY_REMINDER: require('./icons/study.png'),
  REWARD: require('./icons/reward.png'),
  PROMO: require('./icons/offer.png'),
};

export function NotificationsScreen() {
  const { t, i18n } = useTranslation('mobile');
  const { colors } = useAppTheme();
  const { resource, reload } = useCoreResource<NotificationFeedResponse | Announcement[]>('/api/v1/notifications');
  const date = new Intl.DateTimeFormat(i18n.resolvedLanguage, { dateStyle: 'medium', timeStyle: 'short' });
  const items = resource.status === 'ready'
    ? (Array.isArray(resource.data) ? resource.data : (resource.data.items ?? []))
    : [];
  return <FlatList
    className="flex-1 bg-background"
    contentContainerStyle={{ padding: 24, gap: 12 }}
    data={items}
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
        <View className="flex-row items-center gap-1.5">
          <Image accessible={false} source={categoryIcons[item.category] ?? categoryIcons.SYSTEM}
            style={{ width: 18, height: 18, tintColor: colors.primary }} />
          <Text className="text-xs font-medium text-muted-foreground">{t(`notifications.categories.${item.category}`)}</Text>
        </View>
        {item.publishedAt && <Text className="text-xs text-muted-foreground">{date.format(new Date(item.publishedAt))}</Text>}
      </View>
      <Text accessibilityRole="header" className="text-base font-semibold text-foreground">{item.title}</Text>
      <Text className="leading-5 text-foreground">{item.body}</Text>
    </Card>}
  />;
}
