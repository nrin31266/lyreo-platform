import { fireEvent, render } from '@testing-library/react-native';
import { useApiClient } from '@/api/api-provider';
import { useCoreResource } from '@/api/use-core-resource';
import { NotificationsScreen } from '@/features/notifications/notifications-screen';
import { LearnerProgress } from '@/features/progress/learner-progress';

jest.mock('@/api/api-provider', () => ({
  useApiClient: jest.fn(),
}));
jest.mock('@/api/use-core-resource', () => ({
  useCoreResource: jest.fn(),
}));
jest.mock('@/providers/AppThemeProvider', () => ({
  useAppTheme: () => ({ colors: {} }),
}));
jest.mock('expo-router', () => ({
  useRouter: () => ({ canGoBack: () => false, replace: jest.fn() }),
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { resolvedLanguage: 'en' } }),
}));

const coreResource = jest.mocked(useCoreResource);
const apiClient = jest.mocked(useApiClient);

it('shows the learner announcements returned by Core and loads subsequent pages when hasMore is true', async () => {
  const request = jest.fn().mockResolvedValueOnce({
    items: [{ id: 'msg-2', title: 'Older reminder', body: 'Keep your streak going.', category: 'STUDY_REMINDER', publishedAt: null, read: true, readAt: null }],
    total: 2,
    page: 1,
    size: 1,
    hasMore: false,
    unreadCount: 1,
  });
  apiClient.mockReturnValue({ request });
  coreResource.mockReturnValue({
    resource: {
      status: 'ready',
      data: {
        items: [{ id: 'message', title: 'New practice path', body: 'Open the new listening unit.', category: 'SYSTEM', publishedAt: null, read: false, readAt: null }],
        total: 2,
        page: 0,
        size: 1,
        hasMore: true,
        unreadCount: 1,
      },
    },
    reload: jest.fn(),
  });
  const view = await render(<NotificationsScreen />);
  expect(coreResource).toHaveBeenCalledWith('/api/v1/notifications');
  expect(view.getByText('New practice path')).toBeTruthy();
  expect(view.getByText('Open the new listening unit.')).toBeTruthy();

  await fireEvent.press(view.getByRole('button', { name: 'notifications.loadMore' }));
  expect(await view.findByText('Older reminder')).toBeTruthy();
  expect(request).toHaveBeenCalledWith('/api/v1/notifications?page=1&size=1');
  await view.unmount();
});

it('uses the authenticated analytics result instead of showing a sample weakness', async () => {
  coreResource.mockReturnValue({
    resource: { status: 'ready', data: { hasHistory: false, weaknesses: [] } },
    reload: jest.fn(),
  });
  const view = await render(<LearnerProgress summary />);
  expect(coreResource).toHaveBeenCalledWith('/api/v1/analytics/overview');
  expect(view.getByText('common:weakness.empty')).toBeTruthy();
  expect(view.queryByText('Past perfect')).toBeNull();
  await view.unmount();
});
