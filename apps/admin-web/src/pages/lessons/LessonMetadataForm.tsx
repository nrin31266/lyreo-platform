import { useState } from "react";
import { CategoryPicker } from "./CategoryPicker";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AccessTierOption } from "@/components/AccessTierOption";
import { ImageAssetEditor } from "@/components/ImageAssetEditor";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Field,
  FieldGroup,
  FieldLabel,
  FieldError,
} from "@/components/ui/field";
import {
  metadataSchema,
  saveLessonAccess,
  saveMetadata,
  type LessonItem,
  type Metadata,
} from "./catalog.api";
import { catalogKeys, useCategories } from "./catalog.queries";
import { CatalogError } from "./CatalogFeedback";

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
  const initialPlus = lesson.accessMode === "FEATURE";
  const [plusOnly, setPlusOnly] = useState(initialPlus);
  const form = useForm<Metadata>({
    resolver: zodResolver(metadataSchema),
    defaultValues: {
      title: lesson.title,
      description: lesson.description ?? "",
      imageUrl: lesson.imageUrl ?? "",
      categoryIds: lesson.categories.map((c) => c.id),
    },
  });
  const { isDirty } = form.formState;
  const accessDirty = plusOnly !== initialPlus;
  const mutation = useMutation({
    mutationFn: async (data: Metadata) => {
      if (isDirty) {
        await saveMetadata(lesson.id, { ...data, version: lesson.version ?? 0 });
      }
      if (accessDirty) {
        await saveLessonAccess(lesson.id, {
          accessMode: plusOnly ? "FEATURE" : "PUBLIC",
          requiredFeatureKey: plusOnly ? "lesson.plus-lessons" : null,
          version: isDirty ? undefined : lesson.version ?? 0,
        });
      }
    },
    onSuccess: async () => {
      await client.invalidateQueries({ queryKey: catalogKeys.all });
      onSaved();
    },
  });
  return (
    <form
      noValidate
      onSubmit={form.handleSubmit((data) => mutation.mutate(data))}
      className="flex min-h-0 flex-1 flex-col gap-4"
    >
      <div className="min-h-0 overflow-y-auto pr-1">
        <CatalogError
          error={mutation.error || categories.error}
          retry={categories.isError ? () => void categories.refetch() : undefined}
        />
        <fieldset disabled={mutation.isPending}>
          <FieldGroup className="gap-4">
            {(["title", "description"] as const).map((name) => (
              <Field key={name} data-invalid={!!form.formState.errors[name]}>
                <FieldLabel htmlFor={`metadata-${name}`}>
                  {t(`catalog.${name === "description" ? "descriptionField" : name}`)}
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
            <Controller
              name="imageUrl"
              control={form.control}
              render={({ field }) => (
                <Field data-invalid={!!form.formState.errors.imageUrl}>
                  <ImageAssetEditor
                    mode="url"
                    aspect={16 / 9}
                    outputWidth={640}
                    outputHeight={360}
                    urlInputId="metadata-imageUrl"
                    value={field.value || null}
                    onChange={(next) => field.onChange(next ?? "")}
                    label={t("catalog.imageUrl")}
                    alt={form.watch("title") || lesson.title}
                    disabled={mutation.isPending}
                  />
                  <FieldError>
                    {form.formState.errors.imageUrl && t("catalog.invalidField")}
                  </FieldError>
                </Field>
              )}
            />
            <AccessTierOption
              value={plusOnly ? "FEATURE" : "PUBLIC"}
              onChange={(next) => setPlusOnly(next === "FEATURE")}
              disabled={mutation.isPending}
            />
            <Controller
              name="categoryIds"
              control={form.control}
              render={({ field }) =>
                categories.isPending ? (
                  <Skeleton className="h-48" />
                ) : categories.isError ? (
                  <></>
                ) : (
                  <CategoryPicker
                    categories={categories.data ?? []}
                    value={field.value}
                    onChange={field.onChange}
                    disabled={mutation.isPending}
                  />
                )
              }
            />
          </FieldGroup>
        </fieldset>
      </div>
      <div className="flex shrink-0 justify-end gap-2 border-t pt-4">
        <Button type="button" variant="outline" disabled={mutation.isPending} onClick={onCancel}>
          {t("catalog.cancel")}
        </Button>
        <Button
          type="submit"
          disabled={mutation.isPending || categories.isPending || categories.isError || (!isDirty && !accessDirty)}
        >
          {t(mutation.isPending ? "catalog.saving" : "catalog.save")}
        </Button>
      </div>
    </form>
  );
}
