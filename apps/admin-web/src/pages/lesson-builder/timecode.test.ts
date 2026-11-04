import { describe, expect, it } from 'vitest';
import { defaultRange, sourceOffset, parseTimecode, timecode } from './timecode';
describe('source range timecodes', () => {
  it('preselects two minutes from the URL offset, bounded by source duration', () => {
    expect(defaultRange(1207000)).toEqual([0,120000]);
    expect(defaultRange(1207000,'https://youtu.be/abcdefghijk?t=15s')).toEqual([15000,135000]);
    expect(defaultRange(45000)).toEqual([0,45000]);
    expect(defaultRange(45000,'https://youtu.be/abcdefghijk?t=40s')).toEqual([40000,45000]);
  });
  it('round trips millisecond boundaries and positions after minute ten', () => {
    for (const ms of [0, 59999, 60000, 600001, 7199999])
      expect(parseTimecode(timecode(ms))).toBe(ms);
    expect(parseTimecode('11:02.5')).toBe(662500);
    for (const invalid of ['2', '-1:00', '01:60.000', '00:01.1234', ''])
      expect(parseTimecode(invalid)).toBeNull();
  });
  it('reads URL offsets without inventing a selection', () => {
    expect(sourceOffset(7200000)).toBe(0);
    expect(sourceOffset(223000, 'https://youtu.be/abcdefghijk?t=5s')).toBe(5000);
    expect(sourceOffset(223000, 'https://youtu.be/abcdefghijk?t=3m40s')).toBe(220000);
    expect(sourceOffset(223000, 'https://youtu.be/abcdefghijk#t=1m2s')).toBe(62000);
    expect(sourceOffset(5000, 'https://youtu.be/abcdefghijk?start=99')).toBe(4999);
    expect(sourceOffset(5000, 'https://youtu.be/abcdefghijk?t=bad')).toBe(0);
  });
});
