import { PortalHost } from '@rn-primitives/portal';
import type { PropsWithChildren } from 'react';
import { I18nextProvider } from 'react-i18next';
import { AuthProvider } from '../auth';
import { mobileI18n } from '../i18n';
import { AppThemeProvider } from './AppThemeProvider';
import { LocaleProvider } from './LocaleProvider';

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <I18nextProvider i18n={mobileI18n}>
      <LocaleProvider>
        <AppThemeProvider>
          <AuthProvider>
            {children}
            <PortalHost />
          </AuthProvider>
        </AppThemeProvider>
      </LocaleProvider>
    </I18nextProvider>
  );
}
