import "./library.css";
import { Fragment, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api, ApiError } from "@/lib/api";
import { Field, FieldLabel } from "@/components/ui/field";
import { RiArrowLeftLine, RiHistoryLine, RiFileCopyLine, RiSaveLine, RiCheckLine, RiArrowGoBackLine } from "@remixicon/react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { CatalogError, CatalogSkeleton } from "@/pages/lessons/CatalogFeedback";
import { UnsavedChangesGuard } from "@/pages/lesson-builder/UnsavedChangesGuard";
import { EditorialExport } from "./EditorialFiles";
import type { Document, Publication } from "./library.types";

export function TextField({ label, value, onChange, multiline = false, disabled = false, rows = 3 }: { rows?: number; label: string; value: string | null; onChange: (value: string) => void; multiline?: boolean; disabled?: boolean }) {
  const id = useId();
  const props = { id, value: value ?? "", disabled, onChange: (event: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => onChange(event.target.value) };
  return <Field className="min-w-0 gap-2"><FieldLabel htmlFor={id}>{label}</FieldLabel>{multiline ? <Textarea {...props} rows={rows} /> : <Input {...props} />}</Field>;
}

export { LibraryList } from "./LibraryCatalog";

export function DraftLoader<T>({ endpoint, id, initial, children }: { endpoint: string; id: string; initial: T; children: (document: Document<T>) => ReactNode }) {
  const query = useQuery({ queryKey: ["library", endpoint, id], enabled: id !== "new", queryFn: ({ signal }) => api<Document<T>>(`${endpoint}/${id}`, { signal }), refetchOnWindowFocus: false });
  if (id === "new") return children({ id: "new", revision: 0, status: "DRAFT", publicationId: null, hasUnpublishedChanges: true, draft: initial, updatedAt: null, updatedBy: null });
  return <><CatalogError error={query.error} retry={() => void query.refetch()} />{query.isPending ? <CatalogSkeleton /> : query.data && children(query.data)}</>;
}

export function DraftWorkspace<T extends { title: string; titleEn?: string | null }>({ document: initial, endpoint, route, duplicate, assistant, pendingEditorChanges = false, children }: { pendingEditorChanges?: boolean; document: Document<T>; endpoint: string; route: string; duplicate?: (value: T) => T | null; assistant?: (current: Document<T>, dirty: boolean, adopt: (result: Document<T>) => void) => ReactNode; children: (value: T, change: (value: T) => void, dirty: boolean, savedRevision: number, document: Document<T>, adopt: (result: Document<T>) => void) => ReactNode }) {
  const { t, i18n } = useTranslation("admin");
  const navigate = useNavigate();
  const cache = useQueryClient();
  const [document, setDocument] = useState(initial);
  const [value, setValue] = useState(initial.draft);
  const title = i18n.language.startsWith("en") ? value.titleEn || value.title : value.title || value.titleEn;
  const [historyOpen, setHistoryOpen] = useState(false);
  const [removed, setRemoved] = useState(false);
  const [editorEpoch, setEditorEpoch] = useState(0);
  const generation=useRef(0);
  const active=useRef(true);
  useEffect(()=>{active.current=true;return ()=>{active.current=false;};},[]);
  const currentSnapshot=useRef(initial);
  const localValue=useRef(value);localValue.current=value;
  const savedValue=useRef(document.draft);savedValue.current=document.draft;
  const resetEditors=()=>{generation.current++;setEditorEpoch(generation.current);};
  const applySnapshot=(result:Document<T>)=>{if(active.current) {currentSnapshot.current=result;localValue.current=result.draft;savedValue.current=result.draft;setDocument(result);setValue(result.draft);resetEditors();}};
  const change=(next:T)=>{if(active.current && generation.current===editorEpoch && currentSnapshot.current===document) {localValue.current=next;setValue(next);}};
  const adopt=(result:Document<T>)=>{if(active.current && generation.current===editorEpoch && currentSnapshot.current===document && JSON.stringify(localValue.current)===JSON.stringify(savedValue.current)) {currentSnapshot.current=result;localValue.current=result.draft;savedValue.current=result.draft;setDocument(result);setValue(result.draft);}};
  const reload=useMutation({mutationFn:()=>api<Document<T>>(`${endpoint}/${document.id}`),onSuccess:result=>{applySnapshot(result);mutation.reset();}});
  useEffect(() => { if (removed) navigate(route); }, [removed, navigate, route]);
  const remove = useMutation({ mutationFn: () => api(`${endpoint}/${document.id}?revision=${document.revision}`, { method: "DELETE" }), onSuccess: async () => { await cache.invalidateQueries({ queryKey: ["library", endpoint] }); if(active.current) setRemoved(true); } });
  const audit = useQuery({ queryKey: ["library-audit", endpoint, document.id], enabled: historyOpen && document.id !== "new", queryFn: ({ signal }) => api<{ action: string; revision: number; actor: string; reason: string | null; occurred_at: string }[]>(`${endpoint}/${document.id}/audit`, { signal }) });
  const changed = JSON.stringify(value) !== JSON.stringify(document.draft);
  const dirty = document.id === "new" || changed;
  const pendingPublication = changed || document.hasUnpublishedChanges;
  const hasPublication = document.publicationId !== null || document.status !== "DRAFT";
  const publishLabel = hasPublication ? "library.applyChanges" : "library.publish";
  const history = useQuery({ queryKey: ["library-history", endpoint, document.id], enabled: historyOpen && document.id !== "new", queryFn: ({ signal }) => api<Publication<T>[]>(`${endpoint}/${document.id}/history`, { signal }) });
  async function save() {
    return api<Document<T>>(document.id === "new" ? endpoint : `${endpoint}/${document.id}/draft`, { method: document.id === "new" ? "POST" : "PUT", body: JSON.stringify(document.id === "new" ? value : { revision: document.revision, content: value }) });
  }
  const mutation = useMutation({
    mutationFn: async (action: string) => {
      if (action === "duplicate" && duplicate) { const copy = duplicate(value); if (!copy) return; return api<Document<T>>(endpoint, { method: "POST", body: JSON.stringify(copy) }); }
      let current = document;
      if ((action === "save" || action === "publish") && (dirty || current.revision === 0)) { current = await save(); if(action === "publish") applySnapshot(current); }
      if (action === "save") return current;
      if (action.startsWith("restore:")) return api<Document<T>>(`${endpoint}/${current.id}/restore`, { method: "POST", body: JSON.stringify({ revision: current.revision, publicationId: action.slice(8) }) });
      return api<Document<T>>(`${endpoint}/${current.id}/${action === "publish" ? "publish" : "status"}`, { method: action === "publish" ? "POST" : "PUT", body: JSON.stringify({ revision: current.revision, status: action }) });
    },
    onSuccess: async result => { if (!result) return; applySnapshot(result); await cache.invalidateQueries({ queryKey: ["library", endpoint] }); await cache.invalidateQueries({ queryKey: ["library-history", endpoint] }); if (active.current && initial.id !== result.id) navigate(`${route}/${result.id}`, { replace: initial.id === "new" }); },
  });
  const busy=mutation.isPending || remove.isPending || reload.isPending;
  return <section className="library-workspace library-editor">
    <UnsavedChangesGuard dirty={!removed && (changed || pendingEditorChanges)} includeSearch={pendingEditorChanges} />
    <header className="library-editor-header"><div className="library-editor-heading"><Link className="text-xs text-muted-foreground hover:underline" to={route}><RiArrowLeftLine className="mr-1 inline size-3" aria-hidden="true" />{t("library.back")}</Link><h1 title={title ?? undefined}>{title || t(route.endsWith("questions") ? "library.untitledQuestion" : "library.untitled")}</h1><div className="mt-2 flex flex-wrap items-center gap-2"><Badge variant={document.status === "PUBLISHED" ? "success" : document.status === "DRAFT" ? "warning" : "secondary"}>{t(`library.states.${document.status}`)}</Badge><span className="text-xs text-muted-foreground">{document.revision === 0 ? t(document.id === "new" ? "library.newDraft" : "library.sourceVersion") : t("library.revision", { revision: document.revision })} {changed || pendingEditorChanges ? `· ${t("library.unsaved")}` : document.hasUnpublishedChanges ? `· ${t("library.pendingPublication")}` : ""}</span></div></div><div className="library-editor-actions"><Button tooltip={t("library.help.history")} variant="ghost" disabled={busy || document.id === "new"} onClick={() => setHistoryOpen(true)}><RiHistoryLine aria-hidden="true" />{t("library.history")}</Button>{duplicate && <Button tooltip={t("library.help.duplicate")} variant="outline" disabled={busy} onClick={() => mutation.mutate("duplicate")}><RiFileCopyLine aria-hidden="true" />{t("library.duplicate")}</Button>}</div></header>
    <Fragment key={`assistant-${editorEpoch}`}>{assistant?.(document, dirty || busy, adopt)}</Fragment>
    <fieldset key={editorEpoch} className="library-editor-body" disabled={busy}>{children(value, change, dirty, document.revision, document, adopt)}</fieldset>
    <footer className="library-editor-dock" aria-label={t("library.editorActions")} aria-busy={busy}>
      <div className="grid w-full gap-2 empty:hidden">
        <CatalogError error={mutation.error || remove.error || reload.error} />
        {mutation.error instanceof ApiError && mutation.error.status === 409 && <p className="rounded-lg border p-4 text-sm">{t("library.conflictHelp")} <Button variant="link" disabled={busy} onClick={() => reload.mutate()}>{t("library.reload")}</Button></p>}
      </div>
      <p className="w-full text-xs text-muted-foreground">{t(pendingPublication ? "library.pendingPublicationHelp" : "library.upToDate")}</p>
      {document.id !== "new" && <><EditorialExport endpoint={endpoint} id={document.id} disabled={dirty || pendingEditorChanges || busy} />
        {document.publicationId === null && document.revision > 0 && document.status === "DRAFT" && <Button tooltip={t("library.help.deleteDraft")} variant="ghost" disabled={busy || dirty || pendingEditorChanges} onClick={() => { if (window.confirm(t("library.deleteDraftConfirm"))) remove.mutate(); }}>{t("library.deleteDraft")}</Button>}
        {hasPublication && <Button tooltip={t(document.status === "PUBLISHED" ? "library.help.hide" : "library.help.show")} variant="ghost" disabled={busy || changed || pendingEditorChanges} onClick={() => mutation.mutate(document.status === "PUBLISHED" ? "HIDDEN" : "PUBLISHED")}>{t(document.status === "PUBLISHED" ? "library.hide" : "library.show")}</Button>}</>}
      <div className="ml-auto flex flex-wrap items-center gap-2">{(changed || pendingEditorChanges) && <Button variant="ghost" disabled={busy} tooltip={t("library.discardHelp")} onClick={() => { setValue(structuredClone(document.draft)); resetEditors(); mutation.reset(); }}><RiArrowGoBackLine aria-hidden="true" />{t("library.discard")}</Button>}<Button tooltip={t("library.help.save")} variant="outline" disabled={busy || pendingEditorChanges || !dirty} onClick={() => mutation.mutate("save")}><RiSaveLine aria-hidden="true" />{t("library.save")}</Button>{pendingPublication && <Button tooltip={t("library.help.publish")} disabled={busy || pendingEditorChanges} onClick={() => mutation.mutate("publish")}><RiCheckLine aria-hidden="true" />{t(publishLabel)}</Button>}</div>
    </footer>

    <Dialog open={historyOpen} onOpenChange={setHistoryOpen}><DialogContent className="max-h-[85vh] overflow-y-auto"><DialogHeader><DialogTitle>{t("library.history")}</DialogTitle></DialogHeader><CatalogError error={history.error || audit.error} />{history.data?.map(row => <div key={row.id} className="grid gap-2 rounded-lg border p-4"><p className="font-medium">{t("library.revision", { revision: row.revision })}</p><p className="break-all text-xs text-muted-foreground">{new Date(row.publishedAt).toLocaleString()} · {row.publishedBy}</p><Button tooltip={t(changed || pendingEditorChanges ? "library.restoreSaveFirst" : "library.help.restore")} variant="outline" disabled={busy || changed || pendingEditorChanges || row.id === document.publicationId} onClick={() => { if (window.confirm(t("library.restoreConfirm"))) mutation.mutate(`restore:${row.id}`); }}>{t("library.restore")}</Button></div>)}{history.data?.length === 0 && <p className="text-muted-foreground">{t("library.noHistory")}</p>}<h3 className="mt-3 font-medium">{t("library.auditTrail")}</h3>{audit.data?.map((row, index) => <div key={index} className="border-t pt-3 text-xs"><p>{t(`library.auditActions.${row.action}`, {defaultValue:row.action})} · {t("library.revision", { revision: row.revision })}</p><p className="break-all text-muted-foreground">{new Date(row.occurred_at).toLocaleString()} · {row.actor}</p>{row.reason && row.reason !== "Published content" && !row.reason.startsWith("Restore ") && <p>{row.reason}</p>}</div>)}</DialogContent></Dialog>
  </section>;
}
