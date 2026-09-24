export type AuthorizationCallbackParams = {
  code?: string;
  error?: string;
  state?: string;
};

export type PendingAuthorizationRequest = {
  state: string;
  codeVerifier?: string;
};

export type AuthorizationCallbackResolution =
  | { type: 'exchange'; code: string; codeVerifier: string; key: string }
  | { type: 'provider-error' }
  | { type: 'invalid' };

export function resolveAuthorizationCallback(
  params: AuthorizationCallbackParams,
  request: PendingAuthorizationRequest | null,
): AuthorizationCallbackResolution {
  if (params.error) return { type: 'provider-error' };
  if (
    !params.code
    || !params.state
    || !request?.codeVerifier
    || params.state !== request.state
  ) {
    return { type: 'invalid' };
  }

  return {
    type: 'exchange',
    code: params.code,
    codeVerifier: request.codeVerifier,
    key: `${params.state}:${params.code}`,
  };
}
