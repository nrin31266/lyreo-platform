import { PortalHost } from '@rn-primitives/portal';
import { SafeAreaProvider, SafeAreaView, initialWindowMetrics } from 'react-native-safe-area-context';
import type { PropsWithChildren } from 'react';
import { I18nextProvider } from 'react-i18next';
import { ApiProvider } from '@/api/api-provider';
import { SessionProvider } from '@/auth/session-provider';
import { mobileI18n } from '../i18n';
import { AppThemeProvider } from './AppThemeProvider';
import { LocaleProvider } from './LocaleProvider';

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <SafeAreaProvider initialMetrics={initialWindowMetrics}>
      <I18nextProvider i18n={mobileI18n}>
        <LocaleProvider>
          <AppThemeProvider>
            <SafeAreaView style={{ flex: 1 }}>
              <SessionProvider>
                <ApiProvider>
                  {children}
                  <PortalHost />
                </ApiProvider>
              </SessionProvider>
            </SafeAreaView>
          </AppThemeProvider>
        </LocaleProvider>
      </I18nextProvider>
    </SafeAreaProvider>
  );
}
