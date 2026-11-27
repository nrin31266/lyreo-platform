import { categoryName } from "./catalog.api";
import { timecode } from "@/pages/lesson-builder/timecode";
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import {
  useQuery,
  useMutation,
  useQueryClient,
  keepPreviousData,
} from "@tanstack/react-query";
import { RiAddLine as Plus, RiSearchLine as Search, RiDeleteBinLine as Trash2, RiPencilLine as Pencil, RiArrowRightLine as ArrowRight } from "@remixicon/react";
import { AdminPagination } from "@/components/AdminPagination";
import { AccessTierBadge, CefrLevelBadge, statusBadgeVariant } from "@/components/ContentBadges";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
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
  DialogFooter,
} from "@/components/ui/dialog";
import { Empty, EmptyDescription } from "@/components/ui/empty";
import { CatalogError, CatalogSkeleton, CatalogImage } from "./CatalogFeedback";
import { LessonMetadataForm } from "./LessonMetadataForm";
import * as catalog from "./catalog.api";
import { catalogKeys, useCategories } from "./catalog.queries";

const wideColumn = "hidden @min-[1280px]:table-cell";

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
      <SelectTrigger className="w-full min-w-0" aria-label={label}>
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
  const [pendingVisibilityIds, setPendingVisibilityIds] = useState<Set<string>>(() => new Set());
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
  const visibility = useMutation({
    mutationFn: ({ id, visible, version }: { id: string; visible: boolean; version?: number }) => catalog.setVisibility(id, visible, version),
    onMutate: ({ id }) => {
      setPendingVisibilityIds((prev) => new Set(prev).add(id));
    },
    onSuccess: (_data, { id, visible }) => {
      client.setQueriesData<catalog.LessonPage>(
        { queryKey: ['catalog', 'lessons'] },
        (old) =>
          old
            ? {
                ...old,
                items: old.items.map((row) =>
                  row.id === id
                    ? { ...row, visible, version: (row.version ?? 0) + 1 }
                    : row,
                ),
              }
            : old,
      );
      void client.invalidateQueries({ queryKey: catalogKeys.metadata(id) });
    },
    onSettled: (_data, _err, { id }) => {
      setPendingVisibilityIds((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    },
  });
  const deletion = useMutation({
    mutationFn: (item: catalog.LessonItem) => catalog.deleteDraft(item.id,item.version),
    onSuccess: async () => {
      setDeleting(undefined);
      await client.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
  const restore = useMutation({mutationFn: (item: catalog.LessonItem) => catalog.restoreLesson(item.id,item.version),onSuccess:()=>client.invalidateQueries({queryKey:catalogKeys.all})});
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
    "DELETED",
    "AI_FAILED",
  ].map((id) => ({ id, name: id === "DELETED" ? t("finish.deleted") : id === "AI_FAILED" ? t("finish.aiFailed") : t(`lessonAuthoring.labels.${id}`) }));
  return (
    <section className="grid min-w-0 gap-4">
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
        className="grid gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          filter(
            "query",
            String(new FormData(e.currentTarget).get("query") || ""),
          );
        }}
      >
        <div className="flex gap-2">
        <Input
          className="min-w-0 flex-1"
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
        </div>
        <div className="grid grid-cols-2 gap-2 xl:grid-cols-3">
        <Filter
          label={t("catalog.allStatuses")}
          value={params.get("status") || ""}
          options={statuses}
          onChange={(v) => filter("status", v)}
        />
        <Filter
          label={t("catalog.allCategories")}
          value={params.get("category") || ""}
          options={categories.data?.map(category => ({ id: category.id, name: categoryName(category, i18n.language) })) ?? []}
          onChange={(v) => filter("category", v)}
        />
        <Filter label={t('finish.allSources')} value={params.get('source') || ''} options={['YOUTUBE','AUDIO','TEXT'].map(id=>({id,name:id === 'YOUTUBE' ? 'YouTube' : t(id === 'AUDIO' ? 'media.audioType' : 'media.textType')}))} onChange={v=>filter('source',v)} />
        <Filter label={t('finish.allLevels')} value={params.get('cefr') || ''} options={['A1','A2','B1','B2','C1','C2','UNKNOWN'].map(id=>({id,name:id === 'UNKNOWN' ? t('contentAuthoring.types.UNKNOWN') : id}))} onChange={v=>filter('cefr',v)} />
        <Filter label={t('finish.allActivities')} value={params.get('activity') || ''} options={['DICTATION','SHADOWING'].map(id=>({id,name:t(`contentAuthoring.${id}`)}))} onChange={v=>filter('activity',v)} />
        <Filter
          label={t("catalog.allChannels")}
          value={params.get("channel") || ""}
          options={
            channels.data?.map((c) => ({ id: c.id, name: c.title })) ?? []
          }
          onChange={(v) => filter("channel", v)}
        />
        </div>
      </form>
      <CatalogError
        error={restore.error || visibility.error || lessons.error || categories.error || channels.error}
        title={lessons.error ? t("catalog.lessonsLoadFailed") : undefined}
        retrying={lessons.isFetching || categories.isFetching || channels.isFetching}
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
          className="@container min-w-0 overflow-hidden rounded-xl border"
          aria-busy={lessons.isFetching}
        >
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("catalog.lesson")}</TableHead>
                <TableHead className={`${wideColumn} w-32`}>{t("catalog.step_source")}</TableHead>
                <TableHead className={`${wideColumn} w-20`}>{t("catalog.level")}</TableHead>
                <TableHead className={`${wideColumn} w-24`}>{t("catalog.duration")}</TableHead>
                <TableHead className={`${wideColumn} w-28`}>{t("catalog.activities")}</TableHead>
                <TableHead className="w-32">{t("catalog.status")}</TableHead>
                <TableHead className={`${wideColumn} w-28`}>{t("catalog.access")}</TableHead>
                <TableHead className="w-44">{t("catalog.categories")}</TableHead>
                <TableHead className="w-24">{t("catalog.updated")}</TableHead>
                <TableHead className="w-44 text-right @min-[1280px]:w-64">{t("catalog.actions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {lessons.data.items.map((item) => {
                const source = item.channelTitle ?? (item.sourceType === "YOUTUBE" ? "YouTube" : t(item.sourceType === "AUDIO" ? "media.audioType" : "media.textType"));
                const level = item.cefr && item.cefr !== "UNKNOWN" && <CefrLevelBadge level={item.cefr} className="text-[11px]" />;
                const duration = item.startMs != null && item.endMs != null && item.endMs > item.startMs
                  ? timecode(item.endMs - item.startMs).split(".")[0]
                  : undefined;
                const activities = item.activities?.map((activity) => <span key={activity}>{t(`contentAuthoring.${activity}`)}</span>);
                const access = !item.deleted && <AccessTierBadge accessMode={item.accessMode} size="sm" />;
                const status = item.deleted ? "DELETED" : item.processing ? "RUNNING" : item.aiFailed ? "AI_FAILED" : item.status;
                return (
                  <TableRow key={item.id}>
                    <TableCell className="min-w-60 max-w-sm whitespace-normal">
                      <Link to={`/lessons/${item.id}`} className="flex items-center gap-3 hover:underline">
                        <CatalogImage url={item.imageUrl} title="" />
                        <span className="min-w-0">
                          <strong className="line-clamp-2 font-medium">{item.title}</strong>
                          <span className="mt-1 block text-xs text-muted-foreground @min-[1280px]:hidden">{source}</span>
                          <span className="mt-1 flex flex-wrap items-center gap-x-2 text-xs tabular-nums text-muted-foreground @min-[1280px]:hidden">
                            {level}
                            {duration && <span>{duration}</span>}
                            {activities}
                          </span>
                        </span>
                      </Link>
                    </TableCell>
                    <TableCell className={`${wideColumn} max-w-36 whitespace-normal text-xs text-muted-foreground`}>
                      <span className="line-clamp-2 wrap-anywhere" title={source}>{source}</span>
                    </TableCell>
                    <TableCell className={wideColumn}>{level || "—"}</TableCell>
                    <TableCell className={`${wideColumn} text-xs tabular-nums text-muted-foreground`}>{duration ?? "—"}</TableCell>
                    <TableCell className={`${wideColumn} text-xs text-muted-foreground`}>
                      <div className="grid gap-0.5">{activities?.length ? activities : "—"}</div>
                    </TableCell>
                    <TableCell className="whitespace-normal">
                      <div className="flex flex-col items-start gap-2">
                        <Badge variant={statusBadgeVariant(status)}>
                          {item.deleted ? t("finish.deleted") : item.processing ? t("finish.processingBadge") : item.aiFailed ? t("finish.aiFailed") : t(`lessonAuthoring.labels.${item.status}`, { defaultValue: item.status })}
                        </Badge>
                        {access && <div className="@min-[1280px]:hidden">{access}</div>}
                        {(item.processing || item.aiFailed) && !item.deleted && <span className="text-xs text-muted-foreground">{t(`lessonAuthoring.labels.${item.status}`, { defaultValue: item.status })}</span>}
                        {item.status === "PUBLISHED" && !item.deleted && item.channelVisible === false && <span className="text-xs text-warning">{t("catalog.hiddenByChannel")}</span>}
                      </div>
                    </TableCell>
                    <TableCell className={wideColumn}>{access || "—"}</TableCell>
                    <TableCell className="max-w-44 whitespace-normal">
                      <div className="flex flex-wrap gap-1">
                        {!item.categories.length && <span className="text-muted-foreground">—</span>}
                        {item.categories.slice(0, 2).map(category => <Badge key={category.id} variant="outline" className="max-w-full"><span className="truncate" title={categoryName(category, i18n.language)}>{categoryName(category, i18n.language)}</span></Badge>)}
                        {item.categories.length > 2 && <Badge variant="outline" title={item.categories.slice(2).map(category => categoryName(category, i18n.language)).join(", ")} aria-label={t("catalog.moreCategories", { count: item.categories.length - 2 })}>+{item.categories.length - 2}</Badge>}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs tabular-nums text-muted-foreground">
                      <time dateTime={item.updatedAt} title={new Date(item.updatedAt).toLocaleString(i18n.language)}>{new Date(item.updatedAt).toLocaleDateString(i18n.language)}</time>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-col items-end gap-1 @min-[1280px]:flex-row @min-[1280px]:justify-end @min-[1280px]:gap-3">
                        {item.status === "PUBLISHED" && !item.deleted && <label className="flex items-center gap-2 text-xs text-muted-foreground">
                          <Switch size="sm" aria-label={t("catalog.lessonVisibility", { title: item.title })} checked={item.visible} disabled={pendingVisibilityIds.has(item.id)} onCheckedChange={visible => visibility.mutate({ id: item.id, visible, version: item.version })} />
                          {t(item.visible ? "catalog.visible" : "catalog.hiddenShort")}
                        </label>}
                        <div className="flex items-center justify-end gap-1">
                          {item.deleted && <Button size="sm" variant="outline" disabled={restore.isPending} onClick={()=>restore.mutate(item)}>{t('finish.restore')}</Button>}
                          <Button disabled={item.deleted} variant="ghost" size="icon" aria-label={t("catalog.editMetadata")} title={t("catalog.editMetadata")} onClick={() => setEditing(item)}>
                            <Pencil aria-hidden="true" />
                          </Button>
                          <Button variant="outline" size="sm" asChild>
                            <Link to={`/lessons/${item.id}`}>
                              {t(
                                item.status === "PUBLISHED"
                                  || item.deleted ? "catalog.view"
                                  : "catalog.resume",
                              )}
                              <ArrowRight data-icon="inline-end" aria-hidden="true" />
                            </Link>
                          </Button>
                          {!item.deleted && (
                            <Button
                              variant="ghost"
                              size="icon"
                              className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                              disabled={item.processing}
                              aria-label={t("finish.delete")}
                              title={t("finish.delete")}
                              onClick={() => { deletion.reset(); setDeleting(item); }}
                            >
                              <Trash2 aria-hidden="true" />
                            </Button>
                          )}
                        </div>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
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
          <AdminPagination page={lessons.data.page} pageCount={Math.ceil(lessons.data.total / lessons.data.size)} disabled={lessons.isFetching || lessons.isError} onPageChange={page => setParams(previous => {
            const next = new URLSearchParams(previous);
            if (page) next.set("page", String(page)); else next.delete("page");
            return next;
          })} />
        </footer>
      )}
      <Dialog
        open={!!editing}
        onOpenChange={(open) => {
          if (!open) setEditing(undefined);
        }}
      >
        <DialogContent className="flex max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-xl">
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
          if (!open && !deletion.isPending) setDeleting(undefined);
        }}
      >
        <DialogContent showCloseButton={!deletion.isPending}>
          <DialogHeader>
            <DialogTitle>{t("finish.delete")}</DialogTitle>
            <DialogDescription>
              {t(deleting?.status === 'PUBLISHED' ? 'finish.softDeleteHelp' : 'finish.deleteHelp', { title: deleting?.title })}
            </DialogDescription>
          </DialogHeader>
          <CatalogError error={deletion.error} />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={deletion.isPending} onClick={() => setDeleting(undefined)}>{t("catalog.cancel")}</Button>
            <Button
              variant="destructive"
              disabled={deletion.isPending}
              onClick={() => deleting && deletion.mutate(deleting)}
            >
              {t(deletion.isPending ? "catalog.deleting" : "finish.delete")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
