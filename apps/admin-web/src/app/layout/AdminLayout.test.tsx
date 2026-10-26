import { act, cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createInstance } from 'i18next';
import { I18nextProvider, initReactI18next } from 'react-i18next';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { adminI18nResources } from '@lyreo/i18n';
import { AdminLayout } from './AdminLayout';

const { logout } = vi.hoisted(() => ({ logout: vi.fn() }));

vi.mock('@/app/auth', () => ({
  useAuth: () => ({
    user: { profile: { preferred_username: 'Editor' } },
    logout,
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
  localStorage.clear();
  logout.mockClear();
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
  it.each([true, false])('opens logout from the avatar menu (desktop=%s)', async (isDesktop) => {
    desktop = isDesktop;
    const user = userEvent.setup();
    show();
    if (!isDesktop) await user.click(screen.getByRole('button', { name: 'Open navigation' }));
    expect(screen.queryByRole('menuitem', { name: 'Sign out' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
    const account = screen.getByRole('button', { name: 'Editor' });
    await user.click(account);
    expect(account).toHaveAttribute('aria-expanded', 'true');
    await user.keyboard('{Escape}');
    expect(account).toHaveFocus();
    if (!isDesktop) expect(screen.getByRole('dialog')).toBeVisible();
    await user.click(account);
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }));
    expect(logout).toHaveBeenCalledOnce();
  });

  it('uses one sidebar entry for AI configuration', () => {
    desktop = true;
    show();
    const link = screen.getByRole('link', { name: 'AI settings' });
    expect(link).toHaveAttribute('href', '/settings/ai');
    expect(screen.queryByRole('link', { name: 'AI routing' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'AI providers' })).not.toBeInTheDocument();
  });

  it('opens a labelled modal, closes with Escape and restores keyboard focus', async () => {
    const user = userEvent.setup();
    show();
    const menu = screen.getByRole('button', { name: 'Open navigation' });
    await user.click(menu);
    expect(menu).toHaveAttribute('aria-expanded', 'true');
    const dialog = screen.getByRole('dialog', { name: 'Main navigation' });
    expect(dialog.id).toBe(menu.getAttribute('aria-controls'));
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
        name: 'Processing jobs',
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
    expect(screen.getByRole('button', { name: 'Theme' })).toBeInTheDocument();
    const desktopTrigger = screen.getByRole('button', {
      name: 'Collapse sidebar',
    });
    expect(
      document.getElementById(desktopTrigger.getAttribute('aria-controls')!),
    ).toBeInTheDocument();
    desktop = false;
    await act(async () => {
      resize();
    });
    const mobileTrigger = screen.getByRole('button', {
      name: 'Open navigation',
    });
    expect(mobileTrigger).toHaveAttribute('aria-expanded', 'false');
    await user.click(mobileTrigger);
    expect(screen.getByRole('dialog').id).toBe(
      mobileTrigger.getAttribute('aria-controls'),
    );
    desktop = true;
    await act(async () => {
      resize();
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Collapse sidebar' }),
    ).toHaveAttribute('aria-expanded', 'true');
  });
  it('collapses desktop navigation, keeps links named and restores the saved preference', async () => {
    const user = userEvent.setup();
    desktop = true;
    show();
    const collapse = screen.getByRole('button', { name: 'Collapse sidebar' });
    await user.click(collapse);
    expect(
      screen.getByRole('button', { name: 'Expand sidebar' }),
    ).toHaveAttribute('aria-expanded', 'false');
    expect(
      screen.getByRole('button', { name: 'Lessons' }),
    ).toBeInTheDocument();
    expect(localStorage.getItem('lyreo.admin.sidebar-collapsed')).toBe('true');
    cleanup();
    show();
    expect(
      screen.getByRole('button', { name: 'Expand sidebar' }),
    ).toHaveAttribute('aria-expanded', 'false');
    await user.click(screen.getByRole('button', { name: 'Expand sidebar' }));
    expect(
      screen.getByRole('button', { name: 'Collapse sidebar' }),
    ).toHaveAttribute('aria-expanded', 'true');
  });
});
