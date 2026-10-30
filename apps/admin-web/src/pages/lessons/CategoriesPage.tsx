import { RiPencilLine as Pencil, RiDeleteBinLine as Trash2 } from "@remixicon/react";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldError,
} from "@/components/ui/field";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";
import { Empty, EmptyDescription } from "@/components/ui/empty";
import { CatalogError, CatalogSkeleton } from "./CatalogFeedback";
import { useCategories, catalogKeys } from "./catalog.queries";
import { saveCategory, deleteCategory } from "./catalog.api";
const schema = z.object({ name: z.string().trim().min(1).max(100) });
export function CategoriesPage() {
  const { t } = useTranslation("admin");
  const client = useQueryClient();
  const query = useCategories();
  const [id, setId] = useState<string>();
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: { name: "" },
  });
  const mutation = useMutation({
    mutationFn: async (
      command: { name: string; id?: string } | { deleteId: string },
    ) => {
      if ("deleteId" in command) await deleteCategory(command.deleteId);
      else await saveCategory(command.name, command.id);
    },
    onSuccess: async () => {
      form.reset({ name: "" });
      setId(undefined);
      await client.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
  return (
    <section className="grid gap-4">
      <h1>{t("catalog.categories")}</h1>
      <CatalogError
        error={query.error || mutation.error}
        retry={() => void query.refetch()}
      />
      <form
        onSubmit={form.handleSubmit((data) => mutation.mutate({ ...data, id }))}
        className="flex flex-wrap items-end gap-3"
      >
        <FieldGroup className="max-w-sm flex-1">
          <Field data-invalid={!!form.formState.errors.name}>
            <FieldLabel htmlFor="category-name">
              {t("catalog.categoryName")}
            </FieldLabel>
            <Input
              id="category-name"
              {...form.register("name")}
              aria-invalid={!!form.formState.errors.name}
            />
            <FieldError>
              {form.formState.errors.name && t("catalog.invalidField")}
            </FieldError>
          </Field>
        </FieldGroup>
        <Button type="submit" disabled={mutation.isPending}>
          {t("catalog.save")}
        </Button>
        {id && (
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setId(undefined);
              form.reset({ name: "" });
            }}
          >
            {t("catalog.cancel")}
          </Button>
        )}
      </form>
      {query.isPending ? (
        <CatalogSkeleton />
      ) : query.data?.length ? (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("catalog.categoryName")}</TableHead>
              <TableHead>{t("catalog.lessonCount")}</TableHead>
              <TableHead>{t("catalog.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {query.data.map((c) => (
              <TableRow key={c.id}>
                <TableCell>{c.name}</TableCell>
                <TableCell>{c.lessonCount}</TableCell>
                <TableCell>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={t("catalog.editMetadata")}
                    title={t("catalog.editMetadata")}
                    onClick={() => {
                      setId(c.id);
                      form.reset({ name: c.name });
                    }}
                  >
                    <Pencil />
                  </Button>
                  <Button
                    variant="destructive"
                    size="icon"
                    aria-label={t("catalog.delete")}
                    disabled={c.lessonCount > 0 || mutation.isPending}
                    title={
                      c.lessonCount ? t("catalog.categoryInUse") : t("catalog.delete")
                    }
                    onClick={() => mutation.mutate({ deleteId: c.id })}
                  >
                    <Trash2 />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      ) : (
        <Empty>
          <EmptyDescription>{t("catalog.empty")}</EmptyDescription>
        </Empty>
      )}
    </section>
  );
}
