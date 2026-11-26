import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiCheckLine,
  RiEditLine,
  RiLockUnlockLine,
  RiShieldCheckLine,
  RiVipCrownLine,
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
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { fetchSpeakingScenarios, speakingScenariosKey, updateSpeakingScenario } from '@/pages/conversations/speaking.api';
import {
  curriculumKey,
  fetchAdminCurriculumPath,
  fetchAdminCurriculumPaths,
  updateAdminCurriculumPath,
} from '@/pages/curriculum/curriculum.api';
import { fetchLessons, saveLessonAccess } from '@/pages/lessons/catalog.api';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import {
  fetchEntitlementFeatures,
  fetchEntitlementQuotas,
  quotaLimitTypes,
  quotaPeriodUnits,
  updateEntitlementQuota,
  type QuotaPolicy,
} from '@/pages/overview/admin-ops.api';
import { fetchVocabularyPacks, updateVocabularyPackAccess } from './plans.api';

export function PlansPage() {
  const { t } = useTranslation('admin');
  const queryClient = useQueryClient();

  const [quotaEditing, setQuotaEditing] = useState<QuotaPolicy | null>(null);
  const [quotaMaxAmount, setQuotaMaxAmount] = useState('0');
  const [quotaLimitType, setQuotaLimitType] = useState('PERIOD_USAGE');
  const [quotaPeriodUnit, setQuotaPeriodUnit] = useState('DAY');

  const featuresQuery = useQuery({
    queryKey: ['admin-entitlement-features'],
    queryFn: ({ signal }) => fetchEntitlementFeatures(signal),
  });

  const quotasQuery = useQuery({
    queryKey: ['admin-entitlement-quotas'],
    queryFn: ({ signal }) => fetchEntitlementQuotas(signal),
  });

  const lessonsQuery = useQuery({
    queryKey: ['admin-plans-lessons'],
    queryFn: ({ signal }) =>
      fetchLessons(new URLSearchParams({ page: '0', size: '30', status: 'PUBLISHED' }), signal),
  });

  const vocabPacksQuery = useQuery({
    queryKey: ['admin-plans-vocab-packs'],
    queryFn: ({ signal }) => fetchVocabularyPacks(signal),
  });

  const pathsQuery = useQuery({
    queryKey: curriculumKey,
    queryFn: ({ signal }) => fetchAdminCurriculumPaths(signal),
  });

  const scenariosQuery = useQuery({
    queryKey: speakingScenariosKey,
    queryFn: ({ signal }) => fetchSpeakingScenarios(signal),
  });

  const quotaMutation = useMutation({
    mutationFn: () => {
      if (!quotaEditing) throw new Error('No quota selected');
      return updateEntitlementQuota({
        featureKey: quotaEditing.featureKey,
        tier: quotaEditing.tier,
        limitType: quotaLimitType,
        periodUnit: quotaPeriodUnit,
        maxAmount: Math.max(0, Number(quotaMaxAmount) || 0),
        policyVersion: quotaEditing.policyVersion,
      });
    },
    onSuccess: async () => {
      setQuotaEditing(null);
      await queryClient.invalidateQueries({ queryKey: ['admin-entitlement-quotas'] });
    },
  });

  const lessonAccessMutation = useMutation({
    mutationFn: ({
      id,
      version,
      plus,
    }: {
      id: string;
      version?: number;
      plus: boolean;
    }) =>
      saveLessonAccess(id, {
        accessMode: plus ? 'FEATURE' : 'PUBLIC',
        requiredFeatureKey: plus ? 'lesson.plus-lessons' : null,
        version,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-plans-lessons'] });
    },
  });

  const vocabAccessMutation = useMutation({
    mutationFn: ({ id, plus }: { id: string; plus: boolean }) =>
      updateVocabularyPackAccess(
        id,
        plus ? 'FEATURE' : 'PUBLIC',
        plus ? 'vocabulary.plus-packs' : null,
      ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-plans-vocab-packs'] });
    },
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
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: curriculumKey });
    },
  });

  const scenarioAccessMutation = useMutation({
    mutationFn: ({ scenarioId, plus }: { scenarioId: string; plus: boolean }) => {
      const existing = scenariosQuery.data?.find((s) => s.id === scenarioId);
      if (!existing) throw new Error('Scenario not found');
      return updateSpeakingScenario(scenarioId, {
        code: existing.code,
        title: existing.title,
        titleVi: existing.titleVi,
        category: existing.category,
        cefrLevel: existing.cefrLevel,
        coverAssetId: existing.coverAssetId,
        aiRoleName: existing.aiRoleName,
        aiPersona: existing.aiPersona,
        learnerRole: existing.learnerRole,
        settingDescription: existing.settingDescription,
        openingLine: existing.openingLine,
        systemPrompt: existing.systemPrompt,
        objectives: existing.objectives,
        ttsVoice: existing.ttsVoice,
        targetTurns: existing.targetTurns,
        estimatedMinutes: existing.estimatedMinutes,
        accessMode: plus ? 'FEATURE' : 'PUBLIC',
        requiredFeatureKey: plus ? 'speaking.roleplay' : null,
        status: existing.status,
      });
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: speakingScenariosKey });
    },
  });

  function openQuotaEditor(q: QuotaPolicy) {
    setQuotaEditing(q);
    setQuotaMaxAmount(String(q.maxAmount));
    setQuotaLimitType(q.limitType);
    setQuotaPeriodUnit(q.periodUnit);
  }

  return (
    <section className="mx-auto grid w-full max-w-7xl gap-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold tracking-tight">{t('plansConsole.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('plansConsole.subtitle')}</p>
      </header>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader className="border-b">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <RiLockUnlockLine className="size-5 text-muted-foreground" aria-hidden="true" />
                <CardTitle>{t('plansConsole.freeCard.title')}</CardTitle>
              </div>
              <Badge variant="outline">FREE</Badge>
            </div>
            <CardDescription>{t('plansConsole.freeCard.description')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2 pt-4 text-sm">
            {(['item1', 'item2', 'item3'] as const).map((k) => (
              <p key={k} className="flex items-center gap-2">
                <RiCheckLine className="size-4 text-success" aria-hidden="true" />
                <span>{t(`plansConsole.freeCard.${k}`)}</span>
              </p>
            ))}
          </CardContent>
        </Card>

        <Card className="border-warning/40">
          <CardHeader className="border-b">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">
                <RiVipCrownLine className="size-5 text-warning" aria-hidden="true" />
                <CardTitle>{t('plansConsole.plusCard.title')}</CardTitle>
              </div>
              <Badge variant="warning">PLUS</Badge>
            </div>
            <CardDescription>{t('plansConsole.plusCard.description')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2 pt-4 text-sm">
            {(['item1', 'item2', 'item3', 'item4'] as const).map((k) => (
              <p key={k} className="flex items-center gap-2">
                <RiCheckLine className="size-4 text-warning" aria-hidden="true" />
                <span>{t(`plansConsole.plusCard.${k}`)}</span>
              </p>
            ))}
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <RiShieldCheckLine className="size-4 text-muted-foreground" aria-hidden="true" />
            {t('rewardsConsole.quotasTitle')}
          </CardTitle>
          <CardDescription>{t('rewardsConsole.quotasHelp')}</CardDescription>
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
            <Skeleton className="h-44 w-full" />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('rewardsConsole.columns.feature')}</TableHead>
                  <TableHead>{t('rewardsConsole.columns.tier')}</TableHead>
                  <TableHead>{t('rewardsConsole.columns.limitType')}</TableHead>
                  <TableHead>{t('rewardsConsole.columns.period')}</TableHead>
                  <TableHead className="text-right">{t('rewardsConsole.columns.maxAmount')}</TableHead>
                  <TableHead className="text-right">{t('rewardsConsole.columns.actions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(quotasQuery.data ?? []).map((q) => {
                  const feature = featuresQuery.data?.find((f) => f.featureKey === q.featureKey);
                  return (
                    <TableRow key={`${q.featureKey}:${q.tier}`}>
                      <TableCell>
                        <div className="space-y-0.5">
                          <p className="font-medium">{feature?.displayName || q.featureKey}</p>
                          <p className="font-mono text-xs text-muted-foreground">{q.featureKey}</p>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={q.tier === 'PLUS' ? 'warning' : 'outline'}>{q.tier}</Badge>
                      </TableCell>
                      <TableCell className="text-xs">{q.limitType}</TableCell>
                      <TableCell className="text-xs">{q.periodUnit}</TableCell>
                      <TableCell className="text-right font-medium tabular-nums">
                        {q.maxAmount}
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          type="button"
                          size="sm"
                          variant="outline"
                          onClick={() => openQuotaEditor(q)}
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

      <Card>
        <CardHeader>
          <CardTitle>{t('plansConsole.contentMatrixTitle')}</CardTitle>
          <CardDescription>{t('plansConsole.contentMatrixHelp')}</CardDescription>
        </CardHeader>
        <CardContent>
          <Tabs defaultValue="lessons">
            <TabsList>
              <TabsTrigger value="lessons">{t('plansConsole.tabs.lessons')}</TabsTrigger>
              <TabsTrigger value="vocab">{t('plansConsole.tabs.vocab')}</TabsTrigger>
              <TabsTrigger value="paths">{t('plansConsole.tabs.paths')}</TabsTrigger>
              <TabsTrigger value="speaking">{t('plansConsole.tabs.speaking')}</TabsTrigger>
            </TabsList>

            <TabsContent value="lessons" className="mt-4">
              {lessonsQuery.isPending ? (
                <Skeleton className="h-36 w-full" />
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
                          <TableCell className="font-medium">{lesson.title}</TableCell>
                          <TableCell>
                            <Badge variant={isPlus ? 'warning' : 'outline'}>
                              {isPlus ? 'PLUS' : 'FREE'}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-right">
                            <Switch
                              checked={isPlus}
                              disabled={lessonAccessMutation.isPending}
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

            <TabsContent value="vocab" className="mt-4">
              {vocabPacksQuery.isPending ? (
                <Skeleton className="h-36 w-full" />
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
                    {(vocabPacksQuery.data ?? []).map((pack) => {
                      const isPlus = pack.accessMode === 'FEATURE';
                      return (
                        <TableRow key={pack.id}>
                          <TableCell className="font-medium">
                            {pack.titleVi || pack.titleEn}
                          </TableCell>
                          <TableCell>
                            <Badge variant={isPlus ? 'warning' : 'outline'}>
                              {isPlus ? 'PLUS' : 'FREE'}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-right">
                            <Switch
                              checked={isPlus}
                              disabled={vocabAccessMutation.isPending}
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

            <TabsContent value="paths" className="mt-4">
              {pathsQuery.isPending ? (
                <Skeleton className="h-36 w-full" />
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
                    {(pathsQuery.data ?? []).map((p) => {
                      const isPlus = p.accessMode === 'FEATURE';
                      return (
                        <TableRow key={p.id}>
                          <TableCell className="font-medium">{p.name}</TableCell>
                          <TableCell>
                            <Badge variant={isPlus ? 'warning' : 'outline'}>
                              {isPlus ? 'PLUS' : 'FREE'}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-right">
                            <Switch
                              checked={isPlus}
                              disabled={pathAccessMutation.isPending}
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

            <TabsContent value="speaking" className="mt-4">
              {scenariosQuery.isPending ? (
                <Skeleton className="h-36 w-full" />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('plansConsole.scenarioTitle')}</TableHead>
                      <TableHead>{t('plansConsole.tierColumn')}</TableHead>
                      <TableHead className="text-right">{t('plansConsole.plusToggle')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {(scenariosQuery.data ?? []).map((sc) => {
                      const isPlus = sc.accessMode === 'FEATURE';
                      return (
                        <TableRow key={sc.id}>
                          <TableCell className="font-medium">{sc.titleVi || sc.title}</TableCell>
                          <TableCell>
                            <Badge variant={isPlus ? 'warning' : 'outline'}>
                              {isPlus ? 'PLUS' : 'FREE'}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-right">
                            <Switch
                              checked={isPlus}
                              disabled={scenarioAccessMutation.isPending}
                              onCheckedChange={(plus) =>
                                scenarioAccessMutation.mutate({ scenarioId: sc.id, plus })
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
          </Tabs>
        </CardContent>
      </Card>

      <Dialog open={Boolean(quotaEditing)} onOpenChange={(open) => !open && setQuotaEditing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('rewardsConsole.editQuotaTitle')}</DialogTitle>
            <DialogDescription>
              {quotaEditing ? `${quotaEditing.featureKey} (${quotaEditing.tier})` : ''}
            </DialogDescription>
          </DialogHeader>
          <CatalogError error={quotaMutation.error} />
          <div className="grid gap-3">
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.limitType')}</Label>
              <Select value={quotaLimitType} onValueChange={setQuotaLimitType}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {quotaLimitTypes.map((lt) => (
                    <SelectItem key={lt} value={lt}>
                      {lt}
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
                      {pu}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-1">
              <Label>{t('rewardsConsole.columns.maxAmount')}</Label>
              <Input
                type="number"
                min={0}
                value={quotaMaxAmount}
                onChange={(e) => setQuotaMaxAmount(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setQuotaEditing(null)}>
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
