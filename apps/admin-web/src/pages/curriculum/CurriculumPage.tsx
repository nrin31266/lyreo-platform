import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiAddLine,
  RiAlertLine,
  RiArrowDownLine,
  RiArrowUpLine,
  RiCheckDoubleLine,
  RiDeleteBinLine,
  RiImageLine,
  RiMindMap,
  RiScissorsCutLine,
  RiShieldCheckLine,
  RiStackLine,
} from '@remixicon/react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
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
  createAdminCurriculumPath,
  curriculumContentTypes,
  curriculumKey,
  curriculumStatuses,
  curriculumUnlockRules,
  deleteAdminCurriculumPath,
  fetchAdminCurriculumPath,
  fetchAdminCurriculumPaths,
  fetchCurriculumPathDiagnostics,
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

function nextLocalKey(prefix: string) {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}

function emptyItem(): DraftItem {
  return {
    key: nextLocalKey('item'),
    contentType: 'LESSON',
    contentReferenceId: '',
    required: true,
    unlockRule: 'NONE',
    config: {},
  };
}

function emptySection(): DraftSection {
  return {
    key: nextLocalKey('section'),
    title: '',
    items: [emptyItem()],
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

function formatSliceBadge(cfg?: ItemSliceConfig): string | null {
  if (!cfg) return null;
  const tokens: string[] = [];
  if (cfg.activityType) tokens.push(String(cfg.activityType));
  if (Array.isArray(cfg.entryIds) && cfg.entryIds.length > 0) {
    tokens.push(`${cfg.entryIds.length} words`);
  }
  if (Array.isArray(cfg.sentenceIds) && cfg.sentenceIds.length > 0) {
    tokens.push(`${cfg.sentenceIds.length} sentences`);
  }
  if (Array.isArray(cfg.parts) && cfg.parts.length > 0) {
    tokens.push(`Parts ${cfg.parts.join(',')}`);
  }
  if (cfg.questionCount) tokens.push(`${cfg.questionCount}Q`);
  if (cfg.targetTurns) tokens.push(`${cfg.targetTurns} turns`);
  return tokens.length > 0 ? tokens.join(' · ') : null;
}

export function CurriculumPage() {
  const { t } = useTranslation('admin');
  const client = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | 'new' | null>(null);
  const [draft, setDraft] = useState<DraftPath | null>(null);
  const [activeTab, setActiveTab] = useState('builder');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [savedNotice, setSavedNotice] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [expandedUuidKeys, setExpandedUuidKeys] = useState<Record<string, boolean>>({});
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

  const diagnosticsQuery = useQuery({
    queryKey: [...curriculumKey, 'diagnostics', draft?.id],
    enabled: Boolean(draft?.id),
    queryFn: ({ signal }) => fetchCurriculumPathDiagnostics(draft!.id!, signal),
  });

  const titleLookup = useMemo(() => {
    const map: Record<string, string> = {};
    for (const check of diagnosticsQuery.data?.checks ?? []) {
      if (check.title) map[check.contentReferenceId] = check.title;
    }
    return map;
  }, [diagnosticsQuery.data]);

  useEffect(() => {
    if (!paths.data || selectedId !== null) return;
    if (paths.data.length > 0) {
      setSelectedId(paths.data[0].id);
    } else {
      setSelectedId('new');
      setDraft(emptyDraft());
    }
  }, [paths.data, selectedId]);

  useEffect(() => {
    if (selectedId && selectedId !== 'new' && detail.data && detail.data.id === selectedId) {
      setDraft(toDraft(detail.data));
    }
  }, [detail.data, selectedId]);

  const saveMutation = useMutation({
    mutationFn: async (input: { id: string | null; payload: SaveCurriculumPathInput }) =>
      input.id
        ? updateAdminCurriculumPath(input.id, input.payload)
        : createAdminCurriculumPath(input.payload),
    onSuccess: (saved) => {
      setSelectedId(saved.id);
      client.setQueryData([...curriculumKey, 'detail', saved.id], saved);
      setDraft(toDraft(saved));
      setValidationError(null);
      setSavedNotice(true);
      void client.invalidateQueries({ queryKey: curriculumKey });
      void client.invalidateQueries({ queryKey: [...curriculumKey, 'diagnostics', saved.id] });
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (pathId: string) => deleteAdminCurriculumPath(pathId),
    onSuccess: async () => {
      setDeleteOpen(false);
      setSelectedId(null);
      setDraft(null);
      setSavedNotice(false);
      await client.invalidateQueries({ queryKey: curriculumKey });
    },
  });

  function selectPath(pathId: string) {
    setSelectedId(pathId);
    setDraft(null);
    setValidationError(null);
    setSavedNotice(false);
    saveMutation.reset();
  }

  function startNewPath() {
    setSelectedId('new');
    setDraft(emptyDraft());
    setActiveTab('builder');
    setValidationError(null);
    setSavedNotice(false);
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

  function validateAndSave(overrideStatus?: CurriculumStatus) {
    if (!draft) return;
    const status = overrideStatus ?? draft.status;
    if (!draft.code.trim()) {
      setValidationError(t('curriculumWorkspace.validation.codeRequired'));
      return;
    }
    if (!draft.name.trim()) {
      setValidationError(t('curriculumWorkspace.validation.nameRequired'));
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
      code: draft.code.trim(),
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

  return (
    <section className="grid w-full gap-5">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">{t('curriculumWorkspace.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('curriculumWorkspace.subtitle')}</p>
        </div>
        <div className="flex items-center gap-3">
          {savedNotice && (
            <span className="text-sm font-medium text-success" aria-live="polite">
              {t('curriculumWorkspace.saved')}
            </span>
          )}
          <Button type="button" onClick={startNewPath}>
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
        <div className="grid items-start gap-5 lg:grid-cols-[20rem_minmax(0,1fr)] xl:grid-cols-[22rem_minmax(0,1fr)]">
          <aside
            className="grid gap-2 rounded-2xl border bg-card p-3"
            aria-label={t('curriculumWorkspace.pathListLabel')}
          >
            <div className="flex items-center justify-between px-2 py-1 text-xs font-medium text-muted-foreground">
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
                        'grid grid-cols-[2.75rem_minmax(0,1fr)] items-start gap-2.5 rounded-xl border p-2.5 text-left transition-colors hover:bg-muted/60 focus-visible:outline-2 focus-visible:outline-ring',
                        selected ? 'border-primary bg-muted/70' : 'border-transparent bg-muted/25',
                      )}
                    >
                      <AuthenticatedImage
                        src={
                          path.coverAssetId
                            ? `/api/v1/admin/content-media/${encodeURIComponent(path.coverAssetId)}/file`
                            : null
                        }
                        alt={path.name}
                        className="size-11 shrink-0 rounded-lg border object-cover"
                        fallback={
                          <div className="flex size-11 shrink-0 items-center justify-center rounded-lg border bg-muted text-muted-foreground">
                            <RiImageLine className="size-4" aria-hidden="true" />
                          </div>
                        }
                      />
                      <div className="min-w-0 grid gap-1">
                        <div className="flex items-start justify-between gap-1.5">
                          <span className="line-clamp-2 text-sm font-medium leading-snug">
                            {path.name}
                          </span>
                          <Badge variant={statusBadgeVariant(path.status)} className="shrink-0">
                            {t(`curriculumWorkspace.statuses.${path.status}`)}
                          </Badge>
                        </div>
                        <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                          <span className="truncate font-mono">{path.code}</span>
                          {path.level ? <CefrLevelBadge level={path.level} className="h-5 px-1.5 text-[10px]" /> : null}
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
              <Tabs value={activeTab} onValueChange={setActiveTab}>
                <TabsList>
                  <TabsTrigger value="builder">
                    <RiStackLine className="size-4" aria-hidden="true" />
                    {t('curriculumWorkspace.tabs.builder')}
                  </TabsTrigger>
                  <TabsTrigger value="map">
                    <RiMindMap className="size-4" aria-hidden="true" />
                    {t('curriculumWorkspace.tabs.map')}
                  </TabsTrigger>
                  <TabsTrigger value="diagnostics" disabled={!draft.id}>
                    <RiShieldCheckLine className="size-4" aria-hidden="true" />
                    {t('curriculumWorkspace.tabs.diagnostics')}
                  </TabsTrigger>
                </TabsList>

                <TabsContent value="builder" className="mt-4">
                  <form
                    noValidate
                    className="grid gap-4"
                    onSubmit={(event) => {
                      event.preventDefault();
                      validateAndSave();
                    }}
                  >
                    <Card size="sm">
                      <CardHeader className="border-b">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="flex items-center gap-2">
                            <RiStackLine
                              className="size-4 text-muted-foreground"
                              aria-hidden="true"
                            />
                            <CardTitle>
                              {draft.id
                                ? t('curriculumWorkspace.editPathTitle')
                                : t('curriculumWorkspace.newPathTitle')}
                            </CardTitle>
                          </div>
                          <div className="flex items-center gap-2">
                            <AccessTierBadge accessMode={draft.accessMode} />
                            <Badge variant={statusBadgeVariant(draft.status)}>
                              {t(`curriculumWorkspace.statuses.${draft.status}`)}
                            </Badge>
                          </div>
                        </div>
                        <CardDescription>{t('curriculumWorkspace.editorHelp')}</CardDescription>
                      </CardHeader>
                      <CardContent className="grid gap-4 pt-4">
                        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_7rem_10rem]">
                          <div className="grid gap-1.5">
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
                          <div className="grid gap-1.5">
                            <Label htmlFor="curriculum-path-code">
                              {t('curriculumWorkspace.pathCode')}
                            </Label>
                            <Input
                              id="curriculum-path-code"
                              required
                              maxLength={80}
                              className="font-mono text-xs"
                              placeholder="FOUNDATION_PATH"
                              value={draft.code}
                              onChange={(event) => {
                                setDraft({ ...draft, code: event.target.value });
                                setSavedNotice(false);
                              }}
                            />
                          </div>
                          <div className="grid gap-1.5">
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
                          <div className="grid gap-1.5">
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
                              <SelectTrigger id="curriculum-path-status">
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

                        <div className="grid gap-4 sm:grid-cols-2">
                          <ImageAssetEditor
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
                            alt={draft.name}
                          />

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

                        <div className="grid gap-1.5">
                          <Label htmlFor="curriculum-path-description">
                            {t('curriculumWorkspace.description')}
                          </Label>
                          <Textarea
                            id="curriculum-path-description"
                            rows={2}
                            maxLength={500}
                            value={draft.description}
                            onChange={(event) => {
                              setDraft({ ...draft, description: event.target.value });
                              setSavedNotice(false);
                            }}
                          />
                        </div>
                      </CardContent>
                    </Card>

                    <div className="grid gap-3">
                      <div className="flex items-center justify-between gap-2">
                        <h2 className="text-base font-semibold">
                          {t('curriculumWorkspace.sectionsHeading')}
                        </h2>
                        <Button
                          type="button"
                          size="sm"
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

                      {draft.sections.map((section, sectionIndex) => (
                        <Card key={section.key} size="sm">
                          <CardHeader className="border-b">
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              <div className="flex min-w-0 flex-1 items-center gap-2">
                                <span className="text-xs font-semibold text-muted-foreground tabular-nums">
                                  {t('curriculumWorkspace.sectionNumber', {
                                    number: sectionIndex + 1,
                                  })}
                                </span>
                                <Input
                                  aria-label={t('curriculumWorkspace.sectionTitleLabel', {
                                    number: sectionIndex + 1,
                                  })}
                                  placeholder={t('curriculumWorkspace.sectionTitlePlaceholder')}
                                  maxLength={140}
                                  value={section.title}
                                  onChange={(event) =>
                                    updateSection(sectionIndex, { title: event.target.value })
                                  }
                                />
                              </div>
                              <div className="flex items-center gap-1">
                                <Button
                                  type="button"
                                  size="icon-sm"
                                  variant="ghost"
                                  disabled={sectionIndex === 0}
                                  aria-label={t('curriculumWorkspace.moveSectionUp', {
                                    number: sectionIndex + 1,
                                  })}
                                  onClick={() =>
                                    setDraft({
                                      ...draft,
                                      sections: moveElement(draft.sections, sectionIndex, -1),
                                    })
                                  }
                                >
                                  <RiArrowUpLine aria-hidden="true" />
                                </Button>
                                <Button
                                  type="button"
                                  size="icon-sm"
                                  variant="ghost"
                                  disabled={sectionIndex === draft.sections.length - 1}
                                  aria-label={t('curriculumWorkspace.moveSectionDown', {
                                    number: sectionIndex + 1,
                                  })}
                                  onClick={() =>
                                    setDraft({
                                      ...draft,
                                      sections: moveElement(draft.sections, sectionIndex, 1),
                                    })
                                  }
                                >
                                  <RiArrowDownLine aria-hidden="true" />
                                </Button>
                                <Button
                                  type="button"
                                  size="icon-sm"
                                  variant="ghost"
                                  disabled={draft.sections.length <= 1}
                                  aria-label={t('curriculumWorkspace.removeSection', {
                                    number: sectionIndex + 1,
                                  })}
                                  onClick={() =>
                                    setDraft({
                                      ...draft,
                                      sections: draft.sections.filter(
                                        (_, idx) => idx !== sectionIndex,
                                      ),
                                    })
                                  }
                                >
                                  <RiDeleteBinLine aria-hidden="true" />
                                </Button>
                              </div>
                            </div>
                          </CardHeader>
                          <CardContent className="grid gap-3 pt-3">
                            {section.items.map((item, itemIndex) => {
                              const sliceLabel = formatSliceBadge(item.config);
                              const resolvedTitle = titleLookup[item.contentReferenceId];
                              return (
                                <div
                                  key={item.key}
                                  className="grid gap-3 rounded-xl border bg-muted/25 p-3"
                                >
                                  <div className="flex flex-wrap items-center justify-between gap-2">
                                    <div className="flex flex-wrap items-center gap-2">
                                      <span className="text-xs font-medium text-muted-foreground tabular-nums">
                                        {t('curriculumWorkspace.itemNumber', {
                                          section: sectionIndex + 1,
                                          item: itemIndex + 1,
                                        })}
                                      </span>
                                      {resolvedTitle ? (
                                        <span className="text-xs font-medium">{resolvedTitle}</span>
                                      ) : null}
                                      {sliceLabel ? (
                                        <Badge variant="secondary" className="gap-1 text-[11px]">
                                          <RiScissorsCutLine className="size-3" aria-hidden="true" />
                                          {sliceLabel}
                                        </Badge>
                                      ) : null}
                                    </div>
                                    <div className="flex items-center gap-1">
                                      <Button
                                        type="button"
                                        size="icon-sm"
                                        variant="ghost"
                                        disabled={itemIndex === 0}
                                        aria-label={t('curriculumWorkspace.moveItemUp', {
                                          number: itemIndex + 1,
                                        })}
                                        onClick={() =>
                                          updateSection(sectionIndex, {
                                            items: moveElement(section.items, itemIndex, -1),
                                          })
                                        }
                                      >
                                        <RiArrowUpLine aria-hidden="true" />
                                      </Button>
                                      <Button
                                        type="button"
                                        size="icon-sm"
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
                                        size="icon-sm"
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

                                  <div className="grid gap-3 sm:grid-cols-3">
                                    <div className="grid gap-1">
                                      <Label htmlFor={`item-type-${item.key}`}>
                                        {t('curriculumWorkspace.contentType')}
                                      </Label>
                                      <Select
                                        value={item.contentType}
                                        onValueChange={(value) =>
                                          updateItem(sectionIndex, itemIndex, {
                                            contentType: value as CurriculumContentType,
                                            config: {},
                                          })
                                        }
                                      >
                                        <SelectTrigger id={`item-type-${item.key}`}>
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
                                    </div>
                                    <div className="grid gap-1">
                                      <Label htmlFor={`item-ref-${item.key}`}>
                                        {t('curriculumWorkspace.contentReferenceId')}
                                      </Label>
                                      {resolvedTitle && !expandedUuidKeys[item.key] ? (
                                        <div className="flex items-center gap-2">
                                          <Button
                                            type="button"
                                            size="sm"
                                            variant="outline"
                                            className="flex-1 justify-start truncate font-normal"
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
                                            <span className="truncate">{resolvedTitle}</span>
                                          </Button>
                                          <Button
                                            type="button"
                                            size="sm"
                                            variant="ghost"
                                            className="shrink-0 font-mono text-[11px] text-muted-foreground"
                                            onClick={() =>
                                              setExpandedUuidKeys((prev) => ({
                                                ...prev,
                                                [item.key]: true,
                                              }))
                                            }
                                          >
                                            UUID
                                          </Button>
                                        </div>
                                      ) : (
                                        <div className="flex items-center gap-2">
                                          <Input
                                            id={`item-ref-${item.key}`}
                                            placeholder="00000000-0000-0000-0000-000000000000"
                                            className="font-mono text-xs"
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
                                        </div>
                                      )}
                                    </div>
                                    <div className="grid gap-1">
                                      <Label htmlFor={`item-unlock-${item.key}`}>
                                        {t('curriculumWorkspace.unlockRule')}
                                      </Label>
                                      <Select
                                        value={item.unlockRule}
                                        onValueChange={(value) =>
                                          updateItem(sectionIndex, itemIndex, {
                                            unlockRule: value as CurriculumUnlockRule,
                                          })
                                        }
                                      >
                                        <SelectTrigger id={`item-unlock-${item.key}`}>
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
                                    </div>
                                  </div>

                                  <label className="inline-flex items-center gap-2 text-xs font-medium">
                                    <Switch
                                      size="sm"
                                      checked={item.required}
                                      onCheckedChange={(checked) =>
                                        updateItem(sectionIndex, itemIndex, { required: checked })
                                      }
                                    />
                                    {t('curriculumWorkspace.requiredItem')}
                                  </label>
                                </div>
                              );
                            })}

                            <Button
                              type="button"
                              size="sm"
                              variant="outline"
                              className="justify-self-start"
                              onClick={() =>
                                updateSection(sectionIndex, {
                                  items: [...section.items, emptyItem()],
                                })
                              }
                            >
                              <RiAddLine data-icon="inline-start" aria-hidden="true" />
                              {t('curriculumWorkspace.addItem')}
                            </Button>
                          </CardContent>
                        </Card>
                      ))}
                    </div>

                    {validationError && (
                      <p role="alert" className="text-sm font-medium text-destructive">
                        {validationError}
                      </p>
                    )}
                    <CatalogError error={saveMutation.error} />

                    <div className="flex flex-wrap items-center justify-end gap-2">
                      {draft.id && (
                        <Button
                          type="button"
                          variant="destructive"
                          disabled={deleteMutation.isPending || saveMutation.isPending}
                          onClick={() => setDeleteOpen(true)}
                        >
                          <RiDeleteBinLine data-icon="inline-start" aria-hidden="true" />
                          {t('curriculumWorkspace.deletePath')}
                        </Button>
                      )}
                      {draft.status !== 'ARCHIVED' && (
                        <Button
                          type="button"
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
                          variant="secondary"
                          disabled={saveMutation.isPending}
                          onClick={() => validateAndSave('PUBLISHED')}
                        >
                          {t('curriculumWorkspace.publishAction')}
                        </Button>
                      )}
                      <Button type="submit" disabled={saveMutation.isPending}>
                        {t(
                          saveMutation.isPending
                            ? 'curriculumWorkspace.saving'
                            : 'curriculumWorkspace.savePath',
                        )}
                      </Button>
                    </div>
                  </form>
                </TabsContent>

                <TabsContent value="map" className="mt-4">
                  <Card>
                    <CardHeader>
                      <CardTitle>{t('curriculumWorkspace.tabs.map')}</CardTitle>
                      <CardDescription>{t('curriculumWorkspace.graph.simulatorHelp')}</CardDescription>
                    </CardHeader>
                    <CardContent>
                      <CurriculumGraphPreview
                        sections={draft.sections.map((section) => ({
                          ...section,
                          items: section.items.map((item, idx) => ({ ...item, position: idx + 1 })),
                        }))}
                        pathAccessMode={draft.accessMode}
                        titleLookup={titleLookup}
                      />
                    </CardContent>
                  </Card>
                </TabsContent>

                <TabsContent value="diagnostics" className="mt-4 space-y-4">
                  <CatalogError
                    error={diagnosticsQuery.error}
                    retry={() => void diagnosticsQuery.refetch()}
                  />
                  {diagnosticsQuery.isPending ? (
                    <CatalogSkeleton />
                  ) : diagnosticsQuery.data ? (
                    <>
                      <div className="grid gap-3 sm:grid-cols-4">
                        <Card size="sm">
                          <CardHeader className="pb-1">
                            <CardDescription>
                              {t('curriculumWorkspace.diagnostics.total')}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="text-2xl font-semibold tabular-nums">
                            {diagnosticsQuery.data.totalItems}
                          </CardContent>
                        </Card>
                        <Card size="sm">
                          <CardHeader className="pb-1">
                            <CardDescription>
                              {t('curriculumWorkspace.diagnostics.valid')}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="text-2xl font-semibold text-success tabular-nums">
                            {diagnosticsQuery.data.validItems}
                          </CardContent>
                        </Card>
                        <Card size="sm">
                          <CardHeader className="pb-1">
                            <CardDescription>
                              {t('curriculumWorkspace.diagnostics.missing')}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="text-2xl font-semibold text-destructive tabular-nums">
                            {diagnosticsQuery.data.missingItems}
                          </CardContent>
                        </Card>
                        <Card size="sm">
                          <CardHeader className="pb-1">
                            <CardDescription>
                              {t('curriculumWorkspace.diagnostics.plusLocked')}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="text-2xl font-semibold text-warning tabular-nums">
                            {diagnosticsQuery.data.lockedForFreeUsers}
                          </CardContent>
                        </Card>
                      </div>

                      <Card>
                        <CardHeader>
                          <CardTitle>{t('curriculumWorkspace.diagnostics.tableTitle')}</CardTitle>
                          <CardDescription>
                            {t('curriculumWorkspace.diagnostics.tableHelp')}
                          </CardDescription>
                        </CardHeader>
                        <CardContent>
                          <Table>
                            <TableHeader>
                              <TableRow>
                                <TableHead>#</TableHead>
                                <TableHead>{t('curriculumWorkspace.contentType')}</TableHead>
                                <TableHead>{t('curriculumWorkspace.diagnostics.content')}</TableHead>
                                <TableHead>{t('curriculumWorkspace.diagnostics.slice')}</TableHead>
                                <TableHead>{t('curriculumWorkspace.status')}</TableHead>
                              </TableRow>
                            </TableHeader>
                            <TableBody>
                              {diagnosticsQuery.data.checks.map((chk) => (
                                <TableRow key={chk.itemId}>
                                  <TableCell className="tabular-nums text-xs text-muted-foreground">
                                    {chk.sectionPosition}.{chk.itemPosition}
                                  </TableCell>
                                  <TableCell>
                                    <Badge variant="outline">
                                      {t(`curriculumWorkspace.contentTypes.${chk.contentType}`)}
                                    </Badge>
                                  </TableCell>
                                  <TableCell>
                                    <div className="space-y-0.5">
                                      <p className="text-sm font-medium">
                                        {chk.title || chk.contentReferenceId}
                                      </p>
                                      <p className="text-xs text-muted-foreground">{chk.message}</p>
                                    </div>
                                  </TableCell>
                                  <TableCell className="tabular-nums text-xs">
                                    {chk.sliceCount > 0
                                      ? chk.sliceCount
                                      : t('curriculumWorkspace.diagnostics.allSlice')}
                                  </TableCell>
                                  <TableCell>
                                    {chk.exists && chk.sliceValid ? (
                                      <Badge variant="success">
                                        <RiCheckDoubleLine className="size-3" aria-hidden="true" />
                                        {t('curriculumWorkspace.diagnostics.ok')}
                                      </Badge>
                                    ) : (
                                      <Badge variant="destructive">
                                        <RiAlertLine className="size-3" aria-hidden="true" />
                                        {t(
                                          chk.exists
                                            ? 'curriculumWorkspace.diagnostics.sliceIssue'
                                            : 'curriculumWorkspace.diagnostics.missingRef',
                                        )}
                                      </Badge>
                                    )}
                                  </TableCell>
                                </TableRow>
                              ))}
                            </TableBody>
                          </Table>
                        </CardContent>
                      </Card>
                    </>
                  ) : null}
                </TabsContent>
              </Tabs>
            )}
          </div>
        </div>
      )}

      <CurriculumSlicePickerDialog
        open={Boolean(pickerTarget)}
        contentType={pickerTarget?.contentType ?? 'LESSON'}
        currentId={pickerTarget?.currentId ?? ''}
        currentConfig={pickerTarget?.currentConfig ?? {}}
        onOpenChange={(open) => {
          if (!open) setPickerTarget(null);
        }}
        onSelect={(selectedContentId, selectedConfig) => {
          if (pickerTarget) {
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
