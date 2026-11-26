import { render } from '@testing-library/react-native';
import { useCoreResource } from '@/api/use-core-resource';
import { NotificationsScreen } from '@/features/notifications/notifications-screen';
import { LearnerProgress } from '@/features/progress/learner-progress';

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

it('shows the learner announcements returned by Core', async () => {
  coreResource.mockReturnValue({
    resource: {
      status: 'ready',
      data: [{ id: 'message', title: 'New practice path', body: 'Open the new listening unit.', category: 'SYSTEM', publishedAt: null }],
    },
    reload: jest.fn(),
  });
  const view = await render(<NotificationsScreen />);
  expect(coreResource).toHaveBeenCalledWith('/api/v1/notifications');
  expect(view.getByText('New practice path')).toBeTruthy();
  expect(view.getByText('Open the new listening unit.')).toBeTruthy();
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
});
