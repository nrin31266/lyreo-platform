import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiAddLine,
  RiArrowDownLine,
  RiArrowDownSLine,
  RiArrowRightSLine,
  RiArrowUpLine,
  RiDeleteBinLine,
  RiEditLine,
  RiImageLine,
  RiMindMap,
  RiScissorsCutLine,
  RiStackLine,
} from '@remixicon/react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { AccessTierOption } from '@/components/AccessTierOption';
import { AuthenticatedImage } from '@/components/AuthenticatedImage';
import { AccessTierBadge, CefrLevelBadge, statusBadgeVariant } from '@/components/ContentBadges';
import { ImageAssetEditor } from '@/components/ImageAssetEditor';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { CurriculumGraphPreview } from './CurriculumGraphPreview';
import { CurriculumSlicePickerDialog } from './CurriculumSlicePickerDialog';
import {
  contentAccentVar,
  ContentTypeIcon,
  curriculumLegendTypes,
  formatCurriculumSliceBadge,
} from './curriculumMeta';
import {
  createAdminCurriculumPath,
  curriculumContentTypes,
  curriculumKey,
  curriculumStatuses,
  curriculumUnlockRules,
  deleteAdminCurriculumPath,
  fetchAdminCurriculumPath,
  fetchAdminCurriculumPaths,
  updateAdminCurriculumPath,
  type AdminCurriculumPath,
  type CurriculumAccessMode,
  type CurriculumContentType,
  type CurriculumStatus,
  type CurriculumUnlockRule,
  type ItemSliceConfig,
  type SaveCurriculumPathInput,
} from './curriculum.api';

type DraftItem = {
  key: string;
  contentType: CurriculumContentType;
  contentReferenceId: string;
  required: boolean;
  unlockRule: CurriculumUnlockRule;
  config: ItemSliceConfig;
};

type DraftSection = {
  key: string;
  title: string;
  items: DraftItem[];
};

type DraftPath = {
  id: string | null;
  code: string;
  name: string;
  level: string;
  description: string;
  status: CurriculumStatus;
  coverAssetId: string | null;
  accessMode: CurriculumAccessMode;
  requiredFeatureKey: string | null;
  sections: DraftSection[];
};

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function previewAutoPathCode(name: string): string {
  const replaced = name.replace(/đ/g, 'd').replace(/Đ/g, 'D');
  const normalized = replaced
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
  if (!normalized) return '';
  return normalized.slice(0, 56).replace(/_+$/g, '');
}

function serializeDraft(d: DraftPath): string {
  return JSON.stringify({
    name: d.name.trim(),
    level: d.level.trim(),
    description: d.description.trim(),
    status: d.status,
    coverAssetId: d.coverAssetId ?? null,
    accessMode: d.accessMode,
    requiredFeatureKey:
      d.accessMode === 'FEATURE' ? d.requiredFeatureKey || 'lesson.plus-lessons' : null,
    sections: d.sections.map((section) => ({
      title: section.title.trim(),
      items: section.items.map((item) => ({
        contentType: item.contentType,
        contentReferenceId: item.contentReferenceId.trim(),
        required: item.required,
        unlockRule: item.unlockRule,
        config: item.config ?? {},
      })),
    })),
  });
}

function PathCoverThumb({
  coverAssetId,
  alt,
  className = 'h-11 w-16',
}: {
  coverAssetId?: string | null;
  alt: string;
  className?: string;
}) {
  return (
    <AuthenticatedImage
      src={
        coverAssetId
          ? `/api/v1/admin/content-media/${encodeURIComponent(coverAssetId)}/file`
          : null
      }
      alt={alt}
      className={cn(
        'shrink-0 overflow-hidden rounded-xl border border-border/70 object-cover shadow-2xs ring-1 ring-black/5',
        className,
      )}
      fallback={
        <div
          className={cn(
            'flex shrink-0 items-center justify-center rounded-xl border border-border/70 bg-muted text-muted-foreground shadow-2xs',
            className,
          )}
        >
          <RiImageLine className="size-4" aria-hidden="true" />
        </div>
      }
    />
  );
}

function nextLocalKey(prefix: string) {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}

function emptyItem(): DraftItem {
  return {
    key: nextLocalKey('item'),
    contentType: 'LESSON',
    contentReferenceId: '',
    required: true,
    unlockRule: 'PREVIOUS_REQUIRED',
    config: {},
  };
}

function emptySection(): DraftSection {
  return {
    key: nextLocalKey('section'),
    title: '',
    items: [{ ...emptyItem(), unlockRule: 'NONE' }],
  };
}

function emptyDraft(): DraftPath {
  return {
    id: null,
    code: '',
    name: '',
    level: 'A2',
    description: '',
    status: 'DRAFT',
    coverAssetId: null,
    accessMode: 'PUBLIC',
    requiredFeatureKey: null,
    sections: [emptySection()],
  };
}

function toDraft(path: AdminCurriculumPath): DraftPath {
  return {
    id: path.id,
    code: path.code,
    name: path.name,
    level: path.level ?? '',
    description: path.description ?? '',
    status: path.status,
    coverAssetId: path.coverAssetId ?? null,
    accessMode: path.accessMode ?? 'PUBLIC',
    requiredFeatureKey: path.requiredFeatureKey ?? null,
    sections: path.sections
      .slice()
      .sort((a, b) => a.position - b.position)
      .map((section) => ({
        key: section.id,
        title: section.title,
        items: section.items
          .slice()
          .sort((a, b) => a.position - b.position)
          .map((item) => ({
            key: item.id,
            contentType: item.contentType,
            contentReferenceId: item.contentReferenceId,
            required: item.required,
            unlockRule: (item.unlockRule as CurriculumUnlockRule) || 'NONE',
            config: item.config ?? {},
          })),
      })),
  };
}

function moveElement<T>(list: T[], index: number, delta: -1 | 1): T[] {
  const target = index + delta;
  if (target < 0 || target >= list.length) return list;
  const copy = [...list];
  const [moved] = copy.splice(index, 1);
  copy.splice(target, 0, moved);
  return copy;
}

export function CurriculumPage() {
  const { t } = useTranslation('admin');
  const client = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | 'new' | null>(null);
  const [draft, setDraft] = useState<DraftPath | null>(null);
  const [baselineSnapshot, setBaselineSnapshot] = useState<string>('');
  const [activeTab, setActiveTab] = useState('builder');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [savedNotice, setSavedNotice] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [pathInfoOpen, setPathInfoOpen] = useState(false);
  const [expandedUuidKeys, setExpandedUuidKeys] = useState<Record<string, boolean>>({});
  const [collapsedSections, setCollapsedSections] = useState<Record<string, boolean>>({});
  const [localTitles, setLocalTitles] = useState<Record<string, string>>({});
  const [pickerTarget, setPickerTarget] = useState<{
    sectionIndex: number;
    itemIndex: number;
    contentType: CurriculumContentType;
    currentId: string;
    currentConfig: ItemSliceConfig;
  } | null>(null);

  const paths = useQuery({
    queryKey: curriculumKey,
    queryFn: ({ signal }) => fetchAdminCurriculumPaths(signal),
  });

  const detail = useQuery({
    queryKey: [...curriculumKey, 'detail', selectedId],
    enabled: Boolean(selectedId && selectedId !== 'new'),
    queryFn: ({ signal }) => fetchAdminCurriculumPath(selectedId!, signal),
  });

  const titleLookup = useMemo(() => {
    const map: Record<string, string> = {};
    for (const sec of detail.data?.sections ?? []) {
      for (const item of sec.items) {
        if (item.contentTitle) {
          map[item.contentReferenceId] = item.contentTitle;
        }
      }
    }
    return { ...map, ...localTitles };
  }, [detail.data, localTitles]);

  const draftStats = useMemo(() => {
    const typeCounts: Record<CurriculumContentType, number> = {
      VOCABULARY_REVIEW: 0,
      LESSON: 0,
      GRAMMAR_PRACTICE: 0,
      TOEIC_DRILL: 0,
      TOEIC_TEST: 0,
    };
    if (!draft) {
      return { sections: 0, items: 0, required: 0, typeCounts };
    }
    let items = 0;
    let required = 0;
    for (const sec of draft.sections) {
      items += sec.items.length;
      for (const it of sec.items) {
        if (it.required) required += 1;
        typeCounts[it.contentType] = (typeCounts[it.contentType] ?? 0) + 1;
      }
    }
    return { sections: draft.sections.length, items, required, typeCounts };
  }, [draft]);

  const isDirty = useMemo(
    () => Boolean(draft) && serializeDraft(draft!) !== baselineSnapshot,
    [draft, baselineSnapshot],
  );

  useEffect(() => {
    if (!paths.data || selectedId !== null) return;
    if (paths.data.length > 0) {
      setSelectedId(paths.data[0].id);
    } else {
      const next = emptyDraft();
      setSelectedId('new');
      setDraft(next);
      setBaselineSnapshot(serializeDraft(next));
    }
  }, [paths.data, selectedId]);

  useEffect(() => {
    if (selectedId && selectedId !== 'new' && detail.data && detail.data.id === selectedId) {
      const next = toDraft(detail.data);
      setDraft(next);
      setBaselineSnapshot(serializeDraft(next));
      setCollapsedSections({});
    }
  }, [detail.data, selectedId]);

  const saveMutation = useMutation({
    mutationFn: async (input: { id: string | null; payload: SaveCurriculumPathInput }) =>
      input.id
        ? updateAdminCurriculumPath(input.id, input.payload)
        : createAdminCurriculumPath(input.payload),
    onSuccess: (saved) => {
      const next = toDraft(saved);
      setSelectedId(saved.id);
      client.setQueryData([...curriculumKey, 'detail', saved.id], saved);
      setDraft(next);
      setBaselineSnapshot(serializeDraft(next));
      setValidationError(null);
      setSavedNotice(true);
      void client.invalidateQueries({ queryKey: curriculumKey });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (pathId: string) => deleteAdminCurriculumPath(pathId),
    onSuccess: async () => {
      setDeleteOpen(false);
      setSelectedId(null);
      setDraft(null);
      setBaselineSnapshot('');
      setSavedNotice(false);
      await client.invalidateQueries({ queryKey: curriculumKey });
    },
  });

  function selectPath(pathId: string) {
    setSelectedId(pathId);
    setDraft(null);
    setBaselineSnapshot('');
    setValidationError(null);
    setSavedNotice(false);
    saveMutation.reset();
  }

  function startNewPath() {
    const next = emptyDraft();
    setSelectedId('new');
    setDraft(next);
    setBaselineSnapshot(serializeDraft(next));
    setCollapsedSections({});
    setActiveTab('builder');
    setValidationError(null);
    setSavedNotice(false);
    setPathInfoOpen(true);
    saveMutation.reset();
  }

  function updateSection(sectionIndex: number, patch: Partial<DraftSection>) {
    setDraft((current) => {
      if (!current) return current;
      return {
        ...current,
        sections: current.sections.map((section, idx) =>
          idx === sectionIndex ? { ...section, ...patch } : section,
        ),
      };
    });
    setSavedNotice(false);
  }

  function updateItem(sectionIndex: number, itemIndex: number, patch: Partial<DraftItem>) {
    setDraft((current) => {
      if (!current) return current;
      return {
        ...current,
        sections: current.sections.map((section, idx) =>
          idx !== sectionIndex
            ? section
            : {
                ...section,
                items: section.items.map((item, j) =>
                  j === itemIndex ? { ...item, ...patch } : item,
                ),
              },
        ),
      };
    });
    setSavedNotice(false);
  }

  function toggleAllSections(collapse: boolean) {
    if (!draft) return;
    if (!collapse) {
      setCollapsedSections({});
      return;
    }
    const next: Record<string, boolean> = {};
    for (const sec of draft.sections) {
      next[sec.key] = true;
    }
    setCollapsedSections(next);
  }

  function validateAndSave(overrideStatus?: CurriculumStatus) {
    if (!draft) return;
    const status = overrideStatus ?? draft.status;
    if (!draft.name.trim()) {
      setValidationError(t('curriculumWorkspace.validation.nameRequired'));
      setPathInfoOpen(true);
      return;
    }
    if (draft.sections.length === 0) {
      setValidationError(t('curriculumWorkspace.validation.sectionRequired'));
      return;
    }
    for (let sIdx = 0; sIdx < draft.sections.length; sIdx++) {
      const section = draft.sections[sIdx];
      if (!section.title.trim()) {
        setValidationError(t('curriculumWorkspace.validation.sectionTitleRequired'));
        return;
      }
      for (let iIdx = 0; iIdx < section.items.length; iIdx++) {
        const item = section.items[iIdx];
        if (!uuidPattern.test(item.contentReferenceId.trim())) {
          setValidationError(
            t('curriculumWorkspace.validation.invalidUuid', {
              section: sIdx + 1,
              item: iIdx + 1,
            }),
          );
          return;
        }
      }
    }

    setValidationError(null);
    const payload: SaveCurriculumPathInput = {
      ...(draft.id && draft.code.trim() ? { code: draft.code.trim() } : {}),
      name: draft.name.trim(),
      level: draft.level.trim() || null,
      description: draft.description.trim() || null,
      status,
      coverAssetId: draft.coverAssetId || null,
      accessMode: draft.accessMode,
      requiredFeatureKey:
        draft.accessMode === 'FEATURE' ? draft.requiredFeatureKey || 'lesson.plus-lessons' : null,
      sections: draft.sections.map((section, sectionIndex) => ({
        title: section.title.trim(),
        position: sectionIndex + 1,
        items: section.items.map((item, itemIndex) => ({
          position: itemIndex + 1,
          contentType: item.contentType,
          contentReferenceId: item.contentReferenceId.trim(),
          required: item.required,
          unlockRule: item.unlockRule,
          config: item.config ?? {},
        })),
      })),
    };
    saveMutation.mutate({ id: draft.id, payload });
  }

  const allCollapsed =
    Boolean(draft && draft.sections.length > 0) &&
    draft!.sections.every((sec) => Boolean(collapsedSections[sec.key]));

  return (
    <section className="grid w-full gap-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-0.5">
          <h1 className="text-xl font-semibold tracking-tight sm:text-2xl">
            {t('curriculumWorkspace.title')}
          </h1>
          <p className="text-xs text-muted-foreground sm:text-sm">
            {t('curriculumWorkspace.subtitle')}
          </p>
        </div>
        <div className="flex items-center gap-2.5">
          {savedNotice && (
            <span className="text-xs font-medium text-success sm:text-sm" aria-live="polite">
              {t('curriculumWorkspace.saved')}
            </span>
          )}
          <Button type="button" size="sm" onClick={startNewPath}>
            <RiAddLine data-icon="inline-start" aria-hidden="true" />
            {t('curriculumWorkspace.createPath')}
          </Button>
        </div>
      </header>

      <CatalogError
        error={paths.error}
        retry={() => void paths.refetch()}
        retrying={paths.isFetching}
      />
      {paths.isPending && <CatalogSkeleton />}

      {paths.data && (
        <div className="grid items-start gap-5 lg:grid-cols-[19.5rem_minmax(0,1fr)] xl:grid-cols-[21rem_minmax(0,1fr)]">
          <aside
            className="grid gap-2 rounded-2xl border bg-card p-3 shadow-2xs lg:sticky lg:top-4"
            aria-label={t('curriculumWorkspace.pathListLabel')}
          >
            <div className="flex items-center justify-between px-1.5 py-0.5 text-xs font-semibold text-muted-foreground">
              <span>{t('curriculumWorkspace.pathsHeading')}</span>
              <span className="tabular-nums">{paths.data.length}</span>
            </div>
            {paths.data.length === 0 ? (
              <Empty className="border border-dashed py-6">
                <EmptyHeader>
                  <EmptyTitle>{t('curriculumWorkspace.emptyTitle')}</EmptyTitle>
                  <EmptyDescription>{t('curriculumWorkspace.emptyHelp')}</EmptyDescription>
                </EmptyHeader>
              </Empty>
            ) : (
              <div className="grid gap-1.5">
                {paths.data.map((path) => {
                  const selected = selectedId === path.id;
                  return (
                    <button
                      key={path.id}
                      type="button"
                      aria-pressed={selected}
                      onClick={() => selectPath(path.id)}
                      className={cn(
                        'grid grid-cols-[4rem_minmax(0,1fr)] items-center gap-3 rounded-xl border p-2.5 text-left transition-all hover:bg-muted/60 focus-visible:outline-2 focus-visible:outline-ring',
                        selected
                          ? 'border-primary/60 bg-primary/[0.05] shadow-2xs'
                          : 'border-transparent bg-muted/20',
                      )}
                    >
                      <PathCoverThumb coverAssetId={path.coverAssetId} alt={path.name} />
                      <div className="grid min-w-0 gap-1.5">
                        <span className="line-clamp-2 text-sm font-medium leading-snug">
                          {path.name}
                        </span>
                        <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                          <Badge
                            variant={statusBadgeVariant(path.status)}
                            className="h-5 px-2 text-xs"
                          >
                            {t(`curriculumWorkspace.statuses.${path.status}`)}
                          </Badge>
                          {path.level ? (
                            <CefrLevelBadge
                              level={path.level}
                              className="h-5 px-2 text-xs"
                            />
                          ) : null}
                          {path.accessMode === 'FEATURE' ? (
                            <AccessTierBadge accessMode={path.accessMode} size="sm" />
                          ) : null}
                        </div>
                      </div>
                    </button>
                  );
                })}
              </div>
            )}
          </aside>

          <div className="grid min-w-0 gap-4">
            <CatalogError
              error={detail.error}
              retry={() => void detail.refetch()}
              retrying={detail.isFetching}
            />
            {selectedId !== 'new' && detail.isPending && !draft && <CatalogSkeleton />}

            {draft && (
              <Tabs value={activeTab} onValueChange={setActiveTab} className="grid gap-3.5">
                <div className="flex flex-wrap items-center justify-between gap-3.5 rounded-2xl border bg-card px-4 py-3 shadow-2xs">
                  <div className="flex min-w-0 flex-1 items-center gap-3.5">
                    <PathCoverThumb
                      coverAssetId={draft.coverAssetId}
                      alt={draft.name || draft.code || 'Cover'}
                      className="h-14 w-24"
                    />
                    <div className="min-w-0 flex-1 space-y-1.5">
                      <div className="flex flex-wrap items-center gap-2">
                        <h2 className="truncate text-base font-semibold sm:text-lg">
                          {draft.name.trim() || t('curriculumWorkspace.untitledPath')}
                        </h2>
                        <Badge
                          variant={statusBadgeVariant(draft.status)}
                          className="h-5 px-2 text-xs"
                        >
                          {t(`curriculumWorkspace.statuses.${draft.status}`)}
                        </Badge>
                        {draft.level.trim() ? (
                          <CefrLevelBadge level={draft.level.trim()} className="h-5 px-2 text-xs" />
                        ) : null}
                        {draft.accessMode === 'FEATURE' ? (
                          <AccessTierBadge accessMode={draft.accessMode} size="sm" />
                        ) : null}
                        {isDirty ? (
                          <Badge variant="warning" className="h-5 px-2 text-xs">
                            {t('curriculumWorkspace.unsavedChanges')}
                          </Badge>
                        ) : null}
                      </div>
                      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground sm:text-sm">
                        <code className="rounded-md bg-muted/70 px-2 py-0.5 font-mono text-xs text-foreground">
                          {draft.id
                            ? draft.code
                            : previewAutoPathCode(draft.name) ||
                              t('curriculumWorkspace.autoCodeHint')}
                        </code>
                        {draft.description.trim() ? (
                          <span className="line-clamp-1">{draft.description}</span>
                        ) : null}
                      </div>
                    </div>
                  </div>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    onClick={() => setPathInfoOpen(true)}
                  >
                    <RiEditLine data-icon="inline-start" aria-hidden="true" />
                    {t('curriculumWorkspace.editPathInfo')}
                  </Button>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-2.5 rounded-2xl border bg-card px-3.5 py-2.5 shadow-2xs">
                  <div className="flex flex-wrap items-center gap-3">
                    <TabsList>
                      <TabsTrigger value="builder">
                        <RiStackLine className="size-4" aria-hidden="true" />
                        {t('curriculumWorkspace.tabs.builder')}
                      </TabsTrigger>
                      <TabsTrigger value="map">
                        <RiMindMap className="size-4" aria-hidden="true" />
                        {t('curriculumWorkspace.tabs.map')}
                      </TabsTrigger>
                    </TabsList>
                    <span className="hidden text-xs text-muted-foreground tabular-nums xl:inline">
                      {t('curriculumWorkspace.summaryStats', draftStats)}
                    </span>
                  </div>

                  <div className="flex flex-wrap items-center gap-1.5">
                    {draft.id && (
                      <Button
                        type="button"
                        size="sm"
                        variant="destructive"
                        disabled={deleteMutation.isPending || saveMutation.isPending}
                        onClick={() => setDeleteOpen(true)}
                      >
                        <RiDeleteBinLine data-icon="inline-start" aria-hidden="true" />
                        {t('curriculumWorkspace.deletePath')}
                      </Button>
                    )}
                    {draft.status !== 'ARCHIVED' && draft.id && (
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        disabled={saveMutation.isPending}
                        onClick={() => validateAndSave('ARCHIVED')}
                      >
                        {t('curriculumWorkspace.archiveAction')}
                      </Button>
                    )}
                    {draft.status !== 'PUBLISHED' && (
                      <Button
                        type="button"
                        size="sm"
                        variant="secondary"
                        disabled={saveMutation.isPending}
                        onClick={() => validateAndSave('PUBLISHED')}
                      >
                        {t('curriculumWorkspace.publishAction')}
                      </Button>
                    )}
                    <Button
                      type="button"
                      size="sm"
                      disabled={saveMutation.isPending || !isDirty}
                      onClick={() => validateAndSave()}
                    >
                      {t(
                        saveMutation.isPending
                          ? 'curriculumWorkspace.saving'
                          : 'curriculumWorkspace.savePath',
                      )}
                    </Button>
                  </div>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border bg-muted/20 px-4 py-2.5 text-xs">
                  <div className="flex flex-wrap items-center gap-2">
                    {curriculumLegendTypes.map((type) => {
                      const accent = contentAccentVar[type];
                      const count = draftStats.typeCounts[type] ?? 0;
                      return (
                        <span
                          key={type}
                          className="inline-flex items-center gap-2 rounded-full border bg-background px-3 py-1 font-medium text-foreground shadow-2xs"
                          style={{
                            borderColor: `color-mix(in srgb, ${accent} 32%, var(--border))`,
                          }}
                        >
                          <span
                            className="size-2 shrink-0 rounded-full"
                            style={{ backgroundColor: accent }}
                            aria-hidden="true"
                          />
                          <span style={{ color: accent }} className="inline-flex items-center">
                            <ContentTypeIcon type={type} className="size-4" />
                          </span>
                          <span>{t(`curriculumWorkspace.contentTypes.${type}`)}</span>
                          <span className="text-xs font-semibold text-muted-foreground tabular-nums">
                            {count}
                          </span>
                        </span>
                      );
                    })}
                  </div>
                  <span className="text-xs text-muted-foreground tabular-nums xl:hidden">
                    {t('curriculumWorkspace.summaryStats', draftStats)}
                  </span>
                </div>

                {validationError && (
                  <p role="alert" className="text-sm font-medium text-destructive">
                    {validationError}
                  </p>
                )}
                <CatalogError error={saveMutation.error} />

                <TabsContent value="builder" className="mt-0">
                  <form
                    id="curriculum-path-form"
                    noValidate
                    className="grid gap-3.5"
                    onSubmit={(event) => {
                      event.preventDefault();
                      validateAndSave();
                    }}
                  >
                    <div className="grid gap-2.5">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2.5">
                          <h2 className="text-sm font-semibold sm:text-base">
                            {t('curriculumWorkspace.sectionsHeading')}
                          </h2>
                          <span className="text-xs text-muted-foreground tabular-nums">
                            {t('curriculumWorkspace.summaryStats', draftStats)}
                          </span>
                        </div>
                        <div className="flex items-center gap-1.5">
                          {draft.sections.length > 1 && (
                            <Button
                              type="button"
                              size="xs"
                              variant="ghost"
                              onClick={() => toggleAllSections(!allCollapsed)}
                            >
                              {t(
                                allCollapsed
                                  ? 'curriculumWorkspace.expandAll'
                                  : 'curriculumWorkspace.collapseAll',
                              )}
                            </Button>
                          )}
                          <Button
                            type="button"
                            size="xs"
                            variant="outline"
                            onClick={() => {
                              setDraft({
                                ...draft,
                                sections: [...draft.sections, emptySection()],
                              });
                              setSavedNotice(false);
                            }}
                          >
                            <RiAddLine data-icon="inline-start" aria-hidden="true" />
                            {t('curriculumWorkspace.addSection')}
                          </Button>
                        </div>
                      </div>

                      {draft.sections.map((section, sectionIndex) => {
                        const isCollapsed = Boolean(collapsedSections[section.key]);
                        return (
                          <Card key={section.key} size="sm">
                            <div
                              className={cn(
                                'flex flex-wrap items-center justify-between gap-2 px-3 py-2',
                                !isCollapsed && 'border-b',
                              )}
                            >
                              <div className="flex min-w-0 flex-1 items-center gap-2">
                                <Button
                                  type="button"
                                  size="icon-xs"
                                  variant="ghost"
                                  aria-expanded={!isCollapsed}
                                  aria-label={t('curriculumWorkspace.toggleSection', {
                                    number: sectionIndex + 1,
                                  })}
                                  onClick={() =>
                                    setCollapsedSections((prev) => ({
                                      ...prev,
                                      [section.key]: !prev[section.key],
                                    }))
                                  }
                                >
                                  {isCollapsed ? (
                                    <RiArrowRightSLine aria-hidden="true" />
                                  ) : (
                                    <RiArrowDownSLine aria-hidden="true" />
                                  )}
                                </Button>
                                <Badge variant="secondary" className="shrink-0 tabular-nums">
                                  {t('curriculumWorkspace.sectionNumber', {
                                    number: sectionIndex + 1,
                                  })}
                                </Badge>
                                <Input
                                  aria-label={t('curriculumWorkspace.sectionTitleLabel', {
                                    number: sectionIndex + 1,
                                  })}
                                  placeholder={t('curriculumWorkspace.sectionTitlePlaceholder')}
                                  maxLength={140}
                                  className="h-8 flex-1 text-sm font-medium"
                                  value={section.title}
                                  onChange={(event) =>
                                    updateSection(sectionIndex, { title: event.target.value })
                                  }
                                />
                              </div>

                              <div className="flex items-center gap-1">
                                <span className="px-1.5 text-xs text-muted-foreground tabular-nums">
                                  {t('curriculumWorkspace.sectionItemCount', {
                                    count: section.items.length,
                                  })}
                                </span>
                                <Button
                                  type="button"
                                  size="xs"
                                  variant="outline"
                                  onClick={() => {
                                    setCollapsedSections((prev) => ({
                                      ...prev,
                                      [section.key]: false,
                                    }));
                                    updateSection(sectionIndex, {
                                      items: [...section.items, emptyItem()],
                                    });
                                  }}
                                >
                                  <RiAddLine data-icon="inline-start" aria-hidden="true" />
                                  {t('curriculumWorkspace.addItem')}
                                </Button>
                                <Button
                                  type="button"
                                  size="icon-xs"
                                  variant="ghost"
                                  disabled={sectionIndex === 0}
                                  aria-label={t('curriculumWorkspace.moveSectionUp', {
                                    number: sectionIndex + 1,
                                  })}
                                  onClick={() => {
                                    setDraft({
                                      ...draft,
                                      sections: moveElement(draft.sections, sectionIndex, -1),
                                    });
                                    setSavedNotice(false);
                                  }}
                                >
                                  <RiArrowUpLine aria-hidden="true" />
                                </Button>
                                <Button
                                  type="button"
                                  size="icon-xs"
                                  variant="ghost"
                                  disabled={sectionIndex === draft.sections.length - 1}
                                  aria-label={t('curriculumWorkspace.moveSectionDown', {
                                    number: sectionIndex + 1,
                                  })}
                                  onClick={() => {
                                    setDraft({
                                      ...draft,
                                      sections: moveElement(draft.sections, sectionIndex, 1),
                                    });
                                    setSavedNotice(false);
                                  }}
                                >
                                  <RiArrowDownLine aria-hidden="true" />
                                </Button>
                                <Button
                                  type="button"
                                  size="icon-xs"
                                  variant="ghost"
                                  disabled={draft.sections.length <= 1}
                                  aria-label={t('curriculumWorkspace.removeSection', {
                                    number: sectionIndex + 1,
                                  })}
                                  onClick={() => {
                                    setDraft({
                                      ...draft,
                                      sections: draft.sections.filter(
                                        (_, idx) => idx !== sectionIndex,
                                      ),
                                    });
                                    setSavedNotice(false);
                                  }}
                                >
                                  <RiDeleteBinLine aria-hidden="true" />
                                </Button>
                              </div>
                            </div>

                            {!isCollapsed && (
                              <CardContent className="px-3 pt-2 pb-2.5">
                                {section.items.length === 0 ? (
                                  <p className="py-3 text-center text-xs text-muted-foreground">
                                    {t('curriculumWorkspace.addItem')}
                                  </p>
                                ) : (
                                  <div className="divide-y rounded-lg border bg-muted/15">
                                    {section.items.map((item, itemIndex) => {
                                      const sliceLabel = formatCurriculumSliceBadge(item.config, t);
                                      const resolvedTitle = titleLookup[item.contentReferenceId];
                                      const showRawUuid =
                                        !resolvedTitle || Boolean(expandedUuidKeys[item.key]);
                                      const accent = contentAccentVar[item.contentType];

                                      return (
                                        <div
                                          key={item.key}
                                          className="grid gap-2.5 px-3 py-2.5 lg:grid-cols-[2.5rem_10.5rem_minmax(0,1fr)_13rem_auto_auto] lg:items-center"
                                        >
                                          <span className="text-xs font-semibold text-muted-foreground tabular-nums">
                                            {sectionIndex + 1}.{itemIndex + 1}
                                          </span>

                                          <Select
                                            value={item.contentType}
                                            onValueChange={(value) => {
                                              const nextType = value as CurriculumContentType;
                                              const keepRef =
                                                (item.contentType === 'TOEIC_DRILL' ||
                                                  item.contentType === 'TOEIC_TEST') &&
                                                (nextType === 'TOEIC_DRILL' ||
                                                  nextType === 'TOEIC_TEST');
                                              updateItem(sectionIndex, itemIndex, {
                                                contentType: nextType,
                                                contentReferenceId: keepRef
                                                  ? item.contentReferenceId
                                                  : '',
                                                config: {},
                                              });
                                            }}
                                          >
                                            <SelectTrigger
                                              size="sm"
                                              aria-label={t('curriculumWorkspace.contentType')}
                                              className="w-full text-xs sm:text-sm"
                                            >
                                              <span
                                                className="size-2 shrink-0 rounded-full"
                                                style={{ backgroundColor: accent }}
                                                aria-hidden="true"
                                              />
                                              <span
                                                style={{ color: accent }}
                                                className="inline-flex shrink-0 items-center"
                                              >
                                                <ContentTypeIcon
                                                  type={item.contentType}
                                                  config={item.config}
                                                  className="size-4"
                                                />
                                              </span>
                                              <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent>
                                              {curriculumContentTypes.map((type) => (
                                                <SelectItem key={type} value={type}>
                                                  {t(`curriculumWorkspace.contentTypes.${type}`)}
                                                </SelectItem>
                                              ))}
                                            </SelectContent>
                                          </Select>

                                          {!showRawUuid ? (
                                            <div className="flex min-w-0 items-center gap-2">
                                              <Button
                                                type="button"
                                                size="sm"
                                                variant="outline"
                                                className="h-9 min-w-0 flex-1 justify-between gap-2 rounded-xl px-3 text-sm font-normal"
                                                onClick={() =>
                                                  setPickerTarget({
                                                    sectionIndex,
                                                    itemIndex,
                                                    contentType: item.contentType,
                                                    currentId: item.contentReferenceId,
                                                    currentConfig: item.config ?? {},
                                                  })
                                                }
                                              >
                                                <span className="truncate font-medium">
                                                  {resolvedTitle}
                                                </span>
                                                {sliceLabel ? (
                                                  <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-muted px-2.5 py-0.5 text-xs text-muted-foreground tabular-nums">
                                                    <RiScissorsCutLine
                                                      className="size-3"
                                                      aria-hidden="true"
                                                    />
                                                    {sliceLabel}
                                                  </span>
                                                ) : null}
                                              </Button>
                                              <Button
                                                type="button"
                                                size="xs"
                                                variant="ghost"
                                                className="shrink-0 font-mono text-xs text-muted-foreground"
                                                onClick={() =>
                                                  setExpandedUuidKeys((prev) => ({
                                                    ...prev,
                                                    [item.key]: true,
                                                  }))
                                                }
                                              >
                                                {t('curriculumWorkspace.uuidToggle')}
                                              </Button>
                                            </div>
                                          ) : (
                                            <div className="flex min-w-0 items-center gap-2">
                                              <Input
                                                aria-label={t(
                                                  'curriculumWorkspace.contentReferenceId',
                                                )}
                                                placeholder="00000000-0000-0000-0000-000000000000"
                                                className="h-9 min-w-0 flex-1 font-mono text-xs"
                                                value={item.contentReferenceId}
                                                onChange={(event) =>
                                                  updateItem(sectionIndex, itemIndex, {
                                                    contentReferenceId: event.target.value,
                                                  })
                                                }
                                              />
                                              <Button
                                                type="button"
                                                size="sm"
                                                variant="outline"
                                                className="shrink-0 text-xs sm:text-sm"
                                                onClick={() =>
                                                  setPickerTarget({
                                                    sectionIndex,
                                                    itemIndex,
                                                    contentType: item.contentType,
                                                    currentId: item.contentReferenceId,
                                                    currentConfig: item.config ?? {},
                                                  })
                                                }
                                              >
                                                {t('curriculumWorkspace.browseContent')}
                                              </Button>
                                              {resolvedTitle ? (
                                                <Button
                                                  type="button"
                                                  size="xs"
                                                  variant="ghost"
                                                  className="shrink-0 text-xs"
                                                  onClick={() =>
                                                    setExpandedUuidKeys((prev) => ({
                                                      ...prev,
                                                      [item.key]: false,
                                                    }))
                                                  }
                                                >
                                                  OK
                                                </Button>
                                              ) : null}
                                            </div>
                                          )}

                                          <Select
                                            value={item.unlockRule}
                                            onValueChange={(value) =>
                                              updateItem(sectionIndex, itemIndex, {
                                                unlockRule: value as CurriculumUnlockRule,
                                              })
                                            }
                                          >
                                            <SelectTrigger
                                              size="sm"
                                              aria-label={t('curriculumWorkspace.unlockRule')}
                                              className="w-full text-xs"
                                            >
                                              <SelectValue />
                                            </SelectTrigger>
                                            <SelectContent>
                                              {curriculumUnlockRules.map((rule) => (
                                                <SelectItem key={rule} value={rule}>
                                                  {t(`curriculumWorkspace.unlockRules.${rule}`)}
                                                </SelectItem>
                                              ))}
                                            </SelectContent>
                                          </Select>

                                          <label className="inline-flex w-22 shrink-0 items-center gap-1.5 text-xs text-muted-foreground">
                                            <Switch
                                              size="sm"
                                              checked={item.required}
                                              onCheckedChange={(checked) =>
                                                updateItem(sectionIndex, itemIndex, {
                                                  required: checked,
                                                  ...(!checked && item.unlockRule === 'NONE'
                                                    ? {
                                                        unlockRule:
                                                          'PREVIOUS_REQUIRED' as CurriculumUnlockRule,
                                                      }
                                                    : {}),
                                                })
                                              }
                                            />
                                            <span
                                              className={cn(
                                                'truncate',
                                                item.required && 'font-medium text-foreground',
                                              )}
                                            >
                                              {t(
                                                item.required
                                                  ? 'curriculumWorkspace.requiredTag'
                                                  : 'curriculumWorkspace.optionalTag',
                                              )}
                                            </span>
                                          </label>

                                          <div className="flex shrink-0 items-center justify-end gap-0.5">
                                            <Button
                                              type="button"
                                              size="icon-xs"
                                              variant="ghost"
                                              disabled={itemIndex === 0}
                                              aria-label={t('curriculumWorkspace.moveItemUp', {
                                                number: itemIndex + 1,
                                              })}
                                              onClick={() =>
                                                updateSection(sectionIndex, {
                                                  items: moveElement(
                                                    section.items,
                                                    itemIndex,
                                                    -1,
                                                  ),
                                                })
                                              }
                                            >
                                              <RiArrowUpLine aria-hidden="true" />
                                            </Button>
                                            <Button
                                              type="button"
                                              size="icon-xs"
                                              variant="ghost"
                                              disabled={itemIndex === section.items.length - 1}
                                              aria-label={t('curriculumWorkspace.moveItemDown', {
                                                number: itemIndex + 1,
                                              })}
                                              onClick={() =>
                                                updateSection(sectionIndex, {
                                                  items: moveElement(section.items, itemIndex, 1),
                                                })
                                              }
                                            >
                                              <RiArrowDownLine aria-hidden="true" />
                                            </Button>
                                            <Button
                                              type="button"
                                              size="icon-xs"
                                              variant="ghost"
                                              aria-label={t('curriculumWorkspace.removeItem', {
                                                number: itemIndex + 1,
                                              })}
                                              onClick={() =>
                                                updateSection(sectionIndex, {
                                                  items: section.items.filter(
                                                    (_, idx) => idx !== itemIndex,
                                                  ),
                                                })
                                              }
                                            >
                                              <RiDeleteBinLine aria-hidden="true" />
                                            </Button>
                                          </div>
                                        </div>
                                      );
                                    })}
                                  </div>
                                )}
                              </CardContent>
                            )}
                          </Card>
                        );
                      })}
                    </div>
                  </form>
                </TabsContent>

                <TabsContent value="map" className="mt-0">
                  <CurriculumGraphPreview
                    sections={draft.sections.map((section) => ({
                      ...section,
                      items: section.items.map((item, idx) => ({ ...item, position: idx + 1 })),
                    }))}
                    titleLookup={titleLookup}
                  />
                </TabsContent>
              </Tabs>
            )}
          </div>
        </div>
      )}

      {draft ? (
        <Dialog open={pathInfoOpen} onOpenChange={setPathInfoOpen}>
          <DialogContent className="sm:max-w-xl">
            <DialogHeader>
              <DialogTitle>{t('curriculumWorkspace.pathInfoDialogTitle')}</DialogTitle>
              <DialogDescription>{t('curriculumWorkspace.pathInfoDialogHelp')}</DialogDescription>
            </DialogHeader>

            <div className="grid gap-3.5">
              <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_5.5rem_9.5rem]">
                <div className="grid gap-1">
                  <Label htmlFor="curriculum-path-name">
                    {t('curriculumWorkspace.pathName')}
                  </Label>
                  <Input
                    id="curriculum-path-name"
                    required
                    maxLength={140}
                    value={draft.name}
                    onChange={(event) => {
                      setDraft({ ...draft, name: event.target.value });
                      setSavedNotice(false);
                    }}
                  />
                </div>
                <div className="grid gap-1">
                  <Label htmlFor="curriculum-path-level">
                    {t('curriculumWorkspace.pathLevel')}
                  </Label>
                  <Input
                    id="curriculum-path-level"
                    maxLength={24}
                    placeholder="A2"
                    value={draft.level}
                    onChange={(event) => {
                      setDraft({ ...draft, level: event.target.value });
                      setSavedNotice(false);
                    }}
                  />
                </div>
                <div className="grid gap-1">
                  <Label htmlFor="curriculum-path-status">
                    {t('curriculumWorkspace.status')}
                  </Label>
                  <Select
                    value={draft.status}
                    onValueChange={(value) => {
                      setDraft({ ...draft, status: value as CurriculumStatus });
                      setSavedNotice(false);
                    }}
                  >
                    <SelectTrigger id="curriculum-path-status" className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {curriculumStatuses.map((status) => (
                        <SelectItem key={status} value={status}>
                          {t(`curriculumWorkspace.statuses.${status}`)}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <span className="font-medium">{t('curriculumWorkspace.pathCode')}:</span>
                <code className="rounded-md border bg-muted/40 px-2.5 py-0.5 font-mono text-xs text-foreground">
                  {draft.id
                    ? draft.code
                    : previewAutoPathCode(draft.name) || t('curriculumWorkspace.autoCodeHint')}
                </code>
                {!draft.id ? (
                  <Badge variant="outline" className="h-5 px-2 text-xs">
                    {t('curriculumWorkspace.autoCodeBadge')}
                  </Badge>
                ) : null}
              </div>

              <div className="grid gap-1">
                <Label htmlFor="curriculum-path-description">
                  {t('curriculumWorkspace.description')}
                </Label>
                <Textarea
                  id="curriculum-path-description"
                  rows={3}
                  maxLength={500}
                  className="resize-y text-sm"
                  value={draft.description}
                  onChange={(event) => {
                    setDraft({ ...draft, description: event.target.value });
                    setSavedNotice(false);
                  }}
                />
              </div>

              <ImageAssetEditor
                compact
                mode="asset"
                aspect={16 / 9}
                outputWidth={640}
                outputHeight={360}
                value={draft.coverAssetId}
                onChange={(coverAssetId) => {
                  setDraft({ ...draft, coverAssetId });
                  setSavedNotice(false);
                }}
                label={t('curriculumWorkspace.coverImage')}
                alt={draft.name || draft.code}
                urlPlaceholder={t('curriculumWorkspace.coverUrlPlaceholder')}
                urlApplyLabel={t('curriculumWorkspace.coverUrlApply')}
                urlErrorLabel={t('curriculumWorkspace.coverUrlError')}
              />

              <div className="flex items-center rounded-lg border bg-muted/20 px-3 py-2">
                <AccessTierOption
                  value={draft.accessMode}
                  onChange={(next) => {
                    setDraft({
                      ...draft,
                      accessMode: next,
                      requiredFeatureKey: next === 'FEATURE' ? 'lesson.plus-lessons' : null,
                    });
                    setSavedNotice(false);
                  }}
                />
              </div>
            </div>

            <DialogFooter>
              <Button type="button" onClick={() => setPathInfoOpen(false)}>
                {t('curriculumWorkspace.doneEditingInfo')}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}

      <CurriculumSlicePickerDialog
        open={Boolean(pickerTarget)}
        contentType={pickerTarget?.contentType ?? 'LESSON'}
        currentId={pickerTarget?.currentId ?? ''}
        currentConfig={pickerTarget?.currentConfig ?? {}}
        onOpenChange={(open) => {
          if (!open) setPickerTarget(null);
        }}
        onSelect={(selectedContentId, selectedConfig, selectedTitle) => {
          if (selectedTitle) {
            setLocalTitles((prev) => ({ ...prev, [selectedContentId]: selectedTitle }));
          }
          if (pickerTarget) {
            const targetKey =
              draft?.sections[pickerTarget.sectionIndex]?.items[pickerTarget.itemIndex]?.key;
            if (targetKey) {
              setExpandedUuidKeys((prev) => ({ ...prev, [targetKey]: false }));
            }
            updateItem(pickerTarget.sectionIndex, pickerTarget.itemIndex, {
              contentReferenceId: selectedContentId,
              config: selectedConfig,
            });
          }
        }}
      />

      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('curriculumWorkspace.deleteConfirmTitle')}</DialogTitle>
            <DialogDescription>
              {t('curriculumWorkspace.deleteConfirmHelp', {
                name: draft?.name || draft?.code || '',
              })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDeleteOpen(false)}>
              {t('curriculumWorkspace.pickerClose')}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={deleteMutation.isPending || !draft?.id}
              onClick={() => draft?.id && deleteMutation.mutate(draft.id)}
            >
              {t('curriculumWorkspace.confirmDelete')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
