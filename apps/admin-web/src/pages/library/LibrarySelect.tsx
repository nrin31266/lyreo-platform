import { useId } from 'react';
import { Select, SelectContent, SelectGroup, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Field, FieldLabel } from '@/components/ui/field';

export function LibrarySelect({ label, value, options, onValueChange, placeholder, disabled, hideLabel = false }: {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onValueChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  hideLabel?: boolean;
}) {
  const id = useId();
  return <Field className="min-w-0 gap-2">
    {!hideLabel && <FieldLabel htmlFor={id}>{label}</FieldLabel>}
    <Select value={value || '__empty__'} onValueChange={next => onValueChange(next === '__empty__' ? '' : next)} disabled={disabled}>
      <SelectTrigger id={id} className="w-full min-w-0" aria-label={label}><SelectValue placeholder={placeholder || label} /></SelectTrigger>
      <SelectContent position="popper"><SelectGroup>
        {placeholder !== undefined && <SelectItem value="__empty__">{placeholder || label}</SelectItem>}
        {options.map(option => <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>)}
      </SelectGroup></SelectContent>
    </Select>
  </Field>;
}
