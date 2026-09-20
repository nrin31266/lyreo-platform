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
    background: primitiveColors.ivory100,
    foreground: primitiveColors.espresso950,

    surface: primitiveColors.white,
    surfaceElevated: primitiveColors.paper50,

    primary: primitiveColors.brown500,
    primaryForeground: primitiveColors.ivory100,

    secondary: primitiveColors.cream200,
    secondaryForeground: primitiveColors.brown500,

    muted: primitiveColors.paper100,
    mutedForeground: primitiveColors.ink700,

    border: primitiveColors.paper200,
    input: primitiveColors.paper200,

    focusRing: primitiveColors.copper500,

    success: primitiveColors.success700,
    warning: primitiveColors.warning700,

    destructive: primitiveColors.danger700,
    destructiveForeground: primitiveColors.white,

    overlay: primitiveColors.overlayLight,
  },

dark: {
  background: '#2A1812',
  foreground: '#FFF8EF',

  surface: '#362119',
  surfaceElevated: '#493025',

  primary: '#493025',
  primaryForeground: '#FFF8EF',

  secondary: '#DB9466',
  secondaryForeground: '#2A1812',

  muted: '#56392D',
  mutedForeground: '#DEC4B3',

  border: '#765346',
  input: '#765346',

  focusRing: '#F0B986',

  success: primitiveColors.success400,
  warning: primitiveColors.warning400,

  destructive: primitiveColors.danger400,
  destructiveForeground: primitiveColors.danger950,

  overlay: primitiveColors.overlayDark,
},
};

export const semanticColorRoles = Object.freeze(
  Object.keys(semanticThemes.light) as (keyof ThemeColors)[],
);