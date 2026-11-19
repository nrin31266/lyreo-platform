import { LibrarySelect } from "./LibrarySelect";
import { RiDownload2Line } from "@remixicon/react";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { http } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";

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
