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
import { fetchChannels, syncChannels } from "./catalog.api";
const active = (status: string | null) =>
  !!status &&
  ["QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED"].includes(status);
export function ChannelsPage() {
  const { t, i18n } = useTranslation("admin");
  const client = useQueryClient();
  const [selected, setSelected] = useState<string[]>([]);
  const query = useQuery({
    queryKey: catalogKeys.channels,
    queryFn: ({ signal }) => fetchChannels(signal),
    refetchInterval: (q) =>
      q.state.data?.some((c) => active(c.syncStatus)) ? 2500 : false,
  });
  const mutation = useMutation({
    mutationFn: syncChannels,
    onSuccess: async () => {
      setSelected([]);
      await client.invalidateQueries({ queryKey: catalogKeys.channels });
    },
  });
  return (
    <section className="grid gap-4">
      <div className="page-heading">
        <div>
          <h1>{t("catalog.channels")}</h1>
          <p className="lead">{t("catalog.channelSyncHelp")}</p>
        </div>
        <Button
          disabled={
            !selected.length || selected.length > 50 || mutation.isPending
          }
          onClick={() => mutation.mutate(selected)}
        >
          {t("catalog.syncSelected", { count: selected.length })}
        </Button>
      </div>
      <CatalogError
        error={query.error || mutation.error}
        retry={() => void query.refetch()}
      />
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
              <TableHead>{t("catalog.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {query.data.map((c) => (
              <TableRow key={c.id}>
                <TableCell>
                  <Checkbox
                    aria-label={t("catalog.selectChannel", { title: c.title })}
                    disabled={active(c.syncStatus)}
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
                  <Button
                    variant="outline"
                    disabled={mutation.isPending || active(c.syncStatus)}
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
    </section>
  );
}
