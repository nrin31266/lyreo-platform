import { RiSparklingLine } from "@remixicon/react";
import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
import { fetchJob, cancelJob } from "@/pages/jobs/jobs.api";
import { VoiceSelect } from "@/pages/lesson-builder/VoiceSelect";
import { MediaPreview } from "./ContentMedia";
import type { Document } from "./library.types";

type Suggestion = { field: string; value: unknown };
type Request = { id: string; jobId: string; operation: string; baseRevision: number; acceptedRevision: number | null; applied: Record<string, unknown>; output: { suggestions: Suggestion[]; warnings: string[]; audioAssetId?: string | null } | null };

export function AiSuggestions<T>({ endpoint, document, dirty, adopt, resourcePath, context }: { resourcePath?: string; context?: { testRevision: number }; endpoint: string; document: Document<T>; dirty: boolean; adopt: (result: Document<T>) => void }) {
  const { t } = useTranslation("admin");
  const cache = useQueryClient();
  const [open, setOpen] = useState(false);
  const [ttsOpen, setTtsOpen] = useState(false);
  const [brief, setBrief] = useState("");
  const [voice, setVoice] = useState("");
  const text = document.draft as { title?: string; transcriptEn?: string };
  const audioText = text.transcriptEn;
  const base = resourcePath ?? `${endpoint}/${document.id}`;
  const path = `${base}/ai`;
  const history = useQuery({ queryKey: ["library-ai", path], enabled: open && document.revision > 0, queryFn: ({ signal }) => api<Request[]>(path, { signal }), refetchInterval: open ? 3000 : false });
  const active=useRef(true);
  useEffect(()=>{active.current=true;return ()=>{active.current=false;};},[]);
  async function savedDocument() {
    if (document.revision > 0) return document;
    const saved=await api<Document<T>>(`${base}/draft`, {method:"PUT", body:JSON.stringify({revision:0,content:document.draft,...context})});
    if(!active.current) throw new Error(t("library.aiStale"));
    adopt(saved); return saved;
  }
  const request = useMutation({ mutationFn: async () => {
    const current=await savedDocument();
    return api<Request>(path, { method: "POST", body: JSON.stringify({ ...context, revision: current.revision, operation: "SUGGEST", count: 20, brief }) });
  }, onSuccess: () => cache.invalidateQueries({ queryKey: ["library-ai", path] }) });
  const tts = useMutation({ mutationFn: async () => {
    const current=await savedDocument();
    return api<Request>(path, { method: "POST", body: JSON.stringify({ revision: current.revision, operation: "TTS", voice, reviewed: true }) });
  }, onSuccess: () => cache.invalidateQueries({ queryKey: ["library-ai", path] }) });
  return <details className="rounded-xl border bg-card p-5" open={open} onToggle={event => setOpen(event.currentTarget.open)}><summary className="cursor-pointer font-medium"><RiSparklingLine className="mr-2 inline size-4" aria-hidden="true" />{t("library.aiSuggestions")}</summary>{open && <div className="mt-4 grid gap-4"><p className="text-sm text-muted-foreground">{t("library.aiHelp")}</p><p className="text-xs text-muted-foreground">{t("library.aiQuestionContextHelp")}</p>{dirty && <p className="text-sm text-warning">{t("library.aiSaveFirst")}</p>}<details><summary className="cursor-pointer text-sm">{t("library.aiBrief")}</summary><Textarea className="mt-3" aria-label={t("library.aiBrief")} maxLength={2000} value={brief} onChange={event => setBrief(event.target.value)} /></details><Button tooltip={t("library.help.aiRequest")} className="justify-self-start" variant="outline" disabled={dirty || request.isPending || tts.isPending} onClick={() => request.mutate()}><RiSparklingLine aria-hidden="true" />{t(request.isPending ? "library.aiRequesting" : "library.aiRequest")}</Button>{!context && <details className="rounded-lg border p-4" onToggle={event => setTtsOpen(event.currentTarget.open)}><summary className="cursor-pointer text-sm font-medium">{t("library.aiTts")}</summary>{ttsOpen && <div className="mt-4 grid gap-3"><p className="whitespace-pre-wrap text-sm">{audioText || t("library.aiTranscriptRequired")}</p><VoiceSelect value={voice} onChange={setVoice} disabled={dirty || tts.isPending} /><p className="text-xs text-muted-foreground">{t("library.aiTtsConfirmHelp")}</p><Button tooltip={t("library.help.aiTtsConfirm")} className="justify-self-start" variant="outline" disabled={dirty || !audioText?.trim() || !voice || tts.isPending || request.isPending} onClick={() => tts.mutate()}>{t("library.aiTtsConfirm")}</Button></div>}</details>}<CatalogError error={history.error || request.error || tts.error} />{history.data?.map(row => <AiRequest key={row.id} request={row} path={path} context={context} document={document} dirty={dirty} adopt={adopt} refresh={() => cache.invalidateQueries({ queryKey: ["library-ai", path] })} />)}</div>}</details>;
}

function AiRequest<T>({ request, path, document, dirty, adopt, refresh, context }: { context?: {testRevision:number}; request: Request; path: string; document: Document<T>; dirty: boolean; adopt: (result: Document<T>) => void; refresh: () => Promise<unknown> }) {
  const { t } = useTranslation("admin");
  const cache = useQueryClient();
  const job = useQuery({ queryKey: ["job", request.jobId], queryFn: ({ signal }) => fetchJob(request.jobId, signal), refetchInterval: query => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(query.state.data?.status ?? "") ? false : 2500 });
  const cancel = useMutation({ mutationFn: () => cancelJob(request.jobId), onSuccess: () => cache.invalidateQueries({ queryKey: ["job", request.jobId] }) });
  const latest = useRef({dirty,revision:document.revision,alive:true});
  latest.current = {...latest.current,dirty,revision:document.revision};
  useEffect(() => {latest.current.alive=true;return () => {latest.current.alive=false;};}, []);
  const accept = useMutation({ mutationFn: async ({ kind, index }: { kind: string; index: number }) => {
    const result = await api<Document<T> | { document: Document<T> }>(`${path}/${request.id}/accept`, { method: "POST", body: JSON.stringify({ ...context, revision: document.revision, kind, index }) });
    if (!latest.current.alive || latest.current.dirty || latest.current.revision !== document.revision) return null;
    adopt("document" in result ? result.document : result);
  }, onSuccess: refresh });
  const active = ["QUEUED", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED"].includes(job.data?.status ?? "");
  const stale = (request.acceptedRevision ?? request.baseRevision) !== document.revision;
  return <article className="grid gap-3 border-t pt-4"><div className="flex flex-wrap items-center gap-3"><Badge variant="outline">{job.data && t(`lessonAuthoring.labels.${job.data.status}`, {defaultValue:job.data.status})}</Badge><span className="text-xs text-muted-foreground">{t("library.revision", { revision: request.baseRevision })}</span>{active && <Button tooltip={t("library.help.cancel")} variant="outline" size="sm" disabled={cancel.isPending || job.data?.status === "CANCEL_REQUESTED"} onClick={() => cancel.mutate()}>{t("library.cancel")}</Button>}</div><CatalogError error={job.error || cancel.error || accept.error} />{job.data?.errorMessage && <p role="alert" className="text-sm text-destructive">{job.data.errorMessage}</p>}{stale && request.output && <p className="text-sm text-warning">{t("library.aiStale")}</p>}{request.output?.audioAssetId && <div className="grid gap-3 rounded-lg border p-3"><Badge variant="outline">{t("library.syntheticAudio")}</Badge><MediaPreview assetId={request.output.audioAssetId} role="audio" /><Button tooltip={t("library.help.aiAccept")} variant="outline" disabled={dirty || stale || accept.isPending || !!request.applied["audio:0"]} onClick={() => accept.mutate({ kind: "audio", index: 0 })}>{t(request.applied["audio:0"] ? "library.aiAccepted" : "library.aiAccept")}</Button></div>}{request.output?.warnings.map((warning, index) => <p key={index} className="text-sm text-muted-foreground">{warning}</p>)}{request.output?.suggestions.map((row, index) => <div key={index} className="grid gap-2 rounded-lg bg-muted/30 p-3 text-sm"><p className="font-medium">{t(`library.aiFields.${row.field}`, { defaultValue: row.field })}</p><p className="whitespace-pre-wrap">{String(row.value)}</p><Button tooltip={t("library.help.aiAccept")} variant="outline" size="sm" className="justify-self-start" disabled={dirty || stale || accept.isPending || !!request.applied[`suggestion:${index}`]} onClick={() => accept.mutate({ kind: "suggestion", index })}>{t(request.applied[`suggestion:${index}`] ? "library.aiAccepted" : "library.aiAccept")}</Button></div>)}</article>;
}
