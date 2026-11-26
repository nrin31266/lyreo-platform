import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Field, FieldLabel } from '@/components/ui/field';
import { Switch } from '@/components/ui/switch';

type ContentAccessTier = 'PUBLIC' | 'FEATURE';

export function AccessTierOption({
  value,
  onChange,
  disabled,
}: {
  value: ContentAccessTier;
  onChange: (next: ContentAccessTier) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation('admin');
  const id = useId();

  return (
    <Field orientation="horizontal" data-disabled={disabled}>
      <FieldLabel htmlFor={id}>{t('plansConsole.requirePlus')}</FieldLabel>
      <Switch
        id={id}
        checked={value === 'FEATURE'}
        disabled={disabled}
        onCheckedChange={(checked) => onChange(checked ? 'FEATURE' : 'PUBLIC')}
      />
    </Field>
  );
}
