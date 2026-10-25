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
    input: primitiveColors.ink500,

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

/** Dense Admin console; learner-facing themes retain their own brand palette. */
export const adminThemes: Record<ThemeMode, ThemeColors> = {
  light: {
    background: primitiveColors.slate50,
    foreground: primitiveColors.slate900,
    surface: primitiveColors.white,
    surfaceElevated: primitiveColors.white,
    primary: primitiveColors.indigo600,
    primaryForeground: primitiveColors.white,
    secondary: primitiveColors.indigo50,
    secondaryForeground: primitiveColors.indigo700,
    muted: primitiveColors.slate100,
    mutedForeground: primitiveColors.slate600,
    border: primitiveColors.slate200,
    input: primitiveColors.slate400,
    focusRing: primitiveColors.indigo600,
    success: primitiveColors.green700,
    warning: primitiveColors.amber700,
    destructive: primitiveColors.red700,
    destructiveForeground: primitiveColors.white,
    overlay: 'rgba(19, 24, 32, 0.45)',
  },
  dark: {
    background: primitiveColors.slate950,
    foreground: primitiveColors.slateText,
    surface: primitiveColors.slate850,
    surfaceElevated: primitiveColors.slate800,
    primary: primitiveColors.indigo200,
    primaryForeground: primitiveColors.slate950,
    secondary: primitiveColors.slate800,
    secondaryForeground: primitiveColors.indigo200,
    muted: primitiveColors.slate800,
    mutedForeground: primitiveColors.slateMuted,
    border: primitiveColors.slateBorder,
    input: primitiveColors.slate500,
    focusRing: primitiveColors.indigo200,
    success: primitiveColors.green300,
    warning: primitiveColors.amber300,
    destructive: primitiveColors.red300,
    destructiveForeground: primitiveColors.slate950,
    overlay: 'rgba(6, 10, 16, 0.7)',
  },
};

export const semanticColorRoles = Object.freeze(
  Object.keys(semanticThemes.light) as (keyof ThemeColors)[],
);
