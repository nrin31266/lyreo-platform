import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiAddLine,
  RiArrowDownLine,
  RiArrowUpLine,
  RiDeleteBinLine,
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
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { CatalogError, CatalogSkeleton } from '@/pages/lessons/CatalogFeedback';
import { CurriculumContentPickerDialog } from './CurriculumContentPickerDialog';
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
  type CurriculumContentType,
  type CurriculumStatus,
  type CurriculumUnlockRule,
  type SaveCurriculumPathInput,
} from './curriculum.api';

type DraftItem = {
  key: string;
  contentType: CurriculumContentType;
  contentReferenceId: string;
  required: boolean;
  unlockRule: CurriculumUnlockRule;
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
    sections: path.sections
      .slice()
      .sort((a, b) => a.position - b.position)
      .map(section => ({
        key: section.id,
        title: section.title,
        items: section.items
          .slice()
          .sort((a, b) => a.position - b.position)
          .map(item => ({
            key: item.id,
            contentType: item.contentType,
            contentReferenceId: item.contentReferenceId,
            required: item.required,
            unlockRule: (item.unlockRule as CurriculumUnlockRule) || 'NONE',
          })),
      })),
  };
}

function statusVariant(status: CurriculumStatus): 'secondary' | 'success' | 'outline' {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'ARCHIVED') return 'outline';
  return 'secondary';
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
  const [validationError, setValidationError] = useState<string | null>(null);
  const [savedNotice, setSavedNotice] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [pickerTarget, setPickerTarget] = useState<{
    sectionIndex: number;
    itemIndex: number;
    contentType: CurriculumContentType;
    currentId: string;
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
      input.id ? updateAdminCurriculumPath(input.id, input.payload) : createAdminCurriculumPath(input.payload),
    onSuccess: saved => {
      setSelectedId(saved.id);
      client.setQueryData([...curriculumKey, 'detail', saved.id], saved);
      setDraft(toDraft(saved));
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
    setValidationError(null);
    setSavedNotice(false);
    saveMutation.reset();
  }

  function updateSection(sectionIndex: number, patch: Partial<DraftSection>) {
    setDraft(current => {
      if (!current) return current;
      return {
        ...current,
        sections: current.sections.map((section, idx) => (idx === sectionIndex ? { ...section, ...patch } : section)),
      };
    });
    setSavedNotice(false);
  }

  function updateItem(sectionIndex: number, itemIndex: number, patch: Partial<DraftItem>) {
    setDraft(current => {
      if (!current) return current;
      return {
        ...current,
        sections: current.sections.map((section, idx) =>
          idx !== sectionIndex
            ? section
            : {
                ...section,
                items: section.items.map((item, j) => (j === itemIndex ? { ...item, ...patch } : item)),
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
      sections: draft.sections.map((section, sectionIndex) => ({
        title: section.title.trim(),
        position: sectionIndex + 1,
        items: section.items.map((item, itemIndex) => ({
          position: itemIndex + 1,
          contentType: item.contentType,
          contentReferenceId: item.contentReferenceId.trim(),
          required: item.required,
          unlockRule: item.unlockRule,
        })),
      })),
    };
    saveMutation.mutate({ id: draft.id, payload });
  }

  return (
    <section className="mx-auto grid w-full max-w-7xl gap-5">
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

      <CatalogError error={paths.error} retry={() => void paths.refetch()} retrying={paths.isFetching} />
      {paths.isPending && <CatalogSkeleton />}

      {paths.data && (
        <div className="grid items-start gap-5 lg:grid-cols-[19rem_minmax(0,1fr)]">
          <aside className="grid gap-2 rounded-2xl border bg-card p-3" aria-label={t('curriculumWorkspace.pathListLabel')}>
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
                {paths.data.map(path => {
                  const selected = selectedId === path.id;
                  return (
                    <button
                      key={path.id}
                      type="button"
                      aria-pressed={selected}
                      onClick={() => selectPath(path.id)}
                      className={cn(
                        'grid gap-1.5 rounded-xl border p-3 text-left transition-colors hover:bg-muted/60 focus-visible:outline-2 focus-visible:outline-ring',
                        selected ? 'border-primary bg-muted/70' : 'border-transparent bg-muted/25',
                      )}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <span className="font-medium leading-snug">{path.name}</span>
                        <Badge variant={statusVariant(path.status)}>
                          {t(`curriculumWorkspace.statuses.${path.status}`)}
                        </Badge>
                      </div>
                      <span className="font-mono text-xs text-muted-foreground">
                        {path.code}
                        {path.level ? ` · ${path.level}` : ''}
                      </span>
                    </button>
                  );
                })}
              </div>
            )}
          </aside>

          <div className="grid min-w-0 gap-4">
            <CatalogError error={detail.error} retry={() => void detail.refetch()} retrying={detail.isFetching} />
            {selectedId !== 'new' && detail.isPending && !draft && <CatalogSkeleton />}

            {draft && (
              <form
                noValidate
                className="grid gap-4"
                onSubmit={event => {
                  event.preventDefault();
                  validateAndSave();
                }}
              >
                <Card size="sm">
                  <CardHeader className="border-b">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <RiStackLine className="size-4 text-muted-foreground" aria-hidden="true" />
                        <CardTitle>
                          {draft.id ? t('curriculumWorkspace.editPathTitle') : t('curriculumWorkspace.newPathTitle')}
                        </CardTitle>
                      </div>
                      <Badge variant={statusVariant(draft.status)}>
                        {t(`curriculumWorkspace.statuses.${draft.status}`)}
                      </Badge>
                    </div>
                    <CardDescription>{t('curriculumWorkspace.editorHelp')}</CardDescription>
                  </CardHeader>
                  <CardContent className="grid gap-4">
                    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_8rem_11rem]">
                      <div className="grid gap-1.5">
                        <Label htmlFor="curriculum-path-name">{t('curriculumWorkspace.pathName')}</Label>
                        <Input
                          id="curriculum-path-name"
                          required
                          maxLength={140}
                          value={draft.name}
                          onChange={event => {
                            setDraft({ ...draft, name: event.target.value });
                            setSavedNotice(false);
                          }}
                        />
                      </div>
                      <div className="grid gap-1.5">
                        <Label htmlFor="curriculum-path-code">{t('curriculumWorkspace.pathCode')}</Label>
                        <Input
                          id="curriculum-path-code"
                          required
                          maxLength={80}
                          className="font-mono text-xs"
                          placeholder="FOUNDATION_PATH"
                          value={draft.code}
                          onChange={event => {
                            setDraft({ ...draft, code: event.target.value });
                            setSavedNotice(false);
                          }}
                        />
                      </div>
                      <div className="grid gap-1.5">
                        <Label htmlFor="curriculum-path-level">{t('curriculumWorkspace.pathLevel')}</Label>
                        <Input
                          id="curriculum-path-level"
                          maxLength={24}
                          placeholder="A2"
                          value={draft.level}
                          onChange={event => {
                            setDraft({ ...draft, level: event.target.value });
                            setSavedNotice(false);
                          }}
                        />
                      </div>
                      <div className="grid gap-1.5">
                        <Label htmlFor="curriculum-path-status">{t('curriculumWorkspace.status')}</Label>
                        <Select
                          value={draft.status}
                          onValueChange={value => {
                            setDraft({ ...draft, status: value as CurriculumStatus });
                            setSavedNotice(false);
                          }}
                        >
                          <SelectTrigger id="curriculum-path-status">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {curriculumStatuses.map(status => (
                              <SelectItem key={status} value={status}>
                                {t(`curriculumWorkspace.statuses.${status}`)}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                    </div>
                    <div className="grid gap-1.5">
                      <Label htmlFor="curriculum-path-description">{t('curriculumWorkspace.description')}</Label>
                      <Textarea
                        id="curriculum-path-description"
                        rows={2}
                        maxLength={500}
                        value={draft.description}
                        onChange={event => {
                          setDraft({ ...draft, description: event.target.value });
                          setSavedNotice(false);
                        }}
                      />
                    </div>
                  </CardContent>
                </Card>

                <div className="grid gap-3">
                  <div className="flex items-center justify-between gap-2">
                    <h2 className="text-base font-semibold">{t('curriculumWorkspace.sectionsHeading')}</h2>
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => {
                        setDraft({ ...draft, sections: [...draft.sections, emptySection()] });
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
                              {t('curriculumWorkspace.sectionNumber', { number: sectionIndex + 1 })}
                            </span>
                            <Input
                              aria-label={t('curriculumWorkspace.sectionTitleLabel', { number: sectionIndex + 1 })}
                              placeholder={t('curriculumWorkspace.sectionTitlePlaceholder')}
                              maxLength={140}
                              value={section.title}
                              onChange={event => updateSection(sectionIndex, { title: event.target.value })}
                            />
                          </div>
                          <div className="flex items-center gap-1">
                            <Button
                              type="button"
                              size="icon-sm"
                              variant="ghost"
                              disabled={sectionIndex === 0}
                              aria-label={t('curriculumWorkspace.moveSectionUp', { number: sectionIndex + 1 })}
                              onClick={() =>
                                setDraft({ ...draft, sections: moveElement(draft.sections, sectionIndex, -1) })
                              }
                            >
                              <RiArrowUpLine aria-hidden="true" />
                            </Button>
                            <Button
                              type="button"
                              size="icon-sm"
                              variant="ghost"
                              disabled={sectionIndex === draft.sections.length - 1}
                              aria-label={t('curriculumWorkspace.moveSectionDown', { number: sectionIndex + 1 })}
                              onClick={() =>
                                setDraft({ ...draft, sections: moveElement(draft.sections, sectionIndex, 1) })
                              }
                            >
                              <RiArrowDownLine aria-hidden="true" />
                            </Button>
                            <Button
                              type="button"
                              size="icon-sm"
                              variant="ghost"
                              disabled={draft.sections.length <= 1}
                              aria-label={t('curriculumWorkspace.removeSection', { number: sectionIndex + 1 })}
                              onClick={() =>
                                setDraft({
                                  ...draft,
                                  sections: draft.sections.filter((_, idx) => idx !== sectionIndex),
                                })
                              }
                            >
                              <RiDeleteBinLine aria-hidden="true" />
                            </Button>
                          </div>
                        </div>
                      </CardHeader>
                      <CardContent className="grid gap-3">
                        {section.items.map((item, itemIndex) => (
                          <div key={item.key} className="grid gap-3 rounded-xl border bg-muted/25 p-3">
                            <div className="flex flex-wrap items-center justify-between gap-2">
                              <span className="text-xs font-medium text-muted-foreground tabular-nums">
                                {t('curriculumWorkspace.itemNumber', {
                                  section: sectionIndex + 1,
                                  item: itemIndex + 1,
                                })}
                              </span>
                              <div className="flex items-center gap-1">
                                <Button
                                  type="button"
                                  size="icon-sm"
                                  variant="ghost"
                                  disabled={itemIndex === 0}
                                  aria-label={t('curriculumWorkspace.moveItemUp', { number: itemIndex + 1 })}
                                  onClick={() =>
                                    updateSection(sectionIndex, { items: moveElement(section.items, itemIndex, -1) })
                                  }
                                >
                                  <RiArrowUpLine aria-hidden="true" />
                                </Button>
                                <Button
                                  type="button"
                                  size="icon-sm"
                                  variant="ghost"
                                  disabled={itemIndex === section.items.length - 1}
                                  aria-label={t('curriculumWorkspace.moveItemDown', { number: itemIndex + 1 })}
                                  onClick={() =>
                                    updateSection(sectionIndex, { items: moveElement(section.items, itemIndex, 1) })
                                  }
                                >
                                  <RiArrowDownLine aria-hidden="true" />
                                </Button>
                                <Button
                                  type="button"
                                  size="icon-sm"
                                  variant="ghost"
                                  aria-label={t('curriculumWorkspace.removeItem', { number: itemIndex + 1 })}
                                  onClick={() =>
                                    updateSection(sectionIndex, {
                                      items: section.items.filter((_, idx) => idx !== itemIndex),
                                    })
                                  }
                                >
                                  <RiDeleteBinLine aria-hidden="true" />
                                </Button>
                              </div>
                            </div>

                            <div className="grid gap-3 sm:grid-cols-3">
                              <div className="grid gap-1">
                                <Label htmlFor={`item-type-${item.key}`}>{t('curriculumWorkspace.contentType')}</Label>
                                <Select
                                  value={item.contentType}
                                  onValueChange={value =>
                                    updateItem(sectionIndex, itemIndex, {
                                      contentType: value as CurriculumContentType,
                                    })
                                  }
                                >
                                  <SelectTrigger id={`item-type-${item.key}`}>
                                    <SelectValue />
                                  </SelectTrigger>
                                  <SelectContent>
                                    {curriculumContentTypes.map(type => (
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
                                <div className="flex items-center gap-2">
                                  <Input
                                    id={`item-ref-${item.key}`}
                                    placeholder="00000000-0000-0000-0000-000000000000"
                                    className="font-mono text-xs"
                                    value={item.contentReferenceId}
                                    onChange={event =>
                                      updateItem(sectionIndex, itemIndex, { contentReferenceId: event.target.value })
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
                                      })
                                    }
                                  >
                                    {t('curriculumWorkspace.browseContent')}
                                  </Button>
                                </div>
                              </div>
                              <div className="grid gap-1">
                                <Label htmlFor={`item-unlock-${item.key}`}>{t('curriculumWorkspace.unlockRule')}</Label>
                                <Select
                                  value={item.unlockRule}
                                  onValueChange={value =>
                                    updateItem(sectionIndex, itemIndex, {
                                      unlockRule: value as CurriculumUnlockRule,
                                    })
                                  }
                                >
                                  <SelectTrigger id={`item-unlock-${item.key}`}>
                                    <SelectValue />
                                  </SelectTrigger>
                                  <SelectContent>
                                    {curriculumUnlockRules.map(rule => (
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
                                onCheckedChange={checked =>
                                  updateItem(sectionIndex, itemIndex, { required: checked })
                                }
                              />
                              {t('curriculumWorkspace.requiredItem')}
                            </label>
                          </div>
                        ))}

                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          className="justify-self-start"
                          onClick={() => updateSection(sectionIndex, { items: [...section.items, emptyItem()] })}
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
                    {t(saveMutation.isPending ? 'curriculumWorkspace.saving' : 'curriculumWorkspace.savePath')}
                  </Button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}

      <CurriculumContentPickerDialog
        open={Boolean(pickerTarget)}
        contentType={pickerTarget?.contentType ?? 'LESSON'}
        currentId={pickerTarget?.currentId ?? ''}
        onOpenChange={open => {
          if (!open) setPickerTarget(null);
        }}
        onSelect={selectedContentId => {
          if (pickerTarget) {
            updateItem(pickerTarget.sectionIndex, pickerTarget.itemIndex, {
              contentReferenceId: selectedContentId,
            });
          }
        }}
      />

      <Dialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('curriculumWorkspace.deleteConfirmTitle')}</DialogTitle>
            <DialogDescription>
              {t('curriculumWorkspace.deleteConfirmHelp', { name: draft?.name || draft?.code || '' })}
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
