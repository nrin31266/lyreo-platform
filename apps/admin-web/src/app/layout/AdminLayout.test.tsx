import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { adminI18nResources } from '@lyreo/i18n';
import { AdminLayout } from './AdminLayout';

vi.mock('@/app/auth', () => ({
  useAuth: () => ({
    user: { profile: { preferred_username: 'Editor' } },
    logout: vi.fn(),
  }),
}));
vi.mock('@/app/providers/AppThemeProvider', () => ({
  useAppTheme: () => ({ preference: 'light', setPreference: vi.fn() }),
}));
const i18n = createInstance();
let desktop = false;
let resize: () => void;
beforeEach(async () => {
  desktop = false;
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      get matches() {
        return desktop;
      },
      addEventListener: (_: string, fn: () => void) => {
        resize = fn;
      },
      removeEventListener: vi.fn(),
    })),
  );
  await i18n.use(initReactI18next).init({
    resources: adminI18nResources,
    lng: 'en',
    fallbackLng: 'en',
    ns: ['admin', 'common'],
    interpolation: { escapeValue: false },
  });
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
function show() {
  render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter>
        <Routes>
          <Route element={<AdminLayout />}>
            <Route path="/" element={<h1>Overview</h1>} />
            <Route path="/jobs" element={<h1>Job inspection</h1>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </I18nextProvider>,
  );
}

describe('shared Admin navigation', () => {
  it('opens a labelled modal, closes with Escape and restores keyboard focus', async () => {
    const user = userEvent.setup();
    show();
    const menu = screen.getByRole('button', { name: 'Open navigation' });
    await user.click(menu);
    expect(menu).toHaveAttribute('aria-expanded', 'true');
    const dialog = screen.getByRole('dialog', { name: 'Main navigation' });
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    expect(within(dialog).getByRole('navigation')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(menu).toHaveFocus();
  });

  it('closes navigation on route selection and when resizing to desktop', async () => {
    const user = userEvent.setup();
    show();
    const menu = screen.getByRole('button', { name: 'Open navigation' });
    await user.click(menu);
    await user.click(
      within(screen.getByRole('dialog')).getByRole('link', {
        name: 'Background Jobs',
      }),
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: 'Job inspection' }),
    ).toBeInTheDocument();
    await user.click(menu);
    desktop = true;
    await act(async () => {
      resize();
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Skip to content' }),
    ).toHaveAttribute('href', '#main-content');
    expect(
      screen.getByRole('combobox', { name: 'Language' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Theme' })).toBeInTheDocument();
  });
});
