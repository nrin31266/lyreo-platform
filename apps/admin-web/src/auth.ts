import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { env } from './env';

export const userManager = new UserManager({
  authority: `${env.keycloakUrl}/realms/${env.keycloakRealm}`,
  client_id: env.keycloakClientId,
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: 'code',
  scope: 'openid profile email',
  userStore: new WebStorageStateStore({ store: window.localStorage }),
  automaticSilentRenew: true,
});

export async function accessToken() {
  return (await userManager.getUser())?.access_token ?? null;
}

function safeBase64UrlDecode(input: string): string {
  const base64 = input.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(base64.length + (4 - (base64.length % 4)) % 4, '=');
  return atob(padded);
}

export function extractRoles(user: User | null | undefined): string[] {
  if (!user) return [];

  const profileRoles = (user.profile?.realm_access as { roles?: unknown } | undefined)?.roles;
  if (Array.isArray(profileRoles)) {
    return profileRoles.filter((r): r is string => typeof r === 'string');
  }

  if (typeof user.access_token === 'string' && user.access_token.includes('.')) {
    try {
      const parts = user.access_token.split('.');
      if (parts[1]) {
        const payloadJson = safeBase64UrlDecode(parts[1]);
        const payload = JSON.parse(payloadJson) as { realm_access?: { roles?: unknown } };
        const tokenRoles = payload?.realm_access?.roles;
        if (Array.isArray(tokenRoles)) {
          return tokenRoles.filter((r): r is string => typeof r === 'string');
        }
      }
    } catch {
      // Ignored: fallback decoding failed, return empty roles
    }
  }

  return [];
}

export function isAdminUser(user: User | null | undefined): boolean {
  return extractRoles(user).includes('ADMIN');
}

