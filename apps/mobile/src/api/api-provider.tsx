import { createContext, type PropsWithChildren, useContext, useMemo } from 'react';
import { useSession } from '@/auth/use-session';
import { getMobileEnv } from '@/config/env';
import { createApiClient, type ApiClient } from './client';

const ApiContext = createContext<ApiClient | null>(null);

export function ApiProvider({ children }: PropsWithChildren) {
  const { getValidAccessToken, refreshSession, invalidateSession } = useSession();
  const client = useMemo(() => createApiClient({
    baseUrl: getMobileEnv().coreApiUrl,
    getValidAccessToken,
    refreshSession,
    invalidateSession,
  }), [getValidAccessToken, invalidateSession, refreshSession]);

  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

export function useApiClient(): ApiClient {
  const value = useContext(ApiContext);
  if (!value) throw new Error('useApiClient must be used inside ApiProvider');
  return value;
}
