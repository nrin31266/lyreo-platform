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
  ink700: '#6C625C',
  ink500: '#9B8275',
  ink300: '#C8A997',

  // Neutral paper
  paper50: '#FBFAF8',
  paper100: '#EFEBE6',
  paper200: '#DED8D2',
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
  ivory100: '#F7F6F3',

  // Lyreo dark surfaces
  cocoa950: '#211D1B',
  cocoa900: '#292421',
  cocoa800: '#332C28',
  cocoa700: '#44372F',
  cocoa500: '#8B7769',

  // Lyreo dark text & accents
  warmWhite50: '#FFF8EF',
  sand300: '#DEC4B3',
  copper400: '#DB9466',
  apricot300: '#F0B986',

  // Functional colours — intentionally independent from brand colours
  success700: '#2E7D5B',
  success400: '#6FD0A0',

  warning700: '#8A5918',
  warning400: '#E8B04A',

  danger950: '#1A1111',
  danger700: '#A64B47',
  danger400: '#E07A74',

  // Neutral console palette; Admin uses these roles independently of learner branding.
  slate50: '#F4F6F9',
  slate100: '#EDF1F6',
  slate200: '#DCE2EA',
  slate400: '#7E8B9D',
  slate500: '#73839B',
  slate600: '#536176',
  slate800: '#232D3C',
  slate850: '#1A212D',
  slate900: '#1B2433',
  slate950: '#131820',
  slateText: '#E6EDF7',
  slateMuted: '#A6B3C6',
  slateBorder: '#344156',
  indigo50: '#EDF0FF',
  indigo200: '#A5B4FC',
  indigo600: '#4558C8',
  indigo700: '#3847A4',
  green700: '#157347',
  green300: '#69CF9E',
  amber700: '#8A5A08',
  amber300: '#ECC46A',
  red700: '#BC3344',
  red300: '#FA929D',

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
