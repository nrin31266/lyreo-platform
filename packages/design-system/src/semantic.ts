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

/** Neutral operator palette with blue accents, independent of learner themes. */
export const adminThemes: Record<ThemeMode, ThemeColors> = {
  light: {
    background: '#FAFAFA',
    foreground: '#18181B',
    surface: '#FFFFFF',
    surfaceElevated: '#FFFFFF',
    primary: '#2563EB',
    primaryForeground: '#FFFFFF',
    secondary: '#F4F4F5',
    secondaryForeground: '#27272A',
    muted: '#F4F4F5',
    mutedForeground: '#52525B',
    border: '#E4E4E7',
    input: '#71717A',
    focusRing: '#2563EB',
    success: primitiveColors.green700,
    warning: primitiveColors.amber700,
    destructive: primitiveColors.red700,
    destructiveForeground: '#FFFFFF',
    overlay: 'rgba(0, 0, 0, 0.5)',
  },
  dark: {
    background: '#09090B',
    foreground: '#FAFAFA',
    surface: '#18181B',
    surfaceElevated: '#27272A',
    primary: '#93C5FD',
    primaryForeground: '#172554',
    secondary: '#27272A',
    secondaryForeground: '#FAFAFA',
    muted: '#27272A',
    mutedForeground: '#A1A1AA',
    border: '#3F3F46',
    input: '#71717A',
    focusRing: '#93C5FD',
    success: primitiveColors.green300,
    warning: primitiveColors.amber300,
    destructive: primitiveColors.red300,
    destructiveForeground: '#09090B',
    overlay: 'rgba(0, 0, 0, 0.7)',
  },
};

export const adminSidebarThemes = {
  light: {
    sidebar: '#FAFAFA',
    sidebarForeground: '#18181B',
    sidebarMutedForeground: '#52525B',
    sidebarPrimary: '#EFF6FF',
    sidebarPrimaryForeground: '#1D4ED8',
    sidebarAccent: '#F4F4F5',
    sidebarAccentForeground: '#18181B',
    sidebarBorder: '#E4E4E7',
    sidebarRing: '#2563EB',
  },
  dark: {
    sidebar: '#18181B',
    sidebarForeground: '#FAFAFA',
    sidebarMutedForeground: '#A1A1AA',
    sidebarPrimary: '#1E293B',
    sidebarPrimaryForeground: '#BFDBFE',
    sidebarAccent: '#27272A',
    sidebarAccentForeground: '#FAFAFA',
    sidebarBorder: '#3F3F46',
    sidebarRing: '#93C5FD',
  },
};

export const semanticColorRoles = Object.freeze(
  Object.keys(semanticThemes.light) as (keyof ThemeColors)[],
);
