import { describe, expect, it } from 'vitest';
import { adminThemes } from '@lyreo/design-system';

function luminance(hex: string) {
  const rgb = hex
    .slice(1)
    .match(/../g)!
    .map((value) => {
      const channel = parseInt(value, 16) / 255;
      return channel <= 0.04045
        ? channel / 12.92
        : ((channel + 0.055) / 1.055) ** 2.4;
    });
  return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
}
function contrast(first: string, second: string) {
  const [light, dark] = [luminance(first), luminance(second)].sort(
    (a, b) => b - a,
  );
  return (light + 0.05) / (dark + 0.05);
}

describe('shared theme readability', () => {
  for (const [mode, theme] of Object.entries(adminThemes)) {
    it(`${mode} keeps text readable and unfocused form controls visible`, () => {
      for (const background of [
        theme.background,
        theme.surface,
        theme.surfaceElevated,
      ]) {
        for (const text of [
          theme.foreground,
          theme.mutedForeground,
          theme.success,
          theme.warning,
          theme.destructive,
        ]) {
          expect(
            contrast(text, background),
            `${text} on ${background}`,
          ).toBeGreaterThanOrEqual(4.5);
        }
        expect(contrast(theme.input, background)).toBeGreaterThanOrEqual(3);
        expect(contrast(theme.focusRing, background)).toBeGreaterThanOrEqual(3);
      }
      expect(
        contrast(theme.primaryForeground, theme.primary),
      ).toBeGreaterThanOrEqual(4.5);
      expect(
        contrast(theme.secondaryForeground, theme.secondary),
      ).toBeGreaterThanOrEqual(4.5);
      expect(
        contrast(theme.destructiveForeground, theme.destructive),
      ).toBeGreaterThanOrEqual(4.5);
    });
  }
});
