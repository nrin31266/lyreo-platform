import { RiAddLine as Plus, RiPencilLine as Pencil, RiDeleteBinLine as Trash2 } from "@remixicon/react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field, FieldGroup, FieldLabel, FieldError } from "@/components/ui/field";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "@/components/ui/dialog";
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from "@/components/ui/table";
import { Empty, EmptyDescription } from "@/components/ui/empty";
import { CatalogError, CatalogSkeleton } from "./CatalogFeedback";
import { useCategories, catalogKeys } from "./catalog.queries";
import { saveCategory, deleteCategory, categoryName, type Category } from "./catalog.api";
const schema = z.object({ name: z.string().trim().min(1).max(100), vi: z.string().trim().max(100) });

export function CategoriesPage({ embedded = false }: { embedded?: boolean }) {
  const { t, i18n } = useTranslation("admin");
  const client = useQueryClient();
  const query = useCategories();
  const [editor, setEditor] = useState<{ category?: Category }>();
  const [deleting, setDeleting] = useState<Category>();
  const [search, setSearch] = useState("");
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema), defaultValues: { name: "", vi: "" }, mode: "onChange",
  });
  const { isDirty, isValid } = form.formState;
  const save = useMutation({
    mutationFn: async (data: z.infer<typeof schema>) => {
      const translations = { ...editor?.category?.translations };
      if (data.vi) translations.vi = data.vi; else delete translations.vi;
      await saveCategory(data.name, editor?.category?.id, translations);
    },
    onSuccess: async () => { setEditor(undefined); await client.invalidateQueries({ queryKey: catalogKeys.all }); },
  });
  const deletion = useMutation({
    mutationFn: deleteCategory,
    onSuccess: async () => { setDeleting(undefined); await client.invalidateQueries({ queryKey: catalogKeys.all }); },
  });
  const categories = query.data?.filter(category =>
    [category.name, ...Object.values(category.translations ?? {})].some(name => name.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))) ?? [];
  function edit(category?: Category) {
    save.reset();
    form.reset({ name: category?.name ?? "", vi: category?.translations?.vi ?? "" });
    setEditor({ category });
  }
  return <section className="grid gap-4">
    {!embedded && <h1>{t("catalog.categories")}</h1>}
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="flex min-w-0 flex-1 items-center gap-3">
        <Input className="max-w-sm" aria-label={t("catalog.searchCategories")} placeholder={t("catalog.searchCategories")} value={search} onChange={event => setSearch(event.target.value)} />
        {query.data && <span className="shrink-0 text-sm text-muted-foreground">{t("catalog.categoryCount", { count: query.data.length })}</span>}
      </div>
      <Button onClick={() => edit()}><Plus data-icon="inline-start" />{t("catalog.addCategory")}</Button>
    </div>
    <CatalogError error={query.error} retry={() => void query.refetch()} />
    {query.isPending ? <CatalogSkeleton /> : categories.length ? <div className="overflow-hidden rounded-xl border">
      <Table>
        <TableHeader><TableRow>
          <TableHead>{t("catalog.categoryName")}</TableHead>
          <TableHead className="w-28 text-right">{t("catalog.lessonCount")}</TableHead>
          <TableHead className="w-28 text-right">{t("catalog.actions")}</TableHead>
        </TableRow></TableHeader>
        <TableBody>{categories.map(category => <TableRow key={category.id}>
          <TableCell className="whitespace-normal">
            <button type="button" className="text-left font-medium hover:underline focus-visible:rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => edit(category)}>{categoryName(category, i18n.language)}</button>
            {categoryName(category, i18n.language) !== category.name && <p className="mt-1 text-xs text-muted-foreground">{category.name}</p>}
          </TableCell>
          <TableCell className="text-right tabular-nums"><Link className="hover:underline" to={`/lessons?category=${encodeURIComponent(category.id)}`} aria-label={t("catalog.categoryLessons", { title: categoryName(category, i18n.language), count: category.lessonCount })}>{category.lessonCount}</Link></TableCell>
          <TableCell><div className="flex justify-end gap-1">
            <Button variant="ghost" size="icon" aria-label={t("catalog.editCategoryNamed", { title: categoryName(category, i18n.language) })} title={t("catalog.editCategory")} onClick={() => edit(category)}><Pencil aria-hidden="true" /></Button>
            <Button variant="ghost" size="icon" className="text-destructive hover:bg-destructive/10 hover:text-destructive" aria-label={t("catalog.deleteCategoryNamed", { title: categoryName(category, i18n.language) })} disabled={category.lessonCount > 0 || deletion.isPending} title={t(category.lessonCount ? "catalog.categoryInUse" : "catalog.delete")} onClick={() => { deletion.reset(); setDeleting(category); }}><Trash2 aria-hidden="true" /></Button>
          </div></TableCell>
        </TableRow>)}</TableBody>
      </Table>
    </div> : !query.isError && <Empty><EmptyDescription>{t(query.data?.length ? "catalog.noCategoryMatches" : "catalog.noCategories")}</EmptyDescription></Empty>}
    <Dialog open={!!editor} onOpenChange={open => { if (!open && !save.isPending) setEditor(undefined); }}>
      <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto" showCloseButton={!save.isPending}>
        <DialogHeader><DialogTitle>{t(editor?.category ? "catalog.editCategory" : "catalog.addCategory")}</DialogTitle><DialogDescription>{t("catalog.categoryEditorHelp")}</DialogDescription></DialogHeader>
        <form onSubmit={form.handleSubmit(data => save.mutate(data))} className="grid gap-5" noValidate>
          <CatalogError error={save.error} />
          <FieldGroup className="gap-4">
            <Field data-invalid={!!form.formState.errors.name}><FieldLabel htmlFor="category-name">{t("catalog.categoryDefaultName")}</FieldLabel><Input id="category-name" maxLength={100} {...form.register("name")} disabled={save.isPending} aria-invalid={!!form.formState.errors.name} /><FieldError>{form.formState.errors.name && t("catalog.invalidField")}</FieldError></Field>
            <Field data-invalid={!!form.formState.errors.vi}><FieldLabel htmlFor="category-vi">{t("catalog.categoryVietnameseName")}</FieldLabel><Input id="category-vi" maxLength={100} {...form.register("vi")} disabled={save.isPending} aria-invalid={!!form.formState.errors.vi} /><FieldError>{form.formState.errors.vi && t("catalog.invalidField")}</FieldError></Field>
          </FieldGroup>
          <DialogFooter><Button type="button" variant="outline" disabled={save.isPending} onClick={() => setEditor(undefined)}>{t("catalog.cancel")}</Button><Button type="submit" disabled={save.isPending || !isDirty || !isValid}>{t(save.isPending ? "catalog.saving" : "catalog.save")}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
    <Dialog open={!!deleting} onOpenChange={open => { if (!open && !deletion.isPending) setDeleting(undefined); }}>
      <DialogContent showCloseButton={!deletion.isPending}>
        <DialogHeader><DialogTitle>{t("catalog.deleteCategory")}</DialogTitle><DialogDescription>{t("catalog.categoryDeleteConfirm", { title: deleting && categoryName(deleting, i18n.language) })}</DialogDescription></DialogHeader>
        <CatalogError error={deletion.error} />
        <DialogFooter><Button variant="outline" disabled={deletion.isPending} onClick={() => setDeleting(undefined)}>{t("catalog.cancel")}</Button><Button variant="destructive" disabled={deletion.isPending} onClick={() => deleting && deletion.mutate(deleting.id)}>{t(deletion.isPending ? "catalog.deleting" : "catalog.delete")}</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  </section>;
}
