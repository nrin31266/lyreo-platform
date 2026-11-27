import { useDebouncedInput } from "@/hooks/useDebouncedInput";
import { Checkbox } from "@/components/ui/checkbox";
import { AiSuggestions } from "./AiSuggestions";
import { useId, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";
import { api } from "@/lib/api";
import { AdminPagination } from "@/components/AdminPagination";
import { RiAddLine, RiDeleteBinLine, RiQuestionLine, RiTranslate2, RiVolumeUpLine, RiImageLine, RiListCheck2, RiCheckLine } from "@remixicon/react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { CatalogError } from "@/pages/lessons/CatalogFeedback";
import { DraftLoader, DraftWorkspace, TextField } from "./LibraryWorkspace";
import { MediaEditor, MediaPreview } from "./ContentMedia";
import { PassagePreview } from "./PassageEditor";
import type { CatalogPage, Document, Media, Question, QuestionRef, Summary } from "./library.types";


export function QuestionFields({ value, onChange, part }: { part?: number; value: Question; onChange: (value: Question) => void }) {
  const { t } = useTranslation("admin");
  const radio = useId();
  const change = <K extends keyof Question>(key: K, next: Question[K]) => onChange({ ...value, [key]: next });
  return <div className="library-question">
    <Tabs defaultValue="content" className="min-w-0">
      <TabsList><TabsTrigger value="content"><RiQuestionLine aria-hidden="true" />{t("library.content")}</TabsTrigger><TabsTrigger value="translation"><RiTranslate2 aria-hidden="true" />{t("library.translationNotes")}</TabsTrigger><TabsTrigger value="media">{part != null && part >= 5 ? <RiImageLine aria-hidden="true" /> : <RiVolumeUpLine aria-hidden="true" />}{t(part != null && part >= 5 ? "library.questionImages" : "library.mediaTranscript")}</TabsTrigger></TabsList>
      <TabsContent value="content" className="mt-5 grid gap-4">
        {value.kind === "CLOZE" && <p className="text-sm text-muted-foreground">{t("library.clozeContextHelp")}</p>}
        <TextField label={t(value.kind === "CLOZE" || part === 1 ? "library.optionalQuestionStem" : "library.questionText")} value={value.title} onChange={next => change("title", next)} multiline />
        <div className="grid gap-3"><div><h2 className="flex items-center gap-2 text-base font-semibold"><RiListCheck2 className="size-4" aria-hidden="true" />{t("library.answerOptions")}</h2><p className="mt-1 text-sm text-muted-foreground">{t("library.answerOptionsHelp")}</p></div><div className="grid items-start gap-3 xl:grid-cols-2">
          {value.options.map((option, index) => <div key={option.key} className="flex items-start gap-3 rounded-xl border bg-card p-3">
            <label className="flex h-9 shrink-0 cursor-pointer items-center gap-2 text-sm font-medium"><input type="radio" name={radio} aria-label={t("library.correctOption", { key: option.key })} checked={value.correctOption === option.key} onChange={() => change("correctOption", option.key)} />{option.key}</label>
            <div className="min-w-0 flex-1"><TextField label={t("library.option", { key: option.key })} rows={2} value={option.text} onChange={next => change("options", value.options.map((row, i) => i === index ? { ...row, text: next } : row))} multiline /></div>
            {part == null && <Button tooltip={t("library.help.removeOption")} variant="ghost" size="icon-sm" aria-label={t("library.removeOption", { key: option.key })} title={t("library.removeOption", { key: option.key })} disabled={value.options.length <= 2} onClick={() => change("options", value.options.filter((_, i) => i !== index))}><RiDeleteBinLine aria-hidden="true" /></Button>}
          </div>)}
          </div>{part == null && value.options.length < 4 && <Button tooltip={t("library.help.addOption")} className="justify-self-start" variant="outline" onClick={() => { const key = ["A", "B", "C", "D"].find(key => !value.options.some(option => option.key === key))!; change("options", [...value.options, { key, text: "" }]); }}><RiAddLine aria-hidden="true" />{t("library.addOption")}</Button>}
        </div>
        <label className="grid max-w-52 gap-2 text-sm font-medium">{t("library.difficulty")}<Input type="number" min={1} max={5} value={value.difficultyLevel ?? ""} onChange={event => change("difficultyLevel", event.target.value ? Number(event.target.value) : null)} /></label>
      </TabsContent>
      <TabsContent value="translation" className="mt-5 grid gap-5">{value.combinedTranslationVi && <TextField label={t("library.combinedTranslation")} value={value.combinedTranslationVi} onChange={next=>change("combinedTranslationVi",next)} multiline />}<TextField label={t("library.translation")} value={value.translationVi} onChange={next => change("translationVi", next)} multiline /><TextField label={t("library.explanation")} value={value.explanationVi} onChange={next => change("explanationVi", next)} multiline /><TextField label={t("library.answerTranslation")} value={value.answerTranslationVi} onChange={next => change("answerTranslationVi", next)} multiline /><TextField label={t("library.vocabulary")} value={value.vocabularyNote} onChange={next => change("vocabularyNote", next)} multiline /></TabsContent>
      <TabsContent value="media" className="mt-5 grid gap-5">{(part == null || part <= 4) && <TextField label={t("library.transcript")} value={value.transcriptEn} onChange={next => change("transcriptEn", next)} multiline />}<MediaEditor kind={part != null && part >= 5 ? "image" : undefined} value={value.media} onChange={next => change("media", next)} /></TabsContent>
    </Tabs>
  </div>;
}

export function QuestionReview({ value }: { value: Question }) {
  const { t } = useTranslation("admin");
  return <section aria-label={t("library.adminReview")} className="library-review flex flex-col gap-4">
    {value.title?.trim() && <p className="whitespace-pre-wrap text-sm leading-relaxed">{value.title.trim()}</p>}
    <div className={value.media.length ? "library-review-body has-media" : "library-review-body"}>
      {value.media.length > 0 && <div className="flex min-w-0 flex-col items-start gap-3">{value.media.map(media => <MediaPreview key={media.assetId} {...media} />)}</div>}
      <div className="grid min-w-0 content-start gap-4">
        <div className="divide-y rounded-lg bg-muted/30">{value.options.map(option => <div key={option.key} className="flex items-start gap-3 px-3 py-2.5 text-sm">
          <strong className="shrink-0">{option.key}</strong><span className="min-w-0 flex-1 whitespace-pre-wrap">{option.text || t("library.spokenOption")}</span>
          {option.key === value.correctOption && <span className="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-success"><RiCheckLine className="size-4" aria-hidden="true" />{t("library.correctAnswer")}</span>}
        </div>)}</div>
        {value.transcriptEn?.trim() && <div className="grid gap-2"><h3 className="text-sm font-medium">{t("library.transcript")}</h3><p className="whitespace-pre-wrap text-sm leading-relaxed text-muted-foreground">{value.transcriptEn.trim()}</p></div>}
        {([['combinedTranslationVi', 'combinedTranslation'], ['translationVi', 'translation'], ['answerTranslationVi', 'answerTranslation']] as const).map(([field, label]) => value[field]?.trim() && <div key={field} className="grid gap-2 text-sm"><h3 className="font-medium">{t(`library.${label}`)}</h3><p className="whitespace-pre-wrap text-muted-foreground">{value[field]!.trim()}</p></div>)}
        <div className="grid gap-2 text-sm"><h3 className="font-medium">{t("library.explanation")}</h3><p className="whitespace-pre-wrap text-muted-foreground">{value.explanationVi?.trim() || t("library.noExplanation")}</p></div>
      </div>
    </div>
  </section>;
}

export function QuestionPicker({ onSelect, excludeIds = [] }: { onSelect: (references: QuestionRef[]) => void; excludeIds?: string[] }) {
  const { t } = useTranslation("admin");
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [input, setInput] = useDebouncedInput(query, value => { setQuery(value); setPage(0); });
  const [error, setError] = useState<unknown>();
  const [pending, setPending] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const list = useQuery({ queryKey: ["library", "question-picker", query, page], enabled: open, queryFn: ({ signal }) => api<CatalogPage<Summary>>(`/api/v1/admin/assessment-items/page?q=${encodeURIComponent(query)}&page=${page}&size=30`, { signal }) });
  async function addSelected() {
    setPending(true); setError(undefined);
    try {
      const documents=await Promise.all(selected.map(id => api<Document<Question>>(`/api/v1/admin/assessment-items/${id}`)));
      if (documents.some(document => document.status !== "PUBLISHED" || document.draft.kind === "CLOZE")) throw new Error(t("library.pickerUnavailable"));
      onSelect(documents.map(document => ({ itemId: document.id, publicationId: document.publicationId })));
      setSelected([]); setOpen(false);
    } catch (cause) { setError(cause); } finally { setPending(false); }
  }
  return <><Button tooltip={t("library.help.pickQuestions")} variant="outline" onClick={() => setOpen(true)}><RiAddLine aria-hidden="true" />{t("library.pickQuestions")}</Button><Dialog open={open} onOpenChange={setOpen}><DialogContent className="flex max-h-[85vh] flex-col sm:max-w-2xl"><DialogHeader><DialogTitle>{t("library.pickQuestions")}</DialogTitle></DialogHeader><Input aria-label={t("library.search")} value={input} onChange={event => setInput(event.target.value)} /><CatalogError error={error ?? list.error} /><div className="min-h-0 overflow-y-auto">{list.isPending && <p role="status" className="py-4 text-sm text-muted-foreground">{t("library.loadingQuestions")}</p>}{list.data?.items.length === 0 && <p className="py-4 text-sm text-muted-foreground">{t("library.noFilterMatches")}</p>}{list.data?.items.map(row => <label key={row.id} className="flex cursor-pointer items-center gap-3 border-b py-3"><Checkbox aria-label={t("library.selectItem", {title:row.title?.trim() || t(row.toeicPart === 1 ? "library.toeicPhotoQuestion" : row.type === "CLOZE" ? "library.clozeQuestion" : "library.untitledQuestion")})} checked={excludeIds.includes(row.id) || selected.includes(row.id)} disabled={pending || row.requiresContext || excludeIds.includes(row.id) || row.status !== "PUBLISHED" || !selected.includes(row.id) && selected.length >= 100} onCheckedChange={checked => setSelected(previous => checked === true ? [...previous,row.id] : previous.filter(id => id !== row.id))} /><span className="min-w-0 flex-1 line-clamp-3 text-sm">{row.title?.trim() || t(row.toeicPart === 1 ? "library.toeicPhotoQuestion" : row.type === "CLOZE" ? "library.clozeQuestion" : "library.untitledQuestion")}</span>{row.requiresContext && <span className="text-xs text-muted-foreground">{t("library.requiresContext")}</span>}{excludeIds.includes(row.id) && <span className="text-xs text-muted-foreground">{t("library.alreadyAdded")}</span>}</label>)}</div>{list.data && <AdminPagination page={page} pageCount={Math.ceil(list.data.total / list.data.size)} disabled={list.isFetching || list.isError} onPageChange={setPage} />}<Button tooltip={t("library.help.addQuestions")} disabled={pending || !selected.length} onClick={() => void addSelected()}><RiAddLine aria-hidden="true" />{t("library.addQuestions", {count:selected.length})}</Button></DialogContent></Dialog></>;

}

type ImportedQuestionContext = {
  test_id: string;
  test_title: string;
  part: number;
  question_number: number;
  gap_number: number | null;
  passage_html: string | null;
  transcript_en: string | null;
  media: Media[];
};

export function QuestionContext({ value, source }: { value: Question; source?: ImportedQuestionContext }) {
  const { t } = useTranslation("admin");
  const withoutStem = !value.title?.trim();
  const transcript = source?.transcript_en?.trim() || (withoutStem ? value.transcriptEn?.trim() : "");
  const media = [...(source?.media ?? []), ...(withoutStem ? value.media : [])];
  const uniqueMedia = [...new Map(media.map(item => [item.assetId, item])).values()];
  if (!source && !uniqueMedia.length && !transcript) return null;

  return <section aria-label={t("library.questionContext")} className="grid gap-3 rounded-lg border p-4">
    {source && <div className="flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="text-sm font-semibold">
        {t("library.part", { number: source.part })} · {t("library.questionNumber", { number: source.question_number })}
        {source.gap_number != null && ` · ${t("library.gapNumber", { number: source.gap_number })}`}
      </h2>
      <Link className="text-sm text-primary underline underline-offset-2" to={`/toeic/${source.test_id}?tab=content&part=${source.part}&question=${source.question_number}`}>
        {t("library.openQuestionInTest", { title: source.test_title })}
      </Link>
    </div>}
    {withoutStem && source?.part === 1 && <p className="text-sm text-muted-foreground">{t("library.toeicPhotoContext")}</p>}
    {source?.passage_html && <PassagePreview html={source.passage_html} />}
    {uniqueMedia.length > 0 && <div className="grid gap-2">
      {uniqueMedia.map(item => <MediaPreview key={item.assetId} {...item} />)}
    </div>}
    {transcript && <details>
      <summary className="cursor-pointer text-sm font-medium">{t("library.transcript")}</summary>
      <p className="mt-2 whitespace-pre-wrap text-sm">{transcript}</p>
    </details>}
  </section>;
}

export function QuestionDetailPage() {
  const { id = "" } = useParams();
  const { t } = useTranslation("admin");
  const endpoint = "/api/v1/admin/assessment-items";
  const usage = useQuery({
    queryKey: ["question-usage", id],
    queryFn: ({ signal }) => api<{ domain: string; id: string; title: string }[]>(`${endpoint}/${id}/usage`, { signal }),
  });
  const context = useQuery({
    queryKey: ["question-context", id],
    queryFn: ({ signal }) => api<ImportedQuestionContext[]>(`${endpoint}/${id}/context`, { signal }),
  });
  const source = context.data?.[0];
  return <DraftLoader<Question> endpoint={endpoint} id={id}>{document =>
    <DraftWorkspace key={id} document={document} endpoint={endpoint} route="/grammar/questions"
      fallbackTitle={source?.part === 1 ? t("library.toeicPhotoQuestion") : document.draft.kind === "CLOZE" ? t("library.clozeQuestion") : undefined}
      assistant={(current, dirty, adopt) => <AiSuggestions endpoint={endpoint} document={current} dirty={dirty} adopt={adopt} />}>
      {(value, change) => <>
        <CatalogError error={context.error} retry={() => void context.refetch()} retrying={context.isFetching} />
        <QuestionContext value={value} source={source} />
        <QuestionFields value={value} onChange={change} part={source?.part} />
        {!!usage.data?.length && <div className="rounded-lg border p-4">
          <h2 className="mb-3 font-medium">{t("library.usage")}</h2>
          {usage.data.map((row, i) => <p key={`${row.id}-${i}`} className="py-1 text-sm">
            <Link className="underline" to={row.domain.toLowerCase() === "toeic"
              ? `/toeic/${row.id}`
              : `/grammar/${row.domain.startsWith("grammar-") ? row.domain.slice(8) : "topic"}/${row.id}`}>
              {row.title}
            </Link>
          </p>)}
          <p className="mt-3 text-xs text-muted-foreground">{t("library.sharedQuestionHelp")}</p>
        </div>}
      </>}
    </DraftWorkspace>
  }</DraftLoader>;
}
