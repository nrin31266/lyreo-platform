import { RiLockUnlockLine, RiVipCrownLine } from '@remixicon/react';
import { useTranslation } from 'react-i18next';
import { Badge } from '@/components/ui/badge';
import { cn } from '@/lib/utils';

export function statusBadgeVariant(status?: string | null) {
  switch (status) {
    case 'PUBLISHED':
    case 'SUCCEEDED':
    case 'PASSED':
    case 'ENABLED':
    case 'COMPLETED':
      return 'success';
    case 'DRAFT':
    case 'SCHEDULED':
    case 'TRANSCRIPT_REVIEW':
    case 'TIMING_REVIEW':
    case 'CONTENT_REVIEW':
    case 'WARNING':
    case 'STALE':
      return 'warning';
    case 'FAILED':
    case 'AI_FAILED':
    case 'DELETED':
    case 'BLOCKED':
      return 'destructive';
    case 'UNCHECKED':
      return 'outline';
    default:
      return 'secondary';
  }
}

function difficultyBadgeVariant(difficulty: string | number | null | undefined) {
  switch (difficulty) {
    case 'EASY':
    case 1:
    case 2:
      return 'success';
    case 'NORMAL':
    case 3:
      return 'warning';
    case 'HARD':
    case 4:
    case 5:
      return 'destructive';
    default:
      return 'outline';
  }
}

export function DifficultyBadge({ difficulty }: { difficulty?: string | number | null }) {
  const { t } = useTranslation('admin');
  const label = typeof difficulty === 'number'
    ? t('library.difficultyNumber', { number: difficulty })
    : difficulty
      ? t(`rewardsHub.difficulties.${difficulty}`, { defaultValue: difficulty })
      : t('library.unclassified');
  return <Badge variant={difficultyBadgeVariant(difficulty)}>{label}</Badge>;
}

export function AchievementTierBadge({ tier, className }: { tier?: string | null; className?: string }) {
  const { t } = useTranslation('admin');
  const badgeTier = tier || 'BRONZE';
  return <Badge variant="outline" data-tier={badgeTier} className={cn('achievement-tier-badge', className)}>
    {t(`rewardsHub.tiers.${badgeTier}`, { defaultValue: badgeTier })}
  </Badge>;
}

export function AccessTierBadge({
  accessMode,
  size = 'md',
  className,
}: {
  accessMode?: 'PUBLIC' | 'FEATURE' | string | null;
  size?: 'sm' | 'md';
  className?: string;
}) {
  const { t } = useTranslation('admin');
  const isPlus = accessMode === 'FEATURE';
  return (
    <Badge
      variant={isPlus ? 'warning' : 'outline'}
      className={cn(
        'inline-flex items-center gap-1 font-medium',
        size === 'sm' ? 'h-5 px-1.5 text-[11px]' : '',
        className,
      )}
    >
      {isPlus ? (
        <RiVipCrownLine className={size === 'sm' ? 'size-3' : 'size-3.5'} aria-hidden="true" />
      ) : (
        <RiLockUnlockLine className={size === 'sm' ? 'size-3' : 'size-3.5'} aria-hidden="true" />
      )}
      <span>{t(`plansConsole.tiers.${isPlus ? 'PLUS' : 'FREE'}`)}</span>
    </Badge>
  );
}

const cefrColorClass: Record<string, string> = {
  A1: 'border-transparent bg-[var(--cefr-a1-bg)] text-[var(--cefr-a1-ink)]',
  A2: 'border-transparent bg-[var(--cefr-a2-bg)] text-[var(--cefr-a2-ink)]',
  B1: 'border-transparent bg-[var(--cefr-b1-bg)] text-[var(--cefr-b1-ink)]',
  B2: 'border-transparent bg-[var(--cefr-b2-bg)] text-[var(--cefr-b2-ink)]',
  C1: 'border-transparent bg-[var(--cefr-c1-bg)] text-[var(--cefr-c1-ink)]',
  C2: 'border-transparent bg-[var(--cefr-c2-bg)] text-[var(--cefr-c2-ink)]',
};

export function CefrLevelBadge({
  level,
  className,
}: {
  level?: string | null;
  className?: string;
}) {
  if (!level) return null;
  const upper = level.trim().toUpperCase();
  if (!upper || upper === 'UNKNOWN') return null;
  return (
    <Badge
      variant="outline"
      className={cn(
        'font-semibold tracking-wide',
        cefrColorClass[upper] ?? 'bg-muted text-muted-foreground',
        className,
      )}
    >
      {upper}
    </Badge>
  );
}

function posStyleClass(raw: string): string {
  const norm = raw.trim().toLowerCase();
  if (norm === 'noun' || norm === 'n') {
    return 'bg-[var(--pos-noun-bg)] text-[var(--pos-noun-ink)]';
  }
  if (norm === 'verb' || norm === 'v' || norm === 'phrasal verb') {
    return 'bg-[var(--pos-verb-bg)] text-[var(--pos-verb-ink)]';
  }
  if (norm === 'adjective' || norm === 'adj') {
    return 'bg-[var(--pos-adj-bg)] text-[var(--pos-adj-ink)]';
  }
  if (norm === 'adverb' || norm === 'adv') {
    return 'bg-[var(--pos-adv-bg)] text-[var(--pos-adv-ink)]';
  }
  return 'bg-[var(--pos-other-bg)] text-[var(--pos-other-ink)]';
}

export function PosTag({
  pos,
  label,
  className,
}: {
  pos?: string | null;
  label?: string;
  className?: string;
}) {
  if (!pos || !pos.trim()) return null;
  const parts = pos
    .split(',')
    .map((p) => p.trim())
    .filter(Boolean);
  if (parts.length === 0) return null;
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1', className)}>
      {parts.map((part) => (
        <span
          key={part}
          lang="en"
          className={cn(
            'inline-flex items-center rounded-md px-1.5 py-0.5 text-[11px] font-medium leading-none',
            posStyleClass(part),
          )}
        >
          {label ?? part}
        </span>
      ))}
    </span>
  );
}
