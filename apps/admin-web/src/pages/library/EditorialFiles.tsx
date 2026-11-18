import { LibrarySelect } from "./LibrarySelect";
import { AdminPagination } from "@/components/AdminPagination";
import { RiUpload2Line, RiDownload2Line } from "@remixicon/react";
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { http } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { FilePicker } from "@/components/FilePicker";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";

type Preview = { valid: number; invalid: number; rows: { row: number; content: { title?: string } | null; errors: string[] }[] };
export function EditorialImport({ endpoint }: { endpoint: string }) {
  const { t } = useTranslation("admin");
  const cache = useQueryClient();
  const [open, setOpen] = useState(false);
  const [file, setFile] = useState<File>();
  const [preview, setPreview] = useState<Preview>();
  const [page, setPage] = useState(0);
  const [imported, setImported] = useState<number>();
  const mutation = useMutation({ mutationFn: async (apply: boolean) => {
    if (!file || file.size > 10 * 1024 * 1024) throw new Error(t("library.importLimits"));
    const body = new FormData(); body.append("file", file);
    return (await http.post<Preview | unknown[]>(`${endpoint}/import?apply=${apply}`, body)).data;
  }, onSuccess: async result => {
    if (Array.isArray(result)) { setImported(result.length); setPreview(undefined); setFile(undefined); await cache.invalidateQueries({ queryKey: ["library", endpoint] }); }
    else { setPreview(result); setPage(0); }
  } });
  return <><Button tooltip={t("library.help.importContent")} variant="outline" onClick={() => setOpen(true)}><RiUpload2Line aria-hidden="true" />{t("library.importContent")}</Button><Dialog open={open} onOpenChange={setOpen}><DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl"><DialogHeader><DialogTitle>{t("library.importContent")}</DialogTitle></DialogHeader><p className="text-sm text-muted-foreground">{t("library.importLimits")}</p><p className="text-xs text-muted-foreground">{t("library.importTemplate")}</p><FilePicker label={t("library.chooseFile")} accept={endpoint.endsWith("toeic") ? ".json" : ".csv,.json"} fileName={file?.name} disabled={mutation.isPending} onSelect={file => { setFile(file); setPage(0); setPreview(undefined); setImported(undefined); }} /><Button tooltip={t("library.help.previewImport")} disabled={!file || mutation.isPending} onClick={() => mutation.mutate(false)}>{t("library.previewImport")}</Button><CatalogError error={mutation.error} />{imported !== undefined && <p role="status">{t("library.importedDrafts", { count: imported })}</p>}{preview && <><p className="text-sm">{t("library.importSummary", { valid: preview.valid, invalid: preview.invalid })}</p><div className="grid gap-2">{preview.rows.slice(page * 20, (page + 1) * 20).map(row => <div key={row.row} className="rounded-lg border p-3 text-sm"><p>{row.row}. {row.content?.title}</p>{row.errors.map((message, index) => <p key={index} className="text-destructive">{message}</p>)}</div>)}</div><AdminPagination page={Math.min(page, Math.max(0, Math.ceil(preview.rows.length / 20) - 1))} pageCount={Math.ceil(preview.rows.length / 20)} onPageChange={setPage} /><Button tooltip={t("library.help.importAsDrafts")} disabled={preview.invalid > 0 || mutation.isPending} onClick={() => mutation.mutate(true)}>{t("library.importAsDrafts")}</Button></>}</DialogContent></Dialog></>;
}

export function EditorialExport({ endpoint, id, disabled }: { endpoint: string; id: string; disabled: boolean }) {
  const { t } = useTranslation("admin");
  const [format, setFormat] = useState("json");
  const csv = endpoint.endsWith("lexicon") || endpoint.endsWith("assessment-items");
  const mutation = useMutation({ mutationFn: async () => {
    const blob = (await http.get<Blob>(`${endpoint}/${id}/export?format=${format}`, { responseType: "blob" })).data;
    const url = URL.createObjectURL(blob); const anchor = window.document.createElement("a"); anchor.href = url; anchor.download = `lyreo-content.${format}`; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  } });
  return <div className="flex flex-wrap items-center gap-2">{csv && <div className="w-24"><LibrarySelect label={t("library.exportFormat")} value={format} onValueChange={setFormat} hideLabel options={[{value: "json", label: "JSON"}, {value: "csv", label: "CSV"}]} /></div>}<Button tooltip={t("library.help.exportFile")} variant="outline" disabled={disabled || mutation.isPending} onClick={() => mutation.mutate()}><RiDownload2Line aria-hidden="true" />{t("library.exportContent")}</Button><CatalogError error={mutation.error} /></div>;
}
