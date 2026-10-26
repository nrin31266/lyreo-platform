export function timecode(ms: number): string {
  const value = Math.max(0, Math.round(ms));
  return `${String(Math.floor(value / 60000)).padStart(2, '0')}:${String(Math.floor(value / 1000) % 60).padStart(2, '0')}.${String(value % 1000).padStart(3, '0')}`;
}
export function parseTimecode(value: string): number | null {
  const match = /^(\d{1,3}):([0-5]\d)(?:\.(\d{1,3}))?$/.exec(value.trim());
  return match
    ? Number(match[1]) * 60000 +
        Number(match[2]) * 1000 +
        Number((match[3] || '').padEnd(3, '0'))
    : null;
}
export function defaultRange(durationMs: number): [number, number] {
  return [0, durationMs <= 600000 ? durationMs : 300000];
}
