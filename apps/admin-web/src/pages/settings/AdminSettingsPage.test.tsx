import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { I18nextProvider } from 'react-i18next';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { i18n } from '@/i18n';
import { AppThemeProvider } from '@/app/providers/AppThemeProvider';
import { AdminSettingsPage } from './AdminSettingsPage';

vi.mock('@/pages/ai-settings/AiSettingsPage', () => ({
  AiSettingsPage: () => <div data-testid="embedded-ai-settings">AI Settings Studio</div>,
}));

let client: QueryClient;

beforeEach(async () => {
  vi.resetAllMocks();
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
  await i18n.changeLanguage('en');
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
});

afterEach(() => {
  cleanup();
  client.clear();
});

it('shows AI settings by default and switches to focused workspace preferences', async () => {
  render(
    <QueryClientProvider client={client}>
      <AppThemeProvider>
        <I18nextProvider i18n={i18n}>
          <MemoryRouter initialEntries={['/settings']}>
            <AdminSettingsPage />
          </MemoryRouter>
        </I18nextProvider>
      </AppThemeProvider>
    </QueryClientProvider>,
  );

  expect(screen.getByRole('heading', { name: 'Platform Settings' })).toBeInTheDocument();
  expect(screen.getByTestId('embedded-ai-settings')).toBeInTheDocument();

  const user = userEvent.setup();
  await user.click(screen.getByRole('tab', { name: 'Preferences' }));
  expect(screen.getByText('Workspace Language')).toBeInTheDocument();
  expect(screen.getByText('Appearance & Theme')).toBeInTheDocument();
});
