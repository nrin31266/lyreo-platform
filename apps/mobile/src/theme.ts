import { colors, radius, spacing } from '@lyreo/design-system';

export { colors, radius, spacing };

/**
 * Conservative shared card shadow for the starter UI.
 *
 * Keep visual primitives here/design-system instead of scattering literal values
 * through screens. Lyreo intentionally ships one default theme first; later theme
 * import/customization should replace tokens, not require rewriting every screen.
 */
export const shadow = {
  shadowColor: '#173E35',
  shadowOpacity: 0.08,
  shadowRadius: 18,
  shadowOffset: { width: 0, height: 9 },
  elevation: 2,
} as const;
