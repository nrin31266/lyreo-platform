import { AccessTierOption } from "@/components/AccessTierOption";
import { LibrarySelect } from "../library/LibrarySelect";
import { AdminPagination } from "@/components/AdminPagination";
import { RiFolder2Line, RiFolderOpenLine, RiStackLine, RiQuestionLine, RiAlertLine, RiEyeLine, RiAddLine, RiArrowUpLine, RiArrowDownLine, RiSubtractLine, RiEditLine } from "@remixicon/react";
import { useId, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DraftLoader, DraftWorkspace, LibraryList, TextField } from "@/pages/library/LibraryWorkspace";
import { GrammarReview, GrammarCheck } from "./GrammarReview";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
import { QuestionReview, QuestionPicker } from "@/pages/library/QuestionEditor";
import type { CatalogPage, Document, GrammarCatalog, Question, QuestionRef, Summary } from "@/pages/library/library.types";

export function GrammarPage() {
  const { t } = useTranslation("admin");
  const [params, setParams] = useSearchParams();
  const { kind: routeKind } = useParams();
  const selected = params.get("tab") ?? routeKind ?? "topic";
  const kind = ["topic", "subtopic", "bank", "questions", "review"].includes(selected) ? selected : "topic";
  const tabIcons = { topic: RiFolder2Line, subtopic: RiFolderOpenLine, bank: RiStackLine, questions: RiQuestionLine, review: RiAlertLine };
  function selectTab(tab: string) {
    if (tab === kind) return;
    const next = new URLSearchParams(params);
    next.set("tab", tab);
    next.delete("page");
    setParams(next);
  }
  const tabs = <nav className="flex flex-wrap gap-2" aria-label={t("navigation.grammar")}>{["topic", "subtopic", "bank", "questions", "review"].map(tab => <Button key={tab} variant={kind === tab ? "secondary" : "ghost"} onClick={() => selectTab(tab)}>{(() => { const Icon = tabIcons[tab as keyof typeof tabIcons]; return <Icon aria-hidden="true" />; })()}{t(`library.grammarTabs.${tab}`)}</Button>)}</nav>;
  if (kind === "review") return <section className="grid gap-6"><h1>{t("navigation.grammar")}</h1>{tabs}<GrammarReview /></section>;
  return <LibraryList key={kind} title={t("navigation.grammar")} endpoint={kind === "questions" ? "/api/v1/admin/assessment-items" : `/api/v1/admin/grammar/${kind}`} route={`/grammar/${kind}`} extra={tabs} />;
}

export function AccessFields({ value, feature, onChange }: { value: { accessMode: "PUBLIC" | "FEATURE"; requiredFeatureKey: string | null }; feature: string; onChange: (patch: { accessMode: "PUBLIC" | "FEATURE"; requiredFeatureKey: string | null }) => void }) {
  return <AccessTierOption value={value.accessMode} onChange={next => onChange({ accessMode: next, requiredFeatureKey: next === "FEATURE" ? feature : null })} />;
}

export function ReferenceList({ value, onChange }: { value: QuestionRef[]; onChange: (value: QuestionRef[]) => void }) {
  const { t } = useTranslation("admin");
  const [page, setPage] = useState(0);
  function move(index: number, delta: number) { const next = [...value]; [next[index], next[index + delta]] = [next[index + delta], next[index]]; onChange(next); }
  const lastPage = Math.max(0, Math.ceil(value.length / 20) - 1);
  const currentPage = Math.min(page, lastPage);
  return <div className="grid gap-4"><div className="flex flex-wrap items-center justify-between gap-3"><h2 className="font-semibold">{t("library.questionCount", { count: value.length })}</h2><QuestionPicker excludeIds={value.map(row => row.itemId)} onSelect={references => onChange([...value, ...references.filter(reference => !value.some(row => row.itemId === reference.itemId))])} /></div>
    {value.length > 0 && <div className="overflow-hidden rounded-xl border"><Table className="library-table"><TableHeader><TableRow><TableHead className="w-14">#</TableHead><TableHead>{t("library.questionText")}</TableHead><TableHead className="w-60 text-right">{t("catalog.actions")}</TableHead></TableRow></TableHeader><TableBody>{value.slice(currentPage * 20, (currentPage + 1) * 20).map((ref, offset) => { const index = currentPage * 20 + offset; return <ReferenceRow tableRow key={ref.itemId} reference={ref} number={index + 1} actions={<><Button tooltip={t("library.help.moveUp")} aria-label={t("library.moveUp")} size="icon-sm" variant="ghost" disabled={index === 0} onClick={() => move(index, -1)}><RiArrowUpLine aria-hidden="true" /></Button><Button tooltip={t("library.help.moveDown")} aria-label={t("library.moveDown")} size="icon-sm" variant="ghost" disabled={index === value.length - 1} onClick={() => move(index, 1)}><RiArrowDownLine aria-hidden="true" /></Button><Button tooltip={t("library.help.removeMembership")} aria-label={t("library.remove")} size="icon-sm" variant="ghost" onClick={() => onChange(value.filter((_, i) => i !== index))}><RiSubtractLine aria-hidden="true" /></Button></>} onUpdate={reference => onChange(value.map((row, i) => i === index ? reference : row))} />; })}</TableBody></Table></div>}
    <AdminPagination page={currentPage} pageCount={Math.ceil(value.length / 20)} onPageChange={setPage} />
  </div>;
}

export function ReferenceRow({ reference, number, actions, onUpdate, listening = false, initialPreview = false, gapNumber, sharedAssetIds = [], tableRow = false, contextEditor = false }: { contextEditor?: boolean; tableRow?: boolean; sharedAssetIds?: string[]; gapNumber?: number | null; reference: QuestionRef; number: number; actions: React.ReactNode; listening?: boolean; initialPreview?: boolean; onUpdate: (value: QuestionRef) => void }) {
  const { t } = useTranslation("admin");
  const question = useQuery({ queryKey: ["library", "/api/v1/admin/assessment-items", reference.itemId], queryFn: ({ signal }) => api<Document<Question>>(`/api/v1/admin/assessment-items/${reference.itemId}`, { signal }), staleTime: 30000 });
  const [preview, setPreview] = useState(initialPreview);
  const pinned = useQuery({ queryKey: ["question-preview", reference.itemId, reference.publicationId], queryFn: ({ signal }) => api<Question>(`/api/v1/admin/assessment-items/${reference.itemId}/preview${reference.publicationId ? `?publicationId=${reference.publicationId}` : ""}`, { signal }), staleTime: Infinity });
  const latest = question.data?.publicationId;
  const previewId = useId();
  const title = pinned.data?.title || t(pinned.data?.kind === "CLOZE" ? "library.clozeQuestion" : listening ? "library.listeningQuestion" : "library.untitledQuestion");
  const controls = <>{actions}{!contextEditor && <Button size={tableRow ? "icon-sm" : "sm"} aria-label={t("library.openQuestionEditor")} variant="outline" asChild tooltip={t("library.helpEditQuestion")}><Link to={`/grammar/questions/${reference.itemId}`}><RiEditLine aria-hidden="true" />{!tableRow && t("library.openQuestionEditor")}</Link></Button>}<Button tooltip={t("library.help.preview")} aria-label={t(preview ? "library.collapseContent" : "library.expandContent")} aria-expanded={preview} aria-controls={previewId} size={tableRow ? "icon-sm" : "sm"} variant="ghost" onClick={() => setPreview(!preview)}>{tableRow ? <RiEyeLine aria-hidden="true" /> : t(preview ? "library.collapseContent" : "library.expandContent")}</Button>{latest && latest !== reference.publicationId && <Button tooltip={t("library.help.adoptUpdate")} size="sm" variant="outline" onClick={() => onUpdate({ ...reference, publicationId: latest })}>{t("library.adoptUpdate")}</Button>}</>;
  const review = preview && pinned.data && <div id={previewId}><QuestionReview key={`${reference.itemId}-${reference.publicationId}`} value={{...pinned.data,media:pinned.data.media.filter(media => !sharedAssetIds.includes(media.assetId))}} /></div>;
  const error = pinned.error || question.error;
  if (tableRow) return <><TableRow><TableCell className="text-muted-foreground tabular-nums">{number}</TableCell><TableCell><Link className="line-clamp-2 font-medium hover:underline" to={`/grammar/questions/${reference.itemId}`}>{title}</Link></TableCell><TableCell><div className="flex flex-wrap justify-end gap-1">{controls}</div></TableCell></TableRow>{(preview || error) && <TableRow><TableCell colSpan={3}><CatalogError error={error} />{review}</TableCell></TableRow>}</>;
  return <div className="grid gap-3 rounded-lg border bg-card p-4"><div className="flex gap-3"><span className="text-xs font-medium text-muted-foreground">{number}</span><span className="line-clamp-2 text-sm font-medium">{title}{gapNumber != null && ` · ${t("library.gapNumber", {number:gapNumber})}`}</span></div><div className="flex flex-wrap gap-1">{controls}</div><CatalogError error={error} />{review}</div>;
}

export function GrammarDetailPage() {
  const { kind = "topic", id = "" } = useParams();
  const { t, i18n } = useTranslation("admin");
  const endpoint = `/api/v1/admin/grammar/${kind}`;
  const [params, setParams] = useSearchParams();
  const selectedTab = params.get("tab") === "questions" || (!params.has("tab") && kind === "bank") ? "questions" : "metadata";
  const parents = useQuery({ queryKey: ["library", "/api/v1/admin/grammar/topic", "parents"], enabled: kind === "subtopic", queryFn: async ({ signal }) => (await api<CatalogPage<Summary>>("/api/v1/admin/grammar/topic/page?size=100&page=0", { signal })).items });
  return <DraftLoader<GrammarCatalog> endpoint={endpoint} id={id}>{document => <DraftWorkspace key={`${kind}-${id}`} endpoint={endpoint} route={`/grammar/${kind}`} document={document}>{(value, change, dirty, revision) => <Tabs value={selectedTab} onValueChange={tab => setParams(current => {const next = new URLSearchParams(current);next.set("tab",tab);return next;})}><div className="flex flex-wrap items-center justify-between gap-3"><TabsList><TabsTrigger value="metadata"><RiEditLine aria-hidden="true" />{t("library.metadata")}</TabsTrigger><TabsTrigger value="questions"><RiQuestionLine aria-hidden="true" />{t("library.questionCount",{count:value.questions.length})}</TabsTrigger></TabsList>{kind === "topic" && <Button variant="outline" asChild><Link to={`/grammar/subtopic?parentId=${id}`}>{t("library.viewSubtopics")}</Link></Button>}</div><TabsContent value="metadata" className="mt-5"><div className="grid items-start gap-6 lg:grid-cols-2"><div className="grid gap-4"><TextField label={t("library.titleVi")} value={value.title} onChange={title => change({ ...value, title })} /><TextField label={t("library.titleEn")} value={value.titleEn} onChange={titleEn => change({ ...value, titleEn })} /><TextField label={t("library.descriptionVi")} value={value.description} onChange={description => change({ ...value, description })} multiline /><TextField label={t("library.descriptionEn")} value={value.descriptionEn} onChange={descriptionEn => change({ ...value, descriptionEn })} multiline /></div><div className="grid content-start gap-4 rounded-xl border bg-card p-5"><AccessFields value={value} feature="grammar.advanced" onChange={patch => change({ ...value, ...patch })} /><label className="grid gap-2 text-sm font-medium">{t("library.order")}<Input type="number" value={value.orderIndex} onChange={event => change({ ...value, orderIndex: Number(event.target.value) })} /></label>{kind === "subtopic" && <LibrarySelect label={t("library.parentTopic")} value={value.parentId ?? ""} onValueChange={parentId => change({ ...value, parentId: parentId || null })} placeholder={t("library.choose")} options={parents.data?.map(row => ({value: row.id, label: (i18n.language.startsWith("en") ? row.titleEn || row.title : row.title || row.titleEn) ?? ""})) ?? []} />}<p className="text-xs text-muted-foreground">{t("library.catalogAccessHelp")}</p></div></div></TabsContent><TabsContent value="questions" className="mt-5 grid gap-4"><GrammarCheck kind={kind} id={id} dirty={dirty} revision={revision} /><ReferenceList value={value.questions} onChange={questions => change({ ...value, questions })} /></TabsContent></Tabs>}</DraftWorkspace>}</DraftLoader>;
}
