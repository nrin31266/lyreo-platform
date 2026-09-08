import enAdmin from './locales/en/admin.json';
import enCommon from './locales/en/common.json';
import enMobile from './locales/en/mobile.json';
import viAdmin from './locales/vi/admin.json';
import viCommon from './locales/vi/common.json';
import viMobile from './locales/vi/mobile.json';

export const supportedLocales = ['en', 'vi'] as const;
export type SupportedLocale = (typeof supportedLocales)[number];
export const fallbackLocale: SupportedLocale = 'en';

export function normalizeLocale(input: string | null | undefined): SupportedLocale {
  const language = (input ?? '').trim().toLowerCase().split(/[-_]/)[0];
  return supportedLocales.includes(language as SupportedLocale)
    ? (language as SupportedLocale)
    : fallbackLocale;
}

export const adminI18nResources = {
  en: { common: enCommon, admin: enAdmin },
  vi: { common: viCommon, admin: viAdmin },
} as const;

export const mobileI18nResources = {
  en: { common: enCommon, mobile: enMobile },
  vi: { common: viCommon, mobile: viMobile },
} as const;
