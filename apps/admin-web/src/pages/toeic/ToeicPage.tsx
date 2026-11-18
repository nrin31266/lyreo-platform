import { LibrarySelect } from "../library/LibrarySelect";
import { RiFileList3Line, RiInformationLine, RiShieldCheckLine, RiVolumeUpLine, RiBookOpenLine, RiAddLine, RiArrowUpLine, RiEditLine, RiArrowGoBackLine, RiDeleteBinLine } from "@remixicon/react";
import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Navigate, useParams, useSearchParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
import { DraftLoader, DraftWorkspace, LibraryList, TextField } from "@/pages/library/LibraryWorkspace";
import { MediaEditor, MediaPreview } from "@/pages/library/ContentMedia";
import { PassageEditor, PassagePreview } from "@/pages/library/PassageEditor";
import { ToeicQuestion } from "./ToeicQuestion";
import { QualityPanel } from "../library/QualityPanel";
import { AccessFields, ReferenceRow } from "@/pages/grammar/GrammarPage";
import type { Group, ToeicDraft } from "@/pages/library/library.types";

const endpoint = "/api/v1/admin/toeic";
function emptyTest(): ToeicDraft { return { title: "", code: "", type: "FULL_TEST", year: null, testNumber: null, listeningDurationSeconds: 2700, readingDurationSeconds: 4500, accessMode: "PUBLIC", requiredFeatureKey: null, groups: [], placements: [] }; }
export function ToeicPage() {
  const { t } = useTranslation("admin");
  return <LibraryList endpoint={endpoint} route="/toeic" title={t("navigation.toeic")} />;
}
export function ToeicDetailPage() {
  const { id = "new" } = useParams();
  const [questionDirty, setQuestionDirty] = useState(false);
  if (id === "new") return <Navigate to="/toeic" replace />;
  return <DraftLoader endpoint={endpoint} id={id} initial={emptyTest()}>{document => <DraftWorkspace key={id} document={document} endpoint={endpoint} route="/toeic" pendingEditorChanges={questionDirty}>{(value, change, dirty, savedRevision, _current, adopt) => <ToeicFields questionDirty={questionDirty} onQuestionDirtyChange={setQuestionDirty} onSaved={adopt} id={id} value={value} onChange={change} dirty={dirty} savedRevision={savedRevision} />}</DraftWorkspace>}</DraftLoader>;
}
function ToeicFields({ id, value, onChange, dirty, savedRevision, onSaved, questionDirty, onQuestionDirtyChange }: { questionDirty: boolean; onQuestionDirtyChange: (dirty: boolean) => void; onSaved: (document: import("../library/library.types").Document<ToeicDraft>) => void; savedRevision: number; id: string; value: ToeicDraft; onChange: (value: ToeicDraft) => void; dirty: boolean }) {
  const { t } = useTranslation("admin");
  const [params, setParams] = useSearchParams();
  const requestedPart = Number(params.get("part"));
  const part = requestedPart >= 1 && requestedPart <= 7 ? requestedPart : 1;
  const tab = ["quality", "metadata"].includes(params.get("tab") ?? "") ? params.get("tab")! : "content";
  const [groupEditorOpen, setGroupEditorOpen] = useState(params.get("review") === "1");
  const [groupId, setGroupId] = useState(params.get("group") ?? "");
  const [removedDocuments,setRemovedDocuments] = useState<{groupId:string;document:Group["documents"][number];index:number}|null>(null);
  const [previewGroup, setPreviewGroup] = useState(false);
  const groupEditor = useRef<HTMLDetailsElement>(null);
  useEffect(() => { if (groupEditorOpen) groupEditor.current?.scrollIntoView({behavior:"smooth",block:"start"}); }, [groupEditorOpen, groupId, part]);
  const original = useQuery({ queryKey: ["toeic-source", id], enabled: previewGroup && id !== "new", queryFn: ({ signal }) => api<ToeicDraft>(`${endpoint}/${id}/source`, { signal }), retry: false });
  const groups = value.groups.filter(row => row.part === part);
  const group = groups.find(row => row.id === groupId) ?? groups[0];
  const questions = value.placements.filter(row => row.part === part).sort((a, b) => a.questionNumber - b.questionNumber);
  const selected = questions.find(row=>row.questionNumber === Number(params.get("question"))) ?? questions[0];
  const stimulus = groups.find(row => row.id === selected?.groupId);
  const groupQuestions = stimulus ? questions.filter(row => row.groupId === stimulus.id) : [];
  function selectQuestion(number: number) {
    if (selected?.questionNumber === number || questionDirty && !window.confirm(t("catalog.unsaved"))) return;
    const next = questions.find(row => row.questionNumber === number);
    if (groupEditorOpen && next?.groupId !== group?.id) setGroupEditorOpen(false);
    setParams(previous => {
      const updated = new URLSearchParams(previous);
      updated.set("question", String(number));
      updated.delete("location");
      return updated;
    }, { replace: true });
  }

  const updateGroup = (patch: Partial<Group>) => onChange({ ...value, groups: value.groups.map(row => row.id === group?.id ? { ...row, ...patch } : row) });
  function issueTarget(location: string) {
    const group=value.groups.find(row => row.id === location);
    const number=Number(/^Question (\d+)$/.exec(location)?.[1]);
    const question=value.placements.find(row => group ? row.groupId === group.id : row.questionNumber === number);
    return {group,question};
  }
  function openIssue(location: string) {
    const {group,question}=issueTarget(location);
    setParams(previous => { const updated = new URLSearchParams(previous); updated.set("tab", "content"); if (group || question) updated.set("part", String(group?.part ?? question!.part)); if (question) updated.set("question", String(question.questionNumber)); updated.delete("location"); return updated; }, {replace:true});
    setGroupId(group?.id ?? question?.groupId ?? "");
    setGroupEditorOpen(!!group);
  }
  useEffect(() => { const location = params.get("location"); if (location) openIssue(location); }, [params.get("location")]);
  return <><Tabs value={tab} onValueChange={next => { setParams(previous => { const updated = new URLSearchParams(previous); updated.set("tab", next); return updated; }, {replace:true}); }}><TabsList><TabsTrigger value="content"><RiFileList3Line aria-hidden="true" />{t("library.content")}</TabsTrigger><TabsTrigger value="metadata"><RiInformationLine aria-hidden="true" />{t("library.metadata")}</TabsTrigger><TabsTrigger value="quality"><RiShieldCheckLine aria-hidden="true" />{t("library.quality")}</TabsTrigger></TabsList>
    <TabsContent value="metadata" className="mt-6 grid items-start gap-5 lg:grid-cols-2"><div className="grid gap-4"><TextField label={t("library.title")} value={value.title} onChange={title => onChange({ ...value, title })} /><TextField label={t("library.code")} value={value.code} onChange={code => onChange({ ...value, code })} /><p className="text-sm text-muted-foreground">{t(`library.types.${value.type}`)} · {t("library.questionCount", { count: value.placements.length })}</p><AccessFields value={value} feature="toeic.full-access" onChange={patch => onChange({ ...value, ...patch })} /></div><div className="grid content-start gap-4 rounded-xl border bg-card p-5"><label className="grid gap-2 text-sm font-medium">{t("library.year")}<Input type="number" value={value.year ?? ""} onChange={event => onChange({ ...value, year: event.target.value ? Number(event.target.value) : null })} /></label><label className="grid gap-2 text-sm font-medium">{t("library.listeningDuration")}<Input type="number" min={1} max={14400} value={value.listeningDurationSeconds ?? ""} onChange={event => onChange({ ...value, listeningDurationSeconds: event.target.value ? Number(event.target.value) : null })} /></label><label className="grid gap-2 text-sm font-medium">{t("library.readingDuration")}<Input type="number" min={1} max={14400} value={value.readingDurationSeconds ?? ""} onChange={event => onChange({ ...value, readingDurationSeconds: event.target.value ? Number(event.target.value) : null })} /></label><p className="text-sm text-muted-foreground">{t("library.testDistribution")}</p></div></TabsContent>
    <TabsContent value="quality" className="mt-5 grid gap-4"><div className="flex flex-wrap gap-x-6 gap-y-2 text-sm font-medium"><span>{t("library.toeicTotal", {count:value.placements.length})}</span><span>Listening: {value.placements.filter(row => row.part <= 4).length}/100</span><span>Reading: {value.placements.filter(row => row.part >= 5).length}/100</span></div><QualityPanel endpoint={`${endpoint}/${id}/quality`} revision={savedRevision} dirty={dirty} onOpen={issue => openIssue(issue.location)} /></TabsContent>
    <TabsContent value="content" className="mt-6 grid gap-5"><p className="text-sm text-muted-foreground">{t("library.toeicStructureHelp")}</p><nav className="flex flex-wrap gap-2" aria-label={t("library.parts")}>{[1, 2, 3, 4, 5, 6, 7].map(number => <Button key={number} size="sm" aria-pressed={part === number} variant={part === number ? "secondary" : "outline"} onClick={() => { if (number === part || questionDirty && !window.confirm(t("catalog.unsaved"))) return; setGroupEditorOpen(false); setGroupId(""); setParams(previous => { const updated = new URLSearchParams(previous); updated.set("part", String(number)); for (const key of ["question", "group", "location", "review"]) updated.delete(key); return updated; }, {replace:true}); }}>{t("library.part", { number })} <span className="text-xs text-muted-foreground">{value.placements.filter(row => row.part === number).length}</span></Button>)}</nav>
      <div className="grid items-start gap-6 lg:grid-cols-[240px_minmax(0,1fr)]"><aside className="grid gap-4 lg:sticky lg:top-32"><div><h2 className="text-base font-semibold">{t("library.questionCount", { count: questions.length })}</h2><p className="mt-1 text-sm text-muted-foreground">{t("library.chooseQuestionHelp")}</p></div><div className="grid grid-cols-5 gap-2">{questions.map(row => <Button key={row.id} variant={selected?.id === row.id ? "default" : "outline"} aria-pressed={selected?.id === row.id} aria-label={t("library.questionNumber", {number: row.questionNumber})} onClick={() => selectQuestion(row.questionNumber)}>{row.questionNumber}</Button>)}</div></aside>
        <div className="grid min-w-0 gap-6">{stimulus && !groupEditorOpen && <section className="grid gap-3" aria-label={t("library.questionContext")}><h2 className="flex items-center gap-2 text-sm font-semibold">{part <= 4 ? <RiVolumeUpLine aria-hidden="true" className="size-4" /> : <RiBookOpenLine aria-hidden="true" className="size-4" />}{stimulus.title || t("library.questionContext")}</h2>{groupQuestions.length > 1 && <nav className="flex flex-wrap gap-2" aria-label={t("library.questionContext")}>{groupQuestions.map(row => <Button key={row.id} variant={selected?.id === row.id ? "secondary" : "ghost"} size="sm" aria-pressed={selected?.id === row.id} onClick={() => selectQuestion(row.questionNumber)}>{row.questionNumber}</Button>)}</nav>}{stimulus.media.map(media => <MediaPreview key={media.assetId} {...media} />)}{stimulus.documents.map(document => <PassagePreview key={document.id} html={document.html} />)}{stimulus.transcriptEn?.trim() && <details><summary className="cursor-pointer text-sm font-medium">{t("library.transcript")}</summary><p className="mt-3 whitespace-pre-wrap text-sm leading-relaxed">{stimulus.transcriptEn.trim()}</p></details>}</section>}{selected && !groupEditorOpen && <ReferenceRow contextEditor initialPreview key={selected.id} reference={selected} number={selected.questionNumber} gapNumber={selected.gapNumber} sharedAssetIds={stimulus?.media.map(media => media.assetId)} listening={part <= 2} onUpdate={reference => onChange({ ...value, placements: value.placements.map(row => row.id === selected.id ? { ...row, ...reference } : row) })} actions={stimulus ? <Button variant="outline" size="sm" tooltip={t("library.editGroupHelp")} onClick={() => { if (questionDirty && !window.confirm(t("catalog.unsaved"))) return; setGroupId(stimulus.id); setGroupEditorOpen(true); }}><RiEditLine aria-hidden="true" />{t(part >= 5 ? "library.editReadingGroup" : "library.editGroup")}</Button> : null} />}{selected && !groupEditorOpen && <ToeicQuestion onDirtyChange={onQuestionDirtyChange} key={`${selected.id}-${savedRevision}`} testId={id} placement={selected} revision={savedRevision} dirty={dirty} onSaved={onSaved} />}

        {[3, 4, 6, 7].includes(part) && <details ref={groupEditor} open={groupEditorOpen} onToggle={event => { if (event.currentTarget.open && questionDirty && !window.confirm(t("catalog.unsaved"))) { event.currentTarget.open=false; return; } setGroupEditorOpen(event.currentTarget.open); }} className="scroll-mt-44 rounded-xl border bg-card p-5"><summary className="cursor-pointer font-medium"><RiEditLine className="mr-2 inline size-4" aria-hidden="true" />{t(part >= 5 ? "library.editReadingGroup" : "library.editGroup")}</summary><div className="mt-5 grid gap-5"><p className="text-sm text-muted-foreground">{t("library.groupQuestions",{numbers: value.placements.filter(row=>row.groupId === group?.id).map(row=>row.questionNumber).join(", ")})}</p><div className="flex flex-wrap items-center justify-between gap-3"><h2 className="font-semibold">{t("library.groupsPassages")}</h2></div>
          {groups.length > 0 && <LibrarySelect label={t("library.questionGroup")} value={group?.id ?? ""} onValueChange={setGroupId} options={groups.map((row, index) => ({value: row.id, label: row.title || t("library.groupNumber", {number: index + 1})}))} />}
          {group ? <div key={group.id} className="grid gap-4"><TextField label={t("library.title")} value={group.title} onChange={title => updateGroup({ title })} /><details open={part <= 4}><summary className="cursor-pointer text-sm font-medium">{t(part >= 5 ? "library.readingMediaTranslation" : "library.mediaTranscript")}</summary><div className="mt-4 grid gap-4"><MediaEditor kind={part <= 4 ? undefined : "image"} value={group.media} onChange={media => updateGroup({ media })} />{part <= 4 && <TextField label={t("library.transcript")} value={group.transcriptEn} onChange={transcriptEn => updateGroup({ transcriptEn })} multiline />}<TextField label={t("library.translation")} value={group.translationVi} onChange={translationVi => updateGroup({ translationVi })} multiline /></div></details><div className="flex justify-between"><h3 className="font-medium">{t("library.documents")}</h3><Button tooltip={t("library.help.addDocument")}  size="sm" variant="outline" disabled={group.documents.length >= 3} onClick={() => updateGroup({ documents: [...group.documents, { id: crypto.randomUUID(), ordinal: group.documents.length + 1, type: "PASSAGE", html: "<p></p>" }] })}><RiAddLine aria-hidden="true" />{t("library.addDocument")}</Button></div>{group.documents.map((document, index) => <div key={document.id} className="grid gap-2"><p className="text-xs text-muted-foreground">{t("library.documentNumber", { number: index + 1 })}</p><PassageEditor value={document.html} onChange={html => updateGroup({ documents: group.documents.map(row => row.id === document.id ? { ...row, html } : row), reviewed: false })} /><div className="flex flex-wrap gap-2"><Button tooltip={t("library.help.moveUp")}  size="sm" variant="ghost" disabled={index === 0} onClick={() => { const docs = [...group.documents]; [docs[index], docs[index - 1]] = [docs[index - 1], docs[index]]; updateGroup({ documents: docs.map((row, index) => ({ ...row, ordinal: index + 1 })), reviewed: false }); }}><RiArrowUpLine aria-hidden="true" />{t("library.moveUp")}</Button><Button tooltip={t("library.help.removeDocument")} aria-label={t("library.removeDocument")} size="icon-sm" variant="ghost" onClick={() => {setRemovedDocuments({groupId:group.id,document,index});updateGroup({documents:group.documents.filter(row=>row.id!==document.id).map((row,index)=>({...row,ordinal:index+1}))});}}><RiDeleteBinLine aria-hidden="true" /></Button></div></div>)}{removedDocuments?.groupId===group.id && <Button size="sm" variant="outline" className="justify-self-start" onClick={()=>{const restored=[...group.documents];if(!restored.some(row=>row.id===removedDocuments.document.id)) restored.splice(Math.min(removedDocuments.index,restored.length),0,removedDocuments.document);updateGroup({documents:restored.map((row,index)=>({...row,ordinal:index+1}))});setRemovedDocuments(null);}}><RiArrowGoBackLine aria-hidden="true" />{t("quality.undoRemove")}</Button>}<Button tooltip={t("library.help.source")}  variant="outline" onClick={() => setPreviewGroup(!previewGroup)}>{t("library.source")}</Button>{previewGroup && <div className="grid gap-3"><CatalogError error={original.error} /><p className="text-xs text-muted-foreground">{t("library.originalPassage")}</p><pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-lg bg-muted p-4 text-xs">{original.data?.groups.find(row => row.id === group.id)?.documents.map(row => row.html).join("\n\n") ?? t("library.originalContent")}</pre></div>}</div> : <p className="text-sm text-muted-foreground">{t("library.individualPartHelp")}</p>}
        </div></details>}</div></div>
    </TabsContent></Tabs></>;
}
