import foundation from './foundation.json';

export const lyreoBrand = {
  name: 'Lyreo',
  mascot: 'Lyrebird',
  tagline: 'Listen. Notice. Speak.',
} as const;

/**
 * Brand/primitive values are implementation details of the design system.
 * Feature code should prefer semantic theme roles such as `background`,
 * `foreground`, `primary`, or `border` instead of reaching for these names.
 */
export const primitiveColors = {
  ink950: '#101714',
  ink900: '#17201D',
  ink700: '#4A5551',
  ink500: '#75807C',
  ink300: '#AEBAB5',

  paper50: '#F7F8F5',
  paper100: '#EEF1EF',
  paper200: '#DDE3DE',
  white: '#FFFFFF',

  forest950: '#0F1614',
  forest900: '#173E35',
  forest850: '#24312D',
  forest800: '#213D35',
  forest700: '#2B5C4E',
  forest650: '#34443E',
  forest600: '#3E6E5F',
  forest500: '#4F7D6D',
  forest300: '#8FC5B3',

  gold950: '#3A3121',
  gold700: '#9D7027',
  gold500: '#D9A441',
  gold300: '#E8BC62',
  gold100: '#F2E1B9',

  success700: '#2E7D5B',
  success400: '#6FD0A0',
  danger950: '#1A1111',
  danger700: '#A64B47',
  danger400: '#E07A74',

  overlayLight: 'rgba(15, 22, 20, 0.46)',
  overlayDark: 'rgba(0, 0, 0, 0.62)',
} as const;

export const spacing = Object.freeze(foundation.spacing);
export const radius = Object.freeze(foundation.radius);
export const motion = Object.freeze(foundation.motion);
export const typography = Object.freeze(foundation.typography);
