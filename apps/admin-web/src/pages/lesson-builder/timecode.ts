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
export function sourceOffset(durationMs: number, reference?: string): number {
  let seconds = 0;
  try {
    const url = new URL(reference || "");
    const value = url.searchParams.get("t") || url.searchParams.get("start") || url.hash.replace(/^#t=/, "");
    if (/^\d+(?:s)?$/.test(value)) seconds = Number(value.replace(/s$/, ""));
    else { const match = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(value); if (match) seconds = Number(match[1] || 0) * 3600 + Number(match[2] || 0) * 60 + Number(match[3] || 0); }
  } catch { /* A source without an offset begins at zero. */ }
  return Math.min(Math.max(0, seconds * 1000), Math.max(0, durationMs - 1));
}

export function defaultRange(durationMs: number, reference?: string): [number, number] {
  const start = sourceOffset(durationMs, reference);
  return [start, Math.min(durationMs, start + 120000)];
}
