export type SessionStatus = 'bootstrapping' | 'authenticated' | 'unauthenticated';

export type SessionSnapshot = {
  status: SessionStatus;
};

export type PersistentSession = {
  refreshToken: string;
  idToken?: string;
};

export type TokenSet = {
  accessToken: string;
  expiresIn?: number;
  issuedAt: number;
  refreshToken?: string;
  idToken?: string;
};

export type SessionStorage = {
  read: () => Promise<PersistentSession | null>;
  write: (session: PersistentSession) => Promise<void>;
  clear: () => Promise<void>;
  clearLegacyAccessToken: () => Promise<void>;
};

type SessionManagerDependencies = {
  storage: SessionStorage;
  refreshTokens: (refreshToken: string) => Promise<TokenSet>;
  isAccessTokenFresh: (token: TokenSet) => boolean;
  isInvalidRefreshError: (error: unknown) => boolean;
};

export type RefreshFailure = 'invalid' | 'unavailable' | null;
export type BootstrapResult = 'empty' | 'restored' | 'invalid' | 'unavailable';

export class SessionManager {
  private readonly dependencies: SessionManagerDependencies;
  private readonly listeners = new Set<(snapshot: SessionSnapshot) => void>();
  private snapshot: SessionSnapshot = { status: 'bootstrapping' };
  private accessSession: TokenSet | null = null;
  private persistentSession: PersistentSession | null = null;
  private refreshInFlight: Promise<string | null> | null = null;
  private bootstrapInFlight: Promise<BootstrapResult> | null = null;
  private lastRefreshFailure: RefreshFailure = null;

  constructor(dependencies: SessionManagerDependencies) {
    this.dependencies = dependencies;
  }

  getSnapshot(): SessionSnapshot {
    return this.snapshot;
  }

  getIdToken(): string | undefined {
    return this.persistentSession?.idToken;
  }

  getLastRefreshFailure(): RefreshFailure {
    return this.lastRefreshFailure;
  }

  subscribe(listener: (snapshot: SessionSnapshot) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  bootstrap(): Promise<BootstrapResult> {
    if (this.bootstrapInFlight) return this.bootstrapInFlight;
    const operation = this.performBootstrap();
    this.bootstrapInFlight = operation;
    const clear = () => {
      if (this.bootstrapInFlight === operation) this.bootstrapInFlight = null;
    };
    void operation.then(clear, clear);
    return operation;
  }

  async acceptTokenSet(tokens: TokenSet): Promise<string> {
    const refreshToken = tokens.refreshToken ?? this.persistentSession?.refreshToken;
    if (!refreshToken) {
      await this.invalidate();
      throw new Error('OIDC token response did not include a refresh token');
    }

    const persistentSession: PersistentSession = {
      refreshToken,
      idToken: tokens.idToken ?? this.persistentSession?.idToken,
    };

    try {
      await this.dependencies.storage.write(persistentSession);
    } catch (error) {
      await this.invalidate();
      throw error;
    }

    this.persistentSession = persistentSession;
    this.accessSession = tokens;
    this.lastRefreshFailure = null;
    this.updateSnapshot('authenticated');
    return tokens.accessToken;
  }

  async getValidAccessToken(): Promise<string | null> {
    if (this.accessSession && this.dependencies.isAccessTokenFresh(this.accessSession)) {
      return this.accessSession.accessToken;
    }
    return this.refreshSession();
  }

  refreshSession(): Promise<string | null> {
    if (this.refreshInFlight) return this.refreshInFlight;
    const operation = this.performRefresh();
    this.refreshInFlight = operation;
    const clear = () => {
      if (this.refreshInFlight === operation) this.refreshInFlight = null;
    };
    void operation.then(clear, clear);
    return operation;
  }

  async invalidate(): Promise<void> {
    this.accessSession = null;
    this.persistentSession = null;
    this.lastRefreshFailure = null;
    this.updateSnapshot('unauthenticated');
    await this.dependencies.storage.clear();
  }

  suspend(): void {
    this.accessSession = null;
    this.persistentSession = null;
    this.lastRefreshFailure = 'unavailable';
    this.updateSnapshot('unauthenticated');
  }

  private async performBootstrap(): Promise<BootstrapResult> {
    this.updateSnapshot('bootstrapping');
    await this.dependencies.storage.clearLegacyAccessToken();
    const persisted = await this.dependencies.storage.read();
    if (!persisted?.refreshToken) {
      await this.invalidate();
      return 'empty';
    }

    this.persistentSession = persisted;
    const accessToken = await this.refreshSession();
    if (accessToken) return 'restored';
    return this.lastRefreshFailure === 'invalid' ? 'invalid' : 'unavailable';
  }

  private async performRefresh(): Promise<string | null> {
    const refreshToken = this.persistentSession?.refreshToken;
    if (!refreshToken) {
      await this.invalidate();
      return null;
    }

    try {
      const tokens = await this.dependencies.refreshTokens(refreshToken);
      return await this.acceptTokenSet(tokens);
    } catch (error) {
      if (this.dependencies.isInvalidRefreshError(error)) {
        await this.invalidate();
        this.lastRefreshFailure = 'invalid';
      } else {
        this.accessSession = null;
        this.persistentSession = null;
        this.lastRefreshFailure = 'unavailable';
        this.updateSnapshot('unauthenticated');
      }
      return null;
    }
  }

  private updateSnapshot(status: SessionStatus): void {
    if (this.snapshot.status === status) return;
    this.snapshot = { status };
    for (const listener of this.listeners) listener(this.snapshot);
  }
}
