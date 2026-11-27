import { cn } from '@/lib/utils';

export function DiamondIcon({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      className={cn('size-4 shrink-0 text-[var(--reward-diamond)]', className)}
    >
      <path
        d="M6.2 3.5h11.6l3.7 5.2L12 20.5 2.5 8.7 6.2 3.5Z"
        fill="currentColor"
        fillOpacity="0.18"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinejoin="round"
      />
      <path
        d="M2.5 8.7h19M8.3 3.5l-1.3 5.2L12 20.5l5-11.8-1.3-5.2"
        stroke="currentColor"
        strokeWidth="1.5"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function RewardPill({
  kind,
  value,
  prefix = '+',
  className,
}: {
  kind: 'xp' | 'diamond';
  value: number;
  prefix?: string;
  className?: string;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 rounded-full border bg-background px-2 py-0.5 text-xs font-semibold tabular-nums',
        kind === 'diamond'
          ? 'border-[var(--reward-diamond)]/30 text-[var(--reward-diamond)]'
          : 'border-[var(--reward-xp)]/25 text-[var(--reward-xp)]',
        className,
      )}
    >
      {kind === 'diamond' ? <DiamondIcon className="size-3.5" /> : null}
      <span>
        {prefix}
        {value.toLocaleString()}
      </span>
      {kind === 'xp' ? <span className="text-[10px] font-bold">XP</span> : null}
    </span>
  );
}
