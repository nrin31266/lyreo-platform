import { LibrarySelect } from "../library/LibrarySelect";
import { RiVolumeUpLine, RiBookOpenLine, RiFileTextLine, RiAddLine, RiSearchLine } from "@remixicon/react";
import { useState } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { DraftLoader, DraftWorkspace, TextField } from "@/pages/library/LibraryWorkspace";
import { TranslationCandidates } from "./TranslationCandidates";
import { unlinkCandidate } from "./translationCandidates";
import { LexiconAssistant } from "./LexiconAssistant";
import { PronunciationEditor, FormsEditor } from "./PronunciationEditor";
import { lexiconPartsOfSpeech } from "@/pages/library/library.types";
import type { Document, LexiconDraft, Sense } from "@/pages/library/library.types";

import { DictionaryPreview } from "./DictionaryPreview";

const endpoint = "/api/v1/admin/lexicon";
function sense(itemId: string): Sense { return { id: crypto.randomUUID(), itemId, definitionEn: "", translationVi: "", translationStatus: "MISSING", rawGlosses: [], examples: [], tags: [], matchedQualifier: null }; }
function emptyEntry(): LexiconDraft {
  const item = { id: crypto.randomUUID(), partOfSpeech: "noun", posTitle: null, etymologyText: null };
  return { title: "", type: "WORD", headwordId: null, sourceMetadataJson: null, licenseText: null, items: [item], senses: [sense(item.id)], forms: [], pronunciations: [], translations: [] };
}
export function LexiconDetailPage() {
  const { id = "new" } = useParams();
  const { t } = useTranslation("admin");
  return <DraftLoader endpoint={endpoint} id={id} initial={emptyEntry()}>{document => <DraftWorkspace key={id} endpoint={endpoint} route="/lexicon" document={document} duplicate={value => { const title = window.prompt(t("library.duplicateHeadword"), value.title); return title?.trim() ? { ...value, title: title.trim(), headwordId: null } : null; }}>{(value, change, dirty, _revision, current, adopt) => <LexiconFields value={value} onChange={change} creating={id === "new"} document={current} dirty={dirty} adopt={adopt} />}</DraftWorkspace>}</DraftLoader>;
}
function LexiconFields({ value, onChange, creating, document, dirty, adopt }: { value: LexiconDraft; onChange: (value: LexiconDraft) => void; creating: boolean; document: Document<LexiconDraft>; dirty: boolean; adopt: (result: Document<LexiconDraft>) => void }) {
  const { t } = useTranslation("admin");
  const posOptions = lexiconPartsOfSpeech.map(row => ({...row,label:t(`library.partsOfSpeech.${row.value}`, {defaultValue:row.label})}));
  const [language,setLanguage] = useState(value.senses.some(row=>row.translationVi?.trim())?"vi":"en");
  const [selectedSense, setSelectedSense] = useState("");
  const [senseSearch, setSenseSearch] = useState("");
  const search = senseSearch.trim().toLocaleLowerCase();
  const languageSenses = value.senses.filter(row => row.id === selectedSense || (language === "vi" ? !!row.translationVi?.trim() || !row.definitionEn?.trim() : !!row.definitionEn?.trim()));
  const visibleSenses = languageSenses.filter(row => !search || `${row.definitionEn} ${row.translationVi ?? ""}`.toLocaleLowerCase().includes(search));
  const currentSense = languageSenses.find(row => row.id === selectedSense) ?? languageSenses[0];
  const currentItem = value.items.find(row => row.id === currentSense?.itemId);
  const updateSense = (id: string, patch: Partial<Sense>) => { setSelectedSense(id); onChange({ ...value, senses: value.senses.map(row => row.id === id ? { ...row, ...patch } : row) }); };
  return <><div className="grid gap-4 sm:grid-cols-2"><TextField label={t("library.headword")} value={value.title} onChange={title => onChange({ ...value, title })} disabled={!creating} /><LibrarySelect label={t("library.entryType")} value={value.type} onValueChange={type => onChange({ ...value, type })} options={["WORD", "PHRASE", "PHRASAL_VERB", "IDIOM", "COLLOCATION"].map(value => ({value, label: t(`library.types.${value}`)}))} /></div>
    <Tabs defaultValue={creating ? "senses" : "lookup"}><TabsList>{!creating && <TabsTrigger value="lookup"><RiSearchLine aria-hidden="true" />{t("dictionary.lookupPreview")}</TabsTrigger>}<TabsTrigger value="senses"><RiBookOpenLine aria-hidden="true" />{t("dictionary.editMeanings")}</TabsTrigger><TabsTrigger value="sounds"><RiVolumeUpLine aria-hidden="true" />{t("library.pronunciations")}</TabsTrigger><TabsTrigger value="forms"><RiFileTextLine aria-hidden="true" />{t("library.forms")}</TabsTrigger><TabsTrigger value="source"><RiFileTextLine aria-hidden="true" />{t("library.source")}</TabsTrigger></TabsList>
      <TabsContent value="lookup" className="mt-5"><DictionaryPreview entry={value} /></TabsContent>
      <TabsContent value="senses" className="mt-5 grid items-start gap-5 lg:grid-cols-[240px_minmax(0,1fr)]">
        <aside className="grid gap-2 lg:sticky lg:top-32" aria-label={t("library.senses")}><LibrarySelect label={t("dictionary.contentSource")} value={language} onValueChange={next=>{setLanguage(next);setSelectedSense("");}} options={[{value:"vi",label:t("dictionary.vietnameseSenses")},{value:"en",label:t("dictionary.englishReference")}]} /><h2 className="text-sm font-semibold">{t("library.senseCount",{count:languageSenses.length})}</h2>
          <div className="relative"><RiSearchLine className="pointer-events-none absolute top-2.5 left-3 size-4 text-muted-foreground" aria-hidden="true" /><Input className="pl-9" type="search" aria-label={t("library.searchSenses")} placeholder={t("library.searchSenses")} value={senseSearch} onChange={event=>setSenseSearch(event.target.value)} /></div>
          <div className="grid max-h-[min(40vh,16rem)] gap-4 overflow-y-auto overscroll-contain p-1" tabIndex={0} role="region" aria-label={t("library.senseList")}>
            {value.items.map(item => {const rows=visibleSenses.filter(row=>row.itemId===item.id);return rows.length>0 && <div key={item.id} className="grid gap-1"><h3 className="px-2 pb-1 text-xs font-medium text-muted-foreground">{t(`library.partsOfSpeech.${item.partOfSpeech}`,{defaultValue:item.partOfSpeech})} · {t("dictionary.itemNumber",{number:value.items.indexOf(item)+1})}</h3>
              {rows.map(row=><Button key={row.id} variant={currentSense?.id===row.id ? "secondary" : "ghost"} className="h-auto min-h-10 justify-start rounded-lg px-3 py-2 text-left whitespace-normal" aria-pressed={currentSense?.id===row.id} onClick={()=>setSelectedSense(row.id)}><span className="line-clamp-2">{t("library.senseNumber",{number:value.senses.indexOf(row)+1})} · {row.translationVi || row.definitionEn || t("library.newSense")}</span></Button>)}
              <Button size="sm" variant="ghost" className="justify-self-start" tooltip={t("library.help.addSense")} onClick={()=>{const next=sense(item.id);onChange({...value,senses:[...value.senses,next]});setLanguage("vi");setSelectedSense(next.id);setSenseSearch("");}}><RiAddLine aria-hidden="true" />{t("library.addSense")}</Button>
            </div>;})}
            {visibleSenses.length===0 && search && <p className="p-2 text-sm text-muted-foreground">{t("library.noMatchingSense")}</p>}
          </div>
          <Button tooltip={t("library.help.addPartOfSpeech")} size="sm" variant="outline" onClick={()=>{const id=crypto.randomUUID(),next=sense(id);onChange({...value,items:[...value.items,{id,partOfSpeech:"noun",posTitle:null,etymologyText:null}],senses:[...value.senses,next]});setLanguage("vi");setSelectedSense(next.id);setSenseSearch("");}}><RiAddLine aria-hidden="true" />{t("library.addPartOfSpeech")}</Button>
        </aside>
        {currentSense && currentItem ? <section className="grid min-w-0 gap-4" aria-label={t("library.editSense")}>
          <div className="flex flex-wrap items-center gap-3"><h2 className="font-semibold">{t("library.senseNumber",{number:value.senses.indexOf(currentSense)+1})}</h2><Badge variant={currentSense.translationStatus === "VERIFIED" ? "success" : "secondary"}>{t(`library.translationStates.${currentSense.translationStatus}`)}</Badge>
            <Button className="ml-auto" tooltip={t("library.help.removeSense")} size="sm" variant="ghost" onClick={()=>onChange({...value,senses:value.senses.filter(row=>row.id!==currentSense.id),translations:value.translations.map(row=>row.senseId===currentSense.id?unlinkCandidate(row):row)})}>{t("library.removeSense")}</Button></div>
          <div className="grid max-w-80 gap-2"><LibrarySelect label={t("library.partOfSpeech")} value={currentItem.partOfSpeech} onValueChange={partOfSpeech=>onChange({...value,items:value.items.map(row=>row.id===currentItem.id?{...row,partOfSpeech}:row)})} options={posOptions.some(row=>row.value===currentItem.partOfSpeech)?posOptions:[...posOptions,{value:currentItem.partOfSpeech,label:currentItem.partOfSpeech}]} />{value.senses.filter(row=>row.itemId===currentItem.id).length>1 && <p className="text-xs text-muted-foreground">{t("library.sharedPartOfSpeechHelp",{count:value.senses.filter(row=>row.itemId===currentItem.id).length})}</p>}</div>
          {language === "en" && !currentSense.translationVi?.trim() && <p className="text-xs text-muted-foreground">{t("dictionary.noVietnameseMeaning")}</p>}
          <div className="grid gap-4"><TextField label={t(language === "vi" ? "dictionary.vietnameseMeaning" : "library.definition")} value={language === "vi" ? currentSense.translationVi : currentSense.definitionEn} onChange={text=>updateSense(currentSense.id,language === "vi" ? {translationVi:text,translationStatus:text.trim()?"NEEDS_REVIEW":"MISSING"} : {definitionEn:text})} multiline />{language === "vi" && currentSense.definitionEn?.trim() && <TextField label={t("library.definition")} value={currentSense.definitionEn} onChange={definitionEn=>updateSense(currentSense.id,{definitionEn})} multiline />}{language === "en" && <TextField label={t("dictionary.vietnameseMeaning")} value={currentSense.translationVi} onChange={translationVi=>updateSense(currentSense.id,{translationVi,translationStatus:translationVi.trim()?"NEEDS_REVIEW":"MISSING"})} multiline />}</div>
          <Button tooltip={t("library.help.verifyTranslation")} className="justify-self-start" size="sm" variant="outline" disabled={!currentSense.translationVi?.trim()} onClick={()=>updateSense(currentSense.id,{translationStatus:currentSense.translationStatus === "VERIFIED"?"NEEDS_REVIEW":"VERIFIED"})}>{t(currentSense.translationStatus === "VERIFIED"?"library.unverifyTranslation":"library.verifyTranslation")}</Button>

          <details><summary className="cursor-pointer text-sm text-muted-foreground">{t("dictionary.sourceCandidates")}</summary><div className="mt-3"><TranslationCandidates value={value} sense={currentSense} onChange={next=>{onChange(next);if(next.senses.find(row=>row.id===currentSense.id)?.translationVi?.trim())setLanguage("vi");}} /></div></details>
          <LexiconAssistant key={currentSense.id} document={document} dirty={dirty} adopt={adopt} context={{senseId:currentSense.id}} />
          <details className="border-t pt-3" open={currentSense.examples.length > 0}><summary className="cursor-pointer text-sm font-medium">{t("dictionary.examplesCount",{count:currentSense.examples.length})}</summary><div className="mt-3 grid gap-3">{currentSense.examples.map((example,index)=><div key={index} className="grid gap-3 rounded-lg border p-3"><TextField label={t("library.examples")} value={typeof example.text === "string" ? example.text : ""} onChange={text=>updateSense(currentSense.id,{examples:currentSense.examples.map((row,i)=>i===index?{...row,text}:row)})} multiline />{typeof example.translation === "string" || example.translation == null ? <TextField label={t("library.translation")} value={typeof example.translation === "string" ? example.translation : ""} onChange={translation=>updateSense(currentSense.id,{examples:currentSense.examples.map((row,i)=>i===index?{...row,translation}:row)})} multiline /> : <pre className="whitespace-pre-wrap text-xs">{JSON.stringify(example.translation,null,2)}</pre>}</div>)}<Button className="justify-self-start" size="sm" variant="ghost" onClick={()=>updateSense(currentSense.id,{examples:[...currentSense.examples,{text:"",translation:""}]})}><RiAddLine aria-hidden="true" />{t("dictionary.addExample")}</Button></div></details>
        </section> : <p className="text-sm text-muted-foreground">{t("library.addSenseToStart")}</p>}
      </TabsContent>
      <TabsContent value="sounds" className="mt-5"><PronunciationEditor value={value} onChange={onChange} document={document} dirty={dirty} adopt={adopt} /></TabsContent>
      <TabsContent value="forms" className="mt-5"><FormsEditor value={value} onChange={onChange} /></TabsContent>
      <TabsContent value="source" className="mt-6 grid gap-4"><p className="text-sm text-muted-foreground">{t("library.attributionHelp")}</p><pre className="overflow-x-auto whitespace-pre-wrap rounded-xl border bg-muted/30 p-5 text-xs">{value.sourceMetadataJson ?? t("library.originalContent")}</pre><p className="whitespace-pre-wrap text-sm">{value.licenseText}</p></TabsContent>
    </Tabs></>;
}
