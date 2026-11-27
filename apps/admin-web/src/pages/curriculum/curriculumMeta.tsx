import {
  RiArticleLine,
  RiBookletLine,
  RiCheckboxMultipleLine,
  RiEditBoxLine,
  RiGitBranchLine,
  RiHeadphoneLine,
  RiTrophyLine,
  RiVipCrown2Fill,
} from '@remixicon/react';
import type { CurriculumContentType, ItemSliceConfig } from './curriculum.api';

export const contentAccentVar: Record<CurriculumContentType, string> = {
  VOCABULARY_REVIEW: 'var(--node-vocab)',
  LESSON: 'var(--node-lesson)',
  GRAMMAR_PRACTICE: 'var(--node-grammar)',
  TOEIC_DRILL: 'var(--node-toeic)',
  TOEIC_TEST: 'var(--node-toeic-test)',
};

export const curriculumLegendTypes: CurriculumContentType[] = [
  'VOCABULARY_REVIEW',
  'LESSON',
  'GRAMMAR_PRACTICE',
  'TOEIC_DRILL',
  'TOEIC_TEST',
];

export function ContentTypeIcon({
  type,
  config,
  className = 'size-4',
}: {
  type: CurriculumContentType;
  config?: ItemSliceConfig;
  className?: string;
}) {
  if (type === 'VOCABULARY_REVIEW') return <RiBookletLine className={className} aria-hidden="true" />;
  if (type === 'GRAMMAR_PRACTICE') return <RiArticleLine className={className} aria-hidden="true" />;
  if (type === 'TOEIC_DRILL') return <RiCheckboxMultipleLine className={className} aria-hidden="true" />;
  if (type === 'TOEIC_TEST') return <RiTrophyLine className={className} aria-hidden="true" />;
  if (config?.activityType === 'DICTATION') return <RiEditBoxLine className={className} aria-hidden="true" />;
  return <RiHeadphoneLine className={className} aria-hidden="true" />;
}

export function BossCrownIcon({ className = 'size-3.5' }: { className?: string }) {
  return <RiVipCrown2Fill className={className} aria-hidden="true" />;
}

export function BranchTrailIcon({ className = 'size-3.5' }: { className?: string }) {
  return <RiGitBranchLine className={className} aria-hidden="true" />;
}

export function formatCurriculumSliceBadge(
  config: ItemSliceConfig | undefined,
  t: (k: string, o?: Record<string, unknown>) => string,
): string | null {
  if (!config) return null;
  const tokens: string[] = [];
  if (config.activityType) {
    tokens.push(config.activityType === 'DICTATION' ? 'Dictation' : 'Shadowing');
  }
  if (Array.isArray(config.entryIds) && config.entryIds.length > 0) {
    tokens.push(t('curriculumWorkspace.graph.sliceWords', { count: config.entryIds.length }));
  }
  if (Array.isArray(config.sentenceIds) && config.sentenceIds.length > 0) {
    tokens.push(t('curriculumWorkspace.graph.sliceSentences', { count: config.sentenceIds.length }));
  }
  if (Array.isArray(config.subtopicIds) && config.subtopicIds.length > 0) {
    tokens.push(t('curriculumWorkspace.graph.sliceSubtopics', { count: config.subtopicIds.length }));
  }
  if (Array.isArray(config.parts) && config.parts.length > 0) {
    if (config.parts.length === 7) {
      tokens.push(`Part 1–7 · ${config.questionCount ?? 200}Q`);
    } else {
      tokens.push(
        t('curriculumWorkspace.graph.sliceQuestions', {
          count: config.questionCount ?? 10,
          part: config.parts.join(','),
        }),
      );
    }
  } else if (config.questionCount != null) {
    tokens.push(t('curriculumWorkspace.graph.sliceGrammarQuestions', { count: config.questionCount }));
  }
  if (config.targetTurns != null) {
    tokens.push(t('curriculumWorkspace.graph.sliceTurns', { count: config.targetTurns }));
  }
  return tokens.length > 0 ? tokens.join(' · ') : null;
}
