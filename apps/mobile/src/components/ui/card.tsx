import type { ComponentProps } from 'react';
import { View } from 'react-native';
import { cn } from '../../lib/utils';

type CardProps = ComponentProps<typeof View>;

export function Card({ className, ...props }: CardProps) {
  return <View className={cn('rounded-lg border border-border bg-surface p-5', className)} {...props} />;
}
