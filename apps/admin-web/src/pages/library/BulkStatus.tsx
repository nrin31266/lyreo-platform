import { LibrarySelect } from "./LibrarySelect";
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";

type Impact = { id: string; revision: number; title: string; status: string; usage: { namespace: string; title: string }[] };
export function BulkStatus({ endpoint, selected, clear }: { endpoint: string; selected: string[]; clear: () => void }) {
  const { t } = useTranslation("admin");const cache = useQueryClient();
  const [open, setOpen] = useState(false);const [status, setStatus] = useState("HIDDEN");const [impact, setImpact] = useState<Impact[]>();
  const preview = useMutation({ mutationFn: () => api<Impact[]>(`${endpoint}/bulk-preview`, { method: "POST", body: JSON.stringify({ items: selected.map(id => ({ id, revision: 0 })) }) }), onSuccess: result => { setImpact(result); setOpen(true); } });
  const apply = useMutation({ mutationFn: () => api(`${endpoint}/bulk-status`, { method: "POST", body: JSON.stringify({ items: impact?.map(({ id, revision }) => ({ id, revision })), status }) }), onSuccess: async () => { await cache.invalidateQueries({ queryKey: ["library", endpoint] }); setOpen(false); setImpact(undefined); clear(); } });
  return <><Button tooltip={t("library.help.bulkAction")} variant="outline" disabled={!selected.length || selected.length > 100 || preview.isPending} onClick={() => preview.mutate()}>{t("library.bulkAction", { count: selected.length })}</Button><CatalogError error={preview.error} /><Dialog open={open} onOpenChange={setOpen}><DialogContent className="max-h-[85vh] overflow-y-auto"><DialogHeader><DialogTitle>{t("library.bulkPreview")}</DialogTitle></DialogHeader><p className="text-sm text-muted-foreground">{t("library.bulkHelp")}</p><LibrarySelect label={t("library.state")} value={status} onValueChange={setStatus} options={["HIDDEN", "PUBLISHED"].map(value => ({value, label: t(`library.states.${value}`)}))} />{impact?.map(row => <div key={row.id} className="rounded-lg border p-3 text-sm"><p className="font-medium">{row.title}</p><p>{t(`library.states.${row.status}`)} · {t("library.revision", { revision: row.revision })}</p>{row.usage.map((usage, index) => <p key={index} className="text-xs text-muted-foreground">{t(`library.namespaces.${usage.namespace}`, {defaultValue:usage.namespace})}: {usage.title}</p>)}</div>)}<CatalogError error={apply.error} /><Button tooltip={t("library.help.applyBulk")} disabled={apply.isPending || !impact?.length} onClick={() => apply.mutate()}>{t("library.applyBulk")}</Button></DialogContent></Dialog></>;
}
