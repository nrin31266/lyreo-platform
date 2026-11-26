import { RiAddLine, RiArrowGoBackLine, RiDeleteBinLine, RiSearchLine } from "@remixicon/react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { PosTag } from "@/components/ContentBadges";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { LibrarySelect } from "../library/LibrarySelect";
import { TextField } from "../library/LibraryWorkspace";
import { lexiconPartsOfSpeech, type Document, type LexiconDraft, type Sense } from "../library/library.types";
import { LexiconAssistant } from "./LexiconAssistant";
import { TranslationCandidates } from "./TranslationCandidates";
import { candidatesForSense, unlinkCandidate } from "./translationCandidates";

function newSense(itemId: string): Sense {
  return {
    id: crypto.randomUUID(), itemId, definitionEn: "", translationVi: "", translationStatus: "MISSING",
    rawGlosses: [], examples: [], tags: [], matchedQualifier: null,
  };
}

export function SenseEditor({ value, onChange, selectedId, select, document, dirty, adopt }: {
  value: LexiconDraft;
  onChange: (value: LexiconDraft) => void;
  selectedId: string;
  select: (id: string) => void;
  document: Document<LexiconDraft>;
  dirty: boolean;
  adopt: (result: Document<LexiconDraft>) => void;
}) {
  const { t, i18n } = useTranslation("admin");
  const [query, setQuery] = useState("");
  const [removed, setRemoved] = useState<{ sense: Sense; index: number; links: LexiconDraft["translations"] }>();
  const search = query.trim().toLocaleLowerCase();
  const visible = value.senses.filter(row => !search ||
    `${row.definitionEn ?? ""} ${row.translationVi ?? ""}`.toLocaleLowerCase().includes(search));
  const active = visible.find(row => row.id === selectedId) ?? visible[0];
  const item = value.items.find(row => row.id === active?.itemId);
  const vietnamese = i18n.language.startsWith("vi");
  const part = (pos: string) => t(`library.partsOfSpeech.${pos}`, { defaultValue: pos });
  const posOptions = lexiconPartsOfSpeech.map(row => ({ value: row.value, label: part(row.value) }));

  function label(sense: Sense) {
    return (vietnamese ? sense.translationVi || sense.definitionEn : sense.definitionEn || sense.translationVi)
      || t("library.newSense");
  }

  function updateSense(sense: Sense, patch: Partial<Sense>) {
    select(sense.id);
    // Keep the selected editor mounted when the new text no longer matches the filter.
    if (search && !`${patch.definitionEn ?? sense.definitionEn ?? ""} ${patch.translationVi ?? sense.translationVi ?? ""}`
      .toLocaleLowerCase().includes(search)) setQuery("");
    onChange({ ...value, senses: value.senses.map(row => row.id === sense.id ? { ...row, ...patch } : row) });
  }

  function addSense(itemId?: string) {
    const id = itemId ?? crypto.randomUUID();
    const next = newSense(id);
    const items = itemId ? value.items : [...value.items, { id, partOfSpeech: "noun", posTitle: null, etymologyText: null }];
    setQuery("");
    select(next.id);
    onChange({ ...value, items, senses: [...value.senses, next] });
  }

  function removeSense(sense: Sense) {
    const index = value.senses.indexOf(sense);
    setRemoved({ sense, index, links: value.translations.filter(row => row.senseId === sense.id) });
    onChange({
      ...value,
      senses: value.senses.filter(row => row.id !== sense.id),
      translations: value.translations.map(row => row.senseId === sense.id ? unlinkCandidate(row) : row),
    });
  }

  function restoreSense() {
    if (!removed) return;
    const { sense, index, links } = removed;
    const senses = [...value.senses];
    senses.splice(index, 0, sense);
    const translations = value.translations.map(row =>
      row.senseId ? row : links.find(link => link.id === row.id) ?? row);
    onChange({ ...value, senses, translations });
    setQuery("");
    select(sense.id);
    setRemoved(undefined);
  }

  return <div className="grid min-w-0 items-start gap-5 xl:grid-cols-[minmax(16rem,20rem)_minmax(0,1fr)]">
    <aside className="grid min-w-0 gap-3 rounded-lg border p-3" aria-label={t("library.senseList")}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold">{t("library.senseCount", { count: value.senses.length })}</h2>
        <Button type="button" variant="ghost" size="sm" onClick={() => addSense(item?.id)}>
          <RiAddLine aria-hidden="true" />{t("library.addSense")}
        </Button>
      </div>
      {value.senses.length > 3 && <div className="relative">
        <RiSearchLine className="pointer-events-none absolute left-3 top-2.5 size-4 text-muted-foreground" aria-hidden="true" />
        <Input className="pl-9" type="search" aria-label={t("library.searchSenses")} placeholder={t("library.searchSenses")}
          value={query} onChange={event => setQuery(event.target.value)} />
      </div>}
      <div className="grid max-h-64 gap-3 overflow-y-auto overscroll-contain p-1 xl:max-h-[55vh]" tabIndex={0}>
        {value.items.map((group, index) => {
          const rows = visible.filter(row => row.itemId === group.id);
          if (!rows.length) return null;
          const repeatedPos = value.items.filter(row => row.partOfSpeech === group.partOfSpeech).length > 1;
          return <div key={group.id} className="grid gap-1">
            <h3 className="px-2 text-xs font-medium text-muted-foreground">
              {part(group.partOfSpeech)}{repeatedPos && ` · ${t("dictionary.itemNumber", { number: index + 1 })}`}
            </h3>
            {rows.map(row => <Button key={row.id} type="button" variant={active?.id === row.id ? "secondary" : "ghost"}
              className="h-auto min-h-10 min-w-0 justify-start whitespace-normal px-2 py-2 text-left"
              aria-pressed={active?.id === row.id} onClick={() => select(row.id)}>
              <span className="line-clamp-2 wrap-anywhere">{label(row)}</span>
            </Button>)}
          </div>;
        })}
        {!visible.length && <p className="p-2 text-sm text-muted-foreground">{t(search ? "library.noMatchingSense" : "library.addSenseToStart")}</p>}
      </div>
      <Button type="button" variant="ghost" size="sm" className="justify-self-start" onClick={() => addSense()}>
        <RiAddLine aria-hidden="true" />{t("library.addPartOfSpeech")}
      </Button>
    </aside>

    <div className="grid min-w-0 gap-4">
      {removed && <div role="status" className="flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2 text-sm">
        <span>{t("dictionary.senseRemoved")}</span>
        <Button type="button" size="sm" variant="ghost" onClick={restoreSense}><RiArrowGoBackLine aria-hidden="true" />{t("quality.undoRemove")}</Button>
      </div>}
      {active && item && <section key={active.id} className="grid min-w-0 gap-4" aria-label={t("library.editSense")}>
        <div className="flex flex-wrap items-center gap-2 border-b pb-3">
          <h2 className="font-semibold">{t("library.senseNumber", { number: value.senses.indexOf(active) + 1 })}</h2>
          <PosTag pos={item.partOfSpeech} label={part(item.partOfSpeech)} />
          <Button type="button" className="ml-auto" size="icon-sm" variant="ghost"
            aria-label={t("library.removeSense")} tooltip={t("library.help.removeSense")}
            onClick={() => removeSense(active)}><RiDeleteBinLine aria-hidden="true" /></Button>
        </div>
        <DefinitionFields sense={active} vietnamese={vietnamese} update={patch => updateSense(active, patch)} />
        {!!active.translationVi?.trim() && <div className="flex flex-wrap items-center gap-2">
          <Badge variant={active.translationStatus === "VERIFIED" ? "success" : "secondary"}>
            {t(`library.translationStates.${active.translationStatus}`)}
          </Badge>
          <Button type="button" size="sm" variant="ghost" tooltip={t("library.help.verifyTranslation")}
            onClick={() => updateSense(active, { translationStatus: active.translationStatus === "VERIFIED" ? "NEEDS_REVIEW" : "VERIFIED" })}>
            {t(active.translationStatus === "VERIFIED" ? "library.unverifyTranslation" : "library.verifyTranslation")}
          </Button>
        </div>}
        <details className="border-t pt-3">
          <summary className="cursor-pointer text-sm font-medium">{t("dictionary.examplesCount", { count: active.examples.length })}</summary>
          <div className="mt-3 grid gap-3">{active.examples.map((example, index) => <div key={index} className="grid gap-3 rounded-lg border p-3">
            <TextField label={t("library.examples")} value={typeof example.text === "string" ? example.text : ""}
              onChange={text => updateSense(active, { examples: active.examples.map((row, i) => i === index ? { ...row, text } : row) })} multiline />
            {typeof example.translation === "string" || example.translation == null
              ? <TextField label={t("library.translation")} value={typeof example.translation === "string" ? example.translation : ""}
                  onChange={translation => updateSense(active, { examples: active.examples.map((row, i) => i === index ? { ...row, translation } : row) })} multiline />
              : <pre className="whitespace-pre-wrap text-xs">{JSON.stringify(example.translation, null, 2)}</pre>}
          </div>)}
          <Button type="button" className="justify-self-start" size="sm" variant="ghost"
            onClick={() => updateSense(active, { examples: [...active.examples, { text: "", translation: "" }] })}>
            <RiAddLine aria-hidden="true" />{t("dictionary.addExample")}
          </Button></div>
        </details>
        {candidatesForSense(value, active).length > 0 && <details className="border-t pt-3">
          <summary className="cursor-pointer text-sm font-medium">{t("dictionary.sourceCandidates")}</summary>
          <div className="mt-3"><TranslationCandidates value={value} sense={active} onChange={next => { setQuery(""); select(active.id); onChange(next); }} /></div>
        </details>}
        <LexiconAssistant document={document} dirty={dirty} adopt={adopt} context={{ senseId: active.id }} />
        <details className="border-t pt-3">
          <summary className="cursor-pointer text-sm font-medium">{t("dictionary.editGroup")}</summary>
          <div className="mt-3 grid max-w-sm gap-2">
            <LibrarySelect label={t("library.partOfSpeech")} value={item.partOfSpeech}
              onValueChange={partOfSpeech => onChange({ ...value, items: value.items.map(row => row.id === item.id ? { ...row, partOfSpeech } : row) })}
              options={posOptions.some(row => row.value === item.partOfSpeech) ? posOptions : [...posOptions, { value: item.partOfSpeech, label: item.partOfSpeech }]} />
            <p className="text-xs text-muted-foreground">{t("dictionary.groupChangeHelp")}</p>
          </div>
        </details>
      </section>}
    </div>
  </div>;
}

function DefinitionFields({ sense, vietnamese, update }: {
  sense: Sense;
  vietnamese: boolean;
  update: (patch: Partial<Sense>) => void;
}) {
  const { t } = useTranslation("admin");
  const vi = <TextField label={t("dictionary.vietnameseMeaning")} value={sense.translationVi}
    onChange={translationVi => update({ translationVi, translationStatus: translationVi.trim() ? "NEEDS_REVIEW" : "MISSING" })} multiline />;
  const en = <TextField label={t("library.definition")} value={sense.definitionEn}
    onChange={definitionEn => update({ definitionEn })} multiline />;
  return <div className="grid min-w-0 gap-4 sm:grid-cols-2">{vietnamese ? <>{vi}{en}</> : <>{en}{vi}</>}</div>;
}
