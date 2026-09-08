import { cva, type VariantProps } from 'class-variance-authority';
import type { ComponentProps } from 'react';
import { Pressable } from 'react-native';
import { cn } from '../../lib/utils';
import { Text } from './text';

const buttonVariants = cva('items-center justify-center rounded-md px-4 active:opacity-80 disabled:opacity-50', {
  variants: {
    variant: {
      default: 'bg-primary',
      secondary: 'bg-secondary',
      outline: 'border border-border bg-surface',
      ghost: 'bg-transparent',
      destructive: 'bg-destructive',
    },
    size: {
      default: 'min-h-11 py-3',
      sm: 'min-h-9 px-3 py-2',
      lg: 'min-h-13 px-6 py-4',
    },
  },
  defaultVariants: { variant: 'default', size: 'default' },
});

const labelVariants = cva('text-center text-sm font-bold', {
  variants: {
    variant: {
      default: 'text-primary-foreground',
      secondary: 'text-secondary-foreground',
      outline: 'text-foreground',
      ghost: 'text-foreground',
      destructive: 'text-destructive-foreground',
    },
  },
  defaultVariants: { variant: 'default' },
});

type ButtonProps = ComponentProps<typeof Pressable> & VariantProps<typeof buttonVariants> & {
  labelClassName?: string;
};

export function Button({ className, labelClassName, variant, size, children, ...props }: ButtonProps) {
  return (
    <Pressable className={cn(buttonVariants({ variant, size }), className)} {...props}>
      {typeof children === 'string'
        ? <Text className={cn(labelVariants({ variant }), labelClassName)}>{children}</Text>
        : children}
    </Pressable>
  );
}
