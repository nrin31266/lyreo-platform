import { adminI18nResources, normalizeLocale, type SupportedLocale } from '@lyreo/i18n';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';

const STORAGE_KEY = 'lyreo.locale';
const initialLocale = normalizeLocale(window.localStorage.getItem(STORAGE_KEY) ?? navigator.language);

export const i18n = i18next.createInstance();
void i18n.use(initReactI18next).init({
  resources: adminI18nResources,
  lng: initialLocale,
  fallbackLng: 'en',
  ns: ['common', 'admin'],
  defaultNS: 'common',
  interpolation: { escapeValue: false },
});

export function persistAdminLocale(locale: SupportedLocale) {
  window.localStorage.setItem(STORAGE_KEY, locale);
  return i18n.changeLanguage(locale);
}
