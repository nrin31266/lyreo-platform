import { useFocusEffect } from 'expo-router';
import { useCallback, useState } from 'react';
import { useApiClient } from './api-provider';

type Resource<T> = { status: 'loading' } | { status: 'error' } | { status: 'ready'; data: T };

export function useCoreResource<T>(path: string) {
  const client = useApiClient();
  const [resource, setResource] = useState<Resource<T>>({ status: 'loading' });
  const [version, setVersion] = useState(0);

  useFocusEffect(useCallback(() => {
    const controller = new AbortController();
    setResource({ status: 'loading' });
    void client.request<T>(path, { signal: controller.signal }).then(
      (data) => {
        if (!controller.signal.aborted) setResource({ status: 'ready', data });
      },
      () => {
        if (!controller.signal.aborted) setResource({ status: 'error' });
      },
    );
    return () => controller.abort();
  }, [client, path, version]));

  return { resource, reload: () => setVersion((current) => current + 1) };
}
