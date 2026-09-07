import type { ComponentProps } from 'react';
import { Text as RNText } from 'react-native';
import { cn } from '../../lib/utils';

type TextProps = ComponentProps<typeof RNText>;

export function Text({ className, ...props }: TextProps) {
  return <RNText className={cn('text-base text-foreground', className)} {...props} />;
}
