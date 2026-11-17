import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { FieldSet, FieldLegend } from "@/components/ui/field";
import { categoryName, type Category } from "./catalog.api";

export function CategoryPicker({ categories, value, onChange, disabled = false }: {
  categories: Category[];
  value: string[];
  onChange: (ids: string[]) => void;
  disabled?: boolean;
}) {
  const { t, i18n } = useTranslation("admin");
  const id = useId();
  const [search, setSearch] = useState("");
  const filtered = categories.filter(category =>
    [category.name, ...Object.values(category.translations ?? {})].some(name =>
      name.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())));
  return <FieldSet className="min-w-0 gap-2" disabled={disabled}>
    <FieldLegend variant="label" className="mb-0 w-full">
      <span className="flex items-center justify-between gap-2">
        {t("catalog.categories")}
        <span className="text-xs font-normal text-muted-foreground">{t("catalog.categoriesSelected", { count: value.length })}</span>
      </span>
    </FieldLegend>
    <Input aria-label={t("catalog.searchCategories")} placeholder={t("catalog.searchCategories")} value={search} onChange={event => setSearch(event.target.value)} aria-controls={id} />
    <div id={id} role="region" aria-label={t("catalog.categoryChoices")} tabIndex={0} className="max-h-48 overflow-y-auto overscroll-contain rounded-xl border p-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
      {filtered.length ? filtered.map(category => <label key={category.id} className="flex cursor-pointer items-start gap-2 rounded-lg px-2 py-2 hover:bg-muted">
        <Checkbox className="mt-0.5" checked={value.includes(category.id)} onCheckedChange={checked => onChange(checked ? [...value, category.id] : value.filter(id => id !== category.id))} />
        <span className="min-w-0 break-words text-sm">{categoryName(category, i18n.language)}</span>
      </label>) : <p className="p-3 text-sm text-muted-foreground">{t(categories.length ? "catalog.noCategoryMatches" : "catalog.noCategories")}</p>}
    </div>
  </FieldSet>;
}
