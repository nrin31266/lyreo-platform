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
  return (
    <section className="grid gap-4">
      <div className="page-heading">
        <div>
          {!embedded && <h1>{t("catalog.channels")}</h1>}
          <p className="lead">{t("catalog.channelSyncHelp")}</p>
          <p className="text-sm text-muted-foreground">{t("catalog.channelVisibilityHelp")}</p>
          <p className="text-sm text-muted-foreground">{t("catalog.channelDeleteHelp")}</p>
        </div>
        <Button
          disabled={
            !configuration.data?.configured || !selected.length || selected.length > 50 || mutating || query.data?.some((c) => selected.includes(c.id) && active(c.syncStatus))
          }
          onClick={() => mutation.mutate(selected)}
        >
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
      ) : query.data?.length ? (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("catalog.select")}</TableHead>
              <TableHead>{t("catalog.channels")}</TableHead>
              <TableHead>{t("catalog.lessonCount")}</TableHead>
              <TableHead>{t("catalog.updated")}</TableHead>
              <TableHead>{t("catalog.visibility")}</TableHead>
              <TableHead>{t("catalog.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {query.data.map((c) => (
              <TableRow key={c.id}>
                <TableCell>
                  <Checkbox
                    aria-label={t("catalog.selectChannel", { title: c.title })}
                    disabled={mutating || active(c.syncStatus)}
                    checked={selected.includes(c.id)}
                    onCheckedChange={(checked) =>
                      setSelected((old) =>
                        checked
                          ? [...old, c.id]
                          : old.filter((id) => id !== c.id),
                      )
                    }
                  />
                </TableCell>
                <TableCell>
                  <div className="flex items-center gap-3">
                    <CatalogImage avatar url={c.avatarUrl} title="" />
                    <div>
                      <Link
                        to={`/lessons?channel=${encodeURIComponent(c.id)}`}
                        className="font-medium"
                      >
                        {c.title}
                      </Link>
                      <span className="block text-xs text-muted-foreground">
                        {c.id}
                      </span>
                      {c.syncError && (
                        <p className="text-destructive">{c.syncError}</p>
                      )}
                    </div>
                  </div>
                </TableCell>
                <TableCell>{c.lessonCount}</TableCell>
                <TableCell>
                  {c.syncedAt
                    ? new Date(c.syncedAt).toLocaleString(i18n.language)
                    : t("catalog.neverSynced")}
                </TableCell>
                <TableCell>
                  <Switch aria-label={t('catalog.channelVisibility', { title: c.title })} checked={c.visible}
                    disabled={mutating} onCheckedChange={visible => visibility.mutate({ id: c.id, visible })} />
                </TableCell>
                <TableCell>
                  <Button
                    variant="outline"
                    disabled={!configuration.data?.configured || mutating || active(c.syncStatus)}
                    onClick={() => mutation.mutate([c.id])}
                  >
                    {t(
                      active(c.syncStatus) ? "catalog.syncing" : "catalog.sync",
                    )}
                  </Button>
                  {c.syncJobId && (
                    <Button variant="ghost" asChild>
                      <Link to={`/jobs?job=${c.syncJobId}`}>
                        {t("lessonAuthoring.inspectJob")}
                      </Link>
                    </Button>
                  )}
                  <Button
                    variant="ghost"
                    aria-label={t("catalog.deleteChannelNamed", { title: c.title })}
                    disabled={mutating || c.linkedLessonCount > 0 || active(c.syncStatus)}
                    title={c.linkedLessonCount > 0 ? t("catalog.channelDeleteInUse") : active(c.syncStatus) ? t("catalog.channelDeleteSyncing") : undefined}
                    onClick={() => { deletion.reset(); setDeleting(c); }}
                  >
                    {t("catalog.deleteChannel")}
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <Empty>
          <EmptyDescription>{t("catalog.empty")}</EmptyDescription>
        </Empty>
      )}
      <Dialog open={!!deleting} onOpenChange={(open) => { if (!open && !deletion.isPending) setDeleting(undefined); }}>
        <DialogContent>
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
