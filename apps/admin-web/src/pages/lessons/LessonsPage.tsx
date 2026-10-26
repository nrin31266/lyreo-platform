import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  useQuery,
  useMutation,
  useQueryClient,
  keepPreviousData,
} from "@tanstack/react-query";
import { Plus, Search, Trash2, Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";
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
} from "@/components/ui/dialog";
import { Empty, EmptyDescription } from "@/components/ui/empty";
import { CatalogError, CatalogSkeleton, CatalogImage } from "./CatalogFeedback";
import { LessonMetadataForm } from "./LessonMetadataForm";
import * as catalog from "./catalog.api";
import { catalogKeys, useCategories } from "./catalog.queries";
function Filter({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: { id: string; name: string }[];
  onChange: (value: string) => void;
}) {
  return (
    <Select
      value={value || "all"}
      onValueChange={(v) => onChange(v === "all" ? "" : v)}
    >
      <SelectTrigger aria-label={label}>
        <SelectValue placeholder={label} />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          <SelectItem value="all">{label}</SelectItem>
          {options.map((o) => (
            <SelectItem key={o.id} value={o.id}>
              {o.name}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  );
}
export function LessonsPage() {
  const { t, i18n } = useTranslation("admin");
  const client = useQueryClient();
  const [params, setParams] = useSearchParams();
  const query = params.toString();
  const [editing, setEditing] = useState<catalog.LessonItem>();
  const [deleting, setDeleting] = useState<catalog.LessonItem>();
  const lessons = useQuery({
    queryKey: catalogKeys.lessons(query),
    queryFn: ({ signal }) =>
      catalog.fetchLessons(new URLSearchParams(query), signal),
    placeholderData: keepPreviousData,
  });
  const categories = useCategories();
  const channels = useQuery({
    queryKey: catalogKeys.channels,
    queryFn: ({ signal }) => catalog.fetchChannels(signal),
  });
  const deletion = useMutation({
    mutationFn: catalog.deleteDraft,
    onSuccess: async () => {
      setDeleting(undefined);
      await client.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
  function filter(key: string, value: string) {
    const next = new URLSearchParams(params);
    next.delete("page");
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next);
  }
  const statuses = [
    "TRANSCRIPT_REVIEW",
    "TIMING_REVIEW",
    "CONTENT_REVIEW",
    "PUBLISHED",
    "FAILED",
  ].map((id) => ({ id, name: t(`lessonAuthoring.labels.${id}`) }));
  return (
    <section className="grid gap-4">
      <div className="page-heading">
        <div>
          <h1>{t("catalog.lessons")}</h1>
          <p className="lead">{t("catalog.description")}</p>
        </div>
        <Button asChild>
          <Link to="/lessons/new">
            <Plus data-icon="inline-start" />
            {t("navigation.createLesson")}
          </Link>
        </Button>
      </div>
      <form
        className="flex flex-wrap items-center gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          filter(
            "query",
            String(new FormData(e.currentTarget).get("query") || ""),
          );
        }}
      >
        <Input
          className="min-w-40 flex-1"
          name="query"
          key={params.get("query")}
          defaultValue={params.get("query") || ""}
          aria-label={t("catalog.search")}
          placeholder={t("catalog.search")}
        />
        <Button type="submit" variant="outline">
          <Search data-icon="inline-start" />
          {t("catalog.searchAction")}
        </Button>
        <Filter
          label={t("catalog.allStatuses")}
          value={params.get("status") || ""}
          options={statuses}
          onChange={(v) => filter("status", v)}
        />
        <Filter
          label={t("catalog.allCategories")}
          value={params.get("category") || ""}
          options={categories.data ?? []}
          onChange={(v) => filter("category", v)}
        />
        <Filter
          label={t("catalog.allChannels")}
          value={params.get("channel") || ""}
          options={
            channels.data?.map((c) => ({ id: c.id, name: c.title })) ?? []
          }
          onChange={(v) => filter("channel", v)}
        />
      </form>
      <CatalogError
        error={lessons.error || categories.error || channels.error}
        retry={() => {
          void lessons.refetch();
          void categories.refetch();
          void channels.refetch();
        }}
      />
      {lessons.isPending ? (
        <CatalogSkeleton />
      ) : lessons.data?.items.length ? (
        <div
          className="overflow-hidden rounded-lg border"
          aria-busy={lessons.isFetching}
        >
          <Table>
            <TableHeader>
              <TableRow>
                {["lesson", "status", "categories", "updated", "actions"].map(
                  (key) => (
                    <TableHead key={key}>{t(`catalog.${key}`)}</TableHead>
                  ),
                )}
              </TableRow>
            </TableHeader>
            <TableBody>
              {lessons.data.items.map((item) => (
                <TableRow key={item.id}>
                  <TableCell>
                    <Link
                      to={`/lessons/${item.id}`}
                      className="flex items-center gap-3"
                    >
                      <CatalogImage url={item.imageUrl} title="" />
                      <span>
                        <strong>{item.title}</strong>
                        <span className="block text-xs text-muted-foreground">
                          {item.channelTitle ?? item.sourceType}
                        </span>
                      </span>
                    </Link>
                  </TableCell>
                  <TableCell>
                    <Badge variant={item.status === "FAILED" ? "destructive" : item.status === "PUBLISHED" ? "success" : "secondary"}>
                      {t(`lessonAuthoring.labels.${item.status}`, {
                        defaultValue: item.status,
                      })}
                    </Badge>
                    {item.status === "PUBLISHED" && !item.visible && (
                      <Badge variant="outline">{t("catalog.hidden")}</Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {!item.categories.length && <span className="text-muted-foreground">—</span>}
                      {item.categories.map((c) => (
                        <Badge key={c.id} variant="outline">
                          {c.name}
                        </Badge>
                      ))}
                    </div>
                  </TableCell>
                  <TableCell>
                    {new Date(item.updatedAt).toLocaleDateString(i18n.language)}
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-1">
                      <Button variant="ghost" size="icon" aria-label={t("catalog.editMetadata")} title={t("catalog.editMetadata")} onClick={() => setEditing(item)}>
                        <Pencil />
                      </Button>
                      <Button variant="outline" asChild>
                        <Link to={`/lessons/${item.id}`}>
                          {t(
                            item.status === "PUBLISHED"
                              ? "catalog.view"
                              : "catalog.resume",
                          )}
                        </Link>
                      </Button>
                      {item.status !== "PUBLISHED" && (
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                          aria-label={t("catalog.deleteDraft")}
                          title={t("catalog.deleteDraft")}
                          onClick={() => setDeleting(item)}
                        >
                          <Trash2 />
                        </Button>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : (
        !lessons.isError && (
          <Empty>
            <EmptyDescription>{t("catalog.empty")}</EmptyDescription>
          </Empty>
        )
      )}
      {lessons.data && (
        <footer className="flex flex-wrap items-center justify-between gap-3">
          <span>{t("catalog.total", { count: lessons.data.total })}</span>
          <div className="flex gap-2">
            <Button
              variant="outline"
              disabled={lessons.isFetching || lessons.data.page === 0}
              onClick={() =>
                setParams((p) => {
                  p.set("page", String(lessons.data!.page - 1));
                  return p;
                })
              }
            >
              {t("catalog.previous")}
            </Button>
            <Button
              variant="outline"
              disabled={
                lessons.isFetching ||
                (lessons.data.page + 1) * lessons.data.size >=
                  lessons.data.total
              }
              onClick={() =>
                setParams((p) => {
                  p.set("page", String(lessons.data!.page + 1));
                  return p;
                })
              }
            >
              {t("catalog.next")}
            </Button>
          </div>
        </footer>
      )}
      <Dialog
        open={!!editing}
        onOpenChange={(open) => {
          if (!open) setEditing(undefined);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("catalog.editMetadata")}</DialogTitle>
            <DialogDescription>{t("catalog.metadataHelp")}</DialogDescription>
          </DialogHeader>
          {editing && (
            <LessonMetadataForm
              key={editing.id}
              lesson={editing}
              onSaved={() => setEditing(undefined)}
              onCancel={() => setEditing(undefined)}
            />
          )}
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!deleting}
        onOpenChange={(open) => {
          if (!open) setDeleting(undefined);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("catalog.deleteDraft")}</DialogTitle>
            <DialogDescription>
              {t("catalog.deleteConfirm", { title: deleting?.title })}
            </DialogDescription>
          </DialogHeader>
          <CatalogError error={deletion.error} />
          <div className="flex gap-2">
            <Button
              variant="destructive"
              disabled={deletion.isPending}
              onClick={() => deleting && deletion.mutate(deleting.id)}
            >
              {t("catalog.deleteDraft")}
            </Button>
            <Button variant="outline" onClick={() => setDeleting(undefined)}>
              {t("catalog.cancel")}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </section>
  );
}
