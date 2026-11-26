import { useMemo, useState } from 'react';
import {
  Background,
  Controls,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  type Edge,
  type Node,
  type NodeProps,
} from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import {
  RiCheckDoubleLine,
  RiLockLine,
  RiPlayCircleLine,
  RiVipCrownLine,
} from '@remixicon/react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
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

type StepNodeData = {
  label: string;
  sectionTitle: string;
  contentType: CurriculumContentType;
  required: boolean;
  unlockState: 'COMPLETED' | 'UNLOCKED' | 'LOCKED' | 'PLUS_LOCKED';
  sliceSummary: string;
  onToggleComplete: () => void;
};

const nodeAccentClass: Record<CurriculumContentType, string> = {
  LESSON: 'border-l-[color:var(--node-lesson)]',
  VOCABULARY_REVIEW: 'border-l-[color:var(--node-vocab)]',
  GRAMMAR_PRACTICE: 'border-l-[color:var(--node-grammar)]',
  TOEIC_DRILL: 'border-l-[color:var(--node-toeic)]',
  TOEIC_TEST: 'border-l-[color:var(--node-toeic)]',
  SPEAKING_SCENARIO: 'border-l-[color:var(--node-speaking)]',
};

function summarizeSlice(cfg?: ItemSliceConfig): string {
  if (!cfg) return '';
  if (Array.isArray(cfg.entryIds) && cfg.entryIds.length > 0) return `${cfg.entryIds.length} words`;
  if (Array.isArray(cfg.sentenceIds) && cfg.sentenceIds.length > 0) {
    return `${cfg.activityType ?? 'PRACTICE'} · ${cfg.sentenceIds.length}s`;
  }
  if (cfg.activityType) return String(cfg.activityType);
  if (Array.isArray(cfg.parts) && cfg.parts.length > 0) {
    return `P${cfg.parts.join(',')} · ${cfg.questionCount ?? ''}Q`;
  }
  if (cfg.questionCount) return `${cfg.questionCount}Q`;
  if (cfg.targetTurns) return `${cfg.targetTurns} turns`;
  return '';
}

function CurriculumStepNode({ data }: NodeProps<Node<StepNodeData>>) {
  const { t } = useTranslation('admin');
  const { unlockState } = data;
  const StateIcon =
    unlockState === 'COMPLETED'
      ? RiCheckDoubleLine
      : unlockState === 'UNLOCKED'
        ? RiPlayCircleLine
        : unlockState === 'PLUS_LOCKED'
          ? RiVipCrownLine
          : RiLockLine;

  return (
    <button
      type="button"
      onClick={data.onToggleComplete}
      className={`w-56 rounded-xl border border-l-4 bg-card px-3 py-2.5 text-left shadow-sm transition hover:shadow-md ${nodeAccentClass[data.contentType]} ${
        unlockState === 'LOCKED' || unlockState === 'PLUS_LOCKED' ? 'opacity-70' : ''
      }`}
    >
      <Handle type="target" position={Position.Left} className="bg-border!" />
      <div className="flex items-center justify-between gap-2">
        <span className="truncate text-[11px] font-medium text-muted-foreground">
          {data.sectionTitle}
        </span>
        <StateIcon className="size-4 shrink-0 text-primary" aria-hidden="true" />
      </div>
      <p className="mt-1 truncate text-xs font-semibold text-foreground">{data.label}</p>
      <div className="mt-1.5 flex flex-wrap items-center gap-1">
        <Badge variant="outline" className="text-[10px]">
          {t(`curriculumWorkspace.contentTypes.${data.contentType}`)}
        </Badge>
        {data.sliceSummary ? (
          <Badge variant="secondary" className="text-[10px]">
            {data.sliceSummary}
          </Badge>
        ) : null}
        {data.required ? (
          <Badge variant="outline" className="text-[10px]">
            {t('curriculumWorkspace.requiredTag')}
          </Badge>
        ) : null}
      </div>
      <Handle type="source" position={Position.Right} className="bg-border!" />
    </button>
  );
}

const nodeTypes = { stepNode: CurriculumStepNode };

export function CurriculumGraphPreview({
  sections,
  pathAccessMode,
  titleLookup,
}: {
  sections: GraphPreviewSection[];
  pathAccessMode: 'PUBLIC' | 'FEATURE';
  titleLookup?: Record<string, string>;
}) {
  const { t } = useTranslation('admin');
  const [simulatedTier, setSimulatedTier] = useState<'FREE' | 'PLUS'>('PLUS');
  const [completedKeys, setCompletedKeys] = useState<Record<string, boolean>>({});

  const { nodes, edges } = useMemo(() => {
    const outNodes: Node<StepNodeData>[] = [];
    const outEdges: Edge[] = [];

    let previousRequiredCompleted = true;
    let previousKey: string | null = null;

    sections.forEach((sec, sIdx) => {
      sec.items.forEach((item, iIdx) => {
        const isDone = Boolean(completedKeys[item.key]);
        let unlockState: StepNodeData['unlockState'];
        if (simulatedTier === 'FREE' && pathAccessMode === 'FEATURE') {
          unlockState = 'PLUS_LOCKED';
        } else if (isDone) {
          unlockState = 'COMPLETED';
        } else if (item.unlockRule === 'NONE' || previousRequiredCompleted) {
          unlockState = 'UNLOCKED';
        } else {
          unlockState = 'LOCKED';
        }

        if (item.required && !isDone) {
          previousRequiredCompleted = false;
        }

        const resolvedLabel =
          (item.contentReferenceId && titleLookup?.[item.contentReferenceId]) ||
          `${item.contentType} #${iIdx + 1}`;

        outNodes.push({
          id: item.key,
          type: 'stepNode',
          position: { x: sIdx * 285, y: iIdx * 125 },
          data: {
            label: resolvedLabel,
            sectionTitle: sec.title || `Section ${sIdx + 1}`,
            contentType: item.contentType,
            required: item.required,
            unlockState,
            sliceSummary: summarizeSlice(item.config),
            onToggleComplete: () =>
              setCompletedKeys((prev) => ({ ...prev, [item.key]: !prev[item.key] })),
          },
        });

        if (previousKey) {
          outEdges.push({
            id: `${previousKey}->${item.key}`,
            source: previousKey,
            target: item.key,
            animated: unlockState === 'UNLOCKED',
          });
        }
        previousKey = item.key;
      });
    });

    return { nodes: outNodes, edges: outEdges };
  }, [sections, completedKeys, simulatedTier, pathAccessMode, titleLookup]);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border bg-muted/30 px-3 py-2">
        <div className="space-y-0.5">
          <p className="text-xs font-medium">{t('curriculumWorkspace.graph.simulatorTitle')}</p>
          <p className="text-xs text-muted-foreground">
            {t('curriculumWorkspace.graph.simulatorHelp')}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant={simulatedTier === 'FREE' ? 'default' : 'outline'}
            onClick={() => setSimulatedTier('FREE')}
          >
            FREE
          </Button>
          <Button
            type="button"
            size="sm"
            variant={simulatedTier === 'PLUS' ? 'default' : 'outline'}
            onClick={() => setSimulatedTier('PLUS')}
          >
            PLUS
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => setCompletedKeys({})}
          >
            {t('curriculumWorkspace.graph.resetSim')}
          </Button>
        </div>
      </div>

      <div className="h-[420px] w-full overflow-hidden rounded-xl border bg-background">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          fitView
          proOptions={{ hideAttribution: true }}
        >
          <Background gap={20} size={1} />
          <MiniMap zoomable pannable />
          <Controls />
        </ReactFlow>
      </div>
    </div>
  );
}
