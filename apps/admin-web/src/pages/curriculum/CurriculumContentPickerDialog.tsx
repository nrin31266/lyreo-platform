import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { RiCheckLine, RiSearchLine } from '@remixicon/react';
import { api } from '@/lib/api';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import type { CurriculumContentType } from './curriculum.api';
import { fetchLessons } from '@/pages/lessons/catalog.api';
import type { Summary } from '@/pages/library/library.types';

type PickableEntry = {
  id: string;
  title: string;
  meta: string;
};

type VocabPack = { id: string; titleEn: string; titleVi: string };
type VocabUnit = { id: string; title: string; level: string };

async function fetchCandidates(
  contentType: CurriculumContentType,
  search: string,
  signal?: AbortSignal,
): Promise<PickableEntry[]> {
  if (contentType === 'LESSON') {
    const params = new URLSearchParams({ page: '0', size: '25', status: 'PUBLISHED' });
    if (search) params.set('query', search);
    const page = await fetchLessons(params, signal);
    return page.items.map((item) => ({
      id: item.id,
      title: item.title,
      meta: `${item.sourceType} · ${item.status}`,
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
  if (contentType === 'TOEIC_DRILL' || contentType === 'TOEIC_TEST') {
    const params = new URLSearchParams({ page: '0', size: '30', status: 'PUBLISHED' });
    if (search) params.set('q', search);
    const list = await api<Summary[]>(`/api/v1/admin/toeic?${params}`, { signal });
    return list.map((item) => ({
      id: item.id,
      title: item.title,
      meta: `${item.year ?? ''} · ${item.status}`.trim(),
    }));
  }
  return [];
}

export function CurriculumContentPickerDialog({
  open,
  contentType,
  currentId,
  onSelect,
  onOpenChange,
}: {
  open: boolean;
  contentType: CurriculumContentType;
  currentId: string;
  onSelect: (id: string) => void;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation('admin');
  const [search, setSearch] = useState('');

  const supportsPicker = contentType !== 'SPEAKING_SCENARIO';
  const query = useQuery({
    queryKey: ['curriculum-picker', contentType, search],
    queryFn: ({ signal }) => fetchCandidates(contentType, search.trim(), signal),
    enabled: open && supportsPicker,
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('curriculumWorkspace.pickerTitle')}</DialogTitle>
          <DialogDescription>
            {t('curriculumWorkspace.pickerHelp', {
              type: t(`curriculumWorkspace.contentTypes.${contentType}`),
            })}
          </DialogDescription>
        </DialogHeader>

        {!supportsPicker ? (
          <p className="text-sm text-muted-foreground">{t('curriculumWorkspace.pickerManualOnly')}</p>
        ) : (
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

            <div className="max-h-72 space-y-2 overflow-y-auto pr-1">
              {query.isPending ? (
                <Skeleton className="h-24 w-full" />
              ) : !query.data || query.data.length === 0 ? (
                <p className="py-6 text-center text-sm text-muted-foreground">
                  {t('curriculumWorkspace.pickerEmpty')}
                </p>
              ) : (
                query.data.map((entry) => {
                  const selected = entry.id === currentId;
                  return (
                    <button
                      key={entry.id}
                      type="button"
                      onClick={() => {
                        onSelect(entry.id);
                        onOpenChange(false);
                      }}
                      className={`flex w-full items-center justify-between gap-2 rounded-xl border px-3 py-2 text-left transition-colors ${
                        selected ? 'border-primary bg-muted/40' : 'hover:bg-muted/25'
                      }`}
                    >
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium">{entry.title}</p>
                        <p className="truncate font-mono text-xs text-muted-foreground">
                          {entry.meta} · {entry.id}
                        </p>
                      </div>
                      {selected ? (
                        <Badge variant="default" className="shrink-0">
                          <RiCheckLine aria-hidden="true" />
                        </Badge>
                      ) : null}
                    </button>
                  );
                })
              )}
            </div>
          </div>
        )}

        <DialogFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
            {t('curriculumWorkspace.pickerClose')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
