import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  semanticThemes,
  type ThemeColors,
  type ThemeMode,
  type ThemePreference,
} from '@lyreo/design-system';
import { vars } from 'nativewind';
import { createContext, type PropsWithChildren, useContext, useEffect, useMemo, useState } from 'react';
import { useColorScheme, View } from 'react-native';

const STORAGE_KEY = 'lyreo.theme';

type ThemeContextValue = {
  preference: ThemePreference;
  mode: ThemeMode;
  colors: ThemeColors;
  setPreference: (preference: ThemePreference) => Promise<void>;
};

const ThemeContext = createContext<ThemeContextValue | null>(null);

function isThemePreference(value: string | null): value is ThemePreference {
  return value === 'system' || value === 'light' || value === 'dark';
}

function themeVariables(colors: ThemeColors) {
  return vars({
    '--background': colors.background,
    '--foreground': colors.foreground,
    '--surface': colors.surface,
    '--surface-elevated': colors.surfaceElevated,
    '--primary': colors.primary,
    '--primary-foreground': colors.primaryForeground,
    '--secondary': colors.secondary,
    '--secondary-foreground': colors.secondaryForeground,
    '--muted': colors.muted,
    '--muted-foreground': colors.mutedForeground,
    '--border': colors.border,
    '--input': colors.input,
    '--focus-ring': colors.focusRing,
    '--success': colors.success,
    '--warning': colors.warning,
    '--destructive': colors.destructive,
    '--destructive-foreground': colors.destructiveForeground,
    '--overlay': colors.overlay,
  });
}

export function AppThemeProvider({ children }: PropsWithChildren) {
  const systemScheme = useColorScheme();
  const [preference, setPreferenceState] = useState<ThemePreference>('system');

  useEffect(() => {
    let active = true;
    AsyncStorage.getItem(STORAGE_KEY).then(saved => {
      if (active && isThemePreference(saved)) setPreferenceState(saved);
    });
    return () => { active = false; };
  }, []);

  const systemMode: ThemeMode = systemScheme === 'dark' ? 'dark' : 'light';
  const mode: ThemeMode = preference === 'system' ? systemMode : preference;
  const colors = semanticThemes[mode];

  async function setPreference(next: ThemePreference) {
    await AsyncStorage.setItem(STORAGE_KEY, next);
    setPreferenceState(next);
  }

  const value = useMemo(() => ({ preference, mode, colors, setPreference }), [preference, mode, colors]);

  return (
    <ThemeContext.Provider value={value}>
      <View className="flex-1 bg-background" style={themeVariables(colors)}>
        {children}
      </View>
    </ThemeContext.Provider>
  );
}

export function useAppTheme() {
  const value = useContext(ThemeContext);
  if (!value) throw new Error('useAppTheme must be used inside AppThemeProvider');
  return value;
}
