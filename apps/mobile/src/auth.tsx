import * as AuthSession from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';
import { createContext, PropsWithChildren, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { keycloakIssuer, mobileEnv } from './env';
import { sessionStore, StoredSession } from './session';

WebBrowser.maybeCompleteAuthSession();

type AuthState = {
  loading: boolean;
  authenticated: boolean;
  accessToken: string | null;
  error: string | null;
  signIn: () => Promise<void>;
  signOut: () => Promise<void>;
  getAccessToken: () => Promise<string | null>;
};

const AuthContext = createContext<AuthState | null>(null);
const redirectUri = AuthSession.makeRedirectUri({ scheme: 'lyreo', path: 'auth' });

export function AuthProvider({ children }: PropsWithChildren) {
  const [stored, setStored] = useState<StoredSession | null>(null);
  const [initialLoading, setInitialLoading] = useState(true);
  const [exchanging, setExchanging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [discovery, setDiscovery] = useState<AuthSession.DiscoveryDocument | null>(null);

  const [request, response, promptAsync] = AuthSession.useAuthRequest({
    clientId: mobileEnv.keycloakClientId,
    responseType: AuthSession.ResponseType.Code,
    scopes: ['openid', 'profile', 'email', 'offline_access'],
    redirectUri,
    usePKCE: true,
  }, discovery);

  useEffect(() => {
    Promise.all([sessionStore.get(), AuthSession.fetchDiscoveryAsync(keycloakIssuer)])
      .then(([session, doc]) => { setStored(session); setDiscovery(doc); })
      .finally(() => setInitialLoading(false));
  }, []);

  useEffect(() => {
    if (!response) return;
    if (response.type === 'error') {
      setError(response.error?.message ?? 'Authentication failed');
      return;
    }
    if (response.type === 'cancel' || response.type === 'dismiss') {
      return;
    }
    if (response.type !== 'success' || !response.params.code || !request?.codeVerifier || !discovery) return;

    setExchanging(true);
    setError(null);
    AuthSession.exchangeCodeAsync({
      clientId: mobileEnv.keycloakClientId,
      code: response.params.code,
      redirectUri,
      extraParams: { code_verifier: request.codeVerifier },
    }, discovery).then(async token => {
      const expiresAt = token.expiresIn ? Math.floor(Date.now() / 1000) + token.expiresIn : undefined;
      const session: StoredSession = {
        accessToken: token.accessToken,
        refreshToken: token.refreshToken,
        idToken: token.idToken,
        expiresAt,
      };
      await sessionStore.set(session);
      setStored(session);
    }).catch(err => {
      setError(err instanceof Error ? err.message : 'Token exchange failed');
    }).finally(() => {
      setExchanging(false);
    });
  }, [response, request, discovery]);

  const refreshIfNeeded = useCallback(async () => {
    if (!stored) return null;
    const now = Math.floor(Date.now() / 1000);
    if (!stored.expiresAt || stored.expiresAt - now > 45) return stored.accessToken;
    if (!stored.refreshToken || !discovery) {
      await sessionStore.clear();
      setStored(null);
      return null;
    }
    try {
      const token = await AuthSession.refreshAsync({
        clientId: mobileEnv.keycloakClientId,
        refreshToken: stored.refreshToken,
      }, discovery);
      const next: StoredSession = {
        accessToken: token.accessToken,
        refreshToken: token.refreshToken ?? stored.refreshToken,
        idToken: token.idToken ?? stored.idToken,
        expiresAt: token.expiresIn ? now + token.expiresIn : undefined,
      };
      await sessionStore.set(next);
      setStored(next);
      return next.accessToken;
    } catch {
      await sessionStore.clear();
      setStored(null);
      return null;
    }
  }, [stored, discovery]);

  const signIn = useCallback(async () => {
    if (!request || !discovery) return;
    setError(null);
    await promptAsync();
  }, [request, discovery, promptAsync]);

  const signOut = useCallback(async () => {
    const idToken = stored?.idToken;
    await sessionStore.clear();
    setStored(null);
    setError(null);
    // Keycloak logout is browser-based; the local session is cleared even if the browser is dismissed.
    if (discovery?.endSessionEndpoint) {
      const url = new URL(discovery.endSessionEndpoint);
      url.searchParams.set('client_id', mobileEnv.keycloakClientId);
      url.searchParams.set('post_logout_redirect_uri', redirectUri);
      if (idToken) url.searchParams.set('id_token_hint', idToken);
      await WebBrowser.openAuthSessionAsync(url.toString(), redirectUri).catch(() => undefined);
    }
  }, [stored, discovery]);

  const value = useMemo<AuthState>(() => ({
    loading: initialLoading || exchanging,
    authenticated: Boolean(stored?.accessToken),
    accessToken: stored?.accessToken ?? null,
    error,
    signIn,
    signOut,
    getAccessToken: refreshIfNeeded,
  }), [initialLoading, exchanging, stored, error, signIn, signOut, refreshIfNeeded]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
