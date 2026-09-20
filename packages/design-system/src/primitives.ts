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
  // Neutral ink
  ink950: '#1B0E0B',
  ink900: '#2B1813',
  ink700: '#72594D',
  ink500: '#9B8275',
  ink300: '#C8A997',

  // Neutral paper
  paper50: '#FEF8F0',
  paper100: '#F8EDE3',
  paper200: '#E8D5C6',
  white: '#FFFFFF',

  // Lyreo brand — inspired by the mascot
  espresso950: '#1B0E0B',
  espresso900: '#24130F',
  chocolate800: '#2B1813',
  chocolate700: '#3A2018',
  brown600: '#4A2A20',
  brown500: '#56271C',
  chestnut500: '#86442C',

  copper500: '#CD865B',
  apricot400: '#EDB07C',
  peach300: '#F2C69E',
  cream200: '#F3D5B4',
  ivory100: '#FEF6E6',

    // Lyreo dark surfaces
  cocoa950: '#2A1812',
  cocoa900: '#362119',
  cocoa800: '#493025',
  cocoa700: '#56392D',
  cocoa500: '#765346',

  // Lyreo dark text & accents
  warmWhite50: '#FFF8EF',
  sand300: '#DEC4B3',
  copper400: '#DB9466',
  apricot300: '#F0B986',

  // Functional colours — intentionally independent from brand colours
  success700: '#2E7D5B',
  success400: '#6FD0A0',

  warning700: '#A66A18',
  warning400: '#E8B04A',

  danger950: '#1A1111',
  danger700: '#A64B47',
  danger400: '#E07A74',

  // Kept temporarily for backwards compatibility with possible consumers.
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

  overlayLight: 'rgba(43, 19, 15, 0.42)',
  overlayDark: 'rgba(10, 5, 4, 0.68)',
} as const;

export const spacing = Object.freeze(foundation.spacing);
export const radius = Object.freeze(foundation.radius);
export const motion = Object.freeze(foundation.motion);
export const typography = Object.freeze(foundation.typography);