import { ProcessingStatus } from "./ProcessingStatus";
import { AuthoringPrompt } from "./AuthoringPrompt";
import { CreateMetadataFields } from "./CreateMetadataFields";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { CreateMediaLessonPage } from "./CreateMediaLessonPage";
import "./lesson-builder.css";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useNavigate, useSearchParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { RiLink as Link2, RiSearchLine as Search, RiLoader4Line as LoaderCircle, RiErrorWarningLine as AlertCircle } from "@remixicon/react";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle, EmptyDescription } from "@/components/ui/empty";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { InputGroup, InputGroupInput, InputGroupAddon, InputGroupButton } from "@/components/ui/input-group";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldError,
} from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Accordion,
  AccordionItem,
  AccordionTrigger,
  AccordionContent,
} from "@/components/ui/accordion";
import { ApiError } from "@/lib/api";
import { env } from "@/config/env";
import { SourceRangeEditor } from "./SourceRangeEditor";
import { defaultRange, sourceOffset, timecode } from "./timecode";
import { validSelection } from "./timeline-view";
import * as authoring from "./lesson-builder.api";
import type { SourceInspection } from "./lesson-builder.types";
import { createMetadataSchema as metadataSchema } from "@/pages/lessons/catalog.api";
import { useCategories, catalogKeys } from "@/pages/lessons/catalog.queries";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
const sourceSchema = z.object({
  sourceReference: z.string().trim().pipe(z.url()),
});
function CreateYouTubePage({ onDirtyChange }: { onDirtyChange: (dirty: boolean) => void }) {
  const { t } = useTranslation(["admin", "common"]);
  const navigate = useNavigate();
  const client = useQueryClient();
  const categories = useCategories();
  const sourceForm = useForm<z.infer<typeof sourceSchema>>({
    resolver: zodResolver(sourceSchema),
    defaultValues: { sourceReference: "" },
  });
  const metadata = useForm<z.infer<typeof metadataSchema>>({
    resolver: zodResolver(metadataSchema),
    defaultValues: {
      title: "",
      description: "",
      imageUrl: "",
      categoryIds: [],
    },
  });
  const sourceReference = sourceForm.watch("sourceReference");
  useEffect(() => { onDirtyChange(!!sourceReference.trim() || metadata.formState.isDirty); }, [sourceReference, metadata.formState.isDirty, onDirtyChange]);
  const inspectionVersion = useRef(0);
  const inspectionInFlight = useRef(false);
  const lastInspectedReference = useRef<string | null>(null);
  const [selectionVersion, setSelectionVersion] = useState(0);
  const [inspection, setInspection] = useState<SourceInspection | null>(null);
  const [startMs, setStartMs] = useState<number | null>(null);
  const [endMs, setEndMs] = useState<number | null>(null);
  const [rangeInputValid, setRangeInputValid] = useState(false);
  const currentRange = useRef({ startMs, endMs });
  currentRange.current = { startMs, endMs };
  const [error, setError] = useState("");
  const [inspectError, setInspectError] = useState("");
  const inspectionMutation = useMutation({
    mutationFn: authoring.inspectSource,
  });
  const createMutation = useMutation({ mutationFn: authoring.createDraft });
  async function inspectReference(reference: string) {
    if (inspectionInFlight.current) return;
    inspectionInFlight.current = true;
    const version = ++inspectionVersion.current;
    reference = reference.trim();
    setError("");
    setInspectError("");
    try {
      if (!(await sourceForm.trigger()) || version !== inspectionVersion.current) return;
      const next = await inspectionMutation.mutateAsync(reference);
      if (version !== inspectionVersion.current) return;
      const refresh = reference === lastInspectedReference.current;
      setInspection(next);
      const range = currentRange.current;
      if (!refresh || (range.startMs !== null && (range.startMs < 0 || range.startMs >= next.durationMs)) ||
        (range.endMs !== null && (range.startMs === null || !validSelection(range.startMs, range.endMs, next.durationMs)))) {
        const [start, end] = defaultRange(next.durationMs, env.youtubeAutoSelectMaxMinutes);
        setStartMs(start);
        setEndMs(end);
        setRangeInputValid(start !== null && end !== null);
        setSelectionVersion(value => value + 1);
      }
      lastInspectedReference.current = reference;
    } catch (cause) {
      if (version !== inspectionVersion.current) return;
      const code = cause instanceof ApiError ? cause.code : undefined;
      const known =
        code &&
        [
          "YOUTUBE_MEDIA_TOOL_DISABLED",
          "YOUTUBE_MEDIA_TOOL_UNAVAILABLE",
          "YOUTUBE_MEDIA_TIMEOUT",
          "YOUTUBE_MEDIA_FAILED",
          "YOUTUBE_MEDIA_INTERRUPTED",
        ].includes(code);
      const message = known
        ? t(`lessonAuthoring.sourceErrors.${code}`)
        : t("lessonAuthoring.sourceErrors.generic");
      setInspectError(
        cause instanceof ApiError && cause.correlationId
          ? `${message} · ${t("lessonAuthoring.requestId", { id: cause.correlationId })}`
          : message,
      );
    } finally {
      inspectionInFlight.current = false;
    }
  }
  useEffect(() => () => { inspectionVersion.current += 1; }, []);
  async function create(event: FormEvent) {
    event.preventDefault();
    if (inspectionInFlight.current) return;
    const version = inspectionVersion.current;
    setError("");
    if (!(await metadata.trigger())) return;
    if (version !== inspectionVersion.current) return;
    if (
      !inspection ||
      !rangeInputValid ||
      startMs === null || endMs === null ||
      !validSelection(startMs, endMs, inspection.durationMs)
    ) {
      setError(t("lessonAuthoring.invalidRange"));
      return;
    }
    try {
      const result = await createMutation.mutateAsync({
        ...metadata.getValues(),
        sourceReference: sourceReference.trim(),
        startMs,
        endMs,
      });
      await client.invalidateQueries({ queryKey: catalogKeys.all });
      navigate(`/lessons/new?draft=${result.lessonId}`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }
  const overlap = startMs !== null && endMs !== null && inspection?.usedRanges.some(
    (range) => startMs < range.endMs && endMs > range.startMs,
  );
  return (
    <section className="authoring-page source-page">
      {inspectionMutation.isPending && <ProcessingStatus label={t("twoStep.inspecting")} />}
      {createMutation.isPending && <ProcessingStatus label={t("twoStep.preparing")} />}
      <form
        className="source-url-form"
        onSubmit={(event) => {
          event.preventDefault();
          void inspectReference(sourceReference);
        }}
      >
        <Field
          data-invalid={
            !!inspectError || !!sourceForm.formState.errors.sourceReference
          }
        >
          <FieldLabel htmlFor="youtube-source" >
            {t("lessonAuthoring.youtubeUrl")}
          </FieldLabel>
          <InputGroup>
          <InputGroupInput
            id="youtube-source"
            type="url"
            required
            value={sourceReference}
            aria-invalid={
              !!inspectError || !!sourceForm.formState.errors.sourceReference
            }
            onChange={(event) => {
              inspectionVersion.current += 1;
              sourceForm.setValue("sourceReference", event.target.value);
              setInspection(null);
              setInspectError("");
              setError("");
            }}
            placeholder="https://www.youtube.com/watch?v=…"
          />
          <InputGroupAddon align="inline-end">
            <InputGroupButton type="submit" variant="secondary" disabled={inspectionMutation.isPending || !sourceReference.trim()}>
              {inspectionMutation.isPending ? <LoaderCircle className="animate-spin" /> : <Search />}
              {t("lessonAuthoring.inspect")}
            </InputGroupButton>
          </InputGroupAddon>
          </InputGroup>
          <FieldError>
            {sourceForm.formState.errors.sourceReference
              ? t("catalog.invalidField")
              : inspection && inspectError}
          </FieldError>
        </Field>
      </form>
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
      <div className={`source-workspace ${!inspection && !inspectionMutation.isPending ? "source-workspace-empty" : ""}`}>
        <div className="source-media-pane">
          {inspection?.embedAllowed ? (
            <SourceRangeEditor
              key={`${inspection.videoId}:${selectionVersion}`}
              thumbnailUrl={inspection.thumbnailUrl}
              videoId={inspection.videoId}
              durationMs={inspection.durationMs}
              initialPositionMs={sourceOffset(inspection.durationMs, sourceReference.trim())}
              startMs={startMs}
              endMs={endMs}
              onValidityChange={setRangeInputValid}
              onRangeChange={(a, b) => {
                setStartMs(a);
                setEndMs(b);
              }}
            />
          ) : inspection && !inspection.embedAllowed ? (
            <Alert variant="destructive">
              <AlertDescription>
                {t("lessonAuthoring.embedBlocked")}
              </AlertDescription>
            </Alert>
          ) : inspectionMutation.isPending ? (
            <div role="status" aria-label={t("loading")} className="grid gap-3">
              <Skeleton className="aspect-video max-h-64" />
              <Skeleton className="h-20" />
            </div>
          ) : (
            <Empty className="source-empty" role={inspectError ? "alert" : undefined}>
              <EmptyHeader>
                <EmptyMedia variant="icon">{inspectError ? <AlertCircle className="text-destructive" /> : <Link2 />}</EmptyMedia>
                <EmptyTitle>{t(inspectError ? "sourceWorkspace.sourceFailed" : "sourceWorkspace.emptyTitle")}</EmptyTitle>
                <EmptyDescription>{inspectError || t("sourceWorkspace.pasteSource")}</EmptyDescription>
              </EmptyHeader>
            </Empty>
          )}
        </div>
        {(inspection || inspectionMutation.isPending) && <aside className="source-metadata-pane">
          <h2 className="source-section-title">{t("reviewWorkspace.lessonInformation")}</h2>
          {inspectionMutation.isPending && !inspection ? <div role="status" aria-label={t("loading")} className="grid gap-5"><Skeleton className="h-5 w-1/2" /><Skeleton className="h-10" /><Skeleton className="aspect-video" /><Skeleton className="h-20" /></div> : <form id="lesson-create-form" onSubmit={create} noValidate>
            <FieldGroup>
              <CreateMetadataFields form={metadata} titleHint={inspection?.title} imageHint={inspection?.thumbnailUrl || undefined} />
              {overlap && <p role="status" className="text-sm text-muted-foreground">{t("lessonAuthoring.overlap")}</p>}
              {inspection && <>
                  <Accordion type="single" collapsible className="source-used-ranges"><AccordionItem value="used-ranges">
                    <AccordionTrigger className="gap-2 px-3 py-2"><span>{t("lessonAuthoring.usedRanges")} <span className="text-muted-foreground">{inspection.usedRanges.length}</span></span></AccordionTrigger><AccordionContent>
                    {!inspection.usedRanges.length && <p className="text-sm text-muted-foreground">{t("authoringUi.noUsedRanges")}</p>}
                    <div className="source-used-list">{inspection.usedRanges.map(range => {
                      const intersects = startMs !== null && endMs !== null && startMs < range.endMs && endMs > range.startMs;
                      return <article key={range.lessonId} className="source-used-item">
                        <Link className="font-medium hover:underline" to={`/lessons/${range.lessonId}`}>{range.title}</Link>
                        <div className="flex items-center justify-between gap-2">
                          <Button type="button" variant="ghost" size="sm" onClick={() => { setStartMs(range.startMs); setEndMs(range.endMs); }}>{timecode(range.startMs)}–{timecode(range.endMs)}</Button>
                          <span className={intersects ? "text-xs text-warning" : "text-xs text-muted-foreground"}>{intersects ? t("authoringUi.overlap") : range.status}</span>
                        </div>
                      </article>;
                    })}</div>
                  </AccordionContent></AccordionItem></Accordion>
                </>}
            </FieldGroup>
          </form>}
        </aside>}
      </div>
      <footer className="authoring-actionbar">
        <span className="text-xs text-muted-foreground">
          {inspection
            ? startMs !== null && endMs !== null && rangeInputValid
              ? `${timecode(startMs)}–${timecode(endMs)}`
              : t("sourceSelection.chooseRange")
            : t("sourceWorkspace.sourceHint")}
        </span>
        <div className="authoring-action-buttons">
          <Button
            type="submit"
            form="lesson-create-form"
            disabled={
              !inspection?.embedAllowed ||
              inspectionMutation.isPending ||
              !rangeInputValid ||
              startMs === null || endMs === null ||
              createMutation.isPending
            }
          >
            {t(
              createMutation.isPending
                ? "common:status.loading"
                : "lessonAuthoring.prepare",
            )}
          </Button>
        </div>
      </footer>
    </section>
  );
}

export function CreateLessonPage() {
  const { t } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const [dirty, setDirty] = useState(false);
  const type = params.get('type') === 'text' ? 'text' : params.get('type') === 'audio' ? 'audio' : 'youtube';
  return <section className="create-lesson-page grid gap-4">
    <header className="authoring-heading"><h1>{t("navigation.createLesson")}</h1></header>
    <Tabs value={type} onValueChange={value => { if (value !== type && (!dirty || window.confirm(t("catalog.unsaved")))) { setDirty(false); setParams({ type: value }); } }}>
    <div className="flex flex-wrap items-center justify-between gap-3"><TabsList aria-label={t('media.sourceType')}>
      <TabsTrigger value="youtube">YouTube</TabsTrigger><TabsTrigger value="text">{t('media.textType')}</TabsTrigger><TabsTrigger value="audio">Audio</TabsTrigger>
    </TabsList><AuthoringPrompt type={type} /></div>
    <TabsContent value="youtube"><CreateYouTubePage onDirtyChange={setDirty} /></TabsContent>
    <TabsContent value="text"><CreateMediaLessonPage type="text" onDirtyChange={setDirty} /></TabsContent>
    <TabsContent value="audio"><CreateMediaLessonPage type="audio" onDirtyChange={setDirty} /></TabsContent>
  </Tabs></section>;
}
