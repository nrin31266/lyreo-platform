import AsyncStorage from '@react-native-async-storage/async-storage';
import { normalizeLocale, type SupportedLocale } from '@lyreo/i18n';
import { createContext, type PropsWithChildren, useContext, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { deviceLocale } from '../i18n';

const STORAGE_KEY = 'lyreo.locale';

type LocaleContextValue = {
  locale: SupportedLocale;
  setLocale: (locale: SupportedLocale) => Promise<void>;
};

const LocaleContext = createContext<LocaleContextValue | null>(null);


export function LocaleProvider({ children }: PropsWithChildren) {
  const { i18n } = useTranslation();
  const [locale, setLocaleState] = useState<SupportedLocale>(() => deviceLocale());

  useEffect(() => {
    let active = true;
    AsyncStorage.getItem(STORAGE_KEY)
      .then(saved => normalizeLocale(saved ?? deviceLocale()))
      .then(async next => {
        if (!active) return;
        setLocaleState(next);
        await i18n.changeLanguage(next);
      })
      .catch(() => i18n.changeLanguage(deviceLocale()));
    return () => { active = false; };
  }, [i18n]);

  async function setLocale(next: SupportedLocale) {
    await AsyncStorage.setItem(STORAGE_KEY, next);
    setLocaleState(next);
    await i18n.changeLanguage(next);
  }

  const value = useMemo(() => ({ locale, setLocale }), [locale]);
  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useAppLocale() {
  const value = useContext(LocaleContext);
  if (!value) throw new Error('useAppLocale must be used inside LocaleProvider');
  return value;
}
