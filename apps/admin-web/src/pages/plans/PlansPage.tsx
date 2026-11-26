import { useMemo, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiAwardLine,
  RiBookOpenLine,
  RiEditLine,
  RiEyeLine,
  RiFileList3Line,
  RiFlashlightLine,
  RiHeadphoneLine,
  RiLineChartLine,
  RiMicLine,
  RiSearchLine,
  RiShieldCheckLine,
  RiSparklingLine,
  RiTableLine,
  RiTranslate2,
  RiVipCrownLine,
  RiVolumeUpLine,
} from '@remixicon/react';
import { AdminPagination } from '@/components/AdminPagination';
import { AccessTierBadge } from '@/components/ContentBadges';
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
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  curriculumKey,
  fetchAdminCurriculumPath,
  fetchAdminCurriculumPaths,
  updateAdminCurriculumPath,
} from '@/pages/curriculum/curriculum.api';
import { fetchLessons, saveLessonAccess } from '@/pages/lessons/catalog.api';
import { catalogKeys } from '@/pages/lessons/catalog.queries';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import {
  fetchEntitlementFeatures,
  fetchEntitlementQuotas,
  quotaLimitTypes,
  quotaPeriodUnits,
  updateEntitlementQuota,
  type QuotaPolicy,
} from '@/pages/overview/admin-ops.api';
import { vocabularyKey } from '@/pages/vocabulary/vocabulary-api';
import type { LessonPage } from '@/pages/lessons/catalog.api';
import {
  fetchAdminGrammarPage,
  fetchAdminToeicPage,
  fetchVocabularyPacks,
  updateGrammarAccess,
  updateToeicAccess,
  updateVocabularyPackAccess,
  type GrammarCatalogPage,
  type ToeicCatalogPage,
} from './plans.api';

type MainTab = 'quotas' | 'matrix';
type MatrixTab = 'lessons' | 'vocab' | 'toeic' | 'grammar' | 'paths';
type AccessFilter = 'ALL' | 'PUBLIC' | 'FEATURE';

type QuotaPair = {
  featureKey: string;
  free?: QuotaPolicy;
  plus?: QuotaPolicy;
};

const PAGE_SIZE = 10;

function FeatureIcon({ featureKey }: { featureKey: string }) {
  const cls = 'size-4 shrink-0 text-primary';
  if (featureKey.includes('speech')) return <RiMicLine className={cls} aria-hidden="true" />;
  if (featureKey.includes('vocabulary')) return <RiBookOpenLine className={cls} aria-hidden="true" />;
  if (featureKey.includes('toeic')) return <RiFileList3Line className={cls} aria-hidden="true" />;
  if (featureKey.includes('lesson')) return <RiVolumeUpLine className={cls} aria-hidden="true" />;
  return <RiSparklingLine className={cls} aria-hidden="true" />;
}

export function PlansPage() {
  const { t, i18n } = useTranslation('admin');
  const queryClient = useQueryClient();
  const isVi = i18n.resolvedLanguage?.startsWith('vi');

  const [mainTab, setMainTab] = useState<MainTab>('quotas');
  const [previewOpen, setPreviewOpen] = useState(false);
  const [pendingIds, setPendingIds] = useState<Set<string>>(() => new Set());

  function addPending(id: string) {
    setPendingIds((prev) => new Set(prev).add(id));
  }

  function removePending(id: string) {
    setPendingIds((prev) => {
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
  }

  const [pairEditing, setPairEditing] = useState<QuotaPair | null>(null);
  const [freeMaxAmount, setFreeMaxAmount] = useState('0');
  const [plusMaxAmount, setPlusMaxAmount] = useState('0');
  const [quotaLimitType, setQuotaLimitType] = useState('PERIOD_USAGE');
  const [quotaPeriodUnit, setQuotaPeriodUnit] = useState('DAY');

  const [matrixTab, setMatrixTab] = useState<MatrixTab>('lessons');
  const [matrixQuery, setMatrixQuery] = useState('');
  const [accessFilter, setAccessFilter] = useState<AccessFilter>('ALL');
  const [matrixPage, setMatrixPage] = useState(0);

  const featuresQuery = useQuery({
    queryKey: ['admin-entitlement-features'],
    queryFn: ({ signal }) => fetchEntitlementFeatures(signal),
  });

  const quotasQuery = useQuery({
    queryKey: ['admin-entitlement-quotas'],
    queryFn: ({ signal }) => fetchEntitlementQuotas(signal),
  });

  const lessonsQuery = useQuery({
    queryKey: ['admin-plans-lessons', matrixPage, matrixQuery, accessFilter],
    queryFn: ({ signal }) => {
      const p = new URLSearchParams({
        page: String(matrixPage),
        size: String(PAGE_SIZE),
        status: 'PUBLISHED',
      });
      if (matrixQuery.trim()) p.set('q', matrixQuery.trim());
      if (accessFilter !== 'ALL') p.set('access', accessFilter);
      return fetchLessons(p, signal);
    },
    placeholderData: keepPreviousData,
    enabled: mainTab === 'matrix' && matrixTab === 'lessons',
  });

  const toeicQuery = useQuery({
    queryKey: ['admin-plans-toeic', matrixPage, matrixQuery, accessFilter],
    queryFn: ({ signal }) =>
      fetchAdminToeicPage(
        { page: matrixPage, size: PAGE_SIZE, q: matrixQuery.trim(), access: accessFilter },
        signal,
      ),
    placeholderData: keepPreviousData,
    enabled: mainTab === 'matrix' && matrixTab === 'toeic',
  });

  const grammarQuery = useQuery({
    queryKey: ['admin-plans-grammar', matrixPage, matrixQuery, accessFilter],
    queryFn: ({ signal }) =>
      fetchAdminGrammarPage(
        { kind: 'topic', page: matrixPage, size: PAGE_SIZE, q: matrixQuery.trim(), access: accessFilter },
        signal,
      ),
    placeholderData: keepPreviousData,
    enabled: mainTab === 'matrix' && matrixTab === 'grammar',
  });

  const vocabPacksQuery = useQuery({
    queryKey: ['admin-plans-vocab-packs'],
    queryFn: ({ signal }) => fetchVocabularyPacks(signal),
    enabled: mainTab === 'matrix' && matrixTab === 'vocab',
  });

  const pathsQuery = useQuery({
    queryKey: curriculumKey,
    queryFn: ({ signal }) => fetchAdminCurriculumPaths(signal),
    enabled: mainTab === 'matrix' && matrixTab === 'paths',
  });

  const quotaPairs = useMemo<QuotaPair[]>(() => {
    const map = new Map<string, QuotaPair>();
    for (const q of quotasQuery.data ?? []) {
      const entry = map.get(q.featureKey) ?? { featureKey: q.featureKey };
      if (q.tier === 'PLUS') entry.plus = q;
      else entry.free = q;
      map.set(q.featureKey, entry);
    }
    return Array.from(map.values()).sort((a, b) => a.featureKey.localeCompare(b.featureKey));
  }, [quotasQuery.data]);

  const filteredVocab = useMemo(() => {
    const q = matrixQuery.trim().toLowerCase();
    return (vocabPacksQuery.data ?? []).filter((pack) => {
      const label = `${pack.titleVi} ${pack.titleEn} ${pack.sourceTopic}`.toLowerCase();
      const tier = pack.accessMode === 'FEATURE' ? 'FEATURE' : 'PUBLIC';
      return (!q || label.includes(q)) && (accessFilter === 'ALL' || tier === accessFilter);
    });
  }, [vocabPacksQuery.data, matrixQuery, accessFilter]);

  const filteredPaths = useMemo(() => {
    const q = matrixQuery.trim().toLowerCase();
    return (pathsQuery.data ?? []).filter((path) => {
      const label = `${path.name} ${path.code}`.toLowerCase();
      const tier = path.accessMode === 'FEATURE' ? 'FEATURE' : 'PUBLIC';
      return (!q || label.includes(q)) && (accessFilter === 'ALL' || tier === accessFilter);
    });
  }, [pathsQuery.data, matrixQuery, accessFilter]);

  const quotaMutation = useMutation({
    mutationFn: async () => {
      if (!pairEditing) throw new Error('No feature selected');
      const ops: Promise<QuotaPolicy>[] = [];
      if (pairEditing.free) {
        ops.push(
          updateEntitlementQuota({
            featureKey: pairEditing.featureKey,
            tier: 'FREE',
            limitType: quotaLimitType,
            periodUnit: quotaPeriodUnit,
            maxAmount: Math.max(0, Number(freeMaxAmount) || 0),
            policyVersion: pairEditing.free.policyVersion,
          }),
        );
      }
      if (pairEditing.plus) {
        ops.push(
          updateEntitlementQuota({
            featureKey: pairEditing.featureKey,
            tier: 'PLUS',
            limitType: quotaLimitType,
            periodUnit: quotaPeriodUnit,
            maxAmount: Math.max(0, Number(plusMaxAmount) || 0),
            policyVersion: pairEditing.plus.policyVersion,
          }),
        );
      }
      await Promise.all(ops);
    },
    onSuccess: async () => {
      setPairEditing(null);
      await queryClient.invalidateQueries({ queryKey: ['admin-entitlement-quotas'] });
    },
  });

  const lessonAccessMutation = useMutation({
    mutationFn: ({ id, version, plus }: { id: string; version?: number; plus: boolean }) =>
      saveLessonAccess(id, {
        accessMode: plus ? 'FEATURE' : 'PUBLIC',
        requiredFeatureKey: plus ? 'lesson.plus-lessons' : null,
        version,
      }),
    onMutate: ({ id }) => addPending(id),
    onSuccess: (_data, { id, plus }) => {
      const nextMode = plus ? 'FEATURE' : 'PUBLIC';
      const nextFeature = plus ? 'lesson.plus-lessons' : null;
      queryClient.setQueriesData<LessonPage>(
        { queryKey: ['admin-plans-lessons'] },
        (old) =>
          old
            ? {
                ...old,
                items: old.items.map((row) =>
                  row.id === id
                    ? { ...row, accessMode: nextMode, version: (row.version ?? 0) + 1 }
                    : row,
                ),
              }
            : old,
      );
      queryClient.setQueriesData<LessonPage>(
        { queryKey: ['catalog', 'lessons'] },
        (old) =>
          old
            ? {
                ...old,
                items: old.items.map((row) =>
                  row.id === id
                    ? {
                        ...row,
                        accessMode: nextMode,
                        requiredFeatureKey: nextFeature,
                        version: (row.version ?? 0) + 1,
                      }
                    : row,
                ),
              }
            : old,
      );
      void queryClient.invalidateQueries({ queryKey: catalogKeys.metadata(id) });
    },
    onSettled: (_data, _err, { id }) => removePending(id),
  });

  const vocabAccessMutation = useMutation({
    mutationFn: ({ id, plus }: { id: string; plus: boolean }) =>
      updateVocabularyPackAccess(
        id,
        plus ? 'FEATURE' : 'PUBLIC',
        plus ? 'vocabulary.plus-packs' : null,
      ),
    onMutate: ({ id }) => addPending(id),
    onSuccess: (_data, { id, plus }) => {
      const nextMode = plus ? 'FEATURE' : 'PUBLIC';
      queryClient.setQueryData<Awaited<ReturnType<typeof fetchVocabularyPacks>>>(
        ['admin-plans-vocab-packs'],
        (old) =>
          old ? old.map((row) => (row.id === id ? { ...row, accessMode: nextMode } : row)) : old,
      );
      void queryClient.invalidateQueries({ queryKey: vocabularyKey });
    },
    onSettled: (_data, _err, { id }) => removePending(id),
  });

  const toeicAccessMutation = useMutation({
    mutationFn: ({ id, plus }: { id: string; plus: boolean }) =>
      updateToeicAccess(id, plus ? 'FEATURE' : 'PUBLIC'),
    onMutate: ({ id }) => addPending(id),
    onSuccess: (_data, { id, plus }) => {
      const nextMode = plus ? 'FEATURE' : 'PUBLIC';
      queryClient.setQueriesData<ToeicCatalogPage>(
        { queryKey: ['admin-plans-toeic'] },
        (old) =>
          old
            ? {
                ...old,
                items: old.items.map((row) =>
                  row.id === id ? { ...row, accessMode: nextMode } : row,
                ),
              }
            : old,
      );
    },
    onSettled: (_data, _err, { id }) => removePending(id),
  });

  const grammarAccessMutation = useMutation({
    mutationFn: ({ id, plus }: { id: string; plus: boolean }) =>
      updateGrammarAccess('topic', id, plus ? 'FEATURE' : 'PUBLIC'),
    onMutate: ({ id }) => addPending(id),
    onSuccess: (_data, { id, plus }) => {
      const nextMode = plus ? 'FEATURE' : 'PUBLIC';
      queryClient.setQueriesData<GrammarCatalogPage>(
        { queryKey: ['admin-plans-grammar'] },
        (old) =>
          old
            ? {
                ...old,
                items: old.items.map((row) =>
                  row.id === id ? { ...row, accessMode: nextMode } : row,
                ),
              }
            : old,
      );
    },
    onSettled: (_data, _err, { id }) => removePending(id),
  });

  const pathAccessMutation = useMutation({
    mutationFn: async ({ id, plus }: { id: string; plus: boolean }) => {
      const full = await fetchAdminCurriculumPath(id);
      return updateAdminCurriculumPath(id, {
        code: full.code,
        name: full.name,
        level: full.level,
        description: full.description,
        status: full.status,
        coverAssetId: full.coverAssetId ?? null,
        accessMode: plus ? 'FEATURE' : 'PUBLIC',
        requiredFeatureKey: plus ? 'lesson.plus-lessons' : null,
        sections: full.sections.map((s) => ({
          title: s.title,
          position: s.position,
          items: s.items.map((item) => ({
            position: item.position,
            contentType: item.contentType,
            contentReferenceId: item.contentReferenceId,
            required: item.required,
            unlockRule: item.unlockRule,
            config: item.config ?? {},
          })),
        })),
      });
    },
    onMutate: ({ id }) => addPending(id),
    onSuccess: (_data, { id, plus }) => {
      const nextMode = plus ? 'FEATURE' : 'PUBLIC';
      queryClient.setQueryData<Array<{ id: string; accessMode?: string }>>(
        curriculumKey,
        (old) =>
          old ? old.map((row) => (row.id === id ? { ...row, accessMode: nextMode } : row)) : old,
      );
      void queryClient.invalidateQueries({ queryKey: [...curriculumKey, 'detail', id] });
    },
    onSettled: (_data, _err, { id }) => removePending(id),
  });

  function openPairEditor(pair: QuotaPair) {
    const sample = pair.free ?? pair.plus;
    setPairEditing(pair);
    setFreeMaxAmount(String(pair.free?.maxAmount ?? 0));
    setPlusMaxAmount(String(pair.plus?.maxAmount ?? 0));
    setQuotaLimitType(sample?.limitType ?? 'PERIOD_USAGE');
    setQuotaPeriodUnit(sample?.periodUnit ?? 'DAY');
  }

  function switchMatrixTab(next: string) {
    setMatrixTab(next as MatrixTab);
    setMatrixPage(0);
  }

  const currentCount = (() => {
    if (matrixTab === 'lessons') return lessonsQuery.data?.total ?? 0;
    if (matrixTab === 'toeic') return toeicQuery.data?.total ?? 0;
    if (matrixTab === 'grammar') return grammarQuery.data?.total ?? 0;
    if (matrixTab === 'vocab') return filteredVocab.length;
    return filteredPaths.length;
  })();

  const currentPageCount = Math.max(1, Math.ceil(currentCount / PAGE_SIZE));

  const freeHighlights = [
    { key: 'item1', icon: RiVolumeUpLine },
    { key: 'item2', icon: RiBookOpenLine },
    { key: 'item3', icon: RiMicLine },
    { key: 'item4', icon: RiTranslate2 },
  ] as const;

  const plusHighlights = [
    { key: 'item1', icon: RiFileList3Line },
    { key: 'item2', icon: RiFlashlightLine },
    { key: 'item3', icon: RiHeadphoneLine },
    { key: 'item4', icon: RiLineChartLine },
    { key: 'item5', icon: RiAwardLine },
  ] as const;

  return (
    <section className="grid w-full min-w-0 gap-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">{t('plansConsole.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('plansConsole.subtitle')}</p>
        </div>
        <Button type="button" variant="outline" onClick={() => setPreviewOpen(true)}>
          <RiEyeLine data-icon="inline-start" aria-hidden="true" />
          {t('plansConsole.previewDialog.trigger')}
        </Button>
      </header>

      <Tabs value={mainTab} onValueChange={(v) => setMainTab(v as MainTab)}>
        <TabsList>
          <TabsTrigger value="quotas" className="gap-1.5">
            <RiShieldCheckLine className="size-4" aria-hidden="true" />
            {t('plansConsole.mainTabs.quotas')}
          </TabsTrigger>
          <TabsTrigger value="matrix" className="gap-1.5">
            <RiTableLine className="size-4" aria-hidden="true" />
            {t('plansConsole.mainTabs.matrix')}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="quotas" className="mt-4 min-h-[480px]">
          <Card className="min-w-0">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <RiShieldCheckLine className="size-4 text-primary" aria-hidden="true" />
                {t('plansConsole.comparisonTitle')}
              </CardTitle>
              <CardDescription>{t('plansConsole.comparisonHelp')}</CardDescription>
            </CardHeader>
            <CardContent>
              <CatalogError
                error={quotasQuery.error || featuresQuery.error}
                retry={() => {
                  void quotasQuery.refetch();
                  void featuresQuery.refetch();
                }}
              />
              {quotasQuery.isPending ? (
                <Skeleton className="h-64 w-full" />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('rewardsConsole.columns.feature')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.limitType')}</TableHead>
                      <TableHead>{t('rewardsConsole.columns.period')}</TableHead>
                      <TableHead className="text-right">{t('plansConsole.freeQuota')}</TableHead>
                      <TableHead className="text-right">{t('plansConsole.plusQuota')}</TableHead>
                      <TableHead className="text-right">{t('rewardsConsole.columns.actions')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {quotaPairs.map((pair) => {
                      const sample = pair.free ?? pair.plus;
                      const feature = featuresQuery.data?.find((f) => f.featureKey === pair.featureKey);
                      const featureName = t(`plansConsole.features.${pair.featureKey}`, {
                        defaultValue: feature?.displayName || pair.featureKey,
                      });
                      return (
                        <TableRow key={pair.featureKey}>
                          <TableCell>
                            <div className="flex items-center gap-2.5">
                              <FeatureIcon featureKey={pair.featureKey} />
                              <div className="space-y-0.5">
                                <p className="font-medium">{featureName}</p>
                                <p className="font-mono text-xs text-muted-foreground">
                                  {pair.featureKey}
                                </p>
                              </div>
                            </div>
                          </TableCell>
                          <TableCell className="text-xs">
                            {sample
                              ? t(`plansConsole.limitTypes.${sample.limitType}`, {
                                  defaultValue: sample.limitType,
                                })
                              : '—'}
                          </TableCell>
                          <TableCell className="text-xs">
                            {sample
                              ? t(`plansConsole.periods.${sample.periodUnit}`, {
                                  defaultValue: sample.periodUnit,
                                })
                              : '—'}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {pair.free ? (
                              <Badge variant="outline" className="font-semibold">
                                {pair.free.maxAmount.toLocaleString()}
                              </Badge>
                            ) : (
                              '—'
                            )}
                          </TableCell>
                          <TableCell className="text-right tabular-nums">
                            {pair.plus ? (
                              <Badge variant="warning" className="font-semibold">
                                {pair.plus.maxAmount.toLocaleString()}
                              </Badge>
                            ) : (
                              '—'
                            )}
                          </TableCell>
                          <TableCell className="text-right">
                            <Button
                              type="button"
                              size="sm"
                              variant="outline"
                              onClick={() => openPairEditor(pair)}
                            >
                              <RiEditLine data-icon="inline-start" aria-hidden="true" />
                              {t('rewardsConsole.edit')}
                            </Button>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="matrix" className="mt-4 min-h-[480px]">
          <Card className="min-w-0">
            <CardHeader>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <CardTitle>{t('plansConsole.contentMatrixTitle')}</CardTitle>
                  <CardDescription>{t('plansConsole.contentMatrixHelp')}</CardDescription>
                </div>
                <Badge variant="secondary" className="tabular-nums">
                  {t('plansConsole.showingCount', { count: currentCount })}
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              <Tabs value={matrixTab} onValueChange={switchMatrixTab}>
                <TabsList className="flex-wrap">
                  <TabsTrigger value="lessons">{t('plansConsole.tabs.lessons')}</TabsTrigger>
                  <TabsTrigger value="vocab">{t('plansConsole.tabs.vocab')}</TabsTrigger>
                  <TabsTrigger value="toeic">{t('plansConsole.tabs.toeic')}</TabsTrigger>
                  <TabsTrigger value="grammar">{t('plansConsole.tabs.grammar')}</TabsTrigger>
                  <TabsTrigger value="paths">{t('plansConsole.tabs.paths')}</TabsTrigger>
                </TabsList>

                <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center">
                  <div className="relative flex-1">
                    <RiSearchLine
                      className="pointer-events-none absolute top-2.5 left-3 size-4 text-muted-foreground"
                      aria-hidden="true"
                    />
                    <Input
                      value={matrixQuery}
                      onChange={(e) => {
                        setMatrixQuery(e.target.value);
                        setMatrixPage(0);
                      }}
                      placeholder={t('plansConsole.searchPlaceholder')}
                      className="pl-9"
                    />
                  </div>
                  <Select
                    value={accessFilter}
                    onValueChange={(value) => {
                      setAccessFilter(value as AccessFilter);
                      setMatrixPage(0);
                    }}
                  >
                    <SelectTrigger className="w-44">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="ALL">{t('plansConsole.allTiers')}</SelectItem>
                      <SelectItem value="PUBLIC">{t('plansConsole.tiers.FREE')}</SelectItem>
                      <SelectItem value="FEATURE">{t('plansConsole.tiers.PLUS')}</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                <div className="mt-4 flex h-[520px] flex-col overflow-hidden rounded-xl border bg-background">
                  <div className="flex-1 overflow-y-auto">
                    <TabsContent value="lessons" className="mt-0">
                      {lessonsQuery.isPending ? (
                        <div className="p-4">
                          <Skeleton className="h-64 w-full" />
                        </div>
                      ) : (
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>{t('catalog.lesson')}</TableHead>
                              <TableHead>{t('plansConsole.tierColumn')}</TableHead>
                              <TableHead className="text-right">{t('plansConsole.plusToggle')}</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {(lessonsQuery.data?.items ?? []).map((lesson) => {
                              const isPlus = lesson.accessMode === 'FEATURE';
                              return (
                                <TableRow key={lesson.id}>
                                  <TableCell className="whitespace-normal font-medium">
                                    {lesson.title}
                                  </TableCell>
                                  <TableCell>
                                    <AccessTierBadge accessMode={lesson.accessMode} />
                                  </TableCell>
                                  <TableCell className="text-right">
                                    <Switch
                                      checked={isPlus}
                                      disabled={pendingIds.has(lesson.id)}
                                      onCheckedChange={(plus) =>
                                        lessonAccessMutation.mutate({
                                          id: lesson.id,
                                          version: lesson.version,
                                          plus,
                                        })
                                      }
                                    />
                                  </TableCell>
                                </TableRow>
                              );
                            })}
                          </TableBody>
                        </Table>
                      )}
                    </TabsContent>

                    <TabsContent value="vocab" className="mt-0">
                      {vocabPacksQuery.isPending ? (
                        <div className="p-4">
                          <Skeleton className="h-64 w-full" />
                        </div>
                      ) : (
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>{t('plansConsole.packTitle')}</TableHead>
                              <TableHead>{t('plansConsole.tierColumn')}</TableHead>
                              <TableHead className="text-right">{t('plansConsole.plusToggle')}</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {filteredVocab
                              .slice(matrixPage * PAGE_SIZE, (matrixPage + 1) * PAGE_SIZE)
                              .map((pack) => {
                                const isPlus = pack.accessMode === 'FEATURE';
                                return (
                                  <TableRow key={pack.id}>
                                    <TableCell className="whitespace-normal font-medium">
                                      {isVi ? pack.titleVi || pack.titleEn : pack.titleEn || pack.titleVi}
                                    </TableCell>
                                    <TableCell>
                                      <AccessTierBadge accessMode={pack.accessMode} />
                                    </TableCell>
                                    <TableCell className="text-right">
                                      <Switch
                                        checked={isPlus}
                                        disabled={pendingIds.has(pack.id)}
                                        onCheckedChange={(plus) =>
                                          vocabAccessMutation.mutate({ id: pack.id, plus })
                                        }
                                      />
                                    </TableCell>
                                  </TableRow>
                                );
                              })}
                          </TableBody>
                        </Table>
                      )}
                    </TabsContent>

                    <TabsContent value="toeic" className="mt-0">
                      {toeicQuery.isPending ? (
                        <div className="p-4">
                          <Skeleton className="h-64 w-full" />
                        </div>
                      ) : (
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>{t('plansConsole.toeicTest')}</TableHead>
                              <TableHead>{t('plansConsole.tierColumn')}</TableHead>
                              <TableHead className="text-right">{t('plansConsole.plusToggle')}</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {(toeicQuery.data?.items ?? []).map((test) => {
                              const isPlus = test.accessMode === 'FEATURE';
                              return (
                                <TableRow key={test.id}>
                                  <TableCell className="whitespace-normal font-medium">
                                    <div className="flex flex-wrap items-center gap-2">
                                      <span>{test.title}</span>
                                      {test.year ? (
                                        <Badge variant="secondary">{test.year}</Badge>
                                      ) : null}
                                      <span className="text-xs text-muted-foreground">
                                        {t('plansConsole.questionsCount', { count: test.questionCount })}
                                      </span>
                                    </div>
                                  </TableCell>
                                  <TableCell>
                                    <AccessTierBadge accessMode={test.accessMode} />
                                  </TableCell>
                                  <TableCell className="text-right">
                                    <Switch
                                      checked={isPlus}
                                      disabled={pendingIds.has(test.id)}
                                      onCheckedChange={(plus) =>
                                        toeicAccessMutation.mutate({ id: test.id, plus })
                                      }
                                    />
                                  </TableCell>
                                </TableRow>
                              );
                            })}
                          </TableBody>
                        </Table>
                      )}
                    </TabsContent>

                    <TabsContent value="grammar" className="mt-0">
                      {grammarQuery.isPending ? (
                        <div className="p-4">
                          <Skeleton className="h-64 w-full" />
                        </div>
                      ) : (
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>{t('plansConsole.grammarTopic')}</TableHead>
                              <TableHead>{t('plansConsole.tierColumn')}</TableHead>
                              <TableHead className="text-right">{t('plansConsole.plusToggle')}</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {(grammarQuery.data?.items ?? []).map((topic) => {
                              const isPlus = topic.accessMode === 'FEATURE';
                              return (
                                <TableRow key={topic.id}>
                                  <TableCell className="whitespace-normal font-medium">
                                    <div className="flex flex-wrap items-center gap-2">
                                      <span>{isVi ? topic.title : topic.titleEn || topic.title}</span>
                                      <span className="text-xs text-muted-foreground">
                                        {t('plansConsole.questionsCount', { count: topic.questionCount })}
                                      </span>
                                    </div>
                                  </TableCell>
                                  <TableCell>
                                    <AccessTierBadge accessMode={topic.accessMode} />
                                  </TableCell>
                                  <TableCell className="text-right">
                                    <Switch
                                      checked={isPlus}
                                      disabled={pendingIds.has(topic.id)}
                                      onCheckedChange={(plus) =>
                                        grammarAccessMutation.mutate({ id: topic.id, plus })
                                      }
                                    />
                                  </TableCell>
                                </TableRow>
                              );
                            })}
                          </TableBody>
                        </Table>
                      )}
                    </TabsContent>

                    <TabsContent value="paths" className="mt-0">
                      {pathsQuery.isPending ? (
                        <div className="p-4">
                          <Skeleton className="h-64 w-full" />
                        </div>
                      ) : (
                        <Table>
                          <TableHeader>
                            <TableRow>
                              <TableHead>{t('curriculumWorkspace.pathName')}</TableHead>
                              <TableHead>{t('plansConsole.tierColumn')}</TableHead>
                              <TableHead className="text-right">{t('plansConsole.plusToggle')}</TableHead>
                            </TableRow>
                          </TableHeader>
                          <TableBody>
                            {filteredPaths
                              .slice(matrixPage * PAGE_SIZE, (matrixPage + 1) * PAGE_SIZE)
                              .map((p) => {
                                const isPlus = p.accessMode === 'FEATURE';
                                return (
                                  <TableRow key={p.id}>
                                    <TableCell className="whitespace-normal font-medium">
                                      {p.name}
                                    </TableCell>
                                    <TableCell>
                                      <AccessTierBadge accessMode={p.accessMode} />
                                    </TableCell>
                                    <TableCell className="text-right">
                                      <Switch
                                        checked={isPlus}
                                        disabled={pendingIds.has(p.id)}
                                        onCheckedChange={(plus) =>
                                          pathAccessMutation.mutate({ id: p.id, plus })
                                        }
                                      />
                                    </TableCell>
                                  </TableRow>
                                );
                              })}
                          </TableBody>
                        </Table>
                      )}
                    </TabsContent>
                  </div>

                  {currentPageCount > 1 ? (
                    <div className="border-t bg-muted/15 px-3 py-2">
                      <AdminPagination
                        page={matrixPage}
                        pageCount={currentPageCount}
                        onPageChange={setMatrixPage}
                      />
                    </div>
                  ) : null}
                </div>
              </Tabs>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>

      <Dialog open={previewOpen} onOpenChange={setPreviewOpen}>
        <DialogContent className="sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <RiVipCrownLine className="size-5 text-warning" aria-hidden="true" />
              {t('plansConsole.previewDialog.title')}
            </DialogTitle>
            <DialogDescription>{t('plansConsole.previewDialog.subtitle')}</DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-1 md:grid-cols-2">
            <section className="flex flex-col justify-between rounded-2xl border bg-[var(--plan-free-surface)] p-6">
              <div className="space-y-4">
                <div className="flex items-start justify-between gap-2">
                  <div className="space-y-1">
                    <h3 className="text-lg font-semibold tracking-tight">
                      {t('plansConsole.freeCard.title')}
                    </h3>
                    <p className="text-xs leading-relaxed text-muted-foreground">
                      {t('plansConsole.freeCard.description')}
                    </p>
                  </div>
                  <AccessTierBadge accessMode="PUBLIC" />
                </div>

                <p className="text-2xl font-bold tracking-tight">
                  {t('plansConsole.previewDialog.freePrice')}
                </p>

                <ul className="space-y-2.5 border-t pt-4 text-sm">
                  {freeHighlights.map(({ key, icon: Icon }) => (
                    <li key={key} className="flex items-start gap-2.5">
                      <Icon className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                      <span className="leading-snug">{t(`plansConsole.freeCard.${key}`)}</span>
                    </li>
                  ))}
                </ul>

                {quotaPairs.length > 0 ? (
                  <p className="border-t pt-3 text-xs leading-relaxed text-muted-foreground">
                    {quotaPairs
                      .slice(0, 4)
                      .filter((pair) => pair.free)
                      .map(
                        (pair) =>
                          `${t(`plansConsole.features.${pair.featureKey}`, {
                            defaultValue: pair.featureKey,
                          })}: ${pair.free!.maxAmount.toLocaleString()}`,
                      )
                      .join(' · ')}
                  </p>
                ) : null}
              </div>

              <Button type="button" variant="outline" className="mt-6 w-full rounded-full" disabled>
                {t('plansConsole.previewDialog.freeCta')}
              </Button>
            </section>

            <section className="flex flex-col justify-between rounded-2xl border-2 border-[var(--plan-plus-ring)] bg-[var(--plan-plus-surface)] p-6 shadow-sm">
              <div className="space-y-4">
                <div className="flex items-start justify-between gap-2">
                  <div className="space-y-1">
                    <div className="flex items-center gap-2">
                      <RiVipCrownLine className="size-4 text-warning" aria-hidden="true" />
                      <h3 className="text-lg font-semibold tracking-tight">
                        {t('plansConsole.plusCard.title')}
                      </h3>
                    </div>
                    <p className="text-xs leading-relaxed text-muted-foreground">
                      {t('plansConsole.plusCard.description')}
                    </p>
                  </div>
                  <Badge variant="warning" className="font-semibold">
                    {t('plansConsole.previewDialog.recommendedBadge')}
                  </Badge>
                </div>

                <p className="text-2xl font-bold tracking-tight text-warning">
                  {t('plansConsole.previewDialog.plusPrice')}
                </p>

                <ul className="space-y-2.5 border-t border-warning/20 pt-4 text-sm">
                  {plusHighlights.map(({ key, icon: Icon }) => (
                    <li key={key} className="flex items-start gap-2.5">
                      <Icon className="mt-0.5 size-4 shrink-0 text-warning" aria-hidden="true" />
                      <span className="leading-snug font-medium">
                        {t(`plansConsole.plusCard.${key}`)}
                      </span>
                    </li>
                  ))}
                </ul>

                {quotaPairs.length > 0 ? (
                  <p className="border-t border-warning/20 pt-3 text-xs leading-relaxed text-muted-foreground">
                    {quotaPairs
                      .slice(0, 4)
                      .filter((pair) => pair.plus)
                      .map(
                        (pair) =>
                          `${t(`plansConsole.features.${pair.featureKey}`, {
                            defaultValue: pair.featureKey,
                          })}: ${pair.plus!.maxAmount.toLocaleString()}`,
                      )
                      .join(' · ')}
                  </p>
                ) : null}
              </div>

              <Button
                type="button"
                className="mt-6 w-full rounded-full"
                onClick={() => setPreviewOpen(false)}
              >
                <RiVipCrownLine data-icon="inline-start" aria-hidden="true" />
                {t('plansConsole.previewDialog.plusCta')}
              </Button>
            </section>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(pairEditing)} onOpenChange={(open) => !open && setPairEditing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('plansConsole.editPairTitle')}</DialogTitle>
            <DialogDescription>
              {pairEditing
                ? t(`plansConsole.features.${pairEditing.featureKey}`, {
                    defaultValue: pairEditing.featureKey,
                  })
                : ''}
            </DialogDescription>
          </DialogHeader>
          <CatalogError error={quotaMutation.error} />
          <div className="grid gap-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.limitType')}</Label>
                <Select value={quotaLimitType} onValueChange={setQuotaLimitType}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {quotaLimitTypes.map((lt) => (
                      <SelectItem key={lt} value={lt}>
                        {t(`plansConsole.limitTypes.${lt}`, { defaultValue: lt })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('rewardsConsole.columns.period')}</Label>
                <Select value={quotaPeriodUnit} onValueChange={setQuotaPeriodUnit}>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {quotaPeriodUnits.map((pu) => (
                      <SelectItem key={pu} value={pu}>
                        {t(`plansConsole.periods.${pu}`, { defaultValue: pu })}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              {pairEditing?.free ? (
                <div className="grid gap-1">
                  <Label>{t('plansConsole.freeQuota')}</Label>
                  <Input
                    type="number"
                    min={0}
                    value={freeMaxAmount}
                    onChange={(e) => setFreeMaxAmount(e.target.value)}
                  />
                </div>
              ) : null}
              {pairEditing?.plus ? (
                <div className="grid gap-1">
                  <Label>{t('plansConsole.plusQuota')}</Label>
                  <Input
                    type="number"
                    min={0}
                    value={plusMaxAmount}
                    onChange={(e) => setPlusMaxAmount(e.target.value)}
                  />
                </div>
              ) : null}
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setPairEditing(null)}>
              {t('rewardsConsole.cancel')}
            </Button>
            <Button
              type="button"
              disabled={quotaMutation.isPending}
              onClick={() => quotaMutation.mutate()}
            >
              {t('rewardsConsole.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
