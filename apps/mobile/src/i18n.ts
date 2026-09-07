import { mobileI18nResources, normalizeLocale, type SupportedLocale } from '@lyreo/i18n';
import { getLocales } from 'expo-localization';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';

export function deviceLocale(): SupportedLocale {
  const locale = getLocales()[0];
  return normalizeLocale(locale?.languageTag ?? locale?.languageCode);
}

export const mobileI18n = i18next.createInstance();
void mobileI18n.use(initReactI18next).init({
  resources: mobileI18nResources,
  lng: deviceLocale(),
  fallbackLng: 'en',
  ns: ['common', 'mobile'],
  defaultNS: 'common',
  interpolation: { escapeValue: false },
  initImmediate: false,
});
