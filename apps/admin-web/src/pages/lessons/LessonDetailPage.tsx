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
import { YouTubePreview } from "@/pages/lesson-builder/YouTubePreview";
import { useMetadata, catalogKeys } from "./catalog.queries";
import { setVisibility } from "./catalog.api";
import { CatalogError, CatalogSkeleton, CatalogImage } from "./CatalogFeedback";
import { LessonMetadataForm } from "./LessonMetadataForm";
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
  if (metadata.data.authoring && metadata.data.status !== "PUBLISHED")
    return <LessonBuilderPage />;
  return <PublishedLesson id={lessonId!} />;
}
function PublishedLesson({ id }: { id: string }) {
  const { t } = useTranslation("admin");
  const client = useQueryClient();
  const metadata = useMetadata(id);
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
    mutationFn: () => setVisibility(id, !lesson.visible),
    onSuccess: async () => {
      setConfirm(false);
      await client.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
  return (
    <section className="grid gap-4">
      <div className="page-heading">
        <div>
          <Link to="/lessons" className="text-sm text-muted-foreground">
            {t("catalog.lessons")}
          </Link>
          <h1>{lesson.title}</h1>
          <Badge variant="secondary">
            {t(lesson.visible ? "catalog.published" : "catalog.hidden")}
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
                {c.name}
              </Badge>
            ))}
          </div>
        </div>
      </div>
      <CatalogError error={draft.error || legacy.error} />
      {draft.isLoading || legacy.isLoading ? <CatalogSkeleton /> : null}
      <div className="grid items-start gap-4 xl:grid-cols-[minmax(280px,1fr)_2fr]">
        {draft.data && (
          <div className="xl:sticky xl:top-0">
            <YouTubePreview
              videoId={draft.data.videoId}
              startMs={draft.data.startMs}
              endMs={draft.data.endMs}
              sentences={draft.data.sentences}
              seek={null}
              onTimeChange={() => {}}
              onAvailabilityChange={() => {}}
            />
          </div>
        )}
        <Card>
          <CardHeader>
            <CardTitle>{t("catalog.lessonContent")}</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-4">
            {draft.data?.sentences.map((s) => (
              <div key={s.id}>
                <p className="font-medium">{s.text}</p>
                {s.translation && (
                  <p className="text-muted-foreground">{s.translation}</p>
                )}
                {[...s.vocabulary, ...s.grammar].map((a, i) => (
                  <p key={i} className="text-sm">
                    <strong>{a.surface}</strong> {a.explanation}
                  </p>
                ))}
              </div>
            ))}
            {legacy.data?.sentences.map((s) => (
              <p key={s.id}>{s.text}</p>
            ))}
          </CardContent>
        </Card>
      </div>
      <Dialog open={editing} onOpenChange={setEditing}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("catalog.editMetadata")}</DialogTitle>
            <DialogDescription>{t("catalog.metadataHelp")}</DialogDescription>
          </DialogHeader>
          <LessonMetadataForm
            lesson={lesson}
            onSaved={() => setEditing(false)}
            onCancel={() => setEditing(false)}
          />
        </DialogContent>
      </Dialog>
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
