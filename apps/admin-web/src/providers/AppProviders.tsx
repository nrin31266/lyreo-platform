import type { PropsWithChildren } from 'react';
import { I18nextProvider } from 'react-i18next';
import { i18n } from '../i18n';
import { AppThemeProvider } from './AppThemeProvider';

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <I18nextProvider i18n={i18n}>
      <AppThemeProvider>{children}</AppThemeProvider>
    </I18nextProvider>
  );
}
