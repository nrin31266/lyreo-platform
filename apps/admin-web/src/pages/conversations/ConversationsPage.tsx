import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  RiAddLine,
  RiChatVoiceAiLine,
  RiDeleteBinLine,
  RiEditLine,
  RiMicLine,
  RiSendPlane2Line,
  RiSparklingLine,
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
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { CatalogError } from '@/pages/lessons/CatalogFeedback';
import {
  createSpeakingScenario,
  deleteSpeakingScenario,
  fetchSpeakingScenarios,
  previewSpeakingTurn,
  speakingScenariosKey,
  updateSpeakingScenario,
  type SaveSpeakingScenarioInput,
  type SpeakingAccessMode,
  type SpeakingCefrLevel,
  type SpeakingPreviewMessage,
  type SpeakingScenario,
  type SpeakingScenarioStatus,
} from './speaking.api';

const cefrLevels: SpeakingCefrLevel[] = ['A1', 'A2', 'B1', 'B2', 'C1', 'C2'];
const voices = ['af_heart', 'af_bella', 'af_nicole', 'am_michael', 'am_adam', 'bf_emma', 'bm_george'];
const categories = ['DAILY_LIFE', 'TRAVEL', 'WORKPLACE', 'HEALTH', 'SHOPPING', 'INTERVIEW'];

type FormState = {
  id: string | null;
  code: string;
  title: string;
  titleVi: string;
  category: string;
  cefrLevel: SpeakingCefrLevel;
  coverAssetId: string | null;
  aiRoleName: string;
  aiPersona: string;
  learnerRole: string;
  settingDescription: string;
  openingLine: string;
  systemPrompt: string;
  objectivesText: string;
  ttsVoice: string;
  targetTurns: number;
  estimatedMinutes: number;
  accessMode: SpeakingAccessMode;
  requiredFeatureKey: string | null;
  status: SpeakingScenarioStatus;
};

function emptyForm(): FormState {
  return {
    id: null,
    code: '',
    title: '',
    titleVi: '',
    category: 'DAILY_LIFE',
    cefrLevel: 'B1',
    coverAssetId: null,
    aiRoleName: '',
    aiPersona: '',
    learnerRole: '',
    settingDescription: '',
    openingLine: '',
    systemPrompt: '',
    objectivesText: '',
    ttsVoice: 'af_heart',
    targetTurns: 8,
    estimatedMinutes: 5,
    accessMode: 'PUBLIC',
    requiredFeatureKey: null,
    status: 'PUBLISHED',
  };
}

function toForm(scenario: SpeakingScenario): FormState {
  return {
    id: scenario.id,
    code: scenario.code,
    title: scenario.title,
    titleVi: scenario.titleVi,
    category: scenario.category,
    cefrLevel: scenario.cefrLevel,
    coverAssetId: scenario.coverAssetId,
    aiRoleName: scenario.aiRoleName,
    aiPersona: scenario.aiPersona,
    learnerRole: scenario.learnerRole,
    settingDescription: scenario.settingDescription,
    openingLine: scenario.openingLine,
    systemPrompt: scenario.systemPrompt,
    objectivesText: (scenario.objectives ?? []).join('\n'),
    ttsVoice: scenario.ttsVoice || 'af_heart',
    targetTurns: scenario.targetTurns || 8,
    estimatedMinutes: scenario.estimatedMinutes || 5,
    accessMode: scenario.accessMode || 'PUBLIC',
    requiredFeatureKey: scenario.requiredFeatureKey ?? null,
    status: scenario.status,
  };
}

export function ConversationsPage() {
  const { t } = useTranslation('admin');
  const queryClient = useQueryClient();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [deleteTarget, setDeleteTarget] = useState<SpeakingScenario | null>(null);

  const [history, setHistory] = useState<SpeakingPreviewMessage[]>([]);
  const [draftMessage, setDraftMessage] = useState('');
  const [lastTips, setLastTips] = useState<string[]>([]);
  const [lastMeta, setLastMeta] = useState<{ provider: string; latencyMs: number } | null>(null);

  const scenariosQuery = useQuery({
    queryKey: speakingScenariosKey,
    queryFn: ({ signal }) => fetchSpeakingScenarios(signal),
  });

  useEffect(() => {
    if (!scenariosQuery.data || scenariosQuery.data.length === 0) return;
    if (!selectedId || !scenariosQuery.data.some((item) => item.id === selectedId)) {
      setSelectedId(scenariosQuery.data[0].id);
    }
  }, [scenariosQuery.data, selectedId]);

  const selected =
    scenariosQuery.data?.find((item) => item.id === selectedId) ?? scenariosQuery.data?.[0] ?? null;

  useEffect(() => {
    if (!selected) {
      setHistory([]);
      setLastTips([]);
      setLastMeta(null);
      return;
    }
    setHistory(selected.openingLine ? [{ role: 'assistant', content: selected.openingLine }] : []);
    setLastTips([]);
    setLastMeta(null);
    setDraftMessage('');
  }, [selected?.id, selected?.openingLine]);

  const saveMutation = useMutation({
    mutationFn: (state: FormState) => {
      const payload: SaveSpeakingScenarioInput = {
        code: state.code.trim().toUpperCase(),
        title: state.title.trim(),
        titleVi: state.titleVi.trim() || state.title.trim(),
        category: state.category,
        cefrLevel: state.cefrLevel,
        coverAssetId: state.coverAssetId,
        aiRoleName: state.aiRoleName.trim(),
        aiPersona: state.aiPersona.trim(),
        learnerRole: state.learnerRole.trim(),
        settingDescription: state.settingDescription.trim(),
        openingLine: state.openingLine.trim(),
        systemPrompt: state.systemPrompt.trim(),
        objectives: state.objectivesText
          .split('\n')
          .map((line) => line.trim())
          .filter(Boolean),
        ttsVoice: state.ttsVoice,
        targetTurns: Math.max(2, Number(state.targetTurns) || 8),
        estimatedMinutes: Math.max(1, Number(state.estimatedMinutes) || 5),
        accessMode: state.accessMode,
        requiredFeatureKey: state.accessMode === 'FEATURE' ? 'speaking.roleplay' : null,
        status: state.status,
      };
      return state.id
        ? updateSpeakingScenario(state.id, payload)
        : createSpeakingScenario(payload);
    },
    onSuccess: async (saved) => {
      await queryClient.invalidateQueries({ queryKey: speakingScenariosKey });
      setSelectedId(saved.id);
      setDialogOpen(false);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deleteSpeakingScenario(id),
    onSuccess: async () => {
      setDeleteTarget(null);
      setSelectedId(null);
      await queryClient.invalidateQueries({ queryKey: speakingScenariosKey });
    },
  });

  const turnMutation = useMutation({
    mutationFn: async (message: string) => {
      if (!selected) throw new Error('No scenario selected');
      const result = await previewSpeakingTurn(selected.id, history, message);
      return { message, result };
    },
    onSuccess: ({ message, result }) => {
      setHistory((prev) => [
        ...prev,
        { role: 'user', content: message },
        { role: 'assistant', content: result.assistantReply },
      ]);
      setLastTips(result.coachingTips ?? []);
      setLastMeta({ provider: result.provider, latencyMs: result.latencyMs });
      setDraftMessage('');
    },
  });

  function openNewDialog() {
    setForm(emptyForm());
    saveMutation.reset();
    setDialogOpen(true);
  }

  function openEditDialog(scenario: SpeakingScenario) {
    setForm(toForm(scenario));
    saveMutation.reset();
    setDialogOpen(true);
  }

  function resetPlayground() {
    if (!selected) return;
    setHistory(selected.openingLine ? [{ role: 'assistant', content: selected.openingLine }] : []);
    setLastTips([]);
    setLastMeta(null);
    setDraftMessage('');
    turnMutation.reset();
  }

  return (
    <section className="mx-auto grid w-full max-w-7xl gap-5">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-1">
          <h1 className="text-2xl font-semibold tracking-tight">{t('conversationsConsole.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('conversationsConsole.subtitle')}</p>
        </div>
        <Button type="button" onClick={openNewDialog}>
          <RiAddLine data-icon="inline-start" aria-hidden="true" />
          {t('conversationsConsole.createScenario')}
        </Button>
      </header>

      <CatalogError
        error={scenariosQuery.error}
        retry={() => void scenariosQuery.refetch()}
        retrying={scenariosQuery.isFetching}
      />

      {scenariosQuery.isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : !scenariosQuery.data || scenariosQuery.data.length === 0 ? (
        <Empty className="border py-12">
          <EmptyHeader>
            <RiChatVoiceAiLine className="size-8 text-muted-foreground" aria-hidden="true" />
            <EmptyTitle>{t('conversationsConsole.empty')}</EmptyTitle>
            <EmptyDescription>{t('conversationsConsole.emptyHelp')}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : (
        <div className="grid items-start gap-5 lg:grid-cols-[20rem_minmax(0,1fr)]">
          <aside className="grid gap-2 rounded-2xl border bg-card p-3">
            <div className="flex items-center justify-between px-2 py-1 text-xs font-medium text-muted-foreground">
              <span>{t('conversationsConsole.rosterTitle')}</span>
              <span className="tabular-nums">{scenariosQuery.data.length}</span>
            </div>
            <div className="grid gap-1.5">
              {scenariosQuery.data.map((scenario) => {
                const isSelected = selected?.id === scenario.id;
                return (
                  <button
                    key={scenario.id}
                    type="button"
                    aria-pressed={isSelected}
                    onClick={() => setSelectedId(scenario.id)}
                    className={cn(
                      'grid gap-1.5 rounded-xl border p-3 text-left transition-colors hover:bg-muted/60',
                      isSelected ? 'border-primary bg-muted/70' : 'border-transparent bg-muted/25',
                    )}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <span className="font-medium leading-snug">{scenario.titleVi || scenario.title}</span>
                      <div className="flex shrink-0 items-center gap-1">
                        {scenario.accessMode === 'FEATURE' ? (
                          <Badge variant="warning">
                            <RiVipCrownLine className="size-3" aria-hidden="true" />
                            PLUS
                          </Badge>
                        ) : (
                          <Badge variant="outline">FREE</Badge>
                        )}
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                      <Badge variant="secondary">{scenario.cefrLevel}</Badge>
                      <span>{scenario.category}</span>
                      <span>· {scenario.ttsVoice}</span>
                    </div>
                  </button>
                );
              })}
            </div>
          </aside>

          {selected ? (
            <div className="grid gap-5 xl:grid-cols-12">
              <Card className="xl:col-span-6">
                <CardHeader className="border-b">
                  <div className="flex flex-wrap items-start justify-between gap-2">
                    <div className="space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant="secondary">{selected.cefrLevel}</Badge>
                        <Badge variant={selected.status === 'PUBLISHED' ? 'success' : 'outline'}>
                          {selected.status}
                        </Badge>
                        {selected.accessMode === 'FEATURE' ? (
                          <Badge variant="warning">PLUS</Badge>
                        ) : null}
                      </div>
                      <CardTitle>{selected.title}</CardTitle>
                      <CardDescription>{selected.titleVi}</CardDescription>
                    </div>
                    <div className="flex items-center gap-1">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        onClick={() => openEditDialog(selected)}
                      >
                        <RiEditLine data-icon="inline-start" aria-hidden="true" />
                        {t('conversationsConsole.editScenario')}
                      </Button>
                      <Button
                        type="button"
                        size="icon-sm"
                        variant="ghost"
                        className="text-destructive"
                        aria-label={t('conversationsConsole.deleteScenario')}
                        onClick={() => setDeleteTarget(selected)}
                      >
                        <RiDeleteBinLine aria-hidden="true" />
                      </Button>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="space-y-4 pt-4 text-sm">
                  <div className="grid gap-3 sm:grid-cols-2">
                    <div className="rounded-xl border bg-muted/25 p-3">
                      <p className="text-xs font-medium text-muted-foreground">
                        {t('conversationsConsole.fields.aiRole')}
                      </p>
                      <p className="mt-0.5 font-medium">{selected.aiRoleName}</p>
                      <p className="mt-1 text-xs text-muted-foreground">{selected.aiPersona}</p>
                    </div>
                    <div className="rounded-xl border bg-muted/25 p-3">
                      <p className="text-xs font-medium text-muted-foreground">
                        {t('conversationsConsole.fields.learnerRole')}
                      </p>
                      <p className="mt-0.5 font-medium">{selected.learnerRole}</p>
                      <p className="mt-1 text-xs text-muted-foreground">
                        {t('conversationsConsole.turnsSummary', {
                          turns: selected.targetTurns,
                          minutes: selected.estimatedMinutes,
                        })}
                      </p>
                    </div>
                  </div>

                  <div>
                    <p className="text-xs font-medium text-muted-foreground">
                      {t('conversationsConsole.fields.setting')}
                    </p>
                    <p className="mt-1 text-sm">{selected.settingDescription}</p>
                  </div>

                  <div>
                    <p className="text-xs font-medium text-muted-foreground">
                      {t('conversationsConsole.fields.objectives')}
                    </p>
                    <ul className="mt-1 list-disc space-y-1 pl-4 text-xs text-muted-foreground">
                      {(selected.objectives ?? []).map((obj, idx) => (
                        <li key={idx}>{obj}</li>
                      ))}
                    </ul>
                  </div>

                  <div className="rounded-xl border bg-muted/20 p-3">
                    <div className="flex items-center justify-between text-xs text-muted-foreground">
                      <span>{t('conversationsConsole.fields.systemPrompt')}</span>
                      <span className="font-mono">{selected.ttsVoice}</span>
                    </div>
                    <p className="mt-1 text-xs leading-relaxed text-foreground/90">
                      {selected.systemPrompt}
                    </p>
                  </div>
                </CardContent>
              </Card>

              <Card className="xl:col-span-6">
                <CardHeader className="border-b">
                  <div className="flex items-center justify-between gap-2">
                    <div>
                      <CardTitle className="flex items-center gap-2 text-base">
                        <RiMicLine className="size-4 text-primary" aria-hidden="true" />
                        {t('conversationsConsole.playground.title')}
                      </CardTitle>
                      <CardDescription>{t('conversationsConsole.playground.help')}</CardDescription>
                    </div>
                    <Button type="button" size="sm" variant="outline" onClick={resetPlayground}>
                      {t('conversationsConsole.playground.reset')}
                    </Button>
                  </div>
                </CardHeader>
                <CardContent className="flex flex-col gap-4 pt-4">
                  <div className="max-h-80 space-y-2.5 overflow-y-auto rounded-xl border bg-muted/20 p-3">
                    {history.map((turn, idx) => (
                      <div
                        key={idx}
                        className={cn(
                          'flex flex-col gap-1 rounded-xl px-3 py-2 text-sm',
                          turn.role === 'assistant'
                            ? 'bg-card border mr-6'
                            : 'bg-primary text-primary-foreground ml-6',
                        )}
                      >
                        <span className="text-[11px] font-medium opacity-75">
                          {turn.role === 'assistant' ? selected.aiRoleName : selected.learnerRole}
                        </span>
                        <p className="leading-relaxed">{turn.content}</p>
                      </div>
                    ))}
                  </div>

                  {lastTips.length > 0 ? (
                    <div className="rounded-xl border bg-muted/30 p-3 text-xs">
                      <p className="flex items-center gap-1.5 font-medium">
                        <RiSparklingLine className="size-3.5 text-primary" aria-hidden="true" />
                        {t('conversationsConsole.playground.coachingTips')}
                      </p>
                      <ul className="mt-1.5 list-disc space-y-1 pl-4 text-muted-foreground">
                        {lastTips.map((tip, idx) => (
                          <li key={idx}>{tip}</li>
                        ))}
                      </ul>
                      {lastMeta ? (
                        <p className="mt-1.5 text-[11px] text-muted-foreground">
                          {lastMeta.provider} · {lastMeta.latencyMs}ms
                        </p>
                      ) : null}
                    </div>
                  ) : null}

                  <CatalogError error={turnMutation.error} />

                  <form
                    className="flex gap-2"
                    onSubmit={(event) => {
                      event.preventDefault();
                      const text = draftMessage.trim();
                      if (!text || turnMutation.isPending) return;
                      turnMutation.mutate(text);
                    }}
                  >
                    <Input
                      value={draftMessage}
                      onChange={(e) => setDraftMessage(e.target.value)}
                      placeholder={t('conversationsConsole.playground.inputPlaceholder')}
                      disabled={turnMutation.isPending}
                    />
                    <Button type="submit" disabled={turnMutation.isPending || !draftMessage.trim()}>
                      <RiSendPlane2Line data-icon="inline-start" aria-hidden="true" />
                      {t(
                        turnMutation.isPending
                          ? 'conversationsConsole.playground.sending'
                          : 'conversationsConsole.playground.send',
                      )}
                    </Button>
                  </form>
                </CardContent>
              </Card>
            </div>
          ) : null}
        </div>
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>
              {form.id
                ? t('conversationsConsole.editScenario')
                : t('conversationsConsole.createScenario')}
            </DialogTitle>
            <DialogDescription>{t('conversationsConsole.dialogHelp')}</DialogDescription>
          </DialogHeader>
          <CatalogError error={saveMutation.error} />
          <form
            id="speaking-scenario-form"
            className="grid gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              saveMutation.mutate(form);
            }}
          >
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.code')}</Label>
                <Input
                  required
                  value={form.code}
                  placeholder="CAFE_MORNING_RUSH"
                  className="font-mono text-xs"
                  onChange={(e) => setForm({ ...form, code: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.category')}</Label>
                <Select
                  value={form.category}
                  onValueChange={(v) => setForm({ ...form, category: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {categories.map((cat) => (
                      <SelectItem key={cat} value={cat}>
                        {cat}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.titleEn')}</Label>
                <Input
                  required
                  value={form.title}
                  onChange={(e) => setForm({ ...form, title: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.titleVi')}</Label>
                <Input
                  required
                  value={form.titleVi}
                  onChange={(e) => setForm({ ...form, titleVi: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('curriculumWorkspace.pathLevel')}</Label>
                <Select
                  value={form.cefrLevel}
                  onValueChange={(v) => setForm({ ...form, cefrLevel: v as SpeakingCefrLevel })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {cefrLevels.map((lvl) => (
                      <SelectItem key={lvl} value={lvl}>
                        {lvl}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.voice')}</Label>
                <Select
                  value={form.ttsVoice}
                  onValueChange={(v) => setForm({ ...form, ttsVoice: v })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {voices.map((v) => (
                      <SelectItem key={v} value={v}>
                        {v}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.aiRole')}</Label>
                <Input
                  required
                  value={form.aiRoleName}
                  onChange={(e) => setForm({ ...form, aiRoleName: e.target.value })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.learnerRole')}</Label>
                <Input
                  required
                  value={form.learnerRole}
                  onChange={(e) => setForm({ ...form, learnerRole: e.target.value })}
                />
              </div>
            </div>

            <div className="grid gap-1">
              <Label>{t('conversationsConsole.fields.aiPersona')}</Label>
              <Input
                value={form.aiPersona}
                onChange={(e) => setForm({ ...form, aiPersona: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('conversationsConsole.fields.setting')}</Label>
              <Textarea
                rows={2}
                value={form.settingDescription}
                onChange={(e) => setForm({ ...form, settingDescription: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('conversationsConsole.fields.openingLine')}</Label>
              <Textarea
                required
                rows={2}
                value={form.openingLine}
                onChange={(e) => setForm({ ...form, openingLine: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('conversationsConsole.fields.systemPrompt')}</Label>
              <Textarea
                required
                rows={3}
                value={form.systemPrompt}
                onChange={(e) => setForm({ ...form, systemPrompt: e.target.value })}
              />
            </div>
            <div className="grid gap-1">
              <Label>{t('conversationsConsole.fields.objectives')}</Label>
              <Textarea
                rows={3}
                value={form.objectivesText}
                onChange={(e) => setForm({ ...form, objectivesText: e.target.value })}
              />
            </div>

            <div className="grid gap-3 sm:grid-cols-3">
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.targetTurns')}</Label>
                <Input
                  type="number"
                  min={2}
                  max={25}
                  value={form.targetTurns}
                  onChange={(e) => setForm({ ...form, targetTurns: Number(e.target.value) || 8 })}
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('conversationsConsole.fields.estimatedMinutes')}</Label>
                <Input
                  type="number"
                  min={1}
                  max={30}
                  value={form.estimatedMinutes}
                  onChange={(e) =>
                    setForm({ ...form, estimatedMinutes: Number(e.target.value) || 5 })
                  }
                />
              </div>
              <div className="grid gap-1">
                <Label>{t('curriculumWorkspace.status')}</Label>
                <Select
                  value={form.status}
                  onValueChange={(v) => setForm({ ...form, status: v as SpeakingScenarioStatus })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="DRAFT">DRAFT</SelectItem>
                    <SelectItem value="PUBLISHED">PUBLISHED</SelectItem>
                    <SelectItem value="ARCHIVED">ARCHIVED</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <label className="flex items-center justify-between rounded-xl border bg-muted/20 px-3 py-2 text-sm">
              <span>{t('conversationsConsole.fields.plusOnly')}</span>
              <Switch
                checked={form.accessMode === 'FEATURE'}
                onCheckedChange={(checked) =>
                  setForm({
                    ...form,
                    accessMode: checked ? 'FEATURE' : 'PUBLIC',
                    requiredFeatureKey: checked ? 'speaking.roleplay' : null,
                  })
                }
              />
            </label>
          </form>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDialogOpen(false)}>
              {t('catalog.cancel')}
            </Button>
            <Button type="submit" form="speaking-scenario-form" disabled={saveMutation.isPending}>
              {t(saveMutation.isPending ? 'catalog.saving' : 'catalog.save')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('conversationsConsole.deleteScenario')}</DialogTitle>
            <DialogDescription>
              {t('conversationsConsole.deleteConfirm', { name: deleteTarget?.title ?? '' })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setDeleteTarget(null)}>
              {t('catalog.cancel')}
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={deleteMutation.isPending || !deleteTarget}
              onClick={() => deleteTarget && deleteMutation.mutate(deleteTarget.id)}
            >
              {t('curriculumWorkspace.confirmDelete')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
