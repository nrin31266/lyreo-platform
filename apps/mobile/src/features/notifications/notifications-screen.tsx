import { useCallback, useEffect, useMemo, useState } from 'react';
import { FlatList, Image, View, type ImageSourcePropType } from 'react-native';
import { useTranslation } from 'react-i18next';
import { useApiClient } from '@/api/api-provider';
import { useCoreResource } from '@/api/use-core-resource';
import { BackButton } from '@/components/navigation/back-button';
import { EmptyState } from '@/components/states/empty-state';
import { ErrorState } from '@/components/states/error-state';
import { LoadingState } from '@/components/states/loading-state';
import { Button } from '@/components/ui/button';
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
  const client = useApiClient();
  const { resource, reload } = useCoreResource<NotificationFeedResponse | Announcement[]>('/api/v1/notifications');
  const [extraItems, setExtraItems] = useState<Announcement[]>([]);
  const [nextPage, setNextPage] = useState(1);
  const [extraHasMore, setExtraHasMore] = useState<boolean | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState(false);

  const firstPage = useMemo(() => (
    resource.status === 'ready'
      ? (Array.isArray(resource.data)
        ? { items: resource.data, size: 20, hasMore: false }
        : { items: resource.data.items ?? [], size: resource.data.size ?? 20, hasMore: Boolean(resource.data.hasMore) })
      : { items: [] as Announcement[], size: 20, hasMore: false }
  ), [resource]);

  useEffect(() => {
    setExtraItems([]);
    setNextPage(1);
    setExtraHasMore(null);
    setLoadMoreError(false);
  }, [resource]);

  const items = useMemo(() => {
    const merged: Announcement[] = [];
    const seen = new Set<string>();
    for (const entry of [...firstPage.items, ...extraItems]) {
      if (!seen.has(entry.id)) {
        seen.add(entry.id);
        merged.push(entry);
      }
    }
    return merged;
  }, [firstPage.items, extraItems]);

  const hasMore = extraHasMore ?? firstPage.hasMore;
  const pageSize = firstPage.size > 0 ? firstPage.size : 20;

  const handleRefresh = useCallback(() => {
    setExtraItems([]);
    setNextPage(1);
    setExtraHasMore(null);
    setLoadMoreError(false);
    reload();
  }, [reload]);

  const loadMore = useCallback(async () => {
    if (resource.status !== 'ready' || !hasMore || loadingMore) {
      return;
    }
    setLoadingMore(true);
    setLoadMoreError(false);
    try {
      const response = await client.request<NotificationFeedResponse | Announcement[]>(
        `/api/v1/notifications?page=${nextPage}&size=${pageSize}`,
      );
      const pageItems = Array.isArray(response) ? response : (response.items ?? []);
      const moreAvailable = Array.isArray(response) ? false : Boolean(response.hasMore);
      setExtraItems((prev) => [...prev, ...pageItems]);
      setNextPage((prev) => prev + 1);
      setExtraHasMore(moreAvailable);
    } catch {
      setLoadMoreError(true);
    } finally {
      setLoadingMore(false);
    }
  }, [client, hasMore, loadingMore, nextPage, pageSize, resource.status]);

  const date = new Intl.DateTimeFormat(i18n.resolvedLanguage, { dateStyle: 'medium', timeStyle: 'short' });
  return <FlatList
    className="flex-1 bg-background"
    contentContainerStyle={{ padding: 24, gap: 12 }}
    data={items}
    keyExtractor={(item) => item.id}
    refreshing={resource.status === 'loading'}
    onRefresh={handleRefresh}
    onEndReached={() => void loadMore()}
    onEndReachedThreshold={0.4}
    ListHeaderComponent={<View className="gap-2 pb-2">
      <BackButton label={t('progress.back')} />
      <Text accessibilityRole="header" className="text-3xl font-semibold tracking-tight text-foreground">{t('notifications.title')}</Text>
    </View>}
    ListEmptyComponent={resource.status === 'loading' ? <LoadingState />
      : resource.status === 'error' ? <ErrorState onRetry={handleRefresh} />
        : <EmptyState title={t('notifications.empty')} message={t('notifications.emptyHelp')} />}
    ListFooterComponent={hasMore ? <View className="gap-2 pt-2">
      {loadMoreError ? (
        <Text className="text-center text-xs text-destructive">{t('notifications.loadMoreFailed')}</Text>
      ) : null}
      <Button
        variant="outline"
        disabled={loadingMore}
        onPress={() => void loadMore()}
      >
        {loadingMore ? t('notifications.loadingMore') : t('notifications.loadMore')}
      </Button>
    </View> : null}
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
