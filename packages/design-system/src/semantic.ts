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
    background: primitiveColors.paper50,
    foreground: primitiveColors.ink900,
    surface: primitiveColors.white,
    surfaceElevated: primitiveColors.white,
    primary: primitiveColors.forest900,
    primaryForeground: primitiveColors.paper50,
    secondary: primitiveColors.gold100,
    secondaryForeground: primitiveColors.forest900,
    muted: primitiveColors.paper100,
    mutedForeground: primitiveColors.ink700,
    border: primitiveColors.paper200,
    input: primitiveColors.paper200,
    focusRing: primitiveColors.forest500,
    success: primitiveColors.success700,
    warning: primitiveColors.gold500,
    destructive: primitiveColors.danger700,
    destructiveForeground: primitiveColors.white,
    overlay: primitiveColors.overlayLight,
  },
  dark: {
    background: primitiveColors.forest950,
    foreground: primitiveColors.paper50,
    surface: primitiveColors.forest800,
    surfaceElevated: primitiveColors.forest700,
    primary: primitiveColors.forest300,
    primaryForeground: primitiveColors.forest950,
    secondary: primitiveColors.gold950,
    secondaryForeground: primitiveColors.gold100,
    muted: primitiveColors.forest850,
    mutedForeground: primitiveColors.ink300,
    border: primitiveColors.forest650,
    input: primitiveColors.forest650,
    focusRing: primitiveColors.forest300,
    success: primitiveColors.success400,
    warning: primitiveColors.gold300,
    destructive: primitiveColors.danger400,
    destructiveForeground: primitiveColors.danger950,
    overlay: primitiveColors.overlayDark,
  },
};

export const semanticColorRoles = Object.freeze(Object.keys(semanticThemes.light) as (keyof ThemeColors)[]);
