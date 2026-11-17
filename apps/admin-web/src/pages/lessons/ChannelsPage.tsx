import { RiRefreshLine as Refresh, RiHistoryLine as History, RiDeleteBinLine as Trash2 } from "@remixicon/react";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";
import { Empty, EmptyDescription } from "@/components/ui/empty";
import { CatalogError, CatalogSkeleton, CatalogImage } from "./CatalogFeedback";
import { catalogKeys } from "./catalog.queries";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Switch } from "@/components/ui/switch";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "@/components/ui/dialog";
import { fetchChannels, syncChannels, fetchSyncConfiguration, setChannelVisibility, deleteChannel, type Channel } from "./catalog.api";
const active = (status: string | null) =>
  !!status &&
  ["QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED"].includes(status);
export function ChannelsPage({ embedded = false }: { embedded?: boolean }) {
  const { t, i18n } = useTranslation("admin");
  const client = useQueryClient();
  const [selected, setSelected] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [deleting, setDeleting] = useState<Channel>();
  const query = useQuery({
    queryKey: catalogKeys.channels,
    queryFn: ({ signal }) => fetchChannels(signal),
    refetchInterval: (q) =>
      q.state.data?.some((c) => active(c.syncStatus)) ? 2500 : false,
  });
  const configuration = useQuery({ queryKey: [...catalogKeys.channels, 'sync-configuration'], queryFn: ({ signal }) => fetchSyncConfiguration(signal) });
  const visibility = useMutation({
    mutationFn: ({ id, visible }: { id: string; visible: boolean }) => setChannelVisibility(id, visible),
    onSuccess: () => client.invalidateQueries({ queryKey: catalogKeys.all }),
  });
  const mutation = useMutation({
    mutationFn: syncChannels,
    onSuccess: async () => {
      setSelected([]);
      await client.invalidateQueries({ queryKey: catalogKeys.channels });
    },
  });
  const deletion = useMutation({
    mutationFn: deleteChannel,
    onSuccess: async (_, id) => {
      setDeleting(undefined);
      setSelected((ids) => ids.filter((selectedId) => selectedId !== id));
      await client.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
  const mutating = mutation.isPending || deletion.isPending || visibility.isPending;
  const channels = query.data?.filter(channel => `${channel.title} ${channel.id}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())) ?? [];
  return (
    <section className="grid gap-4">
      {!embedded && <h1>{t("catalog.channels")}</h1>}
      <p className="text-sm text-muted-foreground">{t("catalog.channelVisibilityHelp")} {t("catalog.channelDeleteHelp")}</p>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          <Input className="max-w-sm" aria-label={t("catalog.searchChannels")} placeholder={t("catalog.searchChannels")} value={search} onChange={event => setSearch(event.target.value)} />
          {query.data && <span className="shrink-0 text-sm text-muted-foreground">{t("catalog.channelCount", { count: query.data.length })}</span>}
        </div>
        <Button variant="outline" title={t("catalog.channelSyncHelp")}
          disabled={!configuration.data?.configured || !selected.length || selected.length > 50 || mutating || query.data?.some((c) => selected.includes(c.id) && active(c.syncStatus))}
          onClick={() => mutation.mutate(selected)}>
          <Refresh data-icon="inline-start" aria-hidden="true" />
          {t("catalog.syncSelected", { count: selected.length })}
        </Button>
      </div>
      <CatalogError
        error={query.error || configuration.error || visibility.error}
        retry={() => void Promise.all([query.refetch(), configuration.refetch()])}
      />
      {configuration.data?.configured === false && <Alert><AlertDescription>{t('catalog.syncNotConfigured')}</AlertDescription></Alert>}
      <CatalogError error={mutation.error} retry={() => mutation.variables && mutation.mutate(mutation.variables)} retrying={mutation.isPending} />
      {query.isPending ? (
        <CatalogSkeleton />
      ) : channels.length ? (
        <div className="overflow-hidden rounded-xl border"><Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-10"><span className="sr-only">{t("catalog.select")}</span></TableHead>
              <TableHead>{t("catalog.channels")}</TableHead>
              <TableHead className="w-24 text-right">{t("catalog.lessonCount")}</TableHead>
              <TableHead className="w-44">{t("catalog.syncState")}</TableHead>
              <TableHead className="w-32">{t("catalog.visibility")}</TableHead>
              <TableHead className="w-32 text-right">{t("catalog.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {channels.map((c) => (
              <TableRow key={c.id} data-state={selected.includes(c.id) ? "selected" : undefined}>
                <TableCell>
                  <Checkbox aria-label={t("catalog.selectChannel", { title: c.title })} disabled={mutating || active(c.syncStatus)} checked={selected.includes(c.id)}
                    onCheckedChange={checked => setSelected(old => checked ? [...old, c.id] : old.filter(id => id !== c.id))} />
                </TableCell>
                <TableCell className="whitespace-normal">
                  <Link to={`/lessons?channel=${encodeURIComponent(c.id)}`} className="flex items-center gap-3 font-medium hover:underline" title={c.id}>
                    <CatalogImage avatar url={c.avatarUrl} title="" />
                    <span className="min-w-0 break-words">{c.title}</span>
                  </Link>
                </TableCell>
                <TableCell className="text-right tabular-nums"><Link className="hover:underline" to={`/lessons?channel=${encodeURIComponent(c.id)}`}>{c.lessonCount}</Link></TableCell>
                <TableCell className="whitespace-normal">
                  <div className="flex flex-col items-start gap-1">
                    <Badge variant={active(c.syncStatus) ? "secondary" : c.syncError || c.syncStatus === "FAILED" ? "destructive" : c.syncedAt ? "success" : "outline"}>
                      {active(c.syncStatus) ? t(`lessonAuthoring.labels.${c.syncStatus}`) : t(c.syncError || c.syncStatus === "FAILED" ? "lessonAuthoring.labels.FAILED" : c.syncedAt ? "catalog.synced" : "catalog.neverSynced")}
                    </Badge>
                    {c.syncedAt && <span className="text-xs tabular-nums text-muted-foreground">{new Date(c.syncedAt).toLocaleString(i18n.language, { dateStyle: "short", timeStyle: "short" })}</span>}
                    {c.syncError && <p className="line-clamp-2 max-w-44 break-words text-xs text-destructive" title={c.syncError}>{c.syncError}</p>}
                  </div>
                </TableCell>
                <TableCell>
                  <label className="flex items-center gap-2 text-sm">
                    <Switch aria-label={t("catalog.channelVisibility", { title: c.title })} checked={c.visible} disabled={mutating} onCheckedChange={visible => visibility.mutate({ id: c.id, visible })} />
                    <span className="text-muted-foreground">{t(c.visible ? "catalog.visible" : "catalog.hiddenShort")}</span>
                  </label>
                </TableCell>
                <TableCell><div className="flex items-center justify-end gap-1">
                  <Button variant="ghost" size="icon" aria-label={t("catalog.syncChannelNamed", { title: c.title })} title={t(active(c.syncStatus) ? "catalog.syncing" : "catalog.sync")}
                    disabled={!configuration.data?.configured || mutating || active(c.syncStatus)} onClick={() => mutation.mutate([c.id])}>
                    <Refresh aria-hidden="true" className={active(c.syncStatus) ? "motion-safe:animate-spin" : undefined} />
                  </Button>
                  {c.syncJobId && <Button variant="ghost" size="icon" asChild title={t("lessonAuthoring.inspectJob")}><Link to={`/jobs?job=${c.syncJobId}`} aria-label={t("catalog.channelJobNamed", { title: c.title })}><History aria-hidden="true" /></Link></Button>}
                  <span className="inline-flex" title={c.linkedLessonCount > 0 ? t("catalog.channelDeleteInUse") : active(c.syncStatus) ? t("catalog.channelDeleteSyncing") : t("catalog.deleteChannel")}>
                    <Button variant="ghost" size="icon" className="text-destructive hover:bg-destructive/10 hover:text-destructive" aria-label={t("catalog.deleteChannelNamed", { title: c.title })} disabled={mutating || c.linkedLessonCount > 0 || active(c.syncStatus)} onClick={() => { deletion.reset(); setDeleting(c); }}><Trash2 aria-hidden="true" /></Button>
                  </span>
                </div></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table></div>
      ) : (
        !query.isError && <Empty>
          <EmptyDescription>{t(query.data?.length ? "catalog.noChannelMatches" : "catalog.noChannels")}</EmptyDescription>
        </Empty>
      )}
      <Dialog open={!!deleting} onOpenChange={(open) => { if (!open && !deletion.isPending) setDeleting(undefined); }}>
        <DialogContent showCloseButton={!deletion.isPending}>
          <DialogHeader>
            <DialogTitle>{t("catalog.deleteChannel")}</DialogTitle>
            <DialogDescription>{t("catalog.channelDeleteConfirm", { title: deleting?.title })}</DialogDescription>
          </DialogHeader>
          <CatalogError error={deletion.error} />
          <DialogFooter>
            <Button variant="outline" disabled={deletion.isPending} onClick={() => setDeleting(undefined)}>{t("catalog.cancel")}</Button>
            <Button variant="destructive" disabled={deletion.isPending} onClick={() => deleting && deletion.mutate(deleting.id)}>
              {t(deletion.isPending ? "catalog.deleting" : "catalog.deleteChannel")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
