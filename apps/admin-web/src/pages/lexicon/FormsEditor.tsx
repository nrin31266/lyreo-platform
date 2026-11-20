import { RiAddLine, RiArrowGoBackLine, RiDeleteBinLine, RiEditLine } from "@remixicon/react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { LibrarySelect } from "../library/LibrarySelect";
import { TextField } from "../library/LibraryWorkspace";
import type { LexiconDraft } from "../library/library.types";

type Form = LexiconDraft["forms"][number];

export function FormsEditor({ value, onChange }: { value: LexiconDraft; onChange: (value: LexiconDraft) => void }) {
  const { t } = useTranslation("admin");
  const [selected, select] = useState("");
  const [removed, setRemoved] = useState<{ form: Form; index: number }>();
  const scopes = [
    { value: "ENTRY", label: t("dictionary.entryScope") },
    ...value.items.map((item, index) => ({
      value: item.id,
      label: `${t(`library.partsOfSpeech.${item.partOfSpeech}`, { defaultValue: item.partOfSpeech })} · ${t("dictionary.itemNumber", { number: index + 1 })}`,
    })),
  ];
  const form = value.forms.find(row => row.id === selected);

  function update(patch: Partial<Form>) {
    if (!form) return;
    onChange({ ...value, forms: value.forms.map(row => row.id === form.id ? { ...row, ...patch } : row) });
  }

  function add() {
    const next = { id: crypto.randomUUID(), itemId: null, form: "", tags: [] };
    onChange({ ...value, forms: [...value.forms, next] });
    select(next.id);
  }

  function remove() {
    if (!form) return;
    setRemoved({ form, index: value.forms.indexOf(form) });
    onChange({ ...value, forms: value.forms.filter(row => row.id !== form.id) });
    select("");
  }

  function restore() {
    if (!removed) return;
    const forms = [...value.forms];
    forms.splice(removed.index, 0, removed.form);
    onChange({ ...value, forms });
    select(removed.form.id);
    setRemoved(undefined);
  }

  return <section className="grid max-w-3xl gap-4">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h2 className="text-sm font-semibold">{t("dictionary.formCount", { count: value.forms.length })}</h2>
      <Button type="button" variant="outline" size="sm" tooltip={t("library.help.addForm")} onClick={add}>
        <RiAddLine aria-hidden="true" />{t("library.addForm")}
      </Button>
    </div>
    {removed && <div role="status" className="flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2 text-sm">
      <span>{t("dictionary.formRemoved")}</span>
      <Button type="button" variant="ghost" size="sm" onClick={restore}><RiArrowGoBackLine aria-hidden="true" />{t("quality.undoRemove")}</Button>
    </div>}
    {value.forms.length > 0 && <ul className="max-h-72 divide-y overflow-y-auto rounded-lg border">
      {value.forms.map(row => <li key={row.id} className="flex min-w-0 items-center gap-3 px-3 py-2">
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium wrap-anywhere">{row.form || t("library.form")}</p>
          <p className="text-xs text-muted-foreground">{scopes.find(scope => scope.value === (row.itemId ?? "ENTRY"))?.label}</p>
        </div>
        <Button type="button" variant={selected === row.id ? "secondary" : "ghost"} size="icon-sm"
          aria-label={t("dictionary.editForm", { form: row.form || t("library.form") })} aria-pressed={selected === row.id}
          tooltip={t("dictionary.editForm", { form: row.form || t("library.form") })} onClick={() => select(row.id)}>
          <RiEditLine aria-hidden="true" />
        </Button>
      </li>)}
    </ul>}
    {form && <div className="grid items-end gap-3 rounded-lg border p-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]">
      <TextField label={t("library.form")} value={form.form} onChange={text => update({ form: text })} />
      <LibrarySelect label={t("dictionary.pronunciationScope")} value={form.itemId ?? "ENTRY"} options={scopes}
        onValueChange={itemId => update({ itemId: itemId === "ENTRY" ? null : itemId })} />
      <Button type="button" variant="ghost" size="icon-sm" tooltip={t("library.help.remove")}
        aria-label={t("library.remove")} onClick={remove}><RiDeleteBinLine aria-hidden="true" /></Button>
      {form.tags.length > 0 && <p className="text-xs text-muted-foreground sm:col-span-3">
        {t("dictionary.sourceTags")}: {form.tags.join(", ")}
      </p>}
    </div>}
  </section>;
}
