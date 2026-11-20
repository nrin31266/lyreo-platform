import { useEffect, useEffectEvent, useState } from 'react';

export function useDebouncedInput(value: string, onChange: (value: string) => void) {
  const [input, setInput] = useState(value);
  const commit = useEffectEvent(onChange);
  useEffect(() => setInput(value), [value]);
  useEffect(() => {
    if (input === value) return;
    const timer = setTimeout(() => commit(input), 350);
    return () => clearTimeout(timer);
  }, [input, value]);
  return [input, setInput] as const;
}
