import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { ApiError, http } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { FilePicker } from "@/components/FilePicker";
import { AudioPlayer } from "@/components/AudioPlayer";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
import { RiDeleteBinLine, RiArrowGoBackLine, RiCloseLine } from "@remixicon/react";
import type { Media } from "./library.types";

export function MediaPreview({ assetId, role, autoPlay, compact = false }: Media & { autoPlay?: boolean; compact?: boolean }) {
  const { t } = useTranslation("admin");
  const [url, setUrl] = useState("");
  const query = useQuery({ queryKey: ["content-media", assetId], queryFn: async ({ signal }) => (await http.get<Blob>(`/api/v1/admin/content-media/${assetId}/file`, { responseType: "blob", signal })).data, staleTime: Infinity, gcTime: 0 });
  useEffect(() => { if (!query.data) { setUrl(""); return; } const next = URL.createObjectURL(query.data); setUrl(next); return () => URL.revokeObjectURL(next); }, [query.data]);
  return <>{!compact && <CatalogError error={query.error} retry={() => void query.refetch()} />}{query.isPending && !compact && <p role="status" className="text-xs text-muted-foreground">{t("library.loadingMedia")}</p>}{url && (role === "audio" ? <AudioPlayer src={url} autoPlay={autoPlay} aria-label={t("library.audioPreview")} /> : <img className="max-h-64 rounded-lg object-contain" src={url} alt={t("library.imagePreview")} />)}</>;
}

export function MediaEditor({ value, onChange, kind }: { value: Media[]; onChange: (value: Media[]) => void; kind?: Media['role'] }) {
  const { t } = useTranslation("admin");
  const [removed,setRemoved]=useState<{media:Media;index:number}|null>(null);
  const latest = useRef({value,onChange});
  latest.current = {value,onChange};
  const alive = useRef(true);
  const upload = useRef<AbortController | null>(null);
  const generation = useRef(0);
  useEffect(()=>{alive.current=true;return ()=>{alive.current=false;upload.current?.abort();};},[]);
  const mutation = useMutation({ mutationFn: async (file: File) => {
    if (kind && file.type && !file.type.startsWith(`${kind}/`)) throw new ApiError(400, t("library.wrongMediaType"), "MEDIA_TYPE", t("library.wrongMediaType"));
    const controller = new AbortController(); upload.current = controller;
    const requestGeneration = generation.current;
    const body = new FormData(); body.append("file", file);
    const asset = (await http.post<{ id: string; mimeType: string }>("/api/v1/admin/content-media", body, {signal:controller.signal})).data;
    if (kind && !asset.mimeType.startsWith(`${kind}/`)) throw new ApiError(400, t("library.wrongMediaType"), "MEDIA_TYPE", t("library.wrongMediaType"));
    return {asset,generation:requestGeneration};
  }, onSuccess: ({asset,generation:requestGeneration}) => { if(alive.current && generation.current === requestGeneration) latest.current.onChange([...latest.current.value, { assetId: asset.id, role: asset.mimeType.startsWith("audio/") ? "audio" : "image" }]); } });
  return <div className="grid gap-4"><div className="grid gap-2"><FilePicker label={t("library.uploadMedia")} accept={kind === 'audio' ? "audio/mpeg,audio/wav,audio/mp4" : kind === 'image' ? "image/jpeg,image/png,image/webp" : "audio/mpeg,audio/wav,audio/mp4,image/jpeg,image/png,image/webp"} fileName={mutation.variables?.name} disabled={mutation.isPending} onSelect={file => mutation.mutate(file)} /><p className="text-xs text-muted-foreground">{t(kind === 'audio' ? "library.audioLimits" : "library.mediaLimits")}</p>{mutation.isPending && <div className="flex items-center gap-2"><p role="status" className="text-xs text-muted-foreground">{t("library.uploadingMedia")}</p><Button type="button" size="sm" variant="ghost" tooltip={t("library.cancelUpload")} onClick={()=>{generation.current++;upload.current?.abort();mutation.reset();}}><RiCloseLine aria-hidden="true" />{t("library.cancelUpload")}</Button></div>}</div><CatalogError error={mutation.error} />{value.map((media, index) => <div key={`${media.assetId}-${index}`} className="flex items-start gap-2 rounded-lg border p-2 [&>audio]:min-w-0"><div className="min-w-0 flex-1"><MediaPreview {...media} /></div><Button tooltip={t("library.help.remove")} aria-label={t("library.remove")} variant="ghost" size="icon-sm" onClick={() => {setRemoved({media,index});onChange(value.filter((_, i) => i !== index));}}><RiDeleteBinLine aria-hidden="true" /></Button></div>)}{removed && <Button className="justify-self-start" variant="ghost" size="sm" onClick={()=>{const restored=[...value];if(!restored.some(row=>row.assetId===removed.media.assetId && row.role===removed.media.role)) restored.splice(Math.min(removed.index,restored.length),0,removed.media);onChange(restored);setRemoved(null);}}><RiArrowGoBackLine aria-hidden="true" />{t("quality.undoRemove")}</Button>}</div>;
}
