import * as AuthSession from 'expo-auth-session';

import {
  createContext,
  type PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  discoverOidc,
  endOidcSession,
  exchangeAuthorizationCode,
  getAuthRequestConfig,
  isInvalidOidcRefreshError,
  isOidcAccessTokenFresh,
  refreshOidcTokens,
} from './oidc';
import {
  resolveAuthorizationCallback,
  type AuthorizationCallbackParams,
} from './authorization-callback';
import {
  SessionManager,
  SessionUnavailableError,
  type SessionSnapshot,
  type SessionStatus,
} from './session-manager';
import { secureSessionStorage } from './session-storage';

export type SessionErrorCode = 'authenticationFailed' | 'identityUnavailable' | 'sessionExpired';

type SessionContextValue = {
  status: SessionStatus;
  error: SessionErrorCode | null;
  signInReady: boolean;
  signIn: () => Promise<void>;
  completeAuthorizationCallback: (params: AuthorizationCallbackParams) => Promise<void>;
  signOut: () => Promise<void>;
  getValidAccessToken: () => Promise<string | null>;
  refreshSession: () => Promise<string | null>;
  invalidateSession: () => Promise<void>;
  retryInitialization: () => Promise<void>;
  clearError: () => void;
};

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: PropsWithChildren) {
  const discoveryRef = useRef<AuthSession.DiscoveryDocument | null>(null);
  const managerRef = useRef<SessionManager | null>(null);
  if (!managerRef.current) {
    managerRef.current = new SessionManager({
      storage: secureSessionStorage,
      refreshTokens: async refreshToken => {
        if (!discoveryRef.current) throw new Error('OIDC discovery is unavailable');
        return refreshOidcTokens(refreshToken, discoveryRef.current);
      },
      isAccessTokenFresh: isOidcAccessTokenFresh,
      isInvalidRefreshError: isInvalidOidcRefreshError,
    });
  }
  const manager = managerRef.current;

  const [snapshot, setSnapshot] = useState<SessionSnapshot>(() => manager.getSnapshot());
  const [discovery, setDiscovery] = useState<AuthSession.DiscoveryDocument | null>(null);
  const [initializing, setInitializing] = useState(true);
  const [exchanging, setExchanging] = useState(false);
  const [error, setError] = useState<SessionErrorCode | null>(null);
  const initializeInFlight = useRef<Promise<void> | null>(null);
  const handledResponse = useRef<AuthSession.AuthSessionResult | null>(null);
  const handledCallbacks = useRef(new Set<string>());

  const [request, response, promptAsync] = AuthSession.useAuthRequest(
    getAuthRequestConfig(),
    discovery,
  );

  useEffect(() => manager.subscribe(setSnapshot), [manager]);

  const initialize = useCallback((): Promise<void> => {
    if (initializeInFlight.current) return initializeInFlight.current;
    setInitializing(true);
    setError(null);

    const operation = (async () => {
      try {
        const document = await discoverOidc();
        discoveryRef.current = document;
        setDiscovery(document);
        const result = await manager.bootstrap();
        if (result === 'invalid') setError('sessionExpired');
        if (result === 'unavailable') setError('identityUnavailable');
      } catch {
        manager.suspend();
        setError('identityUnavailable');
      } finally {
        setInitializing(false);
      }
    })();

    initializeInFlight.current = operation;
    void operation.then(
      () => { if (initializeInFlight.current === operation) initializeInFlight.current = null; },
      () => { if (initializeInFlight.current === operation) initializeInFlight.current = null; },
    );
    return operation;
  }, [manager]);

  useEffect(() => {
    void initialize();
  }, [initialize]);

  const completeAuthorizationCallback = useCallback(async (
    params: AuthorizationCallbackParams,
  ): Promise<void> => {
    const resolution = resolveAuthorizationCallback(params, request);
    if (resolution.type !== 'exchange' || !discovery) {
      setError('authenticationFailed');
      return;
    }
    if (handledCallbacks.current.has(resolution.key)) return;
    handledCallbacks.current.add(resolution.key);

    setExchanging(true);
    setError(null);
    try {
      const tokens = await exchangeAuthorizationCode(
        resolution.code,
        resolution.codeVerifier,
        discovery,
      );
      await manager.acceptTokenSet(tokens);
    } catch {
      setError('authenticationFailed');
    } finally {
      setExchanging(false);
    }
  }, [discovery, manager, request]);

  useEffect(() => {
    if (!response || handledResponse.current === response) return;
    handledResponse.current = response;

    if (response.type === 'error') {
      void completeAuthorizationCallback({
        error: response.params.error ?? response.error?.code ?? 'authorization_error',
        state: response.params.state,
      });
      return;
    }
    if (response.type === 'success') {
      void completeAuthorizationCallback({
        code: response.params.code,
        state: response.params.state,
      });
    }
  }, [completeAuthorizationCallback, response]);

  const signIn = useCallback(async () => {
    setError(null);
    if (!request || !discovery) {
      setError('identityUnavailable');
      return;
    }
    try {
      await promptAsync();
    } catch {
      setError('authenticationFailed');
    }
  }, [discovery, promptAsync, request]);

  const getValidAccessToken = useCallback(async () => {
    const wasAuthenticated = manager.getSnapshot().status === 'authenticated';
    try {
      const token = await manager.getValidAccessToken();
      if (!token && wasAuthenticated) setError('sessionExpired');
      return token;
    } catch (error) {
      if (error instanceof SessionUnavailableError) setError('identityUnavailable');
      throw error;
    }
  }, [manager]);

  const refreshSession = useCallback(async () => {
    const wasAuthenticated = manager.getSnapshot().status === 'authenticated';
    try {
      const token = await manager.refreshSession();
      if (!token && wasAuthenticated) setError('sessionExpired');
      return token;
    } catch (error) {
      if (error instanceof SessionUnavailableError) setError('identityUnavailable');
      throw error;
    }
  }, [manager]);

  const invalidateSession = useCallback(async () => {
    await manager.invalidate().catch(() => undefined);
  }, [manager]);

  const signOut = useCallback(async () => {
    const idToken = manager.getIdToken();
    setError(null);
    await manager.invalidate().catch(() => undefined);
    if (discovery) {
      await endOidcSession(discovery, idToken).catch(() => undefined);
    }
  }, [discovery, manager]);

  const clearError = useCallback(() => setError(null), []);
  const status: SessionStatus = initializing || exchanging ? 'bootstrapping' : snapshot.status;
  const value = useMemo<SessionContextValue>(() => ({
    status,
    error,
    signInReady: Boolean(request && discovery),
    signIn,
    completeAuthorizationCallback,
    signOut,
    getValidAccessToken,
    refreshSession,
    invalidateSession,
    retryInitialization: initialize,
    clearError,
  }), [
    status,
    error,
    request,
    discovery,
    signIn,
    completeAuthorizationCallback,
    signOut,
    getValidAccessToken,
    refreshSession,
    invalidateSession,
    initialize,
    clearError,
  ]);

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside SessionProvider');
  return value;
}
