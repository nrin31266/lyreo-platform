import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  RiCheckDoubleLine,
  RiCheckLine,
  RiFlagLine,
  RiLockLine,
  RiRefreshLine,
  RiScissorsCutLine,
} from '@remixicon/react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { cn } from '@/lib/utils';
import type { CurriculumContentType, ItemSliceConfig } from './curriculum.api';
import {
  BossCrownIcon,
  BranchTrailIcon,
  contentAccentVar,
  ContentTypeIcon,
  formatCurriculumSliceBadge,
} from './curriculumMeta';

export type GraphPreviewItem = {
  key: string;
  position: number;
  contentType: CurriculumContentType;
  contentReferenceId: string;
  required: boolean;
  unlockRule: string;
  config?: ItemSliceConfig;
};

export type GraphPreviewSection = {
  key: string;
  title: string;
  items: GraphPreviewItem[];
};

type FlattenedStage = {
  stageNumber: number;
  sectionIndex: number;
  sectionKey: string;
  sectionTitle: string;
  item: GraphPreviewItem;
  unlocked: boolean;
  completed: boolean;
  isActive: boolean;
  isBoss: boolean;
};

export function CurriculumGraphPreview({
  sections,
  titleLookup = {},
}: {
  sections: GraphPreviewSection[];
  titleLookup?: Record<string, string>;
}) {
  const { t } = useTranslation('admin');
  const [completedKeys, setCompletedKeys] = useState<string[]>([]);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [chapterFilter, setChapterFilter] = useState<number | 'ALL'>('ALL');

  const stages = useMemo<FlattenedStage[]>(() => {
    const out: FlattenedStage[] = [];
    let runningStage = 0;
    let allPrevRequiredDone = true;

    sections.forEach((sec, secIdx) => {
      let lastRequiredIdx = -1;
      sec.items.forEach((it, idx) => {
        if (it.required !== false) lastRequiredIdx = idx;
      });

      sec.items.forEach((item, itemIdx) => {
        runningStage += 1;
        const completed = completedKeys.includes(item.key);
        const isOptional = item.required === false;
        const unlocked =
          runningStage === 1 ||
          allPrevRequiredDone ||
          (!isOptional && item.unlockRule === 'NONE');

        const isHardToeic =
          item.contentType === 'TOEIC_TEST' ||
          (item.contentType === 'TOEIC_DRILL' &&
            (itemIdx === lastRequiredIdx || (item.config?.questionCount ?? 0) >= 15));
        const isSectionCapstone =
          sec.items.length >= 3 && !isOptional && itemIdx === lastRequiredIdx;

        out.push({
          stageNumber: runningStage,
          sectionIndex: secIdx,
          sectionKey: sec.key,
          sectionTitle: sec.title,
          item,
          unlocked,
          completed,
          isActive: false,
          isBoss: isHardToeic || isSectionCapstone,
        });

        if (!isOptional && !completed) {
          allPrevRequiredDone = false;
        }
      });
    });

    const firstPlayable =
      out.find((s) => s.unlocked && !s.completed && s.item.required !== false) ??
      out.find((s) => s.unlocked && !s.completed);
    if (firstPlayable) {
      firstPlayable.isActive = true;
    }
    return out;
  }, [sections, completedKeys]);

  const activeStage = useMemo(() => {
    if (selectedKey) {
      const found = stages.find((s) => s.item.key === selectedKey);
      if (found) return found;
    }
    return stages.find((s) => s.isActive) ?? stages[0] ?? null;
  }, [stages, selectedKey]);

  const visibleChapters = useMemo(
    () =>
      sections
        .map((section, secIdx) => ({
          section,
          secIdx,
          secStages: stages.filter((s) => s.sectionIndex === secIdx),
        }))
        .filter(
          ({ secIdx, secStages }) =>
            secStages.length > 0 && (chapterFilter === 'ALL' || chapterFilter === secIdx),
        ),
    [sections, stages, chapterFilter],
  );

  function toggleComplete(stage: FlattenedStage) {
    setCompletedKeys((prev) =>
      prev.includes(stage.item.key)
        ? prev.filter((k) => k !== stage.item.key)
        : [...prev, stage.item.key],
    );
  }

  function completeSectionStages(secIdx: number) {
    const keysInSec = stages
      .filter((s) => s.sectionIndex === secIdx)
      .map((s) => s.item.key);
    setCompletedKeys((prev) => Array.from(new Set([...prev, ...keysInSec])));
  }

  return (
    <div className="grid gap-3.5">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-card px-3.5 py-2.5">
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Badge variant="outline" className="tabular-nums">
            {t('curriculumWorkspace.graph.completedCount', {
              done: completedKeys.length,
              total: stages.length,
            })}
          </Badge>
          <span className="hidden text-muted-foreground sm:inline">
            {t('curriculumWorkspace.graph.simulatorHelp')}
          </span>
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          {sections.length > 1 ? (
            <>
              <Button
                type="button"
                size="xs"
                variant={chapterFilter === 'ALL' ? 'secondary' : 'ghost'}
                className="tabular-nums"
                onClick={() => setChapterFilter('ALL')}
              >
                {t('curriculumWorkspace.graph.allChapters', { count: stages.length })}
              </Button>
              {sections.map((sec, idx) => {
                const secDone = stages.filter(
                  (s) => s.sectionIndex === idx && s.completed,
                ).length;
                return (
                  <Button
                    key={sec.key}
                    type="button"
                    size="xs"
                    variant={chapterFilter === idx ? 'secondary' : 'ghost'}
                    className="tabular-nums"
                    onClick={() => setChapterFilter(idx)}
                  >
                    {t('curriculumWorkspace.graph.chapterBadge', { number: idx + 1 })} ({secDone}/
                    {sec.items.length})
                  </Button>
                );
              })}
            </>
          ) : null}
          <Button
            type="button"
            size="xs"
            variant="ghost"
            onClick={() => setCompletedKeys([])}
          >
            <RiRefreshLine data-icon="inline-start" aria-hidden="true" />
            {t('curriculumWorkspace.graph.resetSim')}
          </Button>
        </div>
      </div>

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_19rem] xl:grid-cols-[minmax(0,1fr)_20.5rem]">
        <div className="space-y-4 rounded-2xl border bg-[var(--map-canvas)] p-3.5 sm:p-5">
          {stages.length === 0 ? (
            <div className="py-16 text-center text-sm text-muted-foreground">
              {t('curriculumWorkspace.graph.emptyMap')}
            </div>
          ) : (
            visibleChapters.map(({ section, secIdx, secStages }) => {
              const doneCount = secStages.filter((s) => s.completed).length;
              const allDone = secStages.length > 0 && doneCount === secStages.length;

              return (
                <section
                  key={section.key}
                  className={cn(
                    'rounded-2xl border bg-card/95 p-3.5 shadow-2xs sm:p-4',
                    allDone ? 'border-success/50' : 'border-border',
                  )}
                >
                  <header className="mb-3 flex flex-wrap items-center justify-between gap-2 border-b pb-2.5">
                    <div className="flex min-w-0 items-center gap-2.5">
                      <Badge
                        variant={allDone ? 'success' : 'default'}
                        className="shrink-0 tabular-nums"
                      >
                        {t('curriculumWorkspace.graph.chapterBadge', { number: secIdx + 1 })}
                      </Badge>
                      <h3 className="truncate text-sm font-semibold">{section.title}</h3>
                    </div>
                    <span className="shrink-0 text-xs font-medium text-muted-foreground tabular-nums">
                      {doneCount} / {secStages.length}
                    </span>
                  </header>

                  <div className="grid">
                    {secStages.map((stage, idx) => {
                      const title =
                        titleLookup[stage.item.contentReferenceId] ||
                        t(`curriculumWorkspace.contentTypes.${stage.item.contentType}`);
                      const sliceTag = formatCurriculumSliceBadge(stage.item.config, t);
                      const isSelected = activeStage?.item.key === stage.item.key;
                      const accent = contentAccentVar[stage.item.contentType];
                      const isOptional = stage.item.required === false;

                      let prevMain: FlattenedStage | null = null;
                      for (let i = idx - 1; i >= 0; i--) {
                        if (secStages[i].item.required !== false) {
                          prevMain = secStages[i];
                          break;
                        }
                      }
                      let nextMain: FlattenedStage | null = null;
                      for (let i = idx + 1; i < secStages.length; i++) {
                        if (secStages[i].item.required !== false) {
                          nextMain = secStages[i];
                          break;
                        }
                      }

                      const hasMainAbove = Boolean(prevMain);
                      const hasMainBelow = Boolean(nextMain);
                      const spineAboveDone = Boolean(
                        prevMain?.completed && (isOptional ? nextMain?.completed : stage.completed),
                      );
                      const spineBelowDone = Boolean(
                        (isOptional ? prevMain?.completed : stage.completed) && nextMain?.completed,
                      );

                      if (isOptional) {
                        const branchStroke = stage.completed
                          ? 'var(--map-road-done)'
                          : stage.unlocked
                            ? accent
                            : 'var(--map-stone-locked-edge)';

                        return (
                          <div
                            key={stage.item.key}
                            className="relative flex items-center gap-3 py-1.5 pl-14"
                          >
                            <div
                              className="pointer-events-none absolute inset-y-0 left-0 w-14"
                              aria-hidden="true"
                            >
                              {hasMainAbove ? (
                                <span
                                  className={cn(
                                    'absolute top-0 left-[27px] w-0.5',
                                    hasMainBelow ? 'bottom-0' : 'h-1/2',
                                    spineAboveDone && spineBelowDone
                                      ? 'bg-[var(--map-road-done)]'
                                      : 'bg-[var(--map-road)]',
                                  )}
                                />
                              ) : null}
                              <svg
                                viewBox="0 0 40 56"
                                className="absolute top-0 left-[27px] h-full w-10 overflow-visible"
                              >
                                <path
                                  d="M 1 0 C 1 20, 10 28, 30 28"
                                  fill="none"
                                  stroke={branchStroke}
                                  strokeWidth="2.5"
                                  strokeDasharray={stage.completed ? undefined : '4 4'}
                                  strokeLinecap="round"
                                />
                              </svg>
                            </div>

                            <div
                              style={
                                stage.completed
                                  ? undefined
                                  : stage.unlocked
                                    ? {
                                        borderColor: `color-mix(in srgb, ${accent} 65%, var(--border))`,
                                        color: accent,
                                      }
                                    : undefined
                              }
                              className={cn(
                                'relative z-10 flex size-11 shrink-0 items-center justify-center rounded-full border-2 border-dashed shadow-2xs transition-colors',
                                stage.completed
                                  ? 'border-solid border-[var(--map-stone-done-edge)] bg-[var(--map-stone-done)] text-primary-foreground'
                                  : stage.unlocked
                                    ? 'bg-background'
                                    : 'border-[var(--map-stone-locked-edge)] bg-[var(--map-stone-locked)] text-muted-foreground',
                              )}
                            >
                              <ContentTypeIcon
                                type={stage.item.contentType}
                                config={stage.item.config}
                                className="size-4.5"
                              />
                              {stage.completed ? (
                                <span className="absolute -top-1 -right-1 flex size-4 items-center justify-center rounded-full border border-background bg-[var(--map-stone-done-edge)] text-primary-foreground">
                                  <RiCheckLine className="size-2.5" aria-hidden="true" />
                                </span>
                              ) : !stage.unlocked ? (
                                <span className="absolute -top-1 -right-1 flex size-4 items-center justify-center rounded-full border border-background bg-muted text-muted-foreground">
                                  <RiLockLine className="size-2.5" aria-hidden="true" />
                                </span>
                              ) : null}
                            </div>

                            <button
                              type="button"
                              onClick={() => setSelectedKey(stage.item.key)}
                              aria-label={t('curriculumWorkspace.graph.stageAriaLabel', {
                                stage: stage.stageNumber,
                                title,
                              })}
                              className={cn(
                                'flex min-w-0 flex-1 items-center justify-between gap-3 rounded-2xl border border-dashed bg-muted/20 px-4 py-2.5 text-left transition-colors hover:bg-muted/45 focus-visible:outline-2 focus-visible:outline-ring',
                                isSelected
                                  ? 'border-primary bg-primary/5 ring-2 ring-primary/20'
                                  : 'border-border/80',
                              )}
                            >
                              <div className="min-w-0 flex-1 space-y-1.5">
                                <div className="flex flex-wrap items-center gap-1.5 text-xs">
                                  <span className="font-mono font-semibold text-muted-foreground tabular-nums">
                                    #{stage.stageNumber}
                                  </span>
                                  <span
                                    className="inline-flex items-center gap-1.5 rounded-lg border px-2 py-0.5 font-medium"
                                    style={{
                                      borderColor: `color-mix(in srgb, ${accent} 35%, var(--border))`,
                                      backgroundColor: `color-mix(in srgb, ${accent} 10%, var(--card))`,
                                      color: accent,
                                    }}
                                  >
                                    <ContentTypeIcon
                                      type={stage.item.contentType}
                                      config={stage.item.config}
                                      className="size-3.5"
                                    />
                                    {t(`curriculumWorkspace.contentTypes.${stage.item.contentType}`)}
                                  </span>
                                  <Badge variant="outline" className="h-5 gap-1 px-2 text-xs">
                                    <BranchTrailIcon className="size-3.5" />
                                    {t('curriculumWorkspace.graph.branchTrail')}
                                  </Badge>
                                </div>
                                <p className="line-clamp-2 text-sm font-medium leading-snug text-foreground sm:text-[15px]">
                                  {title}
                                </p>
                                {sliceTag ? (
                                  <p className="inline-flex items-center gap-1 text-xs text-muted-foreground tabular-nums">
                                    <RiScissorsCutLine className="size-3.5 shrink-0" aria-hidden="true" />
                                    <span className="truncate">{sliceTag}</span>
                                  </p>
                                ) : null}
                              </div>

                              <Badge
                                variant={
                                  stage.completed
                                    ? 'success'
                                    : stage.unlocked
                                      ? 'secondary'
                                      : 'outline'
                                }
                                className="shrink-0"
                              >
                                {stage.completed
                                  ? t('curriculumWorkspace.graph.statusCompleted')
                                  : stage.unlocked
                                    ? t('curriculumWorkspace.graph.statusUnlocked')
                                    : t('curriculumWorkspace.graph.statusLocked')}
                              </Badge>
                            </button>
                          </div>
                        );
                      }

                      return (
                        <div key={stage.item.key} className="flex items-stretch gap-3">
                          <div className="flex w-14 shrink-0 flex-col items-center">
                            <div
                              className={cn(
                                'h-3.5 w-0.5',
                                !hasMainAbove
                                  ? 'opacity-0'
                                  : spineAboveDone
                                    ? 'bg-[var(--map-road-done)]'
                                    : 'bg-[var(--map-road)]',
                              )}
                              aria-hidden="true"
                            />

                            <div
                              style={
                                stage.completed
                                  ? undefined
                                  : stage.isBoss && stage.unlocked
                                    ? {
                                        backgroundColor: 'var(--map-boss-bg)',
                                        borderColor: 'var(--map-boss-ring)',
                                        color: 'var(--map-boss-ink)',
                                      }
                                    : stage.isActive
                                      ? {
                                          backgroundColor: accent,
                                          borderColor: accent,
                                        }
                                      : stage.unlocked
                                        ? {
                                            borderColor: `color-mix(in srgb, ${accent} 65%, var(--border))`,
                                            color: accent,
                                          }
                                        : undefined
                              }
                              className={cn(
                                'relative flex shrink-0 items-center justify-center border-2 shadow-xs transition-colors',
                                stage.isBoss ? 'size-12 rounded-2xl' : 'size-11 rounded-full',
                                stage.completed
                                  ? 'border-[var(--map-stone-done-edge)] bg-[var(--map-stone-done)] text-primary-foreground'
                                  : stage.isBoss && stage.unlocked
                                    ? 'ring-4 ring-amber-400/25'
                                    : stage.isActive
                                      ? 'text-primary-foreground ring-4 ring-primary/20'
                                      : stage.unlocked
                                        ? 'bg-background'
                                        : 'border-[var(--map-stone-locked-edge)] bg-[var(--map-stone-locked)] text-muted-foreground',
                              )}
                            >
                              {stage.isBoss ? (
                                <span
                                  className={cn(
                                    'absolute -top-2.5 left-1/2 flex size-5.5 -translate-x-1/2 items-center justify-center rounded-full border shadow-2xs',
                                    stage.unlocked || stage.completed
                                      ? 'border-amber-600 bg-amber-400 text-amber-950'
                                      : 'border-border bg-muted text-amber-600/80',
                                  )}
                                  aria-hidden="true"
                                >
                                  <BossCrownIcon className="size-3.5" />
                                </span>
                              ) : null}

                              <ContentTypeIcon
                                type={stage.item.contentType}
                                config={stage.item.config}
                                className="size-5"
                              />

                              {stage.completed ? (
                                <span className="absolute -right-1 -bottom-1 flex size-4 items-center justify-center rounded-full border border-background bg-[var(--map-stone-done-edge)] text-primary-foreground">
                                  <RiCheckLine className="size-2.5" aria-hidden="true" />
                                </span>
                              ) : !stage.unlocked ? (
                                <span className="absolute -right-1 -bottom-1 flex size-4 items-center justify-center rounded-full border border-background bg-muted text-muted-foreground">
                                  <RiLockLine className="size-2.5" aria-hidden="true" />
                                </span>
                              ) : null}
                            </div>

                            <div
                              className={cn(
                                'min-h-3.5 w-0.5 flex-1',
                                !hasMainBelow
                                  ? 'opacity-0'
                                  : spineBelowDone
                                    ? 'bg-[var(--map-road-done)]'
                                    : 'bg-[var(--map-road)]',
                              )}
                              aria-hidden="true"
                            />
                          </div>

                          <button
                            type="button"
                            onClick={() => setSelectedKey(stage.item.key)}
                            aria-label={t('curriculumWorkspace.graph.stageAriaLabel', {
                              stage: stage.stageNumber,
                              title,
                            })}
                            className={cn(
                              'my-1.5 flex min-w-0 flex-1 items-center justify-between gap-3 rounded-2xl border bg-background px-4 py-3 text-left transition-all hover:bg-muted/50 focus-visible:outline-2 focus-visible:outline-ring',
                              isSelected
                                ? 'border-primary bg-primary/5 ring-2 ring-primary/20'
                                : stage.isBoss && stage.unlocked
                                  ? 'border-amber-500/50 bg-gradient-to-r from-amber-500/[0.04] to-violet-500/[0.03]'
                                  : stage.isActive
                                    ? 'border-primary/55 shadow-2xs'
                                    : 'border-border/80',
                            )}
                          >
                            <div className="min-w-0 flex-1 space-y-1.5">
                              <div className="flex flex-wrap items-center gap-1.5 text-xs">
                                <span className="font-mono font-semibold text-muted-foreground tabular-nums">
                                  #{stage.stageNumber}
                                </span>
                                <span
                                  className="inline-flex items-center gap-1.5 rounded-lg border px-2 py-0.5 font-medium"
                                  style={{
                                    borderColor: `color-mix(in srgb, ${accent} 35%, var(--border))`,
                                    backgroundColor: `color-mix(in srgb, ${accent} 10%, var(--card))`,
                                    color: accent,
                                  }}
                                >
                                  <ContentTypeIcon
                                    type={stage.item.contentType}
                                    config={stage.item.config}
                                    className="size-3.5"
                                  />
                                  {t(`curriculumWorkspace.contentTypes.${stage.item.contentType}`)}
                                </span>
                                {stage.isActive ? (
                                  <Badge variant="default" className="h-5 px-2 text-xs">
                                    {t('curriculumWorkspace.graph.startHere')}
                                  </Badge>
                                ) : null}
                                {stage.isBoss ? (
                                  <Badge
                                    variant="warning"
                                    className="h-5 gap-1 px-2 text-xs"
                                  >
                                    <BossCrownIcon className="size-3.5" />
                                    {t('curriculumWorkspace.graph.challengeBadge')}
                                  </Badge>
                                ) : null}
                              </div>

                              <p className="line-clamp-2 text-sm font-medium leading-snug text-foreground sm:text-[15px]">
                                {title}
                              </p>

                              {sliceTag ? (
                                <p className="inline-flex items-center gap-1 text-xs text-muted-foreground tabular-nums">
                                  <RiScissorsCutLine className="size-3.5 shrink-0" aria-hidden="true" />
                                  <span className="truncate">{sliceTag}</span>
                                </p>
                              ) : null}
                            </div>

                            <Badge
                              variant={
                                stage.completed
                                  ? 'success'
                                  : stage.unlocked
                                    ? 'secondary'
                                    : 'outline'
                              }
                              className="shrink-0"
                            >
                              {stage.completed
                                ? t('curriculumWorkspace.graph.statusCompleted')
                                : stage.unlocked
                                  ? t('curriculumWorkspace.graph.statusUnlocked')
                                  : t('curriculumWorkspace.graph.statusLocked')}
                            </Badge>
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </section>
              );
            })
          )}
        </div>

        <Card size="sm" className="lg:sticky lg:top-4">
          <CardHeader className="border-b">
            <CardTitle className="flex items-center gap-2 text-sm">
              <RiFlagLine className="size-4 text-primary" aria-hidden="true" />
              {t('curriculumWorkspace.graph.inspectorTitle')}
            </CardTitle>
            <CardDescription className="text-xs">
              {t('curriculumWorkspace.graph.inspectorHelp')}
            </CardDescription>
          </CardHeader>
          <CardContent className="pt-3.5">
            {activeStage ? (
              <div className="space-y-3.5">
                <div className="space-y-2 rounded-xl border bg-muted/20 p-3.5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span
                      className="inline-flex items-center gap-1.5 rounded-lg border px-2 py-0.5 text-xs font-medium"
                      style={{
                        borderColor: `color-mix(in srgb, ${contentAccentVar[activeStage.item.contentType]} 35%, var(--border))`,
                        backgroundColor: `color-mix(in srgb, ${contentAccentVar[activeStage.item.contentType]} 10%, var(--card))`,
                        color: contentAccentVar[activeStage.item.contentType],
                      }}
                    >
                      <ContentTypeIcon
                        type={activeStage.item.contentType}
                        config={activeStage.item.config}
                        className="size-4"
                      />
                      {t(`curriculumWorkspace.contentTypes.${activeStage.item.contentType}`)}
                    </span>
                    <Badge
                      variant={
                        activeStage.completed
                          ? 'success'
                          : activeStage.unlocked
                            ? 'secondary'
                            : 'outline'
                      }
                    >
                      {activeStage.completed
                        ? t('curriculumWorkspace.graph.statusCompleted')
                        : activeStage.unlocked
                          ? t('curriculumWorkspace.graph.statusUnlocked')
                          : t('curriculumWorkspace.graph.statusLocked')}
                    </Badge>
                  </div>
                  <p className="text-sm font-semibold leading-snug break-words tabular-nums">
                    #{activeStage.stageNumber} ·{' '}
                    {titleLookup[activeStage.item.contentReferenceId] ||
                      activeStage.item.contentReferenceId}
                  </p>
                  <p className="text-xs text-muted-foreground break-words">
                    {activeStage.sectionTitle}
                  </p>
                </div>

                <dl className="space-y-2 text-xs">
                  <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 border-b pb-1.5">
                    <dt className="shrink-0 text-muted-foreground">
                      {t('curriculumWorkspace.graph.roleLabel')}
                    </dt>
                    <dd className="text-right font-medium">
                      {activeStage.isBoss
                        ? t('curriculumWorkspace.graph.challengeBadge')
                        : activeStage.item.required !== false
                          ? t('curriculumWorkspace.graph.standardType')
                          : t('curriculumWorkspace.graph.branchTrail')}
                    </dd>
                  </div>
                  <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 border-b pb-1.5">
                    <dt className="shrink-0 text-muted-foreground">
                      {t('curriculumWorkspace.unlockRule')}
                    </dt>
                    <dd className="text-right font-medium break-words">
                      {activeStage.item.required === false
                        ? t('curriculumWorkspace.graph.branchHint')
                        : t(`curriculumWorkspace.unlockRules.${activeStage.item.unlockRule}`, {
                            defaultValue: activeStage.item.unlockRule,
                          })}
                    </dd>
                  </div>
                  <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 border-b pb-1.5">
                    <dt className="shrink-0 text-muted-foreground">
                      {t('curriculumWorkspace.graph.sliceLabel')}
                    </dt>
                    <dd className="text-right font-medium break-words tabular-nums">
                      {formatCurriculumSliceBadge(activeStage.item.config, t) ??
                        t('curriculumWorkspace.graph.fullSlice')}
                    </dd>
                  </div>
                  <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                    <dt className="shrink-0 text-muted-foreground">
                      {t('curriculumWorkspace.requiredItem')}
                    </dt>
                    <dd className="text-right font-medium">
                      {activeStage.item.required
                        ? t('curriculumWorkspace.graph.yes')
                        : t('curriculumWorkspace.graph.no')}
                    </dd>
                  </div>
                </dl>

                <div className="grid gap-2">
                  <Button
                    type="button"
                    size="sm"
                    className="w-full"
                    variant={activeStage.completed ? 'outline' : 'default'}
                    onClick={() => toggleComplete(activeStage)}
                  >
                    {activeStage.completed
                      ? t('curriculumWorkspace.graph.markIncomplete')
                      : t('curriculumWorkspace.graph.markCompleted')}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="ghost"
                    className="w-full"
                    onClick={() => completeSectionStages(activeStage.sectionIndex)}
                  >
                    <RiCheckDoubleLine data-icon="inline-start" aria-hidden="true" />
                    {t('curriculumWorkspace.graph.completeChapter')}
                  </Button>
                </div>
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                {t('curriculumWorkspace.graph.emptyMap')}
              </p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
