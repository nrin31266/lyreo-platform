import { primitiveColors } from './primitives';

export type ThemeMode = 'light' | 'dark';
export type ThemePreference = 'system' | ThemeMode;

export type ThemeColors = {
  background: string;
  foreground: string;
  surface: string;
  surfaceElevated: string;
  primary: string;
  primaryForeground: string;
  secondary: string;
  secondaryForeground: string;
  muted: string;
  mutedForeground: string;
  border: string;
  input: string;
  focusRing: string;
  success: string;
  warning: string;
  destructive: string;
  destructiveForeground: string;
  overlay: string;
};

export const semanticThemes: Record<ThemeMode, ThemeColors> = {
  light: {
    background: primitiveColors.stone100,
    foreground: primitiveColors.stone900,
    surface: primitiveColors.white,
    surfaceElevated: primitiveColors.white,
    primary: primitiveColors.blue700,
    primaryForeground: primitiveColors.white,
    secondary: primitiveColors.stone100,
    secondaryForeground: primitiveColors.stone900,
    muted: primitiveColors.stone100,
    mutedForeground: primitiveColors.stone600,
    border: primitiveColors.stone200,
    input: primitiveColors.stone500,
    focusRing: primitiveColors.blue700,
    success: primitiveColors.success700,
    warning: primitiveColors.warning700,
    destructive: primitiveColors.danger700,
    destructiveForeground: primitiveColors.white,
    overlay: 'rgba(28, 25, 23, 0.42)',
  },
  dark: {
    background: primitiveColors.stone900,
    foreground: primitiveColors.stone50,
    surface: primitiveColors.stone800,
    surfaceElevated: primitiveColors.stone700,
    // A lighter blue keeps native text links readable on dark surfaces.
    primary: primitiveColors.blue300,
    primaryForeground: primitiveColors.stone900,
    secondary: primitiveColors.stone700,
    secondaryForeground: primitiveColors.stone50,
    muted: primitiveColors.stone700,
    mutedForeground: primitiveColors.stone200,
    border: primitiveColors.stone600,
    input: primitiveColors.stone500,
    focusRing: primitiveColors.blue300,
    success: primitiveColors.success400,
    warning: primitiveColors.warning400,
    destructive: primitiveColors.danger400,
    destructiveForeground: primitiveColors.danger950,
    overlay: 'rgba(0, 0, 0, 0.68)',
  },
};
