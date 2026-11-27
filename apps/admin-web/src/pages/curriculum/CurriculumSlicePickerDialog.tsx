import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiCheckLine,
  RiCloseLine,
  RiLoader4Line,
  RiRefreshLine,
  RiScissorsCutLine,
  RiSearchLine,
} from '@remixicon/react';
import { api } from '@/lib/api';
import { PosTag } from '@/components/ContentBadges';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { fetchLessons } from '@/pages/lessons/catalog.api';
import type { CatalogPage, Document, Summary, ToeicDraft } from '@/pages/library/library.types';
import type { CurriculumContentType, ItemSliceConfig } from './curriculum.api';
import {
  contentAccentVar,
  ContentTypeIcon,
  formatCurriculumSliceBadge,
} from './curriculumMeta';

type PickableEntry = {
  id: string;
  title: string;
  meta: string;
  authoring?: boolean;
  questionCount?: number;
  packId?: string;
  packLabel?: string;
  cefr?: string | null;
  year?: number | null;
};

type VocabPack = {
  id: string;
  titleEn: string;
  titleVi: string;
  hidden?: boolean;
};

type VocabUnit = {
  id: string;
  title: string;
  level: string;
  entryCount?: number;
  hidden?: boolean;
};

type VocabEntrySummary = {
  id: string;
  term: string;
  pos?: string;
  meaningVi?: string | null;
  definitionEn?: string;
  hidden?: boolean;
};

type VocabEntriesPage = {
  items: VocabEntrySummary[];
  total: number;
};

type LessonSentenceRow = {
  id: string;
  position?: number;
  text: string;
};

type LessonDetailResponse = {
  sentences?: LessonSentenceRow[];
};

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const VOCAB_PAGE_SIZE = 30;
const ALL_TOEIC_PARTS = [1, 2, 3, 4, 5, 6, 7] as const;
const STANDARD_TOEIC_PART_COUNTS: Record<number, number> = {
  1: 6,
  2: 25,
  3: 39,
  4: 30,
  5: 30,
  6: 16,
  7: 54,
};

function defaultConfigForType(contentType: CurriculumContentType): ItemSliceConfig {
  if (contentType === 'LESSON') return { activityType: 'SHADOWING' };
  if (contentType === 'GRAMMAR_PRACTICE') return { questionCount: 10 };
  if (contentType === 'TOEIC_DRILL') return { parts: [5], questionCount: 10 };
  if (contentType === 'TOEIC_TEST') return { parts: [...ALL_TOEIC_PARTS], questionCount: 200 };
  return {};
}

function formatToeicTitle(title: string, year?: number | null): string {
  if (year && !title.includes(String(year))) {
    return `TOEIC ${year} · ${title}`;
  }
  return title;
}

async function fetchCandidates(
  contentType: CurriculumContentType,
  search: string,
  isVi: boolean,
  signal?: AbortSignal,
): Promise<PickableEntry[]> {
  if (contentType === 'LESSON') {
    const params = new URLSearchParams({ page: '0', size: '100', status: 'PUBLISHED' });
    if (search) params.set('query', search);
    const page = await fetchLessons(params, signal);
    return page.items
      .filter((item) => !item.deleted)
      .map((item) => ({
        id: item.id,
        title: item.title,
        meta: `${item.sourceType}${item.cefr ? ` · ${item.cefr}` : ''}`,
        authoring: item.authoring,
        cefr: item.cefr ?? null,
      }));
  }
  if (contentType === 'VOCABULARY_REVIEW') {
    const packs = await api<VocabPack[]>('/api/v1/admin/vocabulary/packs', { signal });
    const visiblePacks = packs.filter((pack) => !pack.hidden);
    const unitLists = await Promise.all(
      visiblePacks.map(async (pack) => {
        const units = await api<VocabUnit[]>(
          `/api/v1/admin/vocabulary/packs/${encodeURIComponent(pack.id)}/units`,
          { signal },
        );
        const packLabel = (isVi ? pack.titleVi : pack.titleEn) || pack.titleEn || pack.titleVi;
        return units
          .filter((unit) => !unit.hidden)
          .map((unit) => ({
            id: unit.id,
            title: `${packLabel} · ${unit.title}`,
            meta:
              typeof unit.entryCount === 'number'
                ? `${unit.level} · ${unit.entryCount}`
                : unit.level,
            packId: pack.id,
            packLabel,
            cefr: unit.level,
          }));
      }),
    );
    const allUnits = unitLists.flat();
    if (!search) return allUnits;
    const lower = search.toLowerCase();
    return allUnits.filter(
      (item) =>
        item.title.toLowerCase().includes(lower) || item.meta.toLowerCase().includes(lower),
    );
  }
  if (contentType === 'GRAMMAR_PRACTICE') {
    const params = new URLSearchParams({ page: '0', size: '100', status: 'PUBLISHED' });
    if (search) params.set('q', search);
    const page = await api<CatalogPage<Summary>>(
      `/api/v1/admin/grammar/topic/page?${params}`,
      { signal },
    );
    return (page.items ?? []).map((item) => ({
      id: item.id,
      title: (isVi ? item.title : item.titleEn) || item.title || item.titleEn || '',
      meta: `${item.questionCount ?? 0}Q`,
      questionCount: item.questionCount ?? 0,
    }));
  }
  const params = new URLSearchParams({ page: '0', size: '100', status: 'PUBLISHED' });
  if (search) params.set('q', search);
  const page = await api<CatalogPage<Summary>>(`/api/v1/admin/toeic/page?${params}`, { signal });
  return (page.items ?? []).map((item) => ({
    id: item.id,
    title: formatToeicTitle(item.title, item.year),
    meta: `${item.year ? `${item.year} · ` : ''}${item.questionCount ?? 200}Q`,
    questionCount: item.questionCount ?? 200,
    year: item.year ?? null,
  }));
}

async function fetchUnitVocabularyEntries(
  unitId: string,
  signal?: AbortSignal,
): Promise<VocabEntrySummary[]> {
  const firstParams = new URLSearchParams({ unitId, page: '0' });
  const firstPage = await api<VocabEntriesPage>(
    `/api/v1/admin/vocabulary/entries?${firstParams}`,
    { signal },
  );
  const firstItems = Array.isArray(firstPage.items) ? firstPage.items : [];
  const total = typeof firstPage.total === 'number' ? firstPage.total : firstItems.length;
  if (total <= firstItems.length || firstItems.length === 0) {
    return firstItems.filter((entry) => !entry.hidden);
  }
  const pageCount = Math.min(8, Math.ceil(total / VOCAB_PAGE_SIZE));
  const restPages = await Promise.all(
    Array.from({ length: pageCount - 1 }, (_, idx) => {
      const pageParams = new URLSearchParams({ unitId, page: String(idx + 1) });
      return api<VocabEntriesPage>(`/api/v1/admin/vocabulary/entries?${pageParams}`, { signal });
    }),
  );
  return [firstItems, ...restPages.map((p) => p.items ?? [])]
    .flat()
    .filter((entry) => !entry.hidden);
}

async function fetchLessonSentences(
  lessonId: string,
  preferAuthoring: boolean | undefined,
  signal?: AbortSignal,
): Promise<LessonSentenceRow[]> {
  const encoded = encodeURIComponent(lessonId);
  if (preferAuthoring === false) {
    const published = await api<LessonDetailResponse>(`/api/v1/admin/lessons/${encoded}`, {
      signal,
    });
    return published.sentences ?? [];
  }
  try {
    const authoring = await api<LessonDetailResponse>(
      `/api/v1/admin/lesson-authoring/${encoded}`,
      { signal },
    );
    return authoring.sentences ?? [];
  } catch {
    const fallback = await api<LessonDetailResponse>(`/api/v1/admin/lessons/${encoded}`, {
      signal,
    });
    return fallback.sentences ?? [];
  }
}

export function CurriculumSlicePickerDialog({
  open,
  contentType,
  currentId,
  currentConfig,
  onSelect,
  onOpenChange,
}: {
  open: boolean;
  contentType: CurriculumContentType;
  currentId: string;
  currentConfig: ItemSliceConfig;
  onSelect: (id: string, config: ItemSliceConfig, title?: string) => void;
  onOpenChange: (open: boolean) => void;
}) {
  const { t, i18n } = useTranslation('admin');
  const isVi = Boolean(i18n.resolvedLanguage?.startsWith('vi'));
  const [search, setSearch] = useState('');
  const [packFilter, setPackFilter] = useState<string>('ALL');
  const [levelFilter, setLevelFilter] = useState<string>('ALL');
  const [yearFilter, setYearFilter] = useState<string>('ALL');
  const [wordFilter, setWordFilter] = useState('');
  const [sentenceFilter, setSentenceFilter] = useState('');
  const [subtopicFilter, setSubtopicFilter] = useState('');
  const [pickedId, setPickedId] = useState(currentId);
  const [sliceConfig, setSliceConfig] = useState<ItemSliceConfig>(currentConfig ?? {});

  useEffect(() => {
    if (open) {
      setPickedId(currentId);
      const hasKeys = currentConfig && Object.keys(currentConfig).length > 0;
      const initial = hasKeys ? { ...currentConfig } : defaultConfigForType(contentType);
      if (
        (contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST') &&
        (!Array.isArray(initial.parts) || initial.parts.length === 0)
      ) {
        initial.parts = [...ALL_TOEIC_PARTS];
      }
      setSliceConfig(initial);
      setPackFilter('ALL');
      setLevelFilter('ALL');
      setYearFilter('ALL');
      setWordFilter('');
      setSentenceFilter('');
      setSubtopicFilter('');
    }
  }, [open, currentId, currentConfig, contentType]);

  const isValidPickedUuid = UUID_PATTERN.test(pickedId.trim());
  const accent = contentAccentVar[contentType];

  const query = useQuery({
    queryKey: ['curriculum-slice-picker', contentType, search, isVi],
    queryFn: ({ signal }) => fetchCandidates(contentType, search.trim(), isVi, signal),
    enabled: open,
  });

  const vocabPacks = useMemo(() => {
    const map = new Map<string, string>();
    for (const entry of query.data ?? []) {
      if (entry.packId && entry.packLabel) {
        map.set(entry.packId, entry.packLabel);
      }
    }
    return Array.from(map.entries()).map(([id, label]) => ({ id, label }));
  }, [query.data]);

  const lessonLevels = useMemo(() => {
    const set = new Set<string>();
    for (const entry of query.data ?? []) {
      if (entry.cefr) set.add(entry.cefr);
    }
    return Array.from(set).sort();
  }, [query.data]);

  const toeicYears = useMemo(() => {
    const set = new Set<number>();
    for (const entry of query.data ?? []) {
      if (entry.year) set.add(entry.year);
    }
    return Array.from(set).sort((a, b) => b - a);
  }, [query.data]);

  const filteredCandidates = useMemo(() => {
    const list = query.data ?? [];
    return list.filter((entry) => {
      if (contentType === 'VOCABULARY_REVIEW' && packFilter !== 'ALL') {
        return entry.packId === packFilter;
      }
      if (contentType === 'LESSON' && levelFilter !== 'ALL') {
        return entry.cefr === levelFilter;
      }
      if (
        (contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST') &&
        yearFilter !== 'ALL'
      ) {
        return String(entry.year ?? '') === yearFilter;
      }
      return true;
    });
  }, [query.data, contentType, packFilter, levelFilter, yearFilter]);

  const selectedCandidate = useMemo(
    () => query.data?.find((entry) => entry.id === pickedId),
    [query.data, pickedId],
  );

  const vocabEntriesQuery = useQuery({
    queryKey: ['curriculum-slice-vocab-entries', pickedId],
    queryFn: ({ signal }) => fetchUnitVocabularyEntries(pickedId, signal),
    enabled: open && contentType === 'VOCABULARY_REVIEW' && isValidPickedUuid,
  });

  const lessonSentencesQuery = useQuery({
    queryKey: ['curriculum-slice-lesson-sentences', pickedId, selectedCandidate?.authoring],
    queryFn: ({ signal }) => fetchLessonSentences(pickedId, selectedCandidate?.authoring, signal),
    enabled: open && contentType === 'LESSON' && isValidPickedUuid,
  });

  const grammarSubtopicsQuery = useQuery({
    queryKey: ['curriculum-slice-grammar-subtopics', pickedId],
    queryFn: async ({ signal }) => {
      const params = new URLSearchParams({
        parentId: pickedId,
        status: 'PUBLISHED',
        page: '0',
        size: '100',
      });
      const page = await api<CatalogPage<Summary>>(
        `/api/v1/admin/grammar/subtopic/page?${params}`,
        { signal },
      );
      return page.items ?? [];
    },
    enabled: open && contentType === 'GRAMMAR_PRACTICE' && isValidPickedUuid,
  });

  const toeicDetailQuery = useQuery({
    queryKey: ['curriculum-slice-toeic-detail', pickedId],
    queryFn: ({ signal }) =>
      api<Document<ToeicDraft>>(`/api/v1/admin/toeic/${encodeURIComponent(pickedId)}`, {
        signal,
      }),
    enabled:
      open &&
      (contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST') &&
      isValidPickedUuid,
  });

  const toeicPartCounts = useMemo<Record<number, number>>(() => {
    const placements = toeicDetailQuery.data?.draft?.placements;
    if (!placements || placements.length === 0) {
      return STANDARD_TOEIC_PART_COUNTS;
    }
    const counts: Record<number, number> = { 1: 0, 2: 0, 3: 0, 4: 0, 5: 0, 6: 0, 7: 0 };
    for (const row of placements) {
      if (row.part >= 1 && row.part <= 7) {
        counts[row.part] = (counts[row.part] ?? 0) + 1;
      }
    }
    return counts;
  }, [toeicDetailQuery.data]);

  const activeToeicParts = useMemo(
    () =>
      Array.isArray(sliceConfig.parts) && sliceConfig.parts.length > 0
        ? sliceConfig.parts
        : [...ALL_TOEIC_PARTS],
    [sliceConfig.parts],
  );

  const maxToeicQuestions = useMemo(() => {
    const sum = activeToeicParts.reduce((acc, p) => acc + (toeicPartCounts[p] ?? 0), 0);
    return Math.max(1, sum);
  }, [activeToeicParts, toeicPartCounts]);

  const subtopics = grammarSubtopicsQuery.data ?? [];
  const selectedSubtopicIds = Array.isArray(sliceConfig.subtopicIds) ? sliceConfig.subtopicIds : [];

  const maxGrammarQuestions = useMemo(() => {
    if (selectedSubtopicIds.length > 0 && subtopics.length > 0) {
      const sum = subtopics
        .filter((sub) => selectedSubtopicIds.includes(sub.id))
        .reduce((acc, sub) => acc + (sub.questionCount ?? 0), 0);
      if (sum > 0) return sum;
    }
    if (selectedCandidate?.questionCount != null && selectedCandidate.questionCount > 0) {
      return selectedCandidate.questionCount;
    }
    if (subtopics.length > 0) {
      const sumAll = subtopics.reduce((acc, sub) => acc + (sub.questionCount ?? 0), 0);
      if (sumAll > 0) return sumAll;
    }
    return 50;
  }, [selectedSubtopicIds, subtopics, selectedCandidate]);

  const filteredWords = useMemo(() => {
    const words = vocabEntriesQuery.data ?? [];
    const q = wordFilter.trim().toLowerCase();
    if (!q) return words;
    return words.filter(
      (w) =>
        w.term.toLowerCase().includes(q) ||
        (w.meaningVi && w.meaningVi.toLowerCase().includes(q)) ||
        (w.definitionEn && w.definitionEn.toLowerCase().includes(q)),
    );
  }, [vocabEntriesQuery.data, wordFilter]);

  const filteredSentences = useMemo(() => {
    const rows = lessonSentencesQuery.data ?? [];
    const q = sentenceFilter.trim().toLowerCase();
    if (!q) return rows;
    return rows.filter((s) => s.text.toLowerCase().includes(q));
  }, [lessonSentencesQuery.data, sentenceFilter]);

  const filteredSubtopics = useMemo(() => {
    const q = subtopicFilter.trim().toLowerCase();
    if (!q) return subtopics;
    return subtopics.filter(
      (sub) =>
        sub.title.toLowerCase().includes(q) ||
        (sub.titleEn && sub.titleEn.toLowerCase().includes(q)),
    );
  }, [subtopics, subtopicFilter]);

  function toggleSliceId(field: 'entryIds' | 'sentenceIds', targetId: string) {
    setSliceConfig((prev) => {
      const existing = Array.isArray(prev[field]) ? [...prev[field]!] : [];
      const next = existing.includes(targetId)
        ? existing.filter((x) => x !== targetId)
        : [...existing, targetId];
      const copy = { ...prev };
      if (next.length === 0) {
        delete copy[field];
      } else {
        copy[field] = next;
      }
      return copy;
    });
  }

  function setSliceIds(field: 'entryIds' | 'sentenceIds', ids: string[]) {
    setSliceConfig((prev) => {
      const copy = { ...prev };
      if (ids.length === 0) {
        delete copy[field];
      } else {
        copy[field] = ids;
      }
      return copy;
    });
  }

  function invertSliceIds(field: 'entryIds' | 'sentenceIds', allIds: string[]) {
    setSliceConfig((prev) => {
      const existing = new Set(Array.isArray(prev[field]) ? prev[field]! : []);
      const next = allIds.filter((id) => !existing.has(id));
      const copy = { ...prev };
      if (next.length === 0) {
        delete copy[field];
      } else {
        copy[field] = next;
      }
      return copy;
    });
  }

  function buildChunks(total: number, step: number) {
    const chunks: Array<{ start: number; end: number }> = [];
    for (let start = 0; start < total; start += step) {
      chunks.push({ start, end: Math.min(total, start + step) });
    }
    return chunks.slice(0, 6);
  }

  function computeGrammarMax(nextSubtopicIds: string[]): number {
    if (nextSubtopicIds.length > 0 && subtopics.length > 0) {
      const sum = subtopics
        .filter((sub) => nextSubtopicIds.includes(sub.id))
        .reduce((acc, sub) => acc + (sub.questionCount ?? 0), 0);
      if (sum > 0) return sum;
    }
    if (selectedCandidate?.questionCount != null && selectedCandidate.questionCount > 0) {
      return selectedCandidate.questionCount;
    }
    if (subtopics.length > 0) {
      const sumAll = subtopics.reduce((acc, sub) => acc + (sub.questionCount ?? 0), 0);
      if (sumAll > 0) return sumAll;
    }
    return 50;
  }

  function toggleGrammarSubtopic(subtopicId: string) {
    setSliceConfig((prev) => {
      const existing = Array.isArray(prev.subtopicIds) ? [...prev.subtopicIds] : [];
      const next = existing.includes(subtopicId)
        ? existing.filter((x) => x !== subtopicId)
        : [...existing, subtopicId];
      const copy: ItemSliceConfig = { ...prev };
      if (next.length === 0) {
        delete copy.subtopicIds;
      } else {
        copy.subtopicIds = next;
      }
      const nextMax = computeGrammarMax(next);
      const prevCount = typeof prev.questionCount === 'number' ? prev.questionCount : 10;
      copy.questionCount = Math.max(1, Math.min(prevCount, nextMax));
      return copy;
    });
  }

  function selectAllValidGrammarSubtopics() {
    const validIds = subtopics.filter((s) => (s.questionCount ?? 0) > 0).map((s) => s.id);
    setSliceConfig((prev) => {
      const copy: ItemSliceConfig = { ...prev };
      if (validIds.length === 0) {
        delete copy.subtopicIds;
      } else {
        copy.subtopicIds = validIds;
      }
      const nextMax = computeGrammarMax(validIds);
      const prevCount = typeof prev.questionCount === 'number' ? prev.questionCount : 10;
      copy.questionCount = Math.max(1, Math.min(prevCount, nextMax));
      return copy;
    });
  }

  function clearGrammarSubtopics() {
    setSliceConfig((prev) => {
      const copy: ItemSliceConfig = { ...prev };
      delete copy.subtopicIds;
      const nextMax = computeGrammarMax([]);
      const prevCount = typeof prev.questionCount === 'number' ? prev.questionCount : 10;
      copy.questionCount = Math.max(1, Math.min(prevCount, nextMax));
      return copy;
    });
  }

  function applyPartsSelection(nextParts: number[]) {
    const sorted = [...nextParts].sort((a, b) => a - b);
    const effectiveParts = sorted.length > 0 ? sorted : [...ALL_TOEIC_PARTS];
    const avail = Math.max(
      1,
      effectiveParts.reduce((acc, p) => acc + (toeicPartCounts[p] ?? 0), 0),
    );
    setSliceConfig((prev) => {
      const copy: ItemSliceConfig = { ...prev, parts: effectiveParts };
      const prevCount = typeof prev.questionCount === 'number' ? prev.questionCount : 10;
      copy.questionCount =
        contentType === 'TOEIC_TEST' ? avail : Math.max(1, Math.min(prevCount, avail));
      return copy;
    });
  }

  function togglePart(partNum: number) {
    const existing = [...activeToeicParts];
    if (existing.includes(partNum) && existing.length === 1) {
      return;
    }
    const next = existing.includes(partNum)
      ? existing.filter((p) => p !== partNum)
      : [...existing, partNum];
    applyPartsSelection(next);
  }

  const selectedEntryIds = Array.isArray(sliceConfig.entryIds) ? sliceConfig.entryIds : [];
  const selectedSentenceIds = Array.isArray(sliceConfig.sentenceIds) ? sliceConfig.sentenceIds : [];
  const totalWords = vocabEntriesQuery.data?.length ?? 0;
  const totalSentences = lessonSentencesQuery.data?.length ?? 0;

  const isDetailLoading =
    (contentType === 'VOCABULARY_REVIEW' && vocabEntriesQuery.isFetching) ||
    (contentType === 'LESSON' && lessonSentencesQuery.isFetching) ||
    (contentType === 'GRAMMAR_PRACTICE' && grammarSubtopicsQuery.isFetching) ||
    ((contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST') &&
      toeicDetailQuery.isFetching);

  const previewSummary = formatCurriculumSliceBadge(sliceConfig, t);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex h-[85vh] max-h-[820px] w-[95vw] flex-col gap-0 overflow-hidden rounded-2xl p-0 sm:max-w-6xl">
        <DialogHeader className="shrink-0 border-b px-6 py-4 pr-14">
          <div className="flex flex-wrap items-center gap-2.5">
            <span
              className="inline-flex items-center gap-1.5 rounded-lg border px-2 py-1 text-xs font-medium"
              style={{
                borderColor: `color-mix(in srgb, ${accent} 35%, var(--border))`,
                backgroundColor: `color-mix(in srgb, ${accent} 10%, var(--card))`,
                color: accent,
              }}
            >
              <ContentTypeIcon type={contentType} className="size-3.5" />
              {t(`curriculumWorkspace.contentTypes.${contentType}`)}
            </span>
            <DialogTitle className="flex items-center gap-2">
              <RiScissorsCutLine className="size-4" aria-hidden="true" />
              {t('curriculumWorkspace.slicePicker.title')}
            </DialogTitle>
            {query.isFetching && !query.isPending ? (
              <RiLoader4Line className="size-4 animate-spin text-muted-foreground" aria-hidden="true" />
            ) : null}
          </div>
          <DialogDescription>
            {t('curriculumWorkspace.slicePicker.description', {
              type: t(`curriculumWorkspace.contentTypes.${contentType}`),
            })}
          </DialogDescription>
        </DialogHeader>

        <div className="grid min-h-0 flex-1 divide-y overflow-hidden md:grid-cols-[22rem_minmax(0,1fr)] md:divide-x md:divide-y-0 lg:grid-cols-[24rem_minmax(0,1fr)]">
          <div className="flex min-h-0 flex-col bg-muted/15">
            <div className="shrink-0 space-y-2.5 border-b p-3.5">
              <div className="flex items-center gap-2">
                <div className="relative flex-1">
                  <RiSearchLine
                    className="pointer-events-none absolute top-2.5 left-3 size-4 text-muted-foreground"
                    aria-hidden="true"
                  />
                  <Input
                    value={search}
                    onChange={(e) => setSearch(e.target.value)}
                    placeholder={t('curriculumWorkspace.pickerSearch')}
                    className="pr-8 pl-9"
                  />
                  {search ? (
                    <button
                      type="button"
                      onClick={() => setSearch('')}
                      aria-label={t('curriculumWorkspace.slicePicker.deselectAll')}
                      className="absolute top-2.5 right-2.5 text-muted-foreground hover:text-foreground"
                    >
                      <RiCloseLine className="size-4" aria-hidden="true" />
                    </button>
                  ) : null}
                </div>
                {query.data ? (
                  <Badge variant="outline" className="shrink-0 tabular-nums">
                    {t('curriculumWorkspace.slicePicker.totalResults', {
                      count: filteredCandidates.length,
                    })}
                  </Badge>
                ) : null}
              </div>

              {contentType === 'VOCABULARY_REVIEW' && vocabPacks.length > 1 ? (
                <Select value={packFilter} onValueChange={setPackFilter}>
                  <SelectTrigger size="sm" className="w-full text-xs">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">
                      {t('curriculumWorkspace.slicePicker.allPacks')}
                    </SelectItem>
                    {vocabPacks.map((p) => (
                      <SelectItem key={p.id} value={p.id}>
                        {p.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              ) : null}

              {contentType === 'LESSON' && lessonLevels.length > 1 ? (
                <div className="flex flex-wrap gap-1">
                  <Button
                    type="button"
                    size="xs"
                    variant={levelFilter === 'ALL' ? 'secondary' : 'ghost'}
                    onClick={() => setLevelFilter('ALL')}
                  >
                    {t('curriculumWorkspace.slicePicker.allLevels')}
                  </Button>
                  {lessonLevels.map((lvl) => (
                    <Button
                      key={lvl}
                      type="button"
                      size="xs"
                      variant={levelFilter === lvl ? 'secondary' : 'ghost'}
                      onClick={() => setLevelFilter(lvl)}
                    >
                      {lvl}
                    </Button>
                  ))}
                </div>
              ) : null}

              {(contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST') &&
              toeicYears.length > 1 ? (
                <div className="flex flex-wrap gap-1">
                  <Button
                    type="button"
                    size="xs"
                    variant={yearFilter === 'ALL' ? 'secondary' : 'ghost'}
                    onClick={() => setYearFilter('ALL')}
                  >
                    {t('curriculumWorkspace.slicePicker.allYears')}
                  </Button>
                  {toeicYears.map((yr) => (
                    <Button
                      key={yr}
                      type="button"
                      size="xs"
                      variant={yearFilter === String(yr) ? 'secondary' : 'ghost'}
                      className="tabular-nums"
                      onClick={() => setYearFilter(String(yr))}
                    >
                      {yr}
                    </Button>
                  ))}
                </div>
              ) : null}
            </div>

            <div className="min-h-0 flex-1 space-y-1.5 overflow-y-auto p-3">
              {query.isPending ? (
                <div className="space-y-2">
                  <Skeleton className="h-14 w-full" />
                  <Skeleton className="h-14 w-full" />
                  <Skeleton className="h-14 w-full" />
                  <Skeleton className="h-14 w-full" />
                  <Skeleton className="h-14 w-full" />
                  <Skeleton className="h-14 w-full" />
                </div>
              ) : query.isError ? (
                <div className="flex items-center justify-between gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive">
                  <span>{t('curriculumWorkspace.slicePicker.loadSliceError')}</span>
                  <Button
                    type="button"
                    size="xs"
                    variant="outline"
                    onClick={() => void query.refetch()}
                  >
                    <RiRefreshLine data-icon="inline-start" aria-hidden="true" />
                    {t('pronunciationPilot.retry')}
                  </Button>
                </div>
              ) : filteredCandidates.length === 0 ? (
                <p className="py-10 text-center text-sm text-muted-foreground">
                  {t('curriculumWorkspace.pickerEmpty')}
                </p>
              ) : (
                filteredCandidates.map((entry) => {
                  const selected = entry.id === pickedId;
                  return (
                    <button
                      key={entry.id}
                      type="button"
                      onClick={() => {
                        if (entry.id !== pickedId) {
                          setPickedId(entry.id);
                          setSliceConfig(
                            contentType === 'LESSON'
                              ? {
                                  activityType:
                                    (sliceConfig.activityType as 'SHADOWING' | 'DICTATION') ||
                                    'SHADOWING',
                                }
                              : defaultConfigForType(contentType),
                          );
                          setWordFilter('');
                          setSentenceFilter('');
                          setSubtopicFilter('');
                        }
                      }}
                      className={cn(
                        'flex w-full items-center justify-between gap-2.5 rounded-xl border px-3 py-2.5 text-left text-sm transition-colors',
                        selected
                          ? 'border-primary bg-primary/8 font-medium shadow-2xs'
                          : 'border-border/70 bg-background hover:bg-muted/50',
                      )}
                    >
                      <span className="min-w-0 flex-1">
                        <span className="line-clamp-1 block">{entry.title}</span>
                        <span className="block truncate text-xs text-muted-foreground tabular-nums">
                          {entry.meta}
                        </span>
                      </span>
                      {selected ? (
                        <Badge variant="default" className="shrink-0">
                          <RiCheckLine className="size-3.5" aria-hidden="true" />
                        </Badge>
                      ) : null}
                    </button>
                  );
                })
              )}
            </div>
          </div>

          <div className="flex min-h-0 flex-col bg-background">
            <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-b bg-muted/10 px-5 py-3">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <h3 className="truncate text-sm font-semibold text-foreground">
                    {selectedCandidate?.title ?? t('curriculumWorkspace.slicePicker.sliceHeading')}
                  </h3>
                  {isDetailLoading ? (
                    <RiLoader4Line
                      className="size-4 shrink-0 animate-spin text-muted-foreground"
                      aria-hidden="true"
                    />
                  ) : null}
                </div>
              </div>

              {contentType === 'VOCABULARY_REVIEW' && isValidPickedUuid && totalWords > 0 ? (
                <Badge variant="secondary" className="tabular-nums">
                  {selectedEntryIds.length > 0
                    ? t('curriculumWorkspace.slicePicker.selectedWordsCount', {
                        selected: selectedEntryIds.length,
                        total: totalWords,
                      })
                    : t('curriculumWorkspace.slicePicker.allWordsDefault', { total: totalWords })}
                </Badge>
              ) : contentType === 'LESSON' && isValidPickedUuid && totalSentences > 0 ? (
                <Badge variant="secondary" className="tabular-nums">
                  {selectedSentenceIds.length > 0
                    ? t('curriculumWorkspace.slicePicker.selectedSentencesCount', {
                        selected: selectedSentenceIds.length,
                        total: totalSentences,
                      })
                    : t('curriculumWorkspace.slicePicker.allSentencesDefault', {
                        total: totalSentences,
                      })}
                </Badge>
              ) : contentType === 'GRAMMAR_PRACTICE' && isValidPickedUuid ? (
                <Badge variant="secondary" className="tabular-nums">
                  {t('curriculumWorkspace.slicePicker.grammarMaxQuestions', {
                    count: maxGrammarQuestions,
                  })}
                </Badge>
              ) : (contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST') &&
                isValidPickedUuid ? (
                <Badge variant="secondary" className="tabular-nums">
                  {t('curriculumWorkspace.slicePicker.toeicMaxQuestions', {
                    count: maxToeicQuestions,
                  })}
                </Badge>
              ) : null}
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto p-5">
              {!isValidPickedUuid ? (
                <div className="flex h-full items-center justify-center text-center text-sm text-muted-foreground">
                  {t('curriculumWorkspace.slicePicker.selectSourceFirst')}
                </div>
              ) : contentType === 'VOCABULARY_REVIEW' ? (
                vocabEntriesQuery.isPending ? (
                  <div className="space-y-2">
                    <Skeleton className="h-10 w-full" />
                    <Skeleton className="h-10 w-full" />
                    <Skeleton className="h-10 w-full" />
                    <Skeleton className="h-10 w-full" />
                    <Skeleton className="h-10 w-full" />
                  </div>
                ) : vocabEntriesQuery.isError ? (
                  <div className="flex items-center justify-between gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive">
                    <span>{t('curriculumWorkspace.slicePicker.loadSliceError')}</span>
                    <Button
                      type="button"
                      size="xs"
                      variant="outline"
                      onClick={() => void vocabEntriesQuery.refetch()}
                    >
                      <RiRefreshLine data-icon="inline-start" aria-hidden="true" />
                      {t('pronunciationPilot.retry')}
                    </Button>
                  </div>
                ) : totalWords === 0 ? (
                  <p className="py-10 text-center text-sm text-muted-foreground">
                    {t('curriculumWorkspace.slicePicker.emptyUnitWords')}
                  </p>
                ) : (
                  <div className="space-y-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Input
                        value={wordFilter}
                        onChange={(e) => setWordFilter(e.target.value)}
                        placeholder={t('curriculumWorkspace.slicePicker.wordFilterPlaceholder')}
                        className="h-9 min-w-[14rem] flex-1 text-sm"
                      />
                      <Button
                        type="button"
                        size="xs"
                        variant="outline"
                        onClick={() =>
                          setSliceIds(
                            'entryIds',
                            filteredWords.map((w) => w.id),
                          )
                        }
                      >
                        {t('curriculumWorkspace.slicePicker.selectAll')}
                      </Button>
                      <Button
                        type="button"
                        size="xs"
                        variant="outline"
                        onClick={() =>
                          invertSliceIds(
                            'entryIds',
                            filteredWords.map((w) => w.id),
                          )
                        }
                      >
                        {t('curriculumWorkspace.slicePicker.invertSelection')}
                      </Button>
                      {selectedEntryIds.length > 0 ? (
                        <Button
                          type="button"
                          size="xs"
                          variant="ghost"
                          onClick={() => setSliceIds('entryIds', [])}
                        >
                          {t('curriculumWorkspace.slicePicker.deselectAll')}
                        </Button>
                      ) : null}
                    </div>

                    <div className="flex flex-wrap items-center gap-1.5">
                      {buildChunks(totalWords, 8).map((chunk) => (
                        <Button
                          key={`${chunk.start}-${chunk.end}`}
                          type="button"
                          size="xs"
                          variant="secondary"
                          className="tabular-nums"
                          onClick={() =>
                            setSliceIds(
                              'entryIds',
                              (vocabEntriesQuery.data ?? [])
                                .slice(chunk.start, chunk.end)
                                .map((w) => w.id),
                            )
                          }
                        >
                          {t('curriculumWorkspace.slicePicker.chunkRange', {
                            from: chunk.start + 1,
                            to: chunk.end,
                          })}
                        </Button>
                      ))}
                      <Button
                        type="button"
                        size="xs"
                        variant="outline"
                        onClick={() =>
                          setSliceIds(
                            'entryIds',
                            (vocabEntriesQuery.data ?? []).slice(0, 6).map((w) => w.id),
                          )
                        }
                      >
                        {t('curriculumWorkspace.slicePicker.pickFirstN', { count: 6 })}
                      </Button>
                    </div>

                    <div className="divide-y rounded-xl border">
                      {filteredWords.map((word, idx) => {
                        const checked = selectedEntryIds.includes(word.id);
                        const gloss = word.meaningVi || word.definitionEn;
                        return (
                          <label
                            key={word.id}
                            className={cn(
                              'flex cursor-pointer items-center gap-3 px-3.5 py-2.5 text-sm transition-colors',
                              checked ? 'bg-primary/8' : 'hover:bg-muted/50',
                            )}
                          >
                            <Checkbox
                              checked={checked}
                              onCheckedChange={() => toggleSliceId('entryIds', word.id)}
                            />
                            <span className="w-6 shrink-0 font-mono text-xs text-muted-foreground tabular-nums">
                              {idx + 1}.
                            </span>
                            <span className="font-medium">{word.term}</span>
                            {word.pos ? <PosTag pos={word.pos} /> : null}
                            {gloss ? (
                              <span className="min-w-0 flex-1 text-right text-xs text-muted-foreground">
                                {gloss}
                              </span>
                            ) : null}
                          </label>
                        );
                      })}
                    </div>
                  </div>
                )
              ) : contentType === 'LESSON' ? (
                <div className="space-y-4">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="flex items-center gap-2">
                      <Label>{t('curriculumWorkspace.slicePicker.activityMode')}</Label>
                      <div className="inline-flex rounded-lg border bg-muted/30 p-0.5">
                        <Button
                          type="button"
                          size="xs"
                          variant={
                            (sliceConfig.activityType || 'SHADOWING') === 'SHADOWING'
                              ? 'default'
                              : 'ghost'
                          }
                          onClick={() =>
                            setSliceConfig((prev) => ({ ...prev, activityType: 'SHADOWING' }))
                          }
                        >
                          <ContentTypeIcon type="LESSON" config={{ activityType: 'SHADOWING' }} className="size-3.5" />
                          Shadowing
                        </Button>
                        <Button
                          type="button"
                          size="xs"
                          variant={sliceConfig.activityType === 'DICTATION' ? 'default' : 'ghost'}
                          onClick={() =>
                            setSliceConfig((prev) => ({ ...prev, activityType: 'DICTATION' }))
                          }
                        >
                          <ContentTypeIcon type="LESSON" config={{ activityType: 'DICTATION' }} className="size-3.5" />
                          Dictation
                        </Button>
                      </div>
                    </div>

                    {totalSentences > 0 ? (
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Button
                          type="button"
                          size="xs"
                          variant="outline"
                          onClick={() =>
                            setSliceIds(
                              'sentenceIds',
                              filteredSentences.map((s) => s.id),
                            )
                          }
                        >
                          {t('curriculumWorkspace.slicePicker.selectAll')}
                        </Button>
                        {selectedSentenceIds.length > 0 ? (
                          <Button
                            type="button"
                            size="xs"
                            variant="ghost"
                            onClick={() => setSliceIds('sentenceIds', [])}
                          >
                            {t('curriculumWorkspace.slicePicker.deselectAll')}
                          </Button>
                        ) : null}
                      </div>
                    ) : null}
                  </div>

                  {totalSentences > 0 ? (
                    <div className="space-y-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Input
                          value={sentenceFilter}
                          onChange={(e) => setSentenceFilter(e.target.value)}
                          placeholder={t(
                            'curriculumWorkspace.slicePicker.sentenceFilterPlaceholder',
                          )}
                          className="h-9 min-w-[14rem] flex-1 text-sm"
                        />
                        <div className="flex flex-wrap gap-1.5">
                          {buildChunks(totalSentences, 4).map((chunk) => (
                            <Button
                              key={`${chunk.start}-${chunk.end}`}
                              type="button"
                              size="xs"
                              variant="secondary"
                              className="tabular-nums"
                              onClick={() =>
                                setSliceIds(
                                  'sentenceIds',
                                  (lessonSentencesQuery.data ?? [])
                                    .slice(chunk.start, chunk.end)
                                    .map((s) => s.id),
                                )
                              }
                            >
                              {t('curriculumWorkspace.slicePicker.chunkRange', {
                                from: chunk.start + 1,
                                to: chunk.end,
                              })}
                            </Button>
                          ))}
                        </div>
                      </div>
                    </div>
                  ) : null}

                  {lessonSentencesQuery.isPending ? (
                    <div className="space-y-2">
                      <Skeleton className="h-12 w-full" />
                      <Skeleton className="h-12 w-full" />
                      <Skeleton className="h-12 w-full" />
                      <Skeleton className="h-12 w-full" />
                    </div>
                  ) : lessonSentencesQuery.isError ? (
                    <div className="flex items-center justify-between gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive">
                      <span>{t('curriculumWorkspace.slicePicker.loadSliceError')}</span>
                      <Button
                        type="button"
                        size="xs"
                        variant="outline"
                        onClick={() => void lessonSentencesQuery.refetch()}
                      >
                        <RiRefreshLine data-icon="inline-start" aria-hidden="true" />
                        {t('pronunciationPilot.retry')}
                      </Button>
                    </div>
                  ) : totalSentences === 0 ? (
                    <p className="py-10 text-center text-sm text-muted-foreground">
                      {t('curriculumWorkspace.slicePicker.emptyLessonSentences')}
                    </p>
                  ) : (
                    <div className="divide-y rounded-xl border">
                      {filteredSentences.map((s, idx) => {
                        const checked = selectedSentenceIds.includes(s.id);
                        return (
                          <label
                            key={s.id}
                            className={cn(
                              'flex cursor-pointer items-start gap-3 px-3.5 py-2.5 text-sm transition-colors',
                              checked ? 'bg-primary/8' : 'hover:bg-muted/50',
                            )}
                          >
                            <Checkbox
                              checked={checked}
                              className="mt-0.5"
                              onCheckedChange={() => toggleSliceId('sentenceIds', s.id)}
                            />
                            <span className="shrink-0 font-mono text-xs text-muted-foreground tabular-nums">
                              {s.position ?? idx + 1}.
                            </span>
                            <span className="leading-relaxed">{s.text}</span>
                          </label>
                        );
                      })}
                    </div>
                  )}
                </div>
              ) : contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST' ? (
                <div className="space-y-5">
                  {toeicDetailQuery.isPending ? (
                    <div className="space-y-2">
                      <Skeleton className="h-24 w-full" />
                      <Skeleton className="h-24 w-full" />
                    </div>
                  ) : toeicDetailQuery.isError ? (
                    <div className="flex items-center justify-between gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive">
                      <span>{t('curriculumWorkspace.slicePicker.loadSliceError')}</span>
                      <Button
                        type="button"
                        size="xs"
                        variant="outline"
                        onClick={() => void toeicDetailQuery.refetch()}
                      >
                        <RiRefreshLine data-icon="inline-start" aria-hidden="true" />
                        {t('pronunciationPilot.retry')}
                      </Button>
                    </div>
                  ) : null}

                  <div className="space-y-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <Label>{t('curriculumWorkspace.slicePicker.toeicParts')}</Label>
                      <div className="flex flex-wrap items-center gap-1.5">
                        <Button
                          type="button"
                          size="xs"
                          variant="outline"
                          onClick={() => applyPartsSelection([1, 2, 3, 4])}
                        >
                          Listening (1–4)
                        </Button>
                        <Button
                          type="button"
                          size="xs"
                          variant="outline"
                          onClick={() => applyPartsSelection([5, 6, 7])}
                        >
                          Reading (5–7)
                        </Button>
                        <Button
                          type="button"
                          size="xs"
                          variant="outline"
                          onClick={() => applyPartsSelection([5, 6])}
                        >
                          Part 5–6
                        </Button>
                        <Button
                          type="button"
                          size="xs"
                          variant="ghost"
                          onClick={() => applyPartsSelection([...ALL_TOEIC_PARTS])}
                        >
                          {t('curriculumWorkspace.slicePicker.clearSlice')}
                        </Button>
                      </div>
                    </div>

                    <div className="grid gap-3 sm:grid-cols-2">
                      <div className="space-y-2 rounded-xl border bg-muted/15 p-3.5">
                        <p className="text-xs font-semibold text-muted-foreground">
                          {t('curriculumWorkspace.slicePicker.listeningGroup')}
                        </p>
                        <div className="grid grid-cols-2 gap-2">
                          {[1, 2, 3, 4].map((p) => {
                            const active = activeToeicParts.includes(p);
                            const partAvail = toeicPartCounts[p] ?? 0;
                            return (
                              <Button
                                key={p}
                                type="button"
                                size="sm"
                                variant={active ? 'default' : 'outline'}
                                className="justify-between tabular-nums"
                                onClick={() => togglePart(p)}
                              >
                                <span>Part {p}</span>
                                <span className="text-xs opacity-80">({partAvail})</span>
                              </Button>
                            );
                          })}
                        </div>
                      </div>

                      <div className="space-y-2 rounded-xl border bg-muted/15 p-3.5">
                        <p className="text-xs font-semibold text-muted-foreground">
                          {t('curriculumWorkspace.slicePicker.readingGroup')}
                        </p>
                        <div className="grid grid-cols-2 gap-2">
                          {[5, 6, 7].map((p) => {
                            const active = activeToeicParts.includes(p);
                            const partAvail = toeicPartCounts[p] ?? 0;
                            return (
                              <Button
                                key={p}
                                type="button"
                                size="sm"
                                variant={active ? 'default' : 'outline'}
                                className="justify-between tabular-nums"
                                onClick={() => togglePart(p)}
                              >
                                <span>Part {p}</span>
                                <span className="text-xs opacity-80">({partAvail})</span>
                              </Button>
                            );
                          })}
                        </div>
                      </div>
                    </div>
                  </div>

                  <div className="space-y-2 rounded-xl border bg-muted/15 p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <Label>{t('curriculumWorkspace.slicePicker.questionCount')}</Label>
                      <span className="text-xs text-muted-foreground tabular-nums">
                        {t('curriculumWorkspace.slicePicker.toeicMaxQuestions', {
                          count: maxToeicQuestions,
                        })}
                      </span>
                    </div>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {[6, 10, 15, 20, 30, 50, 100, maxToeicQuestions]
                        .filter((n, i, arr) => n <= maxToeicQuestions && arr.indexOf(n) === i)
                        .map((preset) => (
                          <Button
                            key={preset}
                            type="button"
                            size="xs"
                            variant={sliceConfig.questionCount === preset ? 'secondary' : 'outline'}
                            className="tabular-nums"
                            onClick={() =>
                              setSliceConfig((prev) => ({ ...prev, questionCount: preset }))
                            }
                          >
                            {preset}Q
                          </Button>
                        ))}
                    </div>
                    <Input
                      type="number"
                      min={1}
                      max={maxToeicQuestions}
                      value={Math.min(
                        sliceConfig.questionCount ??
                          (contentType === 'TOEIC_TEST' ? maxToeicQuestions : 10),
                        maxToeicQuestions,
                      )}
                      onChange={(e) => {
                        const raw = Math.max(1, Number(e.target.value) || 1);
                        setSliceConfig((prev) => ({
                          ...prev,
                          questionCount: Math.min(raw, maxToeicQuestions),
                        }));
                      }}
                    />
                  </div>
                </div>
              ) : (
                <div className="space-y-4">
                  {grammarSubtopicsQuery.isPending ? (
                    <div className="space-y-2">
                      <Skeleton className="h-12 w-full" />
                      <Skeleton className="h-12 w-full" />
                      <Skeleton className="h-12 w-full" />
                    </div>
                  ) : grammarSubtopicsQuery.isError ? (
                    <div className="flex items-center justify-between gap-2 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive">
                      <span>{t('curriculumWorkspace.slicePicker.loadSliceError')}</span>
                      <Button
                        type="button"
                        size="xs"
                        variant="outline"
                        onClick={() => void grammarSubtopicsQuery.refetch()}
                      >
                        <RiRefreshLine data-icon="inline-start" aria-hidden="true" />
                        {t('pronunciationPilot.retry')}
                      </Button>
                    </div>
                  ) : subtopics.length > 0 ? (
                    <div className="space-y-2.5">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <Label>{t('curriculumWorkspace.slicePicker.grammarSubtopics')}</Label>
                        <div className="flex items-center gap-1.5">
                          <Button
                            type="button"
                            size="xs"
                            variant="outline"
                            onClick={selectAllValidGrammarSubtopics}
                          >
                            {t('curriculumWorkspace.slicePicker.selectValidSubtopics')}
                          </Button>
                          {selectedSubtopicIds.length > 0 ? (
                            <Button
                              type="button"
                              size="xs"
                              variant="ghost"
                              onClick={clearGrammarSubtopics}
                            >
                              {t('curriculumWorkspace.slicePicker.clearSlice')}
                            </Button>
                          ) : null}
                        </div>
                      </div>

                      {subtopics.length > 4 ? (
                        <Input
                          value={subtopicFilter}
                          onChange={(e) => setSubtopicFilter(e.target.value)}
                          placeholder={t(
                            'curriculumWorkspace.slicePicker.subtopicFilterPlaceholder',
                          )}
                          className="h-9 text-sm"
                        />
                      ) : null}

                      <div className="divide-y rounded-xl border bg-background">
                        {filteredSubtopics.map((sub) => {
                          const checked = selectedSubtopicIds.includes(sub.id);
                          const qCount = sub.questionCount ?? 0;
                          const isEmptySub = qCount === 0;
                          const subTitle =
                            (isVi ? sub.title : sub.titleEn) || sub.title || sub.titleEn || '';
                          return (
                            <label
                              key={sub.id}
                              className={cn(
                                'flex items-center justify-between gap-3 px-3.5 py-2.5 text-sm transition-colors',
                                isEmptySub
                                  ? 'cursor-not-allowed opacity-55'
                                  : checked
                                    ? 'cursor-pointer bg-primary/8 font-medium'
                                    : 'cursor-pointer hover:bg-muted/50',
                              )}
                            >
                              <span className="flex min-w-0 items-center gap-2.5">
                                <Checkbox
                                  checked={checked}
                                  disabled={isEmptySub}
                                  onCheckedChange={() => {
                                    if (!isEmptySub) toggleGrammarSubtopic(sub.id);
                                  }}
                                />
                                <span className="leading-snug">{subTitle}</span>
                              </span>
                              <Badge
                                variant={isEmptySub ? 'outline' : 'secondary'}
                                className="shrink-0 tabular-nums"
                              >
                                {isEmptySub
                                  ? t('curriculumWorkspace.slicePicker.emptySubtopic')
                                  : `${qCount}Q`}
                              </Badge>
                            </label>
                          );
                        })}
                      </div>
                    </div>
                  ) : null}

                  <div className="space-y-2 rounded-xl border bg-muted/15 p-4">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <Label>{t('curriculumWorkspace.slicePicker.questionCount')}</Label>
                      <span className="text-xs text-muted-foreground tabular-nums">
                        {t('curriculumWorkspace.slicePicker.grammarMaxQuestions', {
                          count: maxGrammarQuestions,
                        })}
                      </span>
                    </div>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {[5, 8, 10, 15, 20, maxGrammarQuestions]
                        .filter((n, i, arr) => n <= maxGrammarQuestions && arr.indexOf(n) === i)
                        .map((preset) => (
                          <Button
                            key={preset}
                            type="button"
                            size="xs"
                            variant={sliceConfig.questionCount === preset ? 'secondary' : 'outline'}
                            className="tabular-nums"
                            onClick={() =>
                              setSliceConfig((prev) => ({ ...prev, questionCount: preset }))
                            }
                          >
                            {preset}Q
                          </Button>
                        ))}
                    </div>
                    <Input
                      type="number"
                      min={1}
                      max={maxGrammarQuestions}
                      value={Math.min(sliceConfig.questionCount ?? 10, maxGrammarQuestions)}
                      onChange={(e) => {
                        const raw = Math.max(1, Number(e.target.value) || 1);
                        setSliceConfig((prev) => ({
                          ...prev,
                          questionCount: Math.min(raw, maxGrammarQuestions),
                        }));
                      }}
                    />
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        <DialogFooter className="shrink-0 flex-wrap items-center justify-between gap-3 border-t bg-muted/20 px-6 py-3.5 sm:justify-between">
          <div className="min-w-0 flex-1 text-xs text-muted-foreground sm:text-sm">
            {selectedCandidate ? (
              <span className="inline-flex flex-wrap items-center gap-1.5">
                <strong className="font-medium text-foreground">{selectedCandidate.title}</strong>
                {previewSummary ? (
                  <>
                    <span>·</span>
                    <span className="tabular-nums">{previewSummary}</span>
                  </>
                ) : null}
              </span>
            ) : (
              <span>{t('curriculumWorkspace.slicePicker.previewEmpty')}</span>
            )}
          </div>
          <div className="flex items-center gap-2">
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              {t('catalog.cancel')}
            </Button>
            <Button
              type="button"
              disabled={!isValidPickedUuid}
              onClick={() => {
                const finalConfig = { ...sliceConfig };
                if (contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST') {
                  finalConfig.parts = [...activeToeicParts];
                  finalConfig.questionCount = Math.min(
                    finalConfig.questionCount ??
                      (contentType === 'TOEIC_TEST' ? maxToeicQuestions : 10),
                    maxToeicQuestions,
                  );
                } else if (contentType === 'GRAMMAR_PRACTICE') {
                  finalConfig.questionCount = Math.min(
                    finalConfig.questionCount ?? 10,
                    maxGrammarQuestions,
                  );
                }
                onSelect(pickedId, finalConfig, selectedCandidate?.title);
                onOpenChange(false);
              }}
            >
              {t('curriculumWorkspace.slicePicker.applySlice')}
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
