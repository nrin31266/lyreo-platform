import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
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
export async function accessToken() { return (await userManager.getUser())?.access_token ?? null; }
