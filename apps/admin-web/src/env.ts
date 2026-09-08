function normalizePublicUrl(value: string | undefined, fallback: string): string {
  const candidate = (value ?? fallback).replace(/\/$/, '');
  if (/^https?:\/\//i.test(candidate)) return candidate;

  // Production self-host may use an empty API base (same origin) and relative `/auth`
  // for Keycloak. Resolve relative values to an absolute browser origin without baking
  // deployment hostnames into the static image.
  if (typeof window !== 'undefined') {
    return new URL(candidate || '/', window.location.origin).toString().replace(/\/$/, '');
  }
  return candidate;
}

export const env = {
  apiBaseUrl: normalizePublicUrl(import.meta.env.VITE_API_BASE_URL, 'http://localhost:8080'),
  keycloakUrl: normalizePublicUrl(import.meta.env.VITE_KEYCLOAK_URL, 'http://localhost:8081'),
  keycloakRealm: import.meta.env.VITE_KEYCLOAK_REALM ?? 'lyreo',
  keycloakClientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? 'lyreo-admin-web',
};
