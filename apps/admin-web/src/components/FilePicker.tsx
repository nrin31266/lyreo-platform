import { useId, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { RiUpload2Line } from '@remixicon/react';
import { Button } from '@/components/ui/button';

export function FilePicker({ label, accept, fileName, disabled, onSelect }: {
  label: string; accept: string; fileName?: string; disabled?: boolean; onSelect: (file: File) => void;
}) {
  const { t } = useTranslation('admin');
  const input = useRef<HTMLInputElement>(null);
  const status = useId();
  return <div className="flex min-w-0 flex-wrap items-center gap-2">
    <input ref={input} className="hidden" type="file" accept={accept} disabled={disabled} onChange={event => {
      const file = event.target.files?.[0]; if (file) onSelect(file); event.target.value = '';
    }} />
    <Button type="button" variant="outline" size="sm" tooltip={label} disabled={disabled} aria-describedby={status} onClick={() => input.current?.click()}>
      <RiUpload2Line aria-hidden="true" />{label}
    </Button>
    <span id={status} className="min-w-0 truncate text-xs text-muted-foreground" title={fileName}>{fileName || t('files.none')}</span>
  </div>;
}
