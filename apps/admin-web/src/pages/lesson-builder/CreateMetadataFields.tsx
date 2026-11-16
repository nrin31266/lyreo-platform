import { Controller, type UseFormReturn } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Checkbox } from '@/components/ui/checkbox';
import { Field, FieldGroup, FieldLabel, FieldError, FieldDescription } from '@/components/ui/field';
import { Accordion, AccordionItem, AccordionTrigger, AccordionContent } from '@/components/ui/accordion';
import { Skeleton } from '@/components/ui/skeleton';
import { CatalogError, CatalogImage } from '@/pages/lessons/CatalogFeedback';
import { categoryName, type Metadata } from '@/pages/lessons/catalog.api';
import { useCategories } from '@/pages/lessons/catalog.queries';

export function CreateMetadataFields({ form, titleHint, imageHint }: { form: UseFormReturn<Metadata>; titleHint?: string; imageHint?: string }) {
  const { t, i18n } = useTranslation('admin');
  const categories = useCategories();
  return <FieldGroup>
    <Field data-invalid={!!form.formState.errors.title}>
      <FieldLabel htmlFor="create-title">{t('catalog.title')}</FieldLabel>
      <Input id="create-title" maxLength={500} placeholder={titleHint} {...form.register('title')} aria-invalid={!!form.formState.errors.title} />
      <FieldDescription>{t('media.titleAutomatic')}</FieldDescription>
      <FieldError>{form.formState.errors.title && t('catalog.invalidField')}</FieldError>
    </Field>
    <Controller name="categoryIds" control={form.control} render={({ field }) => <Field>
      <FieldLabel>{t('catalog.categories')}</FieldLabel>
      {categories.isPending ? <Skeleton className="h-8" /> : categories.isError ? <CatalogError error={categories.error} retry={() => void categories.refetch()} /> : !categories.data?.length ? <p className="text-sm text-muted-foreground">{t('catalog.noCategories')}</p> : categories.data.map(category => <label key={category.id} className="flex items-center gap-2">
        <Checkbox checked={field.value.includes(category.id)} onCheckedChange={checked => field.onChange(checked ? [...field.value, category.id] : field.value.filter(id => id !== category.id))} />
        {categoryName(category, i18n.language)}
      </label>)}
    </Field>} />
    <Accordion type="single" collapsible><AccordionItem value="metadata">
      <AccordionTrigger>{t('sourceWorkspace.additionalInfo')}</AccordionTrigger>
      <AccordionContent><FieldGroup>
        <Field data-invalid={!!form.formState.errors.description}>
          <FieldLabel htmlFor="create-description">{t('catalog.descriptionField')}</FieldLabel>
          <Textarea id="create-description" {...form.register('description')} aria-invalid={!!form.formState.errors.description} />
          <FieldError>{form.formState.errors.description && t('catalog.invalidField')}</FieldError>
        </Field>
        <CatalogImage url={form.watch('imageUrl') || imageHint} title="" />
        <Field data-invalid={!!form.formState.errors.imageUrl}>
          <FieldLabel htmlFor="create-image">{t('catalog.imageUrl')}</FieldLabel>
          <Input id="create-image" {...form.register('imageUrl')} aria-invalid={!!form.formState.errors.imageUrl} />
          <FieldError>{form.formState.errors.imageUrl && t('catalog.invalidField')}</FieldError>
        </Field>
      </FieldGroup></AccordionContent>
    </AccordionItem></Accordion>
  </FieldGroup>;
}
