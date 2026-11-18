/** Jump to either end without a deep offset; adjacent pages use a ranked cursor. */
export function lexiconPageParams(target: number, current: number, total: number | undefined, first?: string, last?: string) {
  const patch: Record<string, string | null> = { page: target ? String(target) : null, after: null, before: null, tail: null, skip: null, take: null };
  if (target <= 2) { patch.skip = target ? String(target * 30) : null; return patch; }
  if (total !== undefined && target >= Math.ceil(total / 30) - 3) {
    const take = Math.min(30, total - target * 30);
    patch.tail = 'true'; patch.take = String(take); patch.skip = String(total - (target * 30 + take));
    return patch;
  }
  if (target === current + 1 && last) { patch.after = last; return patch; }
  if (target === current - 1 && first) { patch.before = first; return patch; }
  throw new Error('Page cursor is unavailable');
}
