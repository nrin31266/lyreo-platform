import assert from 'node:assert/strict';
import test from 'node:test';
import { semanticThemes } from '../theme';

function luminance(hex: string) {
  const channels = [1, 3, 5].map(offset => {
    const value = parseInt(hex.slice(offset, offset + 2), 16) / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
}

for (const [mode, colors] of Object.entries(semanticThemes)) {
  test(`${mode} theme keeps text and actions readable`, () => {
    const pairs = [
      [colors.foreground, colors.background],
      [colors.foreground, colors.surface],
      [colors.primaryForeground, colors.primary],
      [colors.primary, colors.surface],
      [colors.secondaryForeground, colors.secondary],
      [colors.mutedForeground, colors.muted],
    ];
    for (const [text, background] of pairs) {
      const values = [luminance(text), luminance(background)];
      const contrast = (Math.max(...values) + 0.05) / (Math.min(...values) + 0.05);
      assert.ok(contrast >= 4.5, `${text} on ${background}: ${contrast.toFixed(2)}:1`);
    }
  });
}
