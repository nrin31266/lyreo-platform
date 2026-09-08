export const mobileEnv = {
  apiBaseUrl: process.env.EXPO_PUBLIC_API_BASE_URL ?? 'http://10.0.2.2:8080',
  keycloakUrl: process.env.EXPO_PUBLIC_KEYCLOAK_URL ?? 'http://10.0.2.2:8081',
  keycloakRealm: process.env.EXPO_PUBLIC_KEYCLOAK_REALM ?? 'lyreo',
  keycloakClientId: process.env.EXPO_PUBLIC_KEYCLOAK_CLIENT_ID ?? 'lyreo-mobile',
};

export const keycloakIssuer = `${mobileEnv.keycloakUrl}/realms/${mobileEnv.keycloakRealm}`;
