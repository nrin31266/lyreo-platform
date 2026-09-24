import * as AuthSession from 'expo-auth-session';
import * as WebBrowser from 'expo-web-browser';
import { getMobileEnv } from '@/config/env';
import type { TokenSet } from './session-manager';

WebBrowser.maybeCompleteAuthSession();

export const oidcRedirectUri = AuthSession.makeRedirectUri({
  scheme: 'lyreo',
  path: 'auth/callback',
});

export function getAuthRequestConfig(): AuthSession.AuthRequestConfig {
  return {
    clientId: getMobileEnv().oidcClientId,
    responseType: AuthSession.ResponseType.Code,
    scopes: ['openid', 'profile', 'email', 'offline_access'],
    redirectUri: oidcRedirectUri,
    usePKCE: true,
  };
}

export function discoverOidc(): Promise<AuthSession.DiscoveryDocument> {
  return AuthSession.fetchDiscoveryAsync(getMobileEnv().oidcIssuer);
}

export async function exchangeAuthorizationCode(
  code: string,
  codeVerifier: string,
  discovery: AuthSession.DiscoveryDocument,
): Promise<TokenSet> {
  const response = await AuthSession.exchangeCodeAsync({
    clientId: getMobileEnv().oidcClientId,
    code,
    redirectUri: oidcRedirectUri,
    extraParams: { code_verifier: codeVerifier },
  }, discovery);
  return toTokenSet(response);
}

export async function refreshOidcTokens(
  refreshToken: string,
  discovery: AuthSession.DiscoveryDocument,
): Promise<TokenSet> {
  const response = await AuthSession.refreshAsync({
    clientId: getMobileEnv().oidcClientId,
    refreshToken,
  }, discovery);
  return toTokenSet(response);
}

export function isOidcAccessTokenFresh(token: TokenSet): boolean {
  return AuthSession.TokenResponse.isTokenFresh(token, -45);
}

export function isInvalidOidcRefreshError(error: unknown): boolean {
  return error instanceof AuthSession.TokenError
    && (error.code === 'invalid_grant' || error.code === 'invalid_token');
}

export async function endOidcSession(
  discovery: AuthSession.DiscoveryDocument,
  idToken?: string,
): Promise<void> {
  if (!discovery.endSessionEndpoint) return;
  const url = new URL(discovery.endSessionEndpoint);
  url.searchParams.set('client_id', getMobileEnv().oidcClientId);
  url.searchParams.set('post_logout_redirect_uri', oidcRedirectUri);
  if (idToken) url.searchParams.set('id_token_hint', idToken);
  await WebBrowser.openAuthSessionAsync(url.toString(), oidcRedirectUri);
}

function toTokenSet(response: AuthSession.TokenResponse): TokenSet {
  return {
    accessToken: response.accessToken,
    expiresIn: response.expiresIn,
    issuedAt: response.issuedAt,
    refreshToken: response.refreshToken,
    idToken: response.idToken,
  };
}
