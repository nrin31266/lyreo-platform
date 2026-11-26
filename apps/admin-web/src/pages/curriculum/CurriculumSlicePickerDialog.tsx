import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiCheckLine, RiScissorsCutLine, RiSearchLine } from '@remixicon/react';
import { api } from '@/lib/api';
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
import { fetchLessons } from '@/pages/lessons/catalog.api';
import type { Summary } from '@/pages/library/library.types';
import type { CurriculumContentType, ItemSliceConfig } from './curriculum.api';

type PickableEntry = {
  id: string;
  title: string;
  meta: string;
};

type VocabPack = { id: string; titleEn: string; titleVi: string };
type VocabUnit = { id: string; title: string; level: string };
type VocabEntry = { id: string; term: string };
type LessonAuthoringDetail = {
  sentences?: Array<{ id: string; position: number; text: string }>;
};

async function fetchCandidates(
  contentType: CurriculumContentType,
  search: string,
  signal?: AbortSignal,
): Promise<PickableEntry[]> {
  if (contentType === 'LESSON') {
    const params = new URLSearchParams({ page: '0', size: '30', status: 'PUBLISHED' });
    if (search) params.set('query', search);
    const page = await fetchLessons(params, signal);
    return page.items.map((item) => ({
      id: item.id,
      title: item.title,
      meta: `${item.sourceType} · ${item.cefr ?? item.status}`,
    }));
  }
  if (contentType === 'VOCABULARY_REVIEW') {
    const packs = await api<VocabPack[]>('/api/v1/admin/vocabulary/packs', { signal });
    const unitLists = await Promise.all(
      packs.map(async (pack) => {
        const units = await api<VocabUnit[]>(
          `/api/v1/admin/vocabulary/packs/${encodeURIComponent(pack.id)}/units`,
          { signal },
        );
        return units.map((unit) => ({
          id: unit.id,
          title: unit.title,
          meta: `${pack.titleVi || pack.titleEn} · ${unit.level}`,
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
    const params = new URLSearchParams({ page: '0', size: '30', status: 'PUBLISHED' });
    if (search) params.set('q', search);
    const list = await api<Summary[]>(`/api/v1/admin/grammar/topics?${params}`, { signal });
    return list.map((item) => ({
      id: item.id,
      title: item.titleEn || item.title,
      meta: `${item.status} · ${item.questionCount ?? 0}Q`,
    }));
  }
  const params = new URLSearchParams({ page: '0', size: '30', status: 'PUBLISHED' });
  if (search) params.set('q', search);
  const list = await api<Summary[]>(`/api/v1/admin/toeic?${params}`, { signal });
  return list.map((item) => ({
    id: item.id,
    title: item.title,
    meta: `${item.year ?? ''} · ${item.status}`.trim(),
  }));
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
  onSelect: (id: string, config: ItemSliceConfig) => void;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation('admin');
  const [search, setSearch] = useState('');
  const [pickedId, setPickedId] = useState(currentId);
  const [sliceConfig, setSliceConfig] = useState<ItemSliceConfig>(currentConfig ?? {});

  useEffect(() => {
    if (open) {
      setPickedId(currentId);
      setSliceConfig(currentConfig ?? {});
    }
  }, [open, currentId, currentConfig]);

  const query = useQuery({
    queryKey: ['curriculum-slice-picker', contentType, search],
    queryFn: ({ signal }) => fetchCandidates(contentType, search.trim(), signal),
    enabled: open,
  });

  const vocabEntriesQuery = useQuery({
    queryKey: ['curriculum-slice-vocab-entries', pickedId],
    queryFn: ({ signal }) =>
      api<VocabEntry[]>(`/api/v1/admin/vocabulary/units/${encodeURIComponent(pickedId)}/entries`, {
        signal,
      }),
    enabled: open && contentType === 'VOCABULARY_REVIEW' && Boolean(pickedId),
  });

  const lessonSentencesQuery = useQuery({
    queryKey: ['curriculum-slice-lesson-sentences', pickedId],
    queryFn: async ({ signal }) => {
      const detail = await api<LessonAuthoringDetail>(
        `/api/v1/admin/lesson-authoring/${encodeURIComponent(pickedId)}`,
        { signal },
      );
      return detail.sentences ?? [];
    },
    enabled: open && contentType === 'LESSON' && Boolean(pickedId),
  });

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

  function togglePart(partNum: number) {
    setSliceConfig((prev) => {
      const existing = Array.isArray(prev.parts) ? [...prev.parts] : [];
      const next = existing.includes(partNum)
        ? existing.filter((p) => p !== partNum)
        : [...existing, partNum].sort((a, b) => a - b);
      const copy = { ...prev };
      if (next.length === 0) {
        delete copy.parts;
      } else {
        copy.parts = next;
      }
      return copy;
    });
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <RiScissorsCutLine className="size-4" aria-hidden="true" />
            {t('curriculumWorkspace.slicePicker.title')}
          </DialogTitle>
          <DialogDescription>
            {t('curriculumWorkspace.slicePicker.description', {
              type: t(`curriculumWorkspace.contentTypes.${contentType}`),
            })}
          </DialogDescription>
        </DialogHeader>

        <div className="grid gap-4 md:grid-cols-2">
          <div className="space-y-3">
            <div className="relative">
              <RiSearchLine
                className="pointer-events-none absolute top-2.5 left-3 size-4 text-muted-foreground"
                aria-hidden="true"
              />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t('curriculumWorkspace.pickerSearch')}
                className="pl-9"
              />
            </div>

            <div className="max-h-72 space-y-1.5 overflow-y-auto pr-1">
              {query.isPending ? (
                <Skeleton className="h-32 w-full" />
              ) : !query.data || query.data.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">
                  {t('curriculumWorkspace.pickerEmpty')}
                </p>
              ) : (
                query.data.map((entry) => {
                  const selected = entry.id === pickedId;
                  return (
                    <button
                      key={entry.id}
                      type="button"
                      onClick={() => {
                        setPickedId(entry.id);
                        setSliceConfig({});
                      }}
                      className={`flex w-full items-center justify-between rounded-lg border px-3 py-2 text-left text-sm transition ${
                        selected ? 'border-primary bg-muted font-medium' : 'hover:bg-muted/50'
                      }`}
                    >
                      <span className="min-w-0 pr-2">
                        <span className="block truncate">{entry.title}</span>
                        <span className="block truncate text-xs text-muted-foreground">
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

          <div className="space-y-3 rounded-lg border bg-muted/20 p-3">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {t('curriculumWorkspace.slicePicker.sliceHeading')}
            </p>

            {contentType === 'VOCABULARY_REVIEW' ? (
              !pickedId ? (
                <p className="text-xs text-muted-foreground">
                  {t('curriculumWorkspace.slicePicker.selectSourceFirst')}
                </p>
              ) : vocabEntriesQuery.isPending ? (
                <Skeleton className="h-32 w-full" />
              ) : (
                <div className="max-h-60 space-y-1.5 overflow-y-auto pr-1">
                  {(vocabEntriesQuery.data ?? []).map((word) => {
                    const checked = Array.isArray(sliceConfig.entryIds)
                      ? sliceConfig.entryIds.includes(word.id)
                      : false;
                    return (
                      <label
                        key={word.id}
                        className="flex cursor-pointer items-center gap-2 rounded px-2 py-1 text-sm hover:bg-muted"
                      >
                        <Checkbox
                          checked={checked}
                          onCheckedChange={() => toggleSliceId('entryIds', word.id)}
                        />
                        <span>{word.term}</span>
                      </label>
                    );
                  })}
                </div>
              )
            ) : contentType === 'LESSON' ? (
              <div className="space-y-3">
                <div className="space-y-1">
                  <Label>{t('curriculumWorkspace.slicePicker.activityMode')}</Label>
                  <Select
                    value={(sliceConfig.activityType as string) || 'SHADOWING'}
                    onValueChange={(value) =>
                      setSliceConfig((prev) => ({
                        ...prev,
                        activityType: value as 'SHADOWING' | 'DICTATION',
                      }))
                    }
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="SHADOWING">SHADOWING</SelectItem>
                      <SelectItem value="DICTATION">DICTATION</SelectItem>
                    </SelectContent>
                  </Select>
                </div>
                {pickedId ? (
                  <div className="max-h-48 space-y-1.5 overflow-y-auto pr-1">
                    {(lessonSentencesQuery.data ?? []).map((s) => {
                      const checked = Array.isArray(sliceConfig.sentenceIds)
                        ? sliceConfig.sentenceIds.includes(s.id)
                        : false;
                      return (
                        <label
                          key={s.id}
                          className="flex cursor-pointer items-start gap-2 rounded px-2 py-1 text-xs hover:bg-muted"
                        >
                          <Checkbox
                            checked={checked}
                            onCheckedChange={() => toggleSliceId('sentenceIds', s.id)}
                          />
                          <span className="line-clamp-2">{s.text}</span>
                        </label>
                      );
                    })}
                  </div>
                ) : null}
              </div>
            ) : contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST' ? (
              <div className="space-y-3">
                <div className="space-y-1">
                  <Label>{t('curriculumWorkspace.slicePicker.toeicParts')}</Label>
                  <div className="flex flex-wrap gap-2">
                    {[1, 2, 3, 4, 5, 6, 7].map((p) => {
                      const active = Array.isArray(sliceConfig.parts) && sliceConfig.parts.includes(p);
                      return (
                        <Button
                          key={p}
                          type="button"
                          size="sm"
                          variant={active ? 'default' : 'outline'}
                          onClick={() => togglePart(p)}
                        >
                          Part {p}
                        </Button>
                      );
                    })}
                  </div>
                </div>
                <div className="space-y-1">
                  <Label>{t('curriculumWorkspace.slicePicker.questionCount')}</Label>
                  <Input
                    type="number"
                    min={1}
                    max={200}
                    value={sliceConfig.questionCount ?? 10}
                    onChange={(e) =>
                      setSliceConfig((prev) => ({
                        ...prev,
                        questionCount: Math.max(1, Number(e.target.value) || 10),
                      }))
                    }
                  />
                </div>
              </div>
            ) : (
              <div className="space-y-1">
                <Label>{t('curriculumWorkspace.slicePicker.questionCount')}</Label>
                <Input
                  type="number"
                  min={1}
                  max={50}
                  value={sliceConfig.questionCount ?? sliceConfig.targetTurns ?? 8}
                  onChange={(e) =>
                    setSliceConfig((prev) => ({
                      ...prev,
                      questionCount: Math.max(1, Number(e.target.value) || 8),
                    }))
                  }
                />
              </div>
            )}
          </div>
        </div>

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t('catalog.cancel')}
          </Button>
          <Button
            type="button"
            disabled={!pickedId}
            onClick={() => {
              onSelect(pickedId, sliceConfig);
              onOpenChange(false);
            }}
          >
            {t('curriculumWorkspace.slicePicker.applySlice')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
