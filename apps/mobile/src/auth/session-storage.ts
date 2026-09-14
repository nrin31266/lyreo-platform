import * as SecureStore from 'expo-secure-store';
import type { PersistentSession, SessionStorage } from './session-manager';

const ACCESS_TOKEN_KEY = 'lyreo.access-token';
const REFRESH_TOKEN_KEY = 'lyreo.refresh-token';
const ID_TOKEN_KEY = 'lyreo.id-token';
const LEGACY_EXPIRES_AT_KEY = 'lyreo.expires-at';

async function clearLegacyAccessToken(): Promise<void> {
  await Promise.all([
    SecureStore.deleteItemAsync(ACCESS_TOKEN_KEY),
    SecureStore.deleteItemAsync(LEGACY_EXPIRES_AT_KEY),
  ]);
}

export const secureSessionStorage: SessionStorage = {
  async read() {
    const [refreshToken, idToken] = await Promise.all([
      SecureStore.getItemAsync(REFRESH_TOKEN_KEY),
      SecureStore.getItemAsync(ID_TOKEN_KEY),
    ]);
    if (!refreshToken) return null;
    return { refreshToken, idToken: idToken ?? undefined };
  },

  async write(session: PersistentSession) {
    await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, session.refreshToken);
    if (session.idToken) await SecureStore.setItemAsync(ID_TOKEN_KEY, session.idToken);
    else await SecureStore.deleteItemAsync(ID_TOKEN_KEY);
    await clearLegacyAccessToken();
  },

  async clear() {
    await Promise.all([
      SecureStore.deleteItemAsync(ACCESS_TOKEN_KEY),
      SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY),
      SecureStore.deleteItemAsync(ID_TOKEN_KEY),
      SecureStore.deleteItemAsync(LEGACY_EXPIRES_AT_KEY),
    ]);
  },

  clearLegacyAccessToken,
};
