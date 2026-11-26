import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  RiAwardLine,
  RiBookOpenLine,
  RiCheckLine,
  RiFileList3Line,
  RiFlagLine,
  RiLockLine,
  RiPlayFill,
  RiRefreshLine,
  RiStarFill,
  RiVipCrownLine,
  RiVolumeUpLine,
} from '@remixicon/react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import type { CurriculumContentType, ItemSliceConfig } from './curriculum.api';

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
  sectionTitle: string;
  item: GraphPreviewItem;
  unlocked: boolean;
  completed: boolean;
  isActive: boolean;
  isBoss: boolean;
  plusLocked: boolean;
};


const typeBadgeClass: Record<CurriculumContentType, string> = {
  LESSON: 'bg-[var(--node-lesson)] text-primary-foreground',
  VOCABULARY_REVIEW: 'bg-[var(--node-vocab)] text-primary-foreground',
  GRAMMAR_PRACTICE: 'bg-[var(--node-grammar)] text-primary-foreground',
  TOEIC_DRILL: 'bg-[var(--node-toeic)] text-primary-foreground',
  TOEIC_TEST: 'bg-[var(--node-toeic)] text-primary-foreground',
};

function ContentTypeIcon({ type }: { type: CurriculumContentType }) {
  const cls = 'size-5';
  if (type === 'VOCABULARY_REVIEW') return <RiBookOpenLine className={cls} aria-hidden="true" />;
  if (type === 'GRAMMAR_PRACTICE') return <RiAwardLine className={cls} aria-hidden="true" />;
  if (type === 'TOEIC_DRILL' || type === 'TOEIC_TEST') {
    return <RiFileList3Line className={cls} aria-hidden="true" />;
  }
  return <RiVolumeUpLine className={cls} aria-hidden="true" />;
}

function formatSliceBadge(
  config: ItemSliceConfig | undefined,
  t: (k: string, o?: Record<string, unknown>) => string,
): string | null {
  if (!config) return null;
  if (Array.isArray(config.sentenceIds) && config.sentenceIds.length > 0) {
    return t('curriculumWorkspace.graph.sliceSentences', {
      count: config.sentenceIds.length,
    });
  }
  if (Array.isArray(config.entryIds) && config.entryIds.length > 0) {
    return t('curriculumWorkspace.graph.sliceWords', { count: config.entryIds.length });
  }
  if (config.questionCount != null || (Array.isArray(config.parts) && config.parts.length > 0)) {
    const partLabel =
      Array.isArray(config.parts) && config.parts.length > 0 ? config.parts.join(',') : 'ALL';
    return t('curriculumWorkspace.graph.sliceQuestions', {
      count: config.questionCount ?? 10,
      part: partLabel,
    });
  }
  if (config.targetTurns != null) {
    return t('curriculumWorkspace.graph.sliceTurns', { count: config.targetTurns });
  }
  if (config.activityType) return String(config.activityType);
  return null;
}

export function CurriculumGraphPreview({
  sections,
  pathAccessMode,
  titleLookup = {},
}: {
  sections: GraphPreviewSection[];
  pathAccessMode: 'PUBLIC' | 'FEATURE';
  titleLookup?: Record<string, string>;
}) {
  const { t } = useTranslation('admin');
  const [completedKeys, setCompletedKeys] = useState<string[]>([]);
  const [simTier, setSimTier] = useState<'FREE' | 'PLUS'>('PLUS');
  const [selectedKey, setSelectedKey] = useState<string | null>(null);

  const stages = useMemo<FlattenedStage[]>(() => {
    const out: FlattenedStage[] = [];
    let runningStage = 0;
    const totalCount = sections.reduce((sum, sec) => sum + sec.items.length, 0);

    sections.forEach((sec, secIdx) => {
      sec.items.forEach((item, itemIdx) => {
        runningStage += 1;
        const prev = out[out.length - 1];
        const prevCompleted = prev ? completedKeys.includes(prev.item.key) : true;
        const completed = completedKeys.includes(item.key);
        const plusLocked = simTier === 'FREE' && pathAccessMode === 'FEATURE';
        const unlocked =
          !plusLocked &&
          (item.unlockRule === 'NONE' ||
            runningStage === 1 ||
            prevCompleted ||
            itemIdx === 0 && secIdx === 0);
        const isLastInSection = itemIdx === sec.items.length - 1;
        const isLastInPath = runningStage === totalCount;
        out.push({
          stageNumber: runningStage,
          sectionIndex: secIdx,
          sectionTitle: sec.title,
          item,
          unlocked,
          completed,
          isActive: false,
          isBoss: isLastInPath || (sec.items.length > 2 && isLastInSection),
          plusLocked,
        });
      });
    });

    const firstPlayable = out.find((s) => s.unlocked && !s.completed);
    if (firstPlayable) {
      firstPlayable.isActive = true;
    }
    return out;
  }, [sections, completedKeys, simTier, pathAccessMode]);

  const activeStage = useMemo(() => {
    if (selectedKey) {
      const found = stages.find((s) => s.item.key === selectedKey);
      if (found) return found;
    }
    return stages.find((s) => s.isActive) ?? stages[0] ?? null;
  }, [stages, selectedKey]);

  const stagesBySection = useMemo(() => {
    return sections.map((sec, secIdx) => ({
      section: sec,
      secIdx,
      stages: stages.filter((s) => s.sectionIndex === secIdx),
    }));
  }, [sections, stages]);

  function toggleComplete(stage: FlattenedStage) {
    if (stage.plusLocked) return;
    setCompletedKeys((prev) =>
      prev.includes(stage.item.key)
        ? prev.filter((k) => k !== stage.item.key)
        : [...prev, stage.item.key],
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border bg-muted/20 px-4 py-3">
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <Badge variant="outline" className="tabular-nums">
            {t('curriculumWorkspace.graph.completedCount', {
              done: completedKeys.length,
              total: stages.length,
            })}
          </Badge>
          {pathAccessMode === 'FEATURE' ? (
            <Badge variant="warning" className="gap-1">
              <RiVipCrownLine className="size-3.5" aria-hidden="true" />
              Plus
            </Badge>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant={simTier === 'FREE' ? 'default' : 'outline'}
            onClick={() => setSimTier('FREE')}
          >
            {t('curriculumWorkspace.graph.simulateFree')}
          </Button>
          <Button
            type="button"
            size="sm"
            variant={simTier === 'PLUS' ? 'default' : 'outline'}
            onClick={() => setSimTier('PLUS')}
          >
            <RiVipCrownLine data-icon="inline-start" aria-hidden="true" />
            {t('curriculumWorkspace.graph.simulatePlus')}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => setCompletedKeys([])}
          >
            <RiRefreshLine data-icon="inline-start" aria-hidden="true" />
            {t('curriculumWorkspace.graph.resetSim')}
          </Button>
        </div>
      </div>

      <div className="grid items-start gap-5 xl:grid-cols-12">
        <div className="xl:col-span-8 rounded-2xl border bg-[var(--map-canvas)] p-4 sm:p-6">
          {stages.length === 0 ? (
            <div className="py-16 text-center text-sm text-muted-foreground">
              {t('curriculumWorkspace.graph.emptyMap')}
            </div>
          ) : (
            <div className="space-y-8">
              {stagesBySection.map(({ section, secIdx, stages: secStages }) => {
                const secDone = secStages.filter((s) => s.completed).length;
                const sCurveX = [210, 410, 480, 310, 180, 370] as const;
                const stepY = 132;
                const topPad = 76;
                const requiredStages = secStages.filter((s) => s.item.required !== false);
                const mainSource = requiredStages.length > 0 ? requiredStages : secStages;
                const mainKeys = new Set(mainSource.map((s) => s.item.key));

                const mainNodes = mainSource.map((stage, idx) => ({
                  stage,
                  x: sCurveX[idx % sCurveX.length],
                  y: topPad + idx * stepY,
                  isBranch: false as const,
                }));

                const branchCounts = new Map<string, number>();
                let anchor = mainNodes[0] ?? { x: 210, y: topPad };
                const branchNodes: Array<{
                  stage: (typeof secStages)[number];
                  x: number;
                  y: number;
                  isBranch: true;
                  anchorX: number;
                  anchorY: number;
                }> = [];

                let mainCursor = 0;
                for (const stage of secStages) {
                  if (mainKeys.has(stage.item.key)) {
                    anchor = mainNodes[mainCursor] ?? anchor;
                    mainCursor += 1;
                    continue;
                  }
                  const anchorKey = `${anchor.x}:${anchor.y}`;
                  const count = branchCounts.get(anchorKey) ?? 0;
                  branchCounts.set(anchorKey, count + 1);
                  const dir = anchor.x > 340 ? -1 : 1;
                  branchNodes.push({
                    stage,
                    x: Math.max(96, Math.min(584, anchor.x + dir * 160)),
                    y: anchor.y + 18 + count * 92,
                    isBranch: true,
                    anchorX: anchor.x,
                    anchorY: anchor.y,
                  });
                }

                const allNodes = [...mainNodes, ...branchNodes];
                const canvasHeight =
                  Math.max(...allNodes.map((node) => node.y), topPad) + 96;

                return (
                  <div key={section.key} className="space-y-4">
                    <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border bg-background/90 px-4 py-2.5 shadow-2xs">
                      <div className="flex items-center gap-2.5">
                        <Badge variant="default" className="tabular-nums">
                          {t('curriculumWorkspace.graph.chapterBadge', { number: secIdx + 1 })}
                        </Badge>
                        <h3 className="text-sm font-semibold">{section.title}</h3>
                      </div>
                      <span className="text-xs font-medium text-muted-foreground tabular-nums">
                        {secDone} / {secStages.length}
                      </span>
                    </div>

                    <div
                      className="relative mx-auto w-full max-w-[680px]"
                      style={{ height: `${canvasHeight}px` }}
                    >
                      <svg
                        viewBox={`0 0 680 ${canvasHeight}`}
                        preserveAspectRatio="none"
                        className="pointer-events-none absolute inset-0 h-full w-full"
                        aria-hidden="true"
                      >
                        {mainNodes.slice(0, -1).map((from, idx) => {
                          const to = mainNodes[idx + 1];
                          const midY = (from.y + to.y) / 2;
                          const d = `M ${from.x} ${from.y} C ${from.x} ${midY}, ${to.x} ${midY}, ${to.x} ${to.y}`;
                          return (
                            <g key={`spine-${from.stage.item.key}-${to.stage.item.key}`}>
                              <path
                                d={d}
                                fill="none"
                                stroke="var(--map-road)"
                                strokeWidth="18"
                                strokeLinecap="round"
                                opacity="0.55"
                              />
                              <path
                                d={d}
                                fill="none"
                                stroke={
                                  from.stage.completed
                                    ? 'var(--map-road-done)'
                                    : 'var(--map-stone-locked-edge)'
                                }
                                strokeWidth="3.5"
                                strokeDasharray="6 8"
                                strokeLinecap="round"
                              />
                            </g>
                          );
                        })}
                        {branchNodes.map((node) => {
                          const ctrlX = (node.anchorX + node.x) / 2;
                          const ctrlY = node.anchorY - 14;
                          const d = `M ${node.anchorX} ${node.anchorY} Q ${ctrlX} ${ctrlY} ${node.x} ${node.y}`;
                          return (
                            <path
                              key={`branch-${node.stage.item.key}`}
                              d={d}
                              fill="none"
                              stroke={
                                node.stage.completed
                                  ? 'var(--map-road-done)'
                                  : 'var(--map-branch)'
                              }
                              strokeWidth="3.5"
                              strokeDasharray="5 6"
                              strokeLinecap="round"
                            />
                          );
                        })}
                      </svg>

                      {allNodes.map((node) => {
                        const { stage } = node;
                        const title =
                          titleLookup[stage.item.contentReferenceId] ||
                          t(`curriculumWorkspace.contentTypes.${stage.item.contentType}`);
                        const sliceTag = formatSliceBadge(stage.item.config, t);
                        const isSelected = activeStage?.item.key === stage.item.key;
                        const isBoss = stage.isBoss;

                        const stoneColor = stage.completed
                          ? 'bg-[var(--map-stone-done)] border-[var(--map-stone-done-edge)] text-primary-foreground'
                          : stage.isActive
                            ? 'bg-[var(--map-stone-active)] border-[var(--map-stone-active-edge)] text-primary-foreground ring-4 ring-warning/30'
                            : isBoss && stage.unlocked && !stage.plusLocked
                              ? 'bg-[var(--map-boss-bg)] border-[var(--map-boss-ring)] text-[var(--map-boss-ink)] ring-4 ring-[var(--map-boss-ring)]/55'
                              : stage.unlocked
                                ? 'bg-background border-primary/45 text-foreground hover:border-primary'
                                : 'bg-[var(--map-stone-locked)] border-[var(--map-stone-locked-edge)] text-muted-foreground opacity-85';

                        return (
                          <div
                            key={stage.item.key}
                            style={{
                              left: `${(node.x / 680) * 100}%`,
                              top: `${node.y}px`,
                            }}
                            className="absolute z-10 flex w-36 -translate-x-1/2 -translate-y-1/2 flex-col items-center text-center"
                          >
                            <button
                              type="button"
                              onClick={() => setSelectedKey(stage.item.key)}
                              aria-label={t('curriculumWorkspace.graph.stageAriaLabel', {
                                stage: stage.stageNumber,
                                title,
                              })}
                              className={`group relative flex flex-col items-center justify-center rounded-full border-2 border-b-4 transition-all hover:-translate-y-0.5 active:translate-y-0 ${
                                isBoss
                                  ? 'size-20'
                                  : node.isBranch
                                    ? 'size-14 border-dashed'
                                    : 'size-16'
                              } ${stoneColor} ${
                                isSelected ? 'scale-105 outline-2 outline-ring outline-offset-3' : ''
                              }`}
                            >
                              {isBoss ? (
                                <span className="absolute -top-3.5 rounded-full border border-[var(--map-boss-ring)] bg-[var(--map-boss-bg)] px-2 py-0.5 text-[10px] font-bold tracking-wide text-[var(--map-boss-ink)] uppercase shadow-xs">
                                  {t('curriculumWorkspace.graph.bossBadge')}
                                </span>
                              ) : stage.isActive ? (
                                <span className="absolute -top-3 rounded-full bg-warning px-2 py-0.5 text-[10px] font-bold tracking-wide text-primary-foreground uppercase shadow-xs">
                                  {t('curriculumWorkspace.graph.startHere')}
                                </span>
                              ) : node.isBranch ? (
                                <span className="absolute -top-2.5 rounded-full border bg-background px-1.5 py-0.5 text-[9px] font-semibold text-muted-foreground shadow-2xs">
                                  {t('curriculumWorkspace.graph.bonusSideQuest')}
                                </span>
                              ) : null}

                              {stage.plusLocked ? (
                                <RiLockLine className="size-5" aria-hidden="true" />
                              ) : stage.completed ? (
                                <RiCheckLine className="size-6" aria-hidden="true" />
                              ) : isBoss ? (
                                <RiVipCrownLine className="size-6" aria-hidden="true" />
                              ) : stage.isActive ? (
                                <RiPlayFill className="size-6" aria-hidden="true" />
                              ) : stage.unlocked ? (
                                <ContentTypeIcon type={stage.item.contentType} />
                              ) : (
                                <RiLockLine className="size-5" aria-hidden="true" />
                              )}

                              <span className="mt-0.5 text-[10px] font-bold tabular-nums opacity-90">
                                #{stage.stageNumber}
                              </span>
                            </button>

                            <div className="mt-1 flex items-center gap-0.5 rounded-full bg-background/85 px-1.5 py-0.5 shadow-2xs">
                              {[1, 2, 3].map((star) => (
                                <RiStarFill
                                  key={star}
                                  className={`size-2.5 ${
                                    stage.completed
                                      ? 'text-warning'
                                      : 'text-muted-foreground/30'
                                  }`}
                                  aria-hidden="true"
                                />
                              ))}
                            </div>

                            <div className="mt-1 rounded-lg bg-background/90 px-2 py-0.5 shadow-2xs backdrop-blur-xs">
                              <p className="line-clamp-2 text-[11px] font-medium leading-tight">
                                {title}
                              </p>
                              {sliceTag ? (
                                <span className="mt-0.5 block text-[10px] text-muted-foreground">
                                  {sliceTag}
                                </span>
                              ) : null}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <Card className="xl:col-span-4">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <RiFlagLine className="size-4 text-primary" aria-hidden="true" />
              {t('curriculumWorkspace.graph.inspectorTitle')}
            </CardTitle>
            <CardDescription>{t('curriculumWorkspace.graph.inspectorHelp')}</CardDescription>
          </CardHeader>
          <CardContent>
            {activeStage ? (
              <div className="space-y-4">
                <div className="space-y-2 rounded-xl border bg-muted/20 p-3.5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span
                      className={`rounded-md px-2 py-0.5 text-[11px] font-semibold ${typeBadgeClass[activeStage.item.contentType]}`}
                    >
                      {t(`curriculumWorkspace.contentTypes.${activeStage.item.contentType}`)}
                    </span>
                    <Badge variant={activeStage.completed ? 'success' : activeStage.unlocked ? 'warning' : 'outline'}>
                      {activeStage.plusLocked
                        ? t('curriculumWorkspace.graph.statusPlusLocked')
                        : activeStage.completed
                          ? t('curriculumWorkspace.graph.statusCompleted')
                          : activeStage.unlocked
                            ? t('curriculumWorkspace.graph.statusUnlocked')
                            : t('curriculumWorkspace.graph.statusLocked')}
                    </Badge>
                  </div>
                  <p className="text-sm font-semibold">
                    #{activeStage.stageNumber} ·{' '}
                    {titleLookup[activeStage.item.contentReferenceId] ||
                      activeStage.item.contentReferenceId}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {activeStage.sectionTitle}
                  </p>
                </div>

                <dl className="space-y-2 text-xs">
                  <div className="flex items-center justify-between gap-2 border-b pb-1.5">
                    <dt className="text-muted-foreground">
                      {t('curriculumWorkspace.unlockRule')}
                    </dt>
                    <dd className="font-medium">
                      {t(`curriculumWorkspace.unlockRules.${activeStage.item.unlockRule}`, {
                        defaultValue: activeStage.item.unlockRule,
                      })}
                    </dd>
                  </div>
                  <div className="flex items-center justify-between gap-2 border-b pb-1.5">
                    <dt className="text-muted-foreground">
                      {t('curriculumWorkspace.graph.sliceLabel')}
                    </dt>
                    <dd className="font-medium">
                      {formatSliceBadge(activeStage.item.config, t) ??
                        t('curriculumWorkspace.graph.fullSlice')}
                    </dd>
                  </div>
                  <div className="flex items-center justify-between gap-2">
                    <dt className="text-muted-foreground">
                      {t('curriculumWorkspace.requiredItem')}
                    </dt>
                    <dd className="font-medium">
                      {activeStage.item.required
                        ? t('curriculumWorkspace.graph.yes')
                        : t('curriculumWorkspace.graph.no')}
                    </dd>
                  </div>
                </dl>

                <Button
                  type="button"
                  className="w-full"
                  variant={activeStage.completed ? 'outline' : 'default'}
                  disabled={activeStage.plusLocked}
                  onClick={() => toggleComplete(activeStage)}
                >
                  {activeStage.completed
                    ? t('curriculumWorkspace.graph.markIncomplete')
                    : t('curriculumWorkspace.graph.markCompleted')}
                </Button>
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
