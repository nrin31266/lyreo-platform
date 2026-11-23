import { ProcessingStatus } from "./ProcessingStatus";
import { AuthoringPrompt } from "./AuthoringPrompt";
import { CreateMetadataFields } from "./CreateMetadataFields";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { CreateMediaLessonPage } from "./CreateMediaLessonPage";
import "./lesson-builder.css";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useNavigate, useSearchParams, Link, type BlockerFunction } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { RiLink as Link2, RiLoader4Line as LoaderCircle, RiErrorWarningLine as AlertCircle } from "@remixicon/react";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle, EmptyDescription } from "@/components/ui/empty";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { InputGroup, InputGroupInput, InputGroupAddon, InputGroupButton } from "@/components/ui/input-group";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldError,
  FieldDescription,
} from "@/components/ui/field";
import { SourceInspectionSkeleton } from "./AuthoringSkeleton";
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
import { ContentOptionsPanel, defaultContentOptions } from "./ContentEditor";
import type { CaptionFile, SourceInspection } from "./lesson-builder.types";
import { createMetadataSchema as metadataSchema } from "@/pages/lessons/catalog.api";
import { useCategories, catalogKeys } from "@/pages/lessons/catalog.queries";
import { UnsavedChangesGuard } from "./UnsavedChangesGuard";
function isYouTubeVideoUrl(reference: string) {
  if (!/^https?:\/\//.test(reference) || !URL.canParse(reference)) return false;
  const url = new URL(reference);
  if (url.username || url.password || !["", "80", "443"].includes(url.port)) return false;
  const host = url.hostname;
  let id: string | undefined;
  if (host === "youtu.be" || host === "www.youtu.be") {
    id = url.pathname.slice(1);
  } else if (["youtube.com", "www.youtube.com", "m.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com"].includes(host)) {
    if (url.pathname === "/watch") {
      const ids = url.searchParams.getAll("v");
      if (ids.length === 1) id = ids[0];
    } else {
      id = /^\/(?:embed|shorts|live)\/([^/]+)$/.exec(url.pathname)?.[1];
    }
  }
  return id !== undefined && /^[A-Za-z0-9_-]{11}$/.test(id);
}

const sourceSchema = z.object({
  sourceReference: z.string().trim().refine(isYouTubeVideoUrl),
});
function CreateYouTubePage({ onDirtyChange, onBusyChange }: { onDirtyChange: (dirty: boolean) => void; onBusyChange: (busy: boolean) => void }) {
  const { t } = useTranslation(["admin", "common"]);
  const navigate = useNavigate();
  const client = useQueryClient();
  const categories = useCategories();
  const [options,setOptions]=useState(defaultContentOptions);
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
  const [preferEnglishCaptions, setPreferEnglishCaptions] = useState(false);
  const [captionFile, setCaptionFile] = useState<CaptionFile | null>(null);
  const [captionError, setCaptionError] = useState("");
  const [readingCaption, setReadingCaption] = useState(false);
  const captionVersion = useRef(0);
  const captionInput = useRef<HTMLInputElement>(null);

  function clearCaption() {
    captionVersion.current += 1;
    setCaptionFile(null);
    setCaptionError("");
    setReadingCaption(false);
    if (captionInput.current) captionInput.current.value = "";
  }

  async function readCaption(file?: File) {
    const version = ++captionVersion.current;
    setCaptionFile(null);
    setCaptionError("");
    setReadingCaption(false);
    if (!file) return;
    const format = /\.(srt|vtt)$/i.exec(file.name)?.[1].toLowerCase();
    if ((format !== "srt" && format !== "vtt") || !file.size || file.size > 256 * 1024) {
      setCaptionError(t("captions.invalidFile"));
      return;
    }
    setReadingCaption(true);
    try {
      const text = await file.text();
      if (version !== captionVersion.current) return;
      if (text.trim()) setCaptionFile({ format, text });
      else setCaptionError(t("captions.invalidFile"));
    } catch {
      if (version === captionVersion.current) setCaptionError(t("captions.readError"));
    } finally {
      if (version === captionVersion.current) setReadingCaption(false);
    }
  }

  const sourceReference = sourceForm.watch("sourceReference");
  useEffect(() => { onDirtyChange(!!sourceReference.trim() || metadata.formState.isDirty || JSON.stringify(options) !== JSON.stringify(defaultContentOptions)); }, [sourceReference, metadata.formState.isDirty, options, onDirtyChange]);
  const inspectionVersion = useRef(0);
  const inspectionTimer = useRef<number>(undefined);
  const inspectionRequest = useRef<AbortController | null>(null);
  const lastInspectedReference = useRef<string | null>(null);
  const [inspecting, setInspecting] = useState(false);
  const [inspection, setInspection] = useState<SourceInspection | null>(null);
  const [startMs, setStartMs] = useState<number | null>(null);
  const [endMs, setEndMs] = useState<number | null>(null);
  const [rangeInputValid, setRangeInputValid] = useState(false);
  const [error, setError] = useState("");
  const [inspectError, setInspectError] = useState("");
  const createMutation = useMutation({ mutationFn: authoring.createDraft });
  const creating = useRef(false);
  useEffect(() => { onBusyChange(createMutation.isPending); return () => onBusyChange(false); }, [createMutation.isPending, onBusyChange]);
  async function inspectReference(reference: string) {
    clearTimeout(inspectionTimer.current);
    if (inspectionRequest.current || reference === lastInspectedReference.current) return;
    if (!isYouTubeVideoUrl(reference)) {
      void sourceForm.trigger("sourceReference");
      return;
    }
    const request = new AbortController();
    inspectionRequest.current = request;
    const version = inspectionVersion.current;
    setInspecting(true);
    setError("");
    setInspectError("");
    try {
      const next = await authoring.inspectSource(reference, request.signal);
      if (version !== inspectionVersion.current) return;
      setInspection(next);
      setPreferEnglishCaptions(next.englishCaptions?.automatic === false);
      const [start, end] = defaultRange(next.durationMs, env.youtubeAutoSelectMaxMinutes);
      setStartMs(start);
      setEndMs(end);
      setRangeInputValid(start !== null && end !== null);
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
      if (inspectionRequest.current === request) {
        inspectionRequest.current = null;
        setInspecting(false);
      }
    }
  }
  useEffect(() => () => {
    inspectionVersion.current += 1;
    captionVersion.current += 1;
    clearTimeout(inspectionTimer.current);
    inspectionRequest.current?.abort();
    inspectionRequest.current = null;
  }, []);
  async function create(event: FormEvent) {
    event.preventDefault();
    if (inspectionRequest.current || creating.current || readingCaption || captionError) return;
    const version = inspectionVersion.current;
    const fileVersion = captionVersion.current;
    setError("");
    if (!(await metadata.trigger())) return;
    if (version !== inspectionVersion.current || fileVersion !== captionVersion.current) return;
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
      if (creating.current) return;
      creating.current = true;
      const result = await createMutation.mutateAsync({
        options,
        ...metadata.getValues(),
        sourceReference: sourceReference.trim(),
        ...(captionFile ? { captionFile } : { preferEnglishCaptions: !!inspection.englishCaptions && preferEnglishCaptions }),
        startMs,
        endMs,
      });
      await client.invalidateQueries({ queryKey: catalogKeys.all });
      if (version === inspectionVersion.current) navigate(`/lessons/new?draft=${result.lessonId}`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally { creating.current = false; }
  }
  const overlap = startMs !== null && endMs !== null && inspection?.usedRanges.some(
    (range) => startMs < range.endMs && endMs > range.startMs,
  );
  const track = inspection?.englishCaptions;
  const captionAvailability = inspecting ? t("captions.checking") : track
    ? t(track.automatic ? "captions.automaticTrack" : "captions.manualTrack", { language: track.language })
    : inspection?.embedAllowed && t(captionFile ? "captions.noEnglishTrackOnYouTube" : "captions.noEnglishTrack");
  return (
    <section className="authoring-page source-page" aria-busy={createMutation.isPending}>
      {createMutation.isPending && <ProcessingStatus label={t("finish.starting")} />}
      {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}
      <fieldset disabled={createMutation.isPending} className="source-workspace">
        <div className="source-media-pane">
          <h2 className="source-section-title">{t("sourceWorkspace.sourceHeading")}</h2>
      <form
        className="source-url-form"
        onSubmit={(event) => {
          event.preventDefault();
          void inspectReference(sourceReference.trim());
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
            disabled={createMutation.isPending}
            type="url"
            required
            value={sourceReference}
            aria-describedby="source-caption-status"
            aria-invalid={
              !!inspectError || !!sourceForm.formState.errors.sourceReference
            }
            onChange={(event) => {
              const value = event.target.value;
              const reference = value.trim();
              sourceForm.setValue("sourceReference", value);
              if (reference === sourceReference.trim()) return;
              inspectionVersion.current += 1;
              clearTimeout(inspectionTimer.current);
              inspectionRequest.current?.abort();
              inspectionRequest.current = null;
              lastInspectedReference.current = null;
              setInspecting(false);
              setInspection(null);
              sourceForm.clearErrors("sourceReference");
              clearCaption();
              setInspectError("");
              setError("");
              if (isYouTubeVideoUrl(reference)) {
                inspectionTimer.current = window.setTimeout(() => void inspectReference(reference), 350);
              }
            }}
            onBlur={() => {
              if (sourceReference.trim() && !isYouTubeVideoUrl(sourceReference.trim())) void sourceForm.trigger("sourceReference");
            }}
            placeholder="https://www.youtube.com/watch?v=…"
          />
          {(inspecting || inspectError) && <InputGroupAddon align="inline-end">
            {inspecting
              ? <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
              : <InputGroupButton type="submit" variant="secondary">{t("catalog.retry")}</InputGroupButton>}
          </InputGroupAddon>}
          </InputGroup>
          <FieldDescription id="source-caption-status" aria-live="polite">{captionAvailability}</FieldDescription>
          <FieldError>
            {sourceForm.formState.errors.sourceReference
              ? t("captions.invalidYoutubeUrl")
              : inspectError}
          </FieldError>
        </Field>
      </form>

          {inspection?.embedAllowed ? (
            <SourceRangeEditor
              key={inspection.videoId}
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
          ) : inspecting ? (
            <SourceInspectionSkeleton />
          ) : (
            <Empty className="source-empty">
              <EmptyHeader>
                <EmptyMedia variant="icon">{inspectError ? <AlertCircle className="text-destructive" /> : <Link2 />}</EmptyMedia>
                <EmptyTitle>{t(inspectError ? "sourceWorkspace.sourceFailed" : "sourceWorkspace.emptyTitle")}</EmptyTitle>
                {!inspectError && <EmptyDescription>{t("sourceWorkspace.pasteSource")}</EmptyDescription>}
              </EmptyHeader>
            </Empty>
          )}
        </div>
        <aside className="source-metadata-pane">
          <h2 className="source-section-title">{t("reviewWorkspace.lessonInformation")}</h2>
          <form id="lesson-create-form" onSubmit={create} noValidate>
            <FieldGroup>
              {inspection?.embedAllowed && <Field className="gap-2">
                {track && <div className="flex items-center gap-2">
                  <Checkbox id="prefer-english-captions" checked={preferEnglishCaptions}
                    disabled={!!captionFile || readingCaption || createMutation.isPending}
                    aria-describedby="source-caption-status"
                    onCheckedChange={value => setPreferEnglishCaptions(value === true)} />
                  <FieldLabel htmlFor="prefer-english-captions">
                    {t(track.automatic ? "captions.useAutomaticTrack" : "captions.useManualTrack")}
                  </FieldLabel>
                </div>}
                <FieldLabel htmlFor="caption-file">{t("captions.upload")}</FieldLabel>
                <Input id="caption-file" ref={captionInput} type="file" accept=".srt,.vtt"
                  aria-describedby={captionError ? "caption-file-help caption-file-error" : "caption-file-help"}
                  aria-invalid={!!captionError} disabled={createMutation.isPending}
                  onChange={event => void readCaption(event.target.files?.[0])} />
                <FieldDescription id="caption-file-help">{t(captionFile ? "captions.fileOverrides" : "captions.fileHelp")}</FieldDescription>
                {captionError && <FieldError id="caption-file-error">{captionError}</FieldError>}
                {(captionFile || captionError || readingCaption) && <Button className="w-fit" variant="outline" size="sm"
                  type="button" disabled={createMutation.isPending} onClick={clearCaption}>{t("captions.clearFile")}</Button>}
              </Field>}
              <CreateMetadataFields form={metadata} titleHint={inspection?.title} imageHint={inspection?.thumbnailUrl || undefined} /><ContentOptionsPanel draft={{options}} disabled={createMutation.isPending} onChange={setOptions} />
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
          </form>
        </aside>
      </fieldset>
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
              !inspection?.embedAllowed || categories.isPending || categories.isError ||
              inspecting || readingCaption || !!captionError ||
              !rangeInputValid ||
              startMs === null || endMs === null ||
              createMutation.isPending
            }
          >
            {createMutation.isPending && <LoaderCircle className="animate-spin" aria-hidden="true" />}{t(createMutation.isPending ? "finish.starting" : "finish.create")}
          </Button>
        </div>
      </footer>
    </section>
  );
}

function sourceType(params: URLSearchParams) {
  const type = params.get('type');
  return type === 'text' || type === 'audio' ? type : 'youtube';
}

const changesSourceInHistory: BlockerFunction = ({ currentLocation, nextLocation, historyAction }) => {
  if (historyAction !== 'POP') return false;
  const next = new URLSearchParams(nextLocation.search);
  return !!next.get('draft') || sourceType(new URLSearchParams(currentLocation.search)) !== sourceType(next);
};

export function CreateLessonPage() {
  const { t } = useTranslation('admin');
  const [params, setParams] = useSearchParams();
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const type = sourceType(params);
  return <section className="create-lesson-page grid gap-4">
    <UnsavedChangesGuard dirty={dirty || busy} includeSearch={changesSourceInHistory} />
    <header className="authoring-heading"><h1>{t("navigation.createLesson")}</h1></header>
    <Tabs value={type} activationMode="manual" onValueChange={value => { if (value !== type && (!dirty || window.confirm(t("catalog.unsaved")))) { setDirty(false); setParams({ type: value }); } }}>
    <div className="flex flex-wrap items-center justify-between gap-3"><TabsList aria-label={t('media.sourceType')}>
      <TabsTrigger disabled={busy} value="youtube">YouTube</TabsTrigger><TabsTrigger disabled={busy} value="text">{t('media.textType')}</TabsTrigger><TabsTrigger disabled={busy} value="audio">{t('media.audioType')}</TabsTrigger>
    </TabsList><AuthoringPrompt type={type} /></div>
    <TabsContent value="youtube"><CreateYouTubePage onDirtyChange={setDirty} onBusyChange={setBusy} /></TabsContent>
    <TabsContent value="text"><CreateMediaLessonPage type="text" onDirtyChange={setDirty} onBusyChange={setBusy} /></TabsContent>
    <TabsContent value="audio"><CreateMediaLessonPage type="audio" onDirtyChange={setDirty} onBusyChange={setBusy} /></TabsContent>
  </Tabs></section>;
}
