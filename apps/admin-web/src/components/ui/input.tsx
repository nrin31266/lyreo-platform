import type { InputHTMLAttributes } from 'react';
import { cn } from '../../lib/utils';

export function Input({
  className,
  ...props
}: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        'h-9 w-full rounded-sm border border-input bg-surface px-3 py-2 text-base text-foreground md:text-sm placeholder:text-muted-foreground aria-invalid:border-destructive disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    />
  );
}
