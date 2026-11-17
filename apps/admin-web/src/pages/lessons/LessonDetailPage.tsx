import { categoryName } from "./catalog.api";

import { createPlaybackClock, PlaybackWords, SeekBar } from "@/pages/lesson-builder/playback-clock";
import "@/pages/lesson-builder/lesson-builder.css";
import { RiPlayLine as Play } from "@remixicon/react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from "@/components/ui/sheet";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { LessonBuilderPage } from "@/pages/lesson-builder/LessonBuilderPage";
import { fetchDraft } from "@/pages/lesson-builder/lesson-builder.api";
import { LessonPlayer } from "@/pages/lesson-builder/AudioPreview";
import { useMetadata, catalogKeys } from "./catalog.queries";
import { setVisibility } from "./catalog.api";
import { CatalogError, CatalogSkeleton, CatalogImage } from "./CatalogFeedback";
import { LessonMetadataForm } from "./LessonMetadataForm";
function sourceSurface(text: string, start: number, end: number) {
  const tokens = [...text.matchAll(/[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*/gu)];
  return text.slice(tokens[start]?.index ?? 0, end >= tokens.length ? text.length : tokens[end]?.index).trim();
}
export function LessonDetailPage() {
  const { lessonId } = useParams();
  const metadata = useMetadata(lessonId!);
  if (metadata.isPending) return <CatalogSkeleton />;
  if (metadata.isError)
    return (
      <CatalogError
        error={metadata.error}
        retry={() => void metadata.refetch()}
      />
    );
  if (metadata.data.deleted) return <DeletedLesson id={lessonId!} title={metadata.data.title} version={metadata.data.version} />;
  if (metadata.data.authoring && !metadata.data.deleted)
    return <LessonBuilderPage />;
  return <PublishedLesson id={lessonId!} />;
}
function PublishedLesson({ id }: { id: string }) {
  const { t, i18n } = useTranslation("admin");
  const client = useQueryClient();
  const metadata = useMetadata(id);
  const [clock] = useState(createPlaybackClock);
  const [seek, setSeek] = useState<{atMs: number; sequence: number} | null>(null);
  const [tab, setTab] = useState("transcript");
  const [editing, setEditing] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const lesson = metadata.data!;
  const draft = useQuery({
    queryKey: ["published-lesson", id],
    queryFn: () => fetchDraft(id),
    enabled: lesson.authoring,
  });
  const legacy = useQuery({
    queryKey: ["legacy-lesson", id],
    queryFn: () =>
      api<{ sentences: { id: string; text: string }[] }>(
        `/api/v1/admin/lessons/${id}`,
      ),
    enabled: !lesson.authoring,
  });
  const visibility = useMutation({
    mutationFn: () => setVisibility(id, !lesson.visible,lesson.version),
    onSuccess: async () => {
      setConfirm(false);
      await client.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
  return (
    <section className="published-lesson">
      <div className="page-heading">
        <div>
          <Link to="/lessons" className="text-sm text-muted-foreground">
            {t("catalog.lessons")}
          </Link>
          <h1>{lesson.title}</h1>
          <Badge variant="secondary">
            {t(lesson.channelVisible === false ? "catalog.hiddenByChannel" : lesson.visible ? "catalog.published" : "catalog.hiddenIndividually")}
          </Badge>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => setEditing(true)}>
            {t("catalog.editMetadata")}
          </Button>
          {lesson.status === "PUBLISHED" && (
            <Button variant="outline" onClick={() => setConfirm(true)}>
              {t(lesson.visible ? "catalog.unlist" : "catalog.relist")}
            </Button>
          )}
        </div>
      </div>
      <div className="flex items-center gap-3">
        <CatalogImage url={lesson.imageUrl} title="" />
        <div>
          <p>{lesson.description}</p>
          <p className="text-muted-foreground">{lesson.channelTitle}</p>
          <div className="flex gap-1">
            {lesson.categories.map((c) => (
              <Badge key={c.id} variant="outline">
                {categoryName(c, i18n.language)}
              </Badge>
            ))}
          </div>
        </div>
      </div>
      <CatalogError error={draft.error || legacy.error} />
      {draft.isLoading || legacy.isLoading ? <CatalogSkeleton /> : null}
      <p className="text-xs text-muted-foreground">{t("authoringUi.readonly")}</p>
      <div className="published-workspace">
        {draft.data && (
          <div className="published-player">
            <LessonPlayer
              draft={draft.data}
              sentences={draft.data.sentences}
              seek={seek}
              onTimeChange={clock.set}
              onAvailabilityChange={() => {}}
            />
            <SeekBar clock={clock} durationMs={draft.data.endMs-draft.data.startMs} onSeek={atMs=>setSeek({atMs,sequence:Date.now()})} />
          </div>
        )}
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList aria-label={t("catalog.lessonContent")}><TabsTrigger value="transcript">{t("lessonAuthoring.transcriptTab")}</TabsTrigger><TabsTrigger value="focus">{t("authoringUi.focus")}</TabsTrigger><TabsTrigger value="activities">{t("authoringUi.activities")}</TabsTrigger></TabsList>
          <TabsContent value="transcript" className="grid gap-3">
            {draft.data?.sentences.filter(sentence => sentence.usage !== "SKIP").map((sentence, index) => {
              return <article key={sentence.id} className="published-sentence">
                <div className="mb-2 flex items-center justify-between"><span className="text-xs text-muted-foreground">{t("lessonAuthoring.sentence",{number:index+1})}</span><Button size="icon" variant="ghost" disabled={!sentence.words.length} aria-label={t("lessonAuthoring.seek")} onClick={() => setSeek({atMs:sentence.words[0].startMs,sequence:Date.now()})}><Play /></Button></div>
                <p className="text-base font-medium leading-relaxed"><PlaybackWords clock={clock} sentence={sentence} onSeek={atMs=>setSeek({atMs,sequence:Date.now()})} /></p>
                {sentence.translation && <p className="mt-2 text-sm leading-relaxed text-muted-foreground">{sentence.translation}</p>}
                {sentence.ipa && <p className="mt-2 text-sm text-muted-foreground">{sentence.ipa} · {sentence.ipaAccent}</p>}
                {!!(sentence.hints.length+sentence.notes.length) && <Accordion type="single" collapsible><AccordionItem value="support"><AccordionTrigger>{t("lessonAuthoring.notes")}</AccordionTrigger><AccordionContent className="grid gap-2">{sentence.hints.map((hint,i)=><p key={`h${i}`}><Badge variant="outline">{t(`authoringUi.kinds.${hint.kind || 'DICTATION'}`)}</Badge> {sourceSurface(sentence.text,hint.tokenStart,hint.tokenEnd)} {hint.text}</p>)}{sentence.notes.map((note,i)=><p key={`n${i}`}><Badge variant="secondary">{t(`authoringUi.kinds.${note.kind || 'USAGE'}`)}</Badge> {note.text}</p>)}</AccordionContent></AccordionItem></Accordion>}
              </article>;
            })}
            {legacy.data?.sentences.map(sentence=><article className="published-sentence" key={sentence.id}>{sentence.text}</article>)}
          </TabsContent>
          <TabsContent value="focus" className="grid gap-4">{(['vocabulary','grammar'] as const).map(kind=><Card key={kind}><CardHeader><CardTitle>{t(`lessonAuthoring.${kind}`)}</CardTitle></CardHeader><CardContent className="grid gap-4">{draft.data?.sentences.flatMap(sentence=>sentence[kind].map((item,index)=><div key={`${sentence.id}-${index}`}><div className="flex items-center justify-between gap-2"><strong>{item.surface}</strong><Button size="sm" variant="ghost" onClick={()=>{setSeek({atMs:sentence.words[0]?.startMs ?? 0,sequence:Date.now()});setTab('transcript');}}>{t('lessonAuthoring.seek')}</Button></div><p className="text-sm text-muted-foreground">{item.explanation}</p></div>))}{!draft.data?.sentences.some(sentence=>sentence[kind].length) && <p className="text-muted-foreground">—</p>}</CardContent></Card>)}</TabsContent>
          <TabsContent value="activities" className="grid gap-4">{(['dictation','shadowing'] as const).map(activity=><Card key={activity}><CardHeader><CardTitle>{t(`lessonAuthoring.${activity}`)} · {draft.data?.sentences.filter(sentence=>sentence[`${activity}Selected`]).length ?? 0}</CardTitle></CardHeader><CardContent className="grid gap-2">{draft.data?.sentences.filter(sentence=>sentence[`${activity}Selected`]).map(sentence=><Button className="h-auto justify-start whitespace-normal py-3 text-left" variant="outline" key={sentence.id} onClick={()=>{setSeek({atMs:sentence.words[0]?.startMs ?? 0,sequence:Date.now()});setTab('transcript');}}>{sentence.text}</Button>)}</CardContent></Card>)}</TabsContent>
        </Tabs>
      </div>
      <Sheet open={editing} onOpenChange={setEditing}>
        <SheetContent className="min-h-0 overflow-hidden p-4">
          <SheetHeader>
            <SheetTitle>{t("catalog.editMetadata")}</SheetTitle>
            <SheetDescription>{t("catalog.metadataHelp")}</SheetDescription>
          </SheetHeader>
          <LessonMetadataForm
            lesson={lesson}
            onSaved={() => setEditing(false)}
            onCancel={() => setEditing(false)}
          />
        </SheetContent>
      </Sheet>
      <Dialog open={confirm} onOpenChange={setConfirm}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {t(lesson.visible ? "catalog.unlist" : "catalog.relist")}
            </DialogTitle>
            <DialogDescription>{t("catalog.visibilityHelp")}</DialogDescription>
          </DialogHeader>
          <CatalogError error={visibility.error} />
          <Button
            disabled={visibility.isPending}
            onClick={() => visibility.mutate()}
          >
            {t(lesson.visible ? "catalog.unlist" : "catalog.relist")}
          </Button>
        </DialogContent>
      </Dialog>
    </section>
  );
}

function DeletedLesson({id,title,version}:{id:string;title:string;version?:number}) {
 const {t}=useTranslation('admin'),client=useQueryClient();const [error,setError]=useState<unknown>();
 return <section className="grid gap-4"><Link to="/lessons?status=DELETED">{t('catalog.lessons')}</Link><h1>{title}</h1><Badge variant="destructive">{t('finish.deleted')}</Badge><CatalogError error={error}/><Button onClick={()=>{void import('./catalog.api').then(api=>api.restoreLesson(id,version)).then(()=>client.invalidateQueries({queryKey:catalogKeys.all})).catch(setError);}}>{t('finish.restore')}</Button></section>;
}
