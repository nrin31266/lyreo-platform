import "./lesson-builder.css";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useNavigate, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Link2, RefreshCw, LoaderCircle, AlertCircle } from "lucide-react";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle, EmptyDescription, EmptyContent } from "@/components/ui/empty";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldError,
} from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Accordion,
  AccordionItem,
  AccordionTrigger,
  AccordionContent,
} from "@/components/ui/accordion";
import { ApiError } from "@/lib/api";
import { SourceRangeEditor } from "./SourceRangeEditor";
import { defaultRange, timecode } from "./timecode";
import * as authoring from "./lesson-builder.api";
import type { SourceInspection } from "./lesson-builder.types";
import { metadataSchema } from "@/pages/lessons/catalog.api";
import { useCategories, catalogKeys } from "@/pages/lessons/catalog.queries";
import { CatalogImage, CatalogError } from "@/pages/lessons/CatalogFeedback";
const sourceSchema = z.object({
  sourceReference: z.string().trim().pipe(z.url()),
});
function isVideoReference(reference: string) {
  try {
    const url = new URL(reference);
    return (
      ["youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"].includes(
        url.hostname,
      ) &&
      /^[\w-]{11}$/.test(
        url.searchParams.get("v") ?? url.pathname.split("/").pop() ?? "",
      )
    );
  } catch {
    return false;
  }
}
export function CreateLessonPage() {
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
  const latestReference = useRef(sourceReference);
  latestReference.current = sourceReference;
  const inspectionTimer = useRef<ReturnType<typeof setTimeout> | undefined>(
    undefined,
  );
  const [inspection, setInspection] = useState<SourceInspection | null>(null);
  const [startMs, setStartMs] = useState(0);
  const [endMs, setEndMs] = useState(120000);
  const [rangeInputValid, setRangeInputValid] = useState(true);
  const [error, setError] = useState("");
  const [inspectError, setInspectError] = useState("");
  const [rangeActions, setRangeActions] = useState<HTMLDivElement | null>(null);
  const inspectionMutation = useMutation({
    mutationFn: authoring.inspectSource,
  });
  const createMutation = useMutation({ mutationFn: authoring.createDraft });
  async function inspectReference(reference: string) {
    clearTimeout(inspectionTimer.current);
    setError("");
    setInspectError("");
    if (!(await sourceForm.trigger())) return;
    try {
      const next = await inspectionMutation.mutateAsync(reference.trim());
      if (reference !== latestReference.current) return;
      setInspection(next);
      metadata.setValue("title", next.title);
      const [a, b] = defaultRange(next.durationMs);
      setStartMs(a);
      setEndMs(b);
    } catch (cause) {
      if (reference !== latestReference.current) return;
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
    }
  }
  useEffect(() => {
    if (!isVideoReference(sourceReference)) return;
    inspectionTimer.current = setTimeout(
      () => void inspectReference(sourceReference),
      600,
    );
    return () => clearTimeout(inspectionTimer.current);
  }, [sourceReference]);
  async function create(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!(await metadata.trigger())) return;
    if (
      !inspection ||
      !rangeInputValid ||
      !Number.isFinite(startMs) ||
      !Number.isFinite(endMs) ||
      startMs < 0 ||
      endMs <= startMs ||
      endMs > inspection.durationMs ||
      endMs - startMs > 600000
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
      navigate(`/lessons/${result.lessonId}`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }
  const overlap = inspection?.usedRanges.some(
    (range) => startMs < range.endMs && endMs > range.startMs,
  );
  return (
    <section className="authoring-page source-page">
      <header className="authoring-heading">
        <h1>{t("lessonAuthoring.title")}</h1>
        <p className="text-sm text-muted-foreground">
          {t("sourceWorkspace.sourceHint")}
        </p>
      </header>
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
          <FieldLabel htmlFor="youtube-source">
            {t("lessonAuthoring.youtubeUrl")}
          </FieldLabel>
          <Input
            id="youtube-source"
            type="url"
            required
            value={sourceReference}
            aria-invalid={
              !!inspectError || !!sourceForm.formState.errors.sourceReference
            }
            onChange={(event) => {
              sourceForm.setValue("sourceReference", event.target.value);
              setInspection(null);
              setInspectError("");
              setError("");
            }}
            placeholder="https://www.youtube.com/watch?v=…"
          />
          <FieldError>
            {sourceForm.formState.errors.sourceReference &&
              t("catalog.invalidField")}
          </FieldError>
        </Field>
        <Button
          type="submit"
          variant="ghost"
          size="icon"
          title={t("lessonAuthoring.inspect")}
          disabled={inspectionMutation.isPending || !sourceReference.trim()}
        >
          {inspectionMutation.isPending ? <LoaderCircle className="animate-spin" /> : <RefreshCw />}
          <span className="sr-only">{t("lessonAuthoring.inspect")}</span>
        </Button>
      </form>
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
      <div className={`source-workspace ${!inspection && !inspectionMutation.isPending ? "source-workspace-empty" : ""}`}>
        <div className="source-media-pane">
          {inspection?.embedAllowed ? (
            <SourceRangeEditor
              key={inspection.videoId}
              videoId={inspection.videoId}
              reference={sourceReference.trim()}
              durationMs={inspection.durationMs}
              startMs={startMs}
              endMs={endMs}
              actionsContainer={rangeActions}
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
              {inspectError && <EmptyContent><Button variant="outline" onClick={() => void inspectReference(sourceReference)}><RefreshCw data-icon="inline-start" />{t("lessonAuthoring.retryInspect")}</Button></EmptyContent>}
            </Empty>
          )}
        </div>
        {(inspection || inspectionMutation.isPending) && <aside className="source-metadata-pane">
          {inspectionMutation.isPending && !inspection ? <div role="status" aria-label={t("loading")} className="grid gap-5"><Skeleton className="h-5 w-1/2" /><Skeleton className="h-10" /><Skeleton className="aspect-video" /><Skeleton className="h-20" /></div> : <form id="lesson-create-form" onSubmit={create} noValidate>
            <FieldGroup>
              <Field data-invalid={!!metadata.formState.errors.title}>
                <FieldLabel htmlFor="source-lessonTitle">
                  {t("lessonAuthoring.lessonTitle")}
                </FieldLabel>
                <Input
                  id="source-lessonTitle"
                  disabled={!inspection}
                  {...metadata.register("title")}
                  aria-invalid={!!metadata.formState.errors.title}
                />
                <FieldError>
                  {metadata.formState.errors.title && t("catalog.invalidField")}
                </FieldError>
              </Field>
              {inspection && (
                <>
                  <CatalogImage
                    url={metadata.watch("imageUrl") || inspection.thumbnailUrl}
                    title=""
                  />
                  {overlap && (
                    <p role="status" className="text-sm text-muted-foreground">
                      {t("lessonAuthoring.overlap")}
                    </p>
                  )}
                  <Controller
                    name="categoryIds"
                    control={metadata.control}
                    render={({ field }) => (
                      <Field>
                        <FieldLabel>{t("catalog.categories")}</FieldLabel>
                        {categories.isPending ? (
                          <Skeleton className="h-8" />
                        ) : categories.isError ? (
                          <CatalogError error={categories.error} retry={() => void categories.refetch()} />
                        ) : !categories.data?.length ? (
                          <p className="text-sm text-muted-foreground">{t("catalog.noCategories")}</p>
                        ) : (
                          categories.data.map((c) => (
                            <label
                              key={c.id}
                              className="flex items-center gap-2"
                            >
                              <Checkbox
                                checked={field.value.includes(c.id)}
                                onCheckedChange={(checked) =>
                                  field.onChange(
                                    checked
                                      ? [...field.value, c.id]
                                      : field.value.filter((id) => id !== c.id),
                                  )
                                }
                              />
                              {c.name}
                            </label>
                          ))
                        )}
                      </Field>
                    )}
                  />
                  <Accordion type="single" collapsible>
                    <AccordionItem value="metadata">
                      <AccordionTrigger>
                        {t("sourceWorkspace.additionalInfo")}
                      </AccordionTrigger>
                      <AccordionContent>
                        <FieldGroup>
                          <Field
                            data-invalid={
                              !!metadata.formState.errors.description
                            }
                          >
                            <FieldLabel htmlFor="create-description">
                              {t("catalog.descriptionField")}
                            </FieldLabel>
                            <Textarea
                              id="create-description"
                              {...metadata.register("description")}
                            />
                            <FieldError>
                              {metadata.formState.errors.description &&
                                t("catalog.invalidField")}
                            </FieldError>
                          </Field>
                          <Field
                            data-invalid={!!metadata.formState.errors.imageUrl}
                          >
                            <FieldLabel htmlFor="create-image">
                              {t("catalog.imageUrl")}
                            </FieldLabel>
                            <Input
                              id="create-image"
                              {...metadata.register("imageUrl")}
                            />
                            <FieldError>
                              {metadata.formState.errors.imageUrl &&
                                t("catalog.invalidField")}
                            </FieldError>
                          </Field>
                          {!!inspection.usedRanges.length && (
                            <div>
                              <h3>{t("lessonAuthoring.usedRanges")}</h3>
                              {inspection.usedRanges.map((range) => (
                                <p key={range.lessonId}>
                                  <Link to={`/lessons/${range.lessonId}`}>
                                    {range.title}
                                  </Link>{" "}
                                  · {timecode(range.startMs)}–
                                  {timecode(range.endMs)}
                                </p>
                              ))}
                            </div>
                          )}
                        </FieldGroup>
                      </AccordionContent>
                    </AccordionItem>
                  </Accordion>
                </>
              )}
            </FieldGroup>
          </form>}
        </aside>}
      </div>
      <footer className="authoring-actionbar">
        <span className="text-xs text-muted-foreground">
          {inspection
            ? `${timecode(startMs)}–${timecode(endMs)}`
            : t("sourceWorkspace.sourceHint")}
        </span>
        <div className="authoring-action-buttons">
          <div ref={setRangeActions} />
          <Button
            type="submit"
            form="lesson-create-form"
            disabled={
              !inspection?.embedAllowed ||
              !rangeInputValid ||
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
