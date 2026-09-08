import { radius, semanticColorRoles, semanticThemes, type ThemeMode, type ThemePreference } from '@lyreo/design-system';
import { createContext, type PropsWithChildren, useContext, useEffect, useLayoutEffect, useMemo, useState } from 'react';

const STORAGE_KEY = 'lyreo.theme';
const QUERY = '(prefers-color-scheme: dark)';

type ThemeContextValue = {
  preference: ThemePreference;
  mode: ThemeMode;
  setPreference: (preference: ThemePreference) => void;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

function readPreference(): ThemePreference {
  const value = window.localStorage.getItem(STORAGE_KEY);
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
}

function systemMode(): ThemeMode {
  return window.matchMedia(QUERY).matches ? 'dark' : 'light';
}

export function AppThemeProvider({ children }: PropsWithChildren) {
  const [preference, setPreferenceState] = useState<ThemePreference>(readPreference);
  const [system, setSystem] = useState<ThemeMode>(systemMode);
  const mode: ThemeMode = preference === 'system' ? system : preference;

  useEffect(() => {
    const media = window.matchMedia(QUERY);
    const onChange = () => setSystem(media.matches ? 'dark' : 'light');
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);

  useLayoutEffect(() => {
    const root = document.documentElement;
    const theme = semanticThemes[mode];
    root.dataset.theme = mode;
    root.classList.toggle('dark', mode === 'dark');
    root.style.colorScheme = mode;
    for (const [name, value] of Object.entries(radius)) {
      root.style.setProperty(`--lyreo-radius-${name}`, `${value}px`);
    }
    for (const role of semanticColorRoles) {
      root.style.setProperty(`--${role.replace(/[A-Z]/g, value => `-${value.toLowerCase()}`)}`, theme[role]);
    }
  }, [mode]);

  function setPreference(next: ThemePreference) {
    window.localStorage.setItem(STORAGE_KEY, next);
    setPreferenceState(next);
  }

  const value = useMemo(() => ({ preference, mode, setPreference }), [preference, mode]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useAppTheme() {
  const context = useContext(ThemeContext);
  if (!context) throw new Error('useAppTheme must be used inside AppThemeProvider');
  return context;
}
