import { cleanup, render, screen } from '@testing-library/react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, beforeEach, expect, it } from 'vitest';
import { i18n } from '@/i18n';
import { OverviewPage } from './OverviewPage';

beforeEach(async () => {
  await i18n.changeLanguage('en');
});

afterEach(() => {
  cleanup();
});

it('renders clean overview placeholder until all admin modules are finalized', () => {
  render(
    <I18nextProvider i18n={i18n}>
      <OverviewPage />
    </I18nextProvider>,
  );

  expect(screen.getByText('Admin workspace ready')).toBeInTheDocument();
});
