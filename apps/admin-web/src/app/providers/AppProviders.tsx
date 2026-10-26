import { QueryClientProvider } from "@tanstack/react-query";
import { queryClient } from "./query-client";
import type { PropsWithChildren } from "react";
import { I18nextProvider } from "react-i18next";
import { AuthProvider } from "@/app/auth";
import { i18n } from "@/i18n";
import { AppThemeProvider } from "./AppThemeProvider";

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <I18nextProvider i18n={i18n}>
      <AppThemeProvider>
        <QueryClientProvider client={queryClient}>
          <AuthProvider>{children}</AuthProvider>
        </QueryClientProvider>
      </AppThemeProvider>
    </I18nextProvider>
  );
}
