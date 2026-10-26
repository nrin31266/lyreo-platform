import { semanticThemes } from "@lyreo/design-system";
export type ThemeMode = 'light' | 'dark';
export type ThemePreference = ThemeMode | 'system';
import {
  createContext,
  type PropsWithChildren,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
} from 'react';

const STORAGE_KEY = 'lyreo.theme';
const QUERY = '(prefers-color-scheme: dark)';

export type ThemeContextValue = {
  preference: ThemePreference;
  mode: ThemeMode;
  setPreference: (preference: ThemePreference) => void;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

function readPreference(): ThemePreference {
  const value = window.localStorage.getItem(STORAGE_KEY);
  return value === 'light' || value === 'dark' || value === 'system'
    ? value
    : 'system';
}

function systemMode(): ThemeMode {
  return window.matchMedia(QUERY).matches ? 'dark' : 'light';
}

export function AppThemeProvider({ children }: PropsWithChildren) {
  const [preference, setPreferenceState] =
    useState<ThemePreference>(readPreference);
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
    root.dataset.theme = mode;
    root.classList.toggle('dark', mode === 'dark');
    root.style.colorScheme = mode;
    root.style.setProperty('--success', semanticThemes[mode].success);
    root.style.setProperty('--warning', semanticThemes[mode].warning);
  }, [mode]);

  function setPreference(next: ThemePreference) {
    window.localStorage.setItem(STORAGE_KEY, next);
    setPreferenceState(next);
  }

  const value = useMemo(
    () => ({ preference, mode, setPreference }),
    [preference, mode],
  );
  return (
    <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
  );
}

export function useAppTheme() {
  const context = useContext(ThemeContext);
  if (!context)
    throw new Error('useAppTheme must be used inside AppThemeProvider');
  return context;
}
