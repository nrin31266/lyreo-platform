import * as SecureStore from 'expo-secure-store';

const ACCESS_TOKEN = 'lyreo.access-token';
const REFRESH_TOKEN = 'lyreo.refresh-token';
const ID_TOKEN = 'lyreo.id-token';
const EXPIRES_AT = 'lyreo.expires-at';

export type StoredSession = {
  accessToken: string;
  refreshToken?: string;
  idToken?: string;
  expiresAt?: number;
};

export const sessionStore = {
  async get(): Promise<StoredSession | null> {
    const accessToken = await SecureStore.getItemAsync(ACCESS_TOKEN);
    if (!accessToken) return null;
    const [refreshToken, idToken, rawExpiresAt] = await Promise.all([
      SecureStore.getItemAsync(REFRESH_TOKEN),
      SecureStore.getItemAsync(ID_TOKEN),
      SecureStore.getItemAsync(EXPIRES_AT),
    ]);
    return {
      accessToken,
      refreshToken: refreshToken ?? undefined,
      idToken: idToken ?? undefined,
      expiresAt: rawExpiresAt ? Number(rawExpiresAt) : undefined,
    };
  },

  async set(session: StoredSession) {
    await SecureStore.setItemAsync(ACCESS_TOKEN, session.accessToken);
    if (session.refreshToken) await SecureStore.setItemAsync(REFRESH_TOKEN, session.refreshToken);
    else await SecureStore.deleteItemAsync(REFRESH_TOKEN);
    if (session.idToken) await SecureStore.setItemAsync(ID_TOKEN, session.idToken);
    else await SecureStore.deleteItemAsync(ID_TOKEN);
    if (session.expiresAt) await SecureStore.setItemAsync(EXPIRES_AT, String(session.expiresAt));
    else await SecureStore.deleteItemAsync(EXPIRES_AT);
  },

  async clear() {
    await Promise.all([
      SecureStore.deleteItemAsync(ACCESS_TOKEN),
      SecureStore.deleteItemAsync(REFRESH_TOKEN),
      SecureStore.deleteItemAsync(ID_TOKEN),
      SecureStore.deleteItemAsync(EXPIRES_AT),
    ]);
  },
};
