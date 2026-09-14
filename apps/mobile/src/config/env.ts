export type MobileEnv = {
  coreApiUrl: string;
  oidcIssuer: string;
  oidcClientId: string;
};

export type MobileEnvSource = {
  apiBaseUrl?: string;
  keycloakUrl?: string;
  keycloakRealm?: string;
  keycloakClientId?: string;
};

function required(name: string, value: string | undefined): string {
  const normalized = value?.trim();
  if (!normalized) {
    throw new Error(`Missing required Mobile configuration: ${name}`);
  }
  return normalized;
}

function publicHttpUrl(name: string, value: string | undefined): string {
  const normalized = required(name, value).replace(/\/+$/, '');
  let url: URL;
  try {
    url = new URL(normalized);
  } catch {
    throw new Error(`Invalid URL in Mobile configuration: ${name}`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`Mobile configuration ${name} must use http or https`);
  }
  return normalized;
}

export function parseMobileEnv(source: MobileEnvSource): MobileEnv {
  const coreApiUrl = publicHttpUrl('EXPO_PUBLIC_API_BASE_URL', source.apiBaseUrl);
  const keycloakUrl = publicHttpUrl('EXPO_PUBLIC_KEYCLOAK_URL', source.keycloakUrl);
  const realm = required('EXPO_PUBLIC_KEYCLOAK_REALM', source.keycloakRealm);
  if (realm.includes('/')) {
    throw new Error('EXPO_PUBLIC_KEYCLOAK_REALM must be a realm name, not a path');
  }

  return {
    coreApiUrl,
    oidcIssuer: `${keycloakUrl}/realms/${encodeURIComponent(realm)}`,
    oidcClientId: required('EXPO_PUBLIC_KEYCLOAK_CLIENT_ID', source.keycloakClientId),
  };
}

let cachedEnv: MobileEnv | undefined;

export function getMobileEnv(): MobileEnv {
  cachedEnv ??= parseMobileEnv({
    apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL,
    keycloakUrl: process.env.EXPO_PUBLIC_KEYCLOAK_URL,
    keycloakRealm: process.env.EXPO_PUBLIC_KEYCLOAK_REALM,
    keycloakClientId: process.env.EXPO_PUBLIC_KEYCLOAK_CLIENT_ID,
  });
  return cachedEnv;
}
