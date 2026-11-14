import { describe, expect, it } from 'vitest';
import { defaultRange, sourceOffset, parseTimecode, timecode } from './timecode';
describe('source range timecodes', () => {
  it('selects the whole video up to ten minutes and leaves longer sources unselected', () => {
    expect(defaultRange(1207000)).toEqual([null,null]);
    expect(defaultRange(599999)).toEqual([0,599999]);
    expect(defaultRange(600000)).toEqual([0,600000]);
    expect(defaultRange(600001)).toEqual([null,null]);
    expect(defaultRange(45000)).toEqual([0,45000]);
    expect(defaultRange(0)).toEqual([null,null]);
  });
  it('allows a configured shorter threshold or disabling auto-selection without exceeding ten minutes', () => {
    expect(defaultRange(120000,2)).toEqual([0,120000]);
    expect(defaultRange(120001,2)).toEqual([null,null]);
    expect(defaultRange(45000,0)).toEqual([null,null]);
    expect(defaultRange(600001,20)).toEqual([null,null]);
    expect(defaultRange(300000,NaN)).toEqual([0,300000]);
    expect(defaultRange(300000,-1)).toEqual([0,300000]);
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
