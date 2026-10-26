import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldError,
} from "@/components/ui/field";
import {
  metadataSchema,
  saveMetadata,
  type LessonItem,
  type Metadata,
} from "./catalog.api";
import { catalogKeys, useCategories } from "./catalog.queries";
import { CatalogError, CatalogImage } from "./CatalogFeedback";
export function LessonMetadataForm({
  lesson,
  onSaved,
  onCancel,
}: {
  lesson: LessonItem;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation("admin");
  const client = useQueryClient();
  const categories = useCategories();
  const form = useForm<Metadata>({
    resolver: zodResolver(metadataSchema),
    defaultValues: {
      title: lesson.title,
      description: lesson.description ?? "",
      imageUrl: lesson.imageUrl ?? "",
      categoryIds: lesson.categories.map((c) => c.id),
    },
  });
  const mutation = useMutation({
    mutationFn: (data: Metadata) => saveMetadata(lesson.id, data),
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: catalogKeys.all });
      onSaved();
    },
  });
  return (
    <form
      noValidate
      onSubmit={form.handleSubmit((data) => mutation.mutate(data))}
      className="grid gap-4"
    >
      <CatalogError error={mutation.error || categories.error} />
      <FieldGroup>
        {(["title", "description", "imageUrl"] as const).map((name) => (
          <Field key={name} data-invalid={!!form.formState.errors[name]}>
            <FieldLabel htmlFor={`metadata-${name}`}>
              {t(
                `catalog.${name === "description" ? "descriptionField" : name}`,
              )}
            </FieldLabel>
            {name === "description" ? (
              <Textarea
                id={`metadata-${name}`}
                {...form.register(name)}
                aria-invalid={!!form.formState.errors[name]}
              />
            ) : (
              <Input
                id={`metadata-${name}`}
                {...form.register(name)}
                aria-invalid={!!form.formState.errors[name]}
              />
            )}
            <FieldError>
              {form.formState.errors[name] && t("catalog.invalidField")}
            </FieldError>
          </Field>
        ))}
        <CatalogImage
          url={form.watch("imageUrl")}
          title={form.watch("title")}
        />
        <Controller
          name="categoryIds"
          control={form.control}
          render={({ field }) => (
            <Field>
              <FieldLabel>{t("catalog.categories")}</FieldLabel>
              <div className="flex flex-wrap gap-3">
                {categories.data?.map((c) => (
                  <label key={c.id} className="flex items-center gap-2">
                    <Checkbox
                      checked={field.value.includes(c.id)}
                      onCheckedChange={(checked) =>
                        field.onChange(
                          checked
                            ? [...field.value, c.id]
                            : field.value.filter((id) => id !== c.id),
                        )
                      }
                    />
                    {c.name}
                  </label>
                ))}
              </div>
            </Field>
          )}
        />
      </FieldGroup>
      <div className="flex gap-2">
        <Button
          type="submit"
          disabled={
            mutation.isPending || categories.isPending || categories.isError
          }
        >
          {t(mutation.isPending ? "catalog.saving" : "catalog.save")}
        </Button>
        <Button type="button" variant="outline" onClick={onCancel}>
          {t("catalog.cancel")}
        </Button>
      </div>
    </form>
  );
}
