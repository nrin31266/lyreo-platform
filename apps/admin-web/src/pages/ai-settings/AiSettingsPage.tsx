import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslation } from "react-i18next";
import { RiCheckLine as Check, RiKey2Line as KeyRound, RiLoader4Line as LoaderCircle, RiPencilLine as Pencil, RiAddLine as Plus, RiRefreshLine as RefreshCw, RiDeleteBinLine as Trash2 } from "@remixicon/react";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import {
  Empty,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
  EmptyDescription,
} from "@/components/ui/empty";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldError,
} from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
} from "@/components/ui/table";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import {
  Select,
  SelectTrigger,
  SelectValue,
  SelectContent,
  SelectGroup,
  SelectItem,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
import {
  fetchAiProviders,
  fetchAiRoutes,
  saveAiProvider,
  removeAiProvider,
  saveAiRoute,
} from "./ai-settings.api";
import type { Provider, Route, SaveRoutePayload } from "./ai-settings.types";
import "./ai-settings.css";

const presets = {
  GROQ: { displayName: "Groq", baseUrl: "https://api.groq.com/openai/v1" },
  GEMINI: {
    displayName: "Gemini",
    baseUrl: "https://generativelanguage.googleapis.com/v1beta",
  },
  QWEN: { displayName: "Qwen · Local", baseUrl: "" },
  LOCAL_NLP: { displayName: "NLP · Local", baseUrl: "" },
};
const isLocal = (code: string) =>
  [
    "LOCAL_QWEN",
    "LOCAL_NLP",
    "LOCAL_KOKORO",
    "QWEN",
    "QWEN3",
    "QWEN3_ASR",
    "QWEN3_FORCED_ALIGNER",
    "KOKORO",
  ].includes(code);
const capabilities = [
  "STT",
  "ALIGNMENT",
  "GENERAL_LLM",
  "REASONING_LLM",
  "TTS",
  "PRONUNCIATION_JUDGE",
  "NLP",
];
const providerSchema = z.object({
  providerCode: z
    .string()
    .trim()
    .regex(/^[A-Z][A-Z0-9_]*$/)
    .max(50),
  displayName: z.string().trim().min(1).max(200),
  baseUrl: z.union([z.literal(""), z.url()]),
  apiKey: z.string(),
  enabled: z.boolean(),
});
const routeSchema = z.object({
  capability: z.enum(capabilities as [string, ...string[]]),
  providerCode: z.string().min(1),
  model: z.string().trim().min(1),
  priority: z.number().int().min(0),
  fallback: z.boolean(),
  enabled: z.boolean(),
});

export function AiSettingsPage() {
  const { t } = useTranslation(["admin", "common"]);
  const location = useLocation();
  const navigate = useNavigate();
  const activeTab = location.hash === "#routes" ? "routes" : "providers";
  const client = useQueryClient();
  const providersQuery = useQuery({
    queryKey: ["ai", "providers"],
    queryFn: fetchAiProviders,
  });
  const routesQuery = useQuery({
    queryKey: ["ai", "routes"],
    queryFn: fetchAiRoutes,
  });
  const providers = providersQuery.data ?? [];
  const [preset, setPreset] = useState("GROQ");
  const [showInactive, setShowInactive] = useState(false);
  const [removing, setRemoving] = useState<Provider | null>(null);
  const [routeOpen, setRouteOpen] = useState(false);
  const [editingRoute, setEditingRoute] = useState(false);
  const [notice, setNotice] = useState("");
  const form = useForm<z.infer<typeof providerSchema>>({
    resolver: zodResolver(providerSchema),
    defaultValues: {
      providerCode: "GROQ",
      ...presets.GROQ,
      apiKey: "",
      enabled: true,
    },
  });
  const routeForm = useForm<SaveRoutePayload>({
    resolver: zodResolver(routeSchema),
    defaultValues: {
      capability: "GENERAL_LLM",
      providerCode: "",
      model: "",
      priority: 100,
      fallback: false,
      enabled: true,
    },
  });
  // Load saved metadata without ever reading the stored credential.
  useEffect(() => {
    if (preset === "CUSTOM") return;
    const saved = providersQuery.data?.find((p) => p.code === preset);
    const defaults = presets[preset as keyof typeof presets];
    form.reset({
      providerCode: preset,
      displayName: saved?.display_name ?? defaults.displayName,
      baseUrl: saved?.base_url ?? defaults.baseUrl,
      apiKey: "",
      enabled: saved?.enabled ?? true,
    });
  }, [preset, providersQuery.data, form]);
  async function refreshed(message: string) {
    setNotice(message);
    await client.invalidateQueries({ queryKey: ["ai"] });
  }
  const save = useMutation({
    mutationFn: ({
      providerCode,
      apiKey,
      ...payload
    }: z.infer<typeof providerSchema>) =>
      saveAiProvider(providerCode, {
        ...payload,
        apiKey: apiKey.trim() || null,
      }),
    onSuccess: () => {
      form.setValue("apiKey", "");
      return refreshed(t("aiSettings.saved"));
    },
  });
  const remove = useMutation({
    mutationFn: removeAiProvider,
    onSuccess: () => {
      setRemoving(null);
      return refreshed(t("aiSettings.removed"));
    },
  });
  const routeSave = useMutation({
    mutationFn: saveAiRoute,
    onSuccess: () => {
      setRouteOpen(false);
      return refreshed(t("aiSettings.saved"));
    },
  });
  function editProvider(provider: Provider) {
    setNotice("");
    save.reset();
    setPreset(provider.code in presets ? provider.code : "CUSTOM");
    form.reset({
      providerCode: provider.code,
      displayName: provider.display_name,
      baseUrl: provider.base_url ?? "",
      apiKey: "",
      enabled: provider.enabled,
    });
    document.getElementById("provider-preset")?.focus();
  }
  function editRoute(route?: Route) {
    routeSave.reset();
    setEditingRoute(!!route);
    routeForm.reset(
      route
        ? {
            capability: route.capability,
            providerCode: route.provider,
            model: route.model,
            priority: route.priority,
            fallback: route.is_fallback,
            enabled: route.enabled,
          }
        : {
            capability: "GENERAL_LLM",
            providerCode: providers.find((p) => p.enabled)?.code ?? "",
            model: "",
            priority: 100,
            fallback: false,
            enabled: true,
          },
    );
    setRouteOpen(true);
  }
  const visibleProviders = providers.filter(
    (p) => showInactive || p.enabled || p.configured,
  );
  const visibleRoutes = (routesQuery.data ?? []).filter(
    (r) =>
      showInactive ||
      (r.enabled && providers.some((p) => p.code === r.provider && p.enabled)),
  );
  const selected = providers.find((p) => p.code === form.watch("providerCode"));
  const local = isLocal(form.watch("providerCode"));
  const saving = save.isPending;
  return (
    <section className="ai-settings-page">
      <header className="settings-heading">
        <div>
          <h1>{t("aiSettings.title")}</h1>
          <p className="lead">{t("aiSettings.lead")}</p>
        </div>
        <Button
          variant="outline"
          size="icon"
          aria-label={t("lessonAuthoring.refresh")}
          title={t("lessonAuthoring.refresh")}
          disabled={providersQuery.isFetching || routesQuery.isFetching}
          onClick={() => void client.invalidateQueries({ queryKey: ["ai"] })}
        >
          <RefreshCw
            className={
              providersQuery.isFetching || routesQuery.isFetching
                ? "animate-spin"
                : ""
            }
          />
        </Button>
      </header>
      {notice && (
        <Alert role="status">
          <Check />
          <AlertDescription>{notice}</AlertDescription>
        </Alert>
      )}
      <Tabs
        className="settings-tabs"
        value={activeTab}
        onValueChange={(value) =>
          navigate(
            { pathname: location.pathname, hash: `#${value}` },
            { replace: true },
          )
        }
      >
        <div className="settings-tabbar">
          <TabsList aria-label={t("navigation.aiSettings")}>
            <TabsTrigger value="providers">
              {t("aiSettings.providers")}
            </TabsTrigger>
            <TabsTrigger value="routes">{t("navigation.routes")}</TabsTrigger>
          </TabsList>
          <div className="settings-inactive-toggle">
            <Switch
              id="inactive-providers"
              checked={showInactive}
              onCheckedChange={setShowInactive}
            />
            <label htmlFor="inactive-providers">
              {t("aiSettings.showInactive")}
            </label>
          </div>
        </div>
        <TabsContent value="providers" className="settings-tabpanel">
          <div className="settings-workspace">
            <Card id="providers">
              <CardHeader>
                <CardTitle>{t("aiSettings.providers")}</CardTitle>
                <CardDescription>
                  {t("aiSettings.providerHint")}
                </CardDescription>
              </CardHeader>
              <CardContent className="settings-scroll-region">
                <CatalogError
                  error={providersQuery.error}
                  retry={() => void providersQuery.refetch()}
                />
                {providersQuery.isPending ? (
                  <SettingsSkeleton label={t("common:status.loading")} />
                ) : (
                  !providersQuery.isError &&
                  (visibleProviders.length ? (
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>
                            {t("aiSettings.table.provider")}
                          </TableHead>
                          <TableHead>
                            {t("aiSettings.table.credential")}
                          </TableHead>
                          <TableHead>{t("aiSettings.table.status")}</TableHead>
                          <TableHead>
                            <span className="sr-only">
                              {t("aiSettings.actions")}
                            </span>
                          </TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {visibleProviders.map((p) => (
                          <TableRow key={p.id}>
                            <TableCell>
                              <strong>{p.display_name}</strong>
                              <span className="provider-code">{p.code}</span>
                            </TableCell>
                            <TableCell>
                              {p.configured
                                ? `•••• ${p.key_last4 || "—"}`
                                : isLocal(p.code)
                                  ? t("aiSettings.noKeyNeeded")
                                  : "—"}
                            </TableCell>
                            <TableCell>
                              <Badge
                                variant={
                                  !p.enabled
                                    ? "secondary"
                                    : p.configured || isLocal(p.code)
                                      ? "success"
                                      : "outline"
                                }
                              >
                                {t(
                                  !p.enabled
                                    ? "aiSettings.disabled"
                                    : p.configured || isLocal(p.code)
                                      ? "aiSettings.enabled"
                                      : "aiSettings.notConfigured",
                                )}
                              </Badge>
                            </TableCell>
                            <TableCell>
                              <div className="flex justify-end gap-2">
                                <Button
                                  variant="ghost"
                                  size="icon"
                                  aria-label={t("aiSettings.editProvider", {
                                    name: p.display_name,
                                  })}
                                  title={t("aiSettings.editProvider", {
                                    name: p.display_name,
                                  })}
                                  onClick={() => editProvider(p)}
                                >
                                  <Pencil />
                                </Button>
                                <Button
                                  variant="destructive"
                                  size="icon"
                                  aria-label={t("aiSettings.removeProvider", {
                                    name: p.display_name,
                                  })}
                                  title={t("aiSettings.removeProvider", {
                                    name: p.display_name,
                                  })}
                                  onClick={() => {
                                    remove.reset();
                                    setRemoving(p);
                                  }}
                                >
                                  <Trash2 />
                                </Button>
                              </div>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  ) : (
                    <SettingsEmpty
                      title={t("aiSettings.noProviders")}
                      description={t("aiSettings.providerHint")}
                    />
                  ))
                )}
              </CardContent>
            </Card>
            <Card id="provider-config">
              <CardHeader>
                <CardTitle>{t("aiSettings.configure")}</CardTitle>
                <CardDescription>
                  {t("aiSettings.encryptionNote")}
                </CardDescription>
              </CardHeader>
              <CardContent className="settings-scroll-region">
                <form
                  id="ai-provider-form"
                  onSubmit={form.handleSubmit((values) => {
                    setNotice("");
                    save.mutate(values);
                  })}
                >
                  <FieldGroup>
                    <Field>
                      <FieldLabel htmlFor="provider-preset">
                        {t("aiSettings.table.provider")}
                      </FieldLabel>
                      <Select
                        value={preset}
                        disabled={saving}
                        onValueChange={(value) => {
                          setPreset(value);
                          save.reset();
                          setNotice("");
                          form.reset({
                            providerCode: value === "CUSTOM" ? "" : value,
                            displayName: "",
                            baseUrl: "",
                            apiKey: "",
                            enabled: true,
                          });
                        }}
                      >
                        <SelectTrigger id="provider-preset" className="w-full">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectGroup>
                            {Object.entries(presets).map(([code, config]) => (
                              <SelectItem key={code} value={code}>
                                {config.displayName}
                              </SelectItem>
                            ))}
                            <SelectItem value="CUSTOM">
                              {t("aiSettings.custom")}
                            </SelectItem>
                          </SelectGroup>
                        </SelectContent>
                      </Select>
                    </Field>
                    {preset === "CUSTOM" &&
                      (["providerCode", "displayName", "baseUrl"] as const).map(
                        (key) => (
                          <Field
                            key={key}
                            data-invalid={!!form.formState.errors[key]}
                          >
                            <FieldLabel htmlFor={`provider-${key}`}>
                              {t(
                                `aiSettings.fields.${key === "providerCode" ? "code" : key}`,
                              )}
                            </FieldLabel>
                            <Input
                              id={`provider-${key}`}
                              {...form.register(key)}
                              disabled={saving}
                              aria-invalid={!!form.formState.errors[key]}
                            />
                            <FieldError>
                              {form.formState.errors[key] &&
                                t("catalog.invalidField")}
                            </FieldError>
                          </Field>
                        ),
                      )}
                    {local ? (
                      <p className="text-sm text-muted-foreground">
                        {t(
                          preset === "LOCAL_NLP"
                            ? "aiSettings.nlpHint"
                            : "aiSettings.localHint",
                        )}
                      </p>
                    ) : (
                      <Field>
                        <FieldLabel htmlFor="provider-apiKey">
                          {t("aiSettings.fields.apiKey")}
                        </FieldLabel>
                        <Input
                          id="provider-apiKey"
                          type="password"
                          autoComplete="new-password"
                          disabled={saving}
                          {...form.register("apiKey")}
                          placeholder={t(
                            selected?.configured
                              ? "aiSettings.fields.apiKeyPlaceholder"
                              : "aiSettings.enterKey",
                          )}
                        />
                      </Field>
                    )}
                    <Field orientation="horizontal">
                      <FieldLabel htmlFor="provider-enabled">
                        {t("aiSettings.fields.enabled")}
                      </FieldLabel>
                      <Switch
                        id="provider-enabled"
                        disabled={saving}
                        checked={form.watch("enabled")}
                        onCheckedChange={(v) => form.setValue("enabled", v)}
                      />
                    </Field>
                  </FieldGroup>
                  <CatalogError error={save.error} />
                </form>
              </CardContent>

              <CardFooter>
                <Button
                  className="w-full"
                  form="ai-provider-form"
                  type="submit"
                  disabled={saving}
                >
                  {saving && <LoaderCircle className="animate-spin" />}
                  {saving ? t("common:status.saving") : t("aiSettings.save")}
                </Button>
              </CardFooter>
            </Card>
          </div>
        </TabsContent>
        <TabsContent
          value="routes"
          className="settings-tabpanel settings-routes-panel"
        >
          <Card id="routes">
            <CardHeader className="settings-card-heading">
              <div>
                <CardTitle>{t("aiSettings.routes")}</CardTitle>
                <CardDescription>
                  {t("aiSettings.modelNamesNote")}
                </CardDescription>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => editRoute()}
                disabled={!providers.some((p) => p.enabled)}
              >
                <Plus />
                {t("aiSettings.addRoute")}
              </Button>
            </CardHeader>
            <CardContent className="settings-scroll-region">
              <CatalogError
                error={routesQuery.error}
                retry={() => void routesQuery.refetch()}
              />
              {routesQuery.isPending ? (
                <SettingsSkeleton label={t("common:status.loading")} />
              ) : (
                !routesQuery.isError &&
                (visibleRoutes.length ? (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        {[
                          "capability",
                          "provider",
                          "model",
                          "priority",
                          "status",
                        ].map((key) => (
                          <TableHead key={key}>
                            {t(`aiSettings.table.${key}`)}
                          </TableHead>
                        ))}
                        <TableHead>
                          <span className="sr-only">
                            {t("aiSettings.actions")}
                          </span>
                        </TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {visibleRoutes.map((r) => (
                        <TableRow key={r.id}>
                          <TableCell>
                            {t(`aiSettings.capabilities.${r.capability}`, {
                              defaultValue: r.capability,
                            })}
                          </TableCell>
                          <TableCell>{r.provider}</TableCell>
                          <TableCell>{r.model || "—"}</TableCell>
                          <TableCell>{r.priority}</TableCell>
                          <TableCell>
                            <Badge
                              variant={
                                !r.enabled
                                  ? "secondary"
                                  : r.is_fallback
                                    ? "outline"
                                    : "default"
                              }
                            >
                              {t(
                                !r.enabled
                                  ? "aiSettings.disabled"
                                  : r.is_fallback
                                    ? "aiSettings.table.fallback"
                                    : "aiSettings.primaryRoute",
                              )}
                            </Badge>
                          </TableCell>
                          <TableCell>
                            <Button
                              variant="ghost"
                              size="icon"
                              aria-label={t("aiSettings.editRoute", {
                                name: r.model,
                              })}
                              title={t("aiSettings.editRoute", {
                                name: r.model,
                              })}
                              onClick={() => editRoute(r)}
                            >
                              <Pencil />
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                ) : (
                  <SettingsEmpty
                    title={t("aiSettings.noRoutes")}
                    description={t("aiSettings.modelNamesNote")}
                  />
                ))
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
      <Dialog
        open={!!removing}
        onOpenChange={(open) => {
          if (!open && !remove.isPending) setRemoving(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {t("aiSettings.removeProvider", { name: removing?.display_name })}
            </DialogTitle>
            <DialogDescription>
              {t("aiSettings.removeDescription")}
            </DialogDescription>
          </DialogHeader>
          <CatalogError error={remove.error} />
          <DialogFooter>
            <Button
              variant="outline"
              disabled={remove.isPending}
              onClick={() => setRemoving(null)}
            >
              {t("aiSettings.cancel")}
            </Button>
            <Button
              variant="destructive"
              disabled={remove.isPending}
              onClick={() => removing && remove.mutate(removing.code)}
            >
              {remove.isPending ? (
                <LoaderCircle className="animate-spin" />
              ) : (
                <Trash2 />
              )}
              {t("aiSettings.confirmRemove")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={routeOpen}
        onOpenChange={(open) => {
          if (!routeSave.isPending) setRouteOpen(open);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("aiSettings.routeConfiguration")}</DialogTitle>
            <DialogDescription>
              {t("aiSettings.modelNamesNote")}
            </DialogDescription>
          </DialogHeader>
          <form
            onSubmit={routeForm.handleSubmit((values) =>
              routeSave.mutate(values),
            )}
          >
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor="route-capability">
                  {t("aiSettings.table.capability")}
                </FieldLabel>
                <Select
                  value={routeForm.watch("capability")}
                  disabled={editingRoute || routeSave.isPending}
                  onValueChange={(v) => routeForm.setValue("capability", v)}
                >
                  <SelectTrigger id="route-capability" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {capabilities.map((c) => (
                        <SelectItem key={c} value={c}>
                          {t(`aiSettings.capabilities.${c}`)}
                        </SelectItem>
                      ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>
              <Field>
                <FieldLabel htmlFor="route-provider">
                  {t("aiSettings.table.provider")}
                </FieldLabel>
                <Select
                  value={routeForm.watch("providerCode")}
                  disabled={editingRoute || routeSave.isPending}
                  onValueChange={(v) => routeForm.setValue("providerCode", v)}
                >
                  <SelectTrigger id="route-provider" className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectGroup>
                      {providers
                        .filter(
                          (p) =>
                            p.enabled ||
                            p.code === routeForm.watch("providerCode"),
                        )
                        .map((p) => (
                          <SelectItem key={p.code} value={p.code}>
                            {p.display_name}
                          </SelectItem>
                        ))}
                    </SelectGroup>
                  </SelectContent>
                </Select>
              </Field>
              <Field data-invalid={!!routeForm.formState.errors.model}>
                <FieldLabel htmlFor="route-model">
                  {t("aiSettings.table.model")}
                </FieldLabel>
                <Input
                  id="route-model"
                  {...routeForm.register("model")}
                  readOnly={editingRoute}
                  disabled={routeSave.isPending}
                  aria-invalid={!!routeForm.formState.errors.model}
                />
                <FieldError>
                  {routeForm.formState.errors.model &&
                    t("catalog.invalidField")}
                </FieldError>
              </Field>
              <Field data-invalid={!!routeForm.formState.errors.priority}>
                <FieldLabel htmlFor="route-priority">
                  {t("aiSettings.table.priority")}
                </FieldLabel>
                <Input
                  id="route-priority"
                  type="number"
                  min={0}
                  step={1}
                  disabled={routeSave.isPending}
                  {...routeForm.register("priority", { valueAsNumber: true })}
                  aria-invalid={!!routeForm.formState.errors.priority}
                />
                <FieldError>
                  {routeForm.formState.errors.priority &&
                    t("catalog.invalidField")}
                </FieldError>
              </Field>
              {(["fallback", "enabled"] as const).map((key) => (
                <Field key={key} orientation="horizontal">
                  <FieldLabel htmlFor={`route-${key}`}>
                    {t(
                      key === "fallback"
                        ? "aiSettings.table.fallback"
                        : "aiSettings.fields.enabled",
                    )}
                  </FieldLabel>
                  <Switch
                    id={`route-${key}`}
                    disabled={routeSave.isPending}
                    checked={routeForm.watch(key)}
                    onCheckedChange={(v) => routeForm.setValue(key, v)}
                  />
                </Field>
              ))}
            </FieldGroup>
            <CatalogError error={routeSave.error} />
            <DialogFooter className="mt-5">
              <Button
                type="button"
                variant="outline"
                disabled={routeSave.isPending}
                onClick={() => setRouteOpen(false)}
              >
                {t("aiSettings.cancel")}
              </Button>
              <Button type="submit" disabled={routeSave.isPending}>
                {routeSave.isPending && (
                  <LoaderCircle className="animate-spin" />
                )}
                {t("aiSettings.saveRoute")}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </section>
  );
}
function SettingsSkeleton({ label }: { label: string }) {
  return (
    <div role="status" aria-label={label} className="grid gap-4">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-12 w-full" />
      ))}
    </div>
  );
}
function SettingsEmpty({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <Empty>
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <KeyRound />
        </EmptyMedia>
        <EmptyTitle>{title}</EmptyTitle>
        <EmptyDescription>{description}</EmptyDescription>
      </EmptyHeader>
    </Empty>
  );
}
