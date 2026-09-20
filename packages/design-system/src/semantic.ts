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

    // Base surface < elevated surface
    surface: primitiveColors.paper50,
    surfaceElevated: primitiveColors.white,

    primary: primitiveColors.brown500,
    primaryForeground: primitiveColors.ivory100,

    secondary: primitiveColors.cream200,
    secondaryForeground: primitiveColors.brown500,

    muted: primitiveColors.paper100,
    mutedForeground: primitiveColors.ink700,

    border: primitiveColors.paper200,
    input: primitiveColors.paper200,

    // Darker tone for accessible focus indication
    focusRing: primitiveColors.chestnut500,

    success: primitiveColors.success700,
    warning: primitiveColors.warning700,

    destructive: primitiveColors.danger700,
    destructiveForeground: primitiveColors.white,

    overlay: primitiveColors.overlayLight,
  },

  dark: {
    background: primitiveColors.cocoa950,
    foreground: primitiveColors.warmWhite50,

    surface: primitiveColors.cocoa900,
    surfaceElevated: primitiveColors.cocoa800,

    // Primary must be the visible CTA/accent color
    primary: primitiveColors.copper400,
    primaryForeground: primitiveColors.cocoa950,

    // Keep secondary quieter than primary
    secondary: primitiveColors.cocoa700,
    secondaryForeground: primitiveColors.warmWhite50,

    muted: primitiveColors.cocoa700,
    mutedForeground: primitiveColors.sand300,

    border: primitiveColors.cocoa500,
    input: primitiveColors.cocoa500,

    focusRing: primitiveColors.apricot300,

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