import { useEffect, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { RiEditLine, RiSaveLine, RiArrowGoBackLine } from "@remixicon/react";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { CatalogError, CatalogSkeleton } from "@/pages/lessons/CatalogFeedback";
import { QuestionFields } from "../library/QuestionEditor";
import { AiSuggestions } from "../library/AiSuggestions";
import type { Document, Question, Placement, ToeicDraft } from "../library/library.types";

export function ToeicQuestion({testId,placement,revision,dirty,onSaved,onDirtyChange}: {testId:string;placement:Placement;revision:number;dirty:boolean;onSaved:(doc:Document<ToeicDraft>)=>void;onDirtyChange:(dirty:boolean)=>void}) {
  const {t}=useTranslation("admin");const [open,setOpen]=useState(false);
  const [editorDirty,setEditorDirty]=useState(false);
  useEffect(()=>{onDirtyChange(editorDirty);},[editorDirty,onDirtyChange]);
  useEffect(()=>()=>onDirtyChange(false),[onDirtyChange]);
  const path=`/api/v1/admin/toeic/${testId}/questions/${placement.id}`;
  const query=useQuery({queryKey:["toeic-question-editor",path,revision],enabled:open,queryFn:({signal})=>api<Document<Question>>(path,{signal})});
  return <><Button size="sm" variant="outline" tooltip={t("library.helpEditQuestion")} disabled={dirty} onClick={()=>{if(open && editorDirty && !window.confirm(t("catalog.unsaved"))) return;setOpen(!open);}}><RiEditLine aria-hidden="true" />{t("library.openQuestionEditor")}</Button>{open && <section className="grid gap-4 rounded-lg border p-4"><CatalogError error={query.error} retry={()=>void query.refetch()} />{query.isPending?<CatalogSkeleton />:query.data && <Editor key={`${path}-${revision}`} initial={query.data} path={path} part={placement.part} testRevision={revision} onDirtyChange={setEditorDirty} onSaved={doc=>{onSaved(doc);setOpen(false);}} />}</section>}</>;
}
function Editor({initial,path,part,testRevision,onSaved,onDirtyChange}: {initial:Document<Question>;path:string;part:number;testRevision:number;onSaved:(doc:Document<ToeicDraft>)=>void;onDirtyChange:(dirty:boolean)=>void}) {
  const {t}=useTranslation("admin");const cache=useQueryClient();
  const [document,setDocument]=useState(initial);const [value,setValue]=useState(initial.draft);const [epoch,setEpoch]=useState(0);
  const generation=useRef(0);
  const dirty=JSON.stringify(value)!==JSON.stringify(document.draft);
  const active=useRef(true);
  useEffect(()=>{active.current=true;return ()=>{active.current=false;onDirtyChange(false);};},[onDirtyChange]);
  useEffect(()=>{onDirtyChange(dirty || document.hasUnpublishedChanges);},[dirty,document.hasUnpublishedChanges,onDirtyChange]);
  const save=useMutation({mutationFn:()=>api<Document<ToeicDraft>>(path,{method:"PUT",body:JSON.stringify({testRevision,revision:document.revision,content:value})}),onSuccess:doc=>{void cache.invalidateQueries({queryKey:["question-preview"]});void cache.invalidateQueries({queryKey:["library","/api/v1/admin/assessment-items"]});if(active.current) onSaved(doc);}});
  return <><p className="text-xs text-muted-foreground">{t("quality.contextEditor")}</p><fieldset key={epoch} disabled={save.isPending}><QuestionFields value={value} onChange={setValue} part={part} /><div className="mt-4"><AiSuggestions resourcePath={path} context={{testRevision}} endpoint="/api/v1/admin/assessment-items" document={document} dirty={dirty} adopt={doc=>{if(active.current && generation.current===epoch) {setDocument(doc);setValue(doc.draft);}}} /></div></fieldset><CatalogError error={save.error} /><div className="flex justify-end gap-2">{dirty && <Button size="sm" variant="ghost" tooltip={t("library.discardHelp")} disabled={save.isPending} onClick={()=>{setValue(structuredClone(document.draft));generation.current++;setEpoch(generation.current);save.reset();}}><RiArrowGoBackLine aria-hidden="true" />{t("library.discard")}</Button>}<Button size="sm" tooltip={t("quality.saveQuestion")} disabled={save.isPending || !dirty && !document.hasUnpublishedChanges} onClick={()=>save.mutate()}><RiSaveLine aria-hidden="true" />{t("quality.saveQuestion")}</Button></div></>;
}
