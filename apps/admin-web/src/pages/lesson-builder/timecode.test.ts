import { describe, expect, it } from 'vitest';
import { defaultRange, parseTimecode, timecode } from './timecode';
describe('source range timecodes', () => {
  it('round trips millisecond boundaries and positions after minute ten', () => {
    for (const ms of [0, 59999, 60000, 600001, 7199999])
      expect(parseTimecode(timecode(ms))).toBe(ms);
    expect(parseTimecode('11:02.5')).toBe(662500);
    for (const invalid of ['2', '-1:00', '01:60.000', '00:01.1234', ''])
      expect(parseTimecode(invalid)).toBeNull();
  });
  it('selects full short sources and only defaults long sources to five minutes', () => {
    expect(defaultRange(599999)).toEqual([0, 599999]);
    expect(defaultRange(600000)).toEqual([0, 600000]);
    expect(defaultRange(7200000)).toEqual([0, 300000]);
  });
});
