import assert from 'node:assert/strict';
import test from 'node:test';
import {
  SessionManager,
  type PersistentSession,
  type TokenSet,
} from '../auth/session-manager';

function memoryStorage(initial: PersistentSession | null = null) {
  let value = initial;
  let clearCount = 0;
  let legacyClearCount = 0;
  return {
    storage: {
      async read() { return value; },
      async write(next: PersistentSession) { value = next; },
      async clear() { value = null; clearCount += 1; },
      async clearLegacyAccessToken() { legacyClearCount += 1; },
    },
    current: () => value,
    clearCount: () => clearCount,
    legacyClearCount: () => legacyClearCount,
  };
}

function tokenSet(overrides: Partial<TokenSet> = {}): TokenSet {
  return {
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    idToken: 'id-1',
    expiresIn: 300,
    issuedAt: 100,
    ...overrides,
  };
}

test('bootstrap without a refresh token becomes unauthenticated and removes legacy access data', async () => {
  const memory = memoryStorage();
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => tokenSet(),
    isAccessTokenFresh: () => true,
    isInvalidRefreshError: () => true,
  });

  assert.equal(await manager.bootstrap(), 'empty');
  assert.equal(manager.getSnapshot().status, 'unauthenticated');
  assert.equal(memory.legacyClearCount(), 1);
  assert.equal(memory.clearCount(), 1);
});

test('bootstrap validates a persisted session by refreshing it', async () => {
  const memory = memoryStorage({ refreshToken: 'persisted-refresh', idToken: 'persisted-id' });
  let receivedRefreshToken: string | undefined;
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async refreshToken => {
      receivedRefreshToken = refreshToken;
      return tokenSet({ refreshToken: 'rotated-refresh' });
    },
    isAccessTokenFresh: () => true,
    isInvalidRefreshError: () => true,
  });

  assert.equal(await manager.bootstrap(), 'restored');
  assert.equal(receivedRefreshToken, 'persisted-refresh');
  assert.equal(manager.getSnapshot().status, 'authenticated');
  assert.equal(await manager.getValidAccessToken(), 'access-1');
  assert.equal(memory.current()?.refreshToken, 'rotated-refresh');
});

test('an invalid refresh clears the persistent session', async () => {
  const memory = memoryStorage({ refreshToken: 'revoked' });
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => { throw new Error('invalid_grant'); },
    isAccessTokenFresh: () => false,
    isInvalidRefreshError: () => true,
  });

  assert.equal(await manager.bootstrap(), 'invalid');
  assert.equal(manager.getSnapshot().status, 'unauthenticated');
  assert.equal(memory.current(), null);
});

test('a temporary refresh failure preserves the persisted session for retry', async () => {
  const persisted = { refreshToken: 'persisted-refresh', idToken: 'persisted-id' };
  const memory = memoryStorage(persisted);
  let attempts = 0;
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => {
      attempts += 1;
      if (attempts === 1) throw new TypeError('Network request failed');
      return tokenSet({ accessToken: 'retry-access', refreshToken: 'retry-refresh' });
    },
    isAccessTokenFresh: () => false,
    isInvalidRefreshError: () => false,
  });

  assert.equal(await manager.bootstrap(), 'unavailable');
  assert.equal(manager.getSnapshot().status, 'unauthenticated');
  assert.deepEqual(memory.current(), persisted);
  assert.equal(manager.getLastRefreshFailure(), 'unavailable');
  assert.equal(await manager.refreshSession(), 'retry-access');
  assert.equal(manager.getSnapshot().status, 'authenticated');
  assert.equal(memory.current()?.refreshToken, 'retry-refresh');
});

test('a temporary refresh failure keeps an established session mounted', async () => {
  const memory = memoryStorage();
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => { throw new TypeError('Network request failed'); },
    isAccessTokenFresh: () => false,
    isInvalidRefreshError: () => false,
  });
  await manager.acceptTokenSet(tokenSet());

  await assert.rejects(() => manager.refreshSession());

  assert.equal(manager.getSnapshot().status, 'authenticated');
  assert.equal(manager.getLastRefreshFailure(), 'unavailable');
  assert.equal(memory.current()?.refreshToken, 'refresh-1');
  assert.equal(memory.clearCount(), 0);
});

test('refresh after suspend does not clear the persisted credentials', async () => {
  const persisted = { refreshToken: 'persisted-refresh', idToken: 'persisted-id' };
  const memory = memoryStorage(persisted);
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => tokenSet(),
    isAccessTokenFresh: () => false,
    isInvalidRefreshError: () => false,
  });

  manager.suspend();

  assert.equal(await manager.refreshSession(), null);
  assert.deepEqual(memory.current(), persisted);
  assert.equal(memory.clearCount(), 0);
});

test('concurrent expired-token requests share one refresh and persist rotation', async () => {
  const memory = memoryStorage();
  let refreshCount = 0;
  let resolveRefresh!: (value: TokenSet | PromiseLike<TokenSet>) => void;
  const refreshResult = new Promise<TokenSet>(resolve => { resolveRefresh = resolve; });
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => {
      refreshCount += 1;
      return refreshResult;
    },
    isAccessTokenFresh: () => false,
    isInvalidRefreshError: () => true,
  });
  await manager.acceptTokenSet(tokenSet());

  const requests = Array.from({ length: 8 }, () => manager.getValidAccessToken());
  resolveRefresh(tokenSet({ accessToken: 'access-2', refreshToken: 'refresh-2' }));

  assert.deepEqual(await Promise.all(requests), Array(8).fill('access-2'));
  assert.equal(refreshCount, 1);
  assert.equal(memory.current()?.refreshToken, 'refresh-2');
});

test('fresh access tokens stay in memory and logout clears all session material', async () => {
  const memory = memoryStorage();
  let refreshCount = 0;
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => { refreshCount += 1; return tokenSet(); },
    isAccessTokenFresh: () => true,
    isInvalidRefreshError: () => true,
  });
  await manager.acceptTokenSet(tokenSet());

  assert.equal(await manager.getValidAccessToken(), 'access-1');
  assert.equal(refreshCount, 0);
  await manager.invalidate();
  assert.equal(manager.getSnapshot().status, 'unauthenticated');
  assert.equal(memory.current(), null);
});

test('a refresh response arriving after logout cannot restore the session', async () => {
  const memory = memoryStorage();
  let resolveRefresh!: (value: TokenSet | PromiseLike<TokenSet>) => void;
  const refreshResult = new Promise<TokenSet>(resolve => { resolveRefresh = resolve; });
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: () => refreshResult,
    isAccessTokenFresh: () => false,
    isInvalidRefreshError: () => false,
  });
  await manager.acceptTokenSet(tokenSet());

  const pendingRefresh = manager.getValidAccessToken();
  await manager.invalidate();
  resolveRefresh(tokenSet({ accessToken: 'late-access', refreshToken: 'late-refresh' }));

  assert.equal(await pendingRefresh, null);
  assert.equal(manager.getSnapshot().status, 'unauthenticated');
  assert.equal(memory.current(), null);
});

test('logout clears a rotated token even when its secure write is in progress', async () => {
  const memory = memoryStorage();
  const write = memory.storage.write;
  let signalWriteStarted!: () => void;
  let finishWrite!: () => void;
  const writeStarted = new Promise<void>(resolve => { signalWriteStarted = resolve; });
  const writeBarrier = new Promise<void>(resolve => { finishWrite = resolve; });
  memory.storage.write = async session => {
    if (session.refreshToken === 'rotated-refresh') {
      signalWriteStarted();
      await writeBarrier;
    }
    await write(session);
  };
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => tokenSet({ refreshToken: 'rotated-refresh' }),
    isAccessTokenFresh: () => false,
    isInvalidRefreshError: () => false,
  });
  await manager.acceptTokenSet(tokenSet());

  const pendingRefresh = manager.refreshSession();
  await writeStarted;
  const pendingLogout = manager.invalidate();
  finishWrite();
  await Promise.all([pendingRefresh, pendingLogout]);

  assert.equal(manager.getSnapshot().status, 'unauthenticated');
  assert.equal(memory.current(), null);
});

test('a late invalid refresh cannot clear a newer sign-in', async () => {
  const memory = memoryStorage();
  let rejectRefresh!: (reason?: unknown) => void;
  const refreshResult = new Promise<TokenSet>((_resolve, reject) => { rejectRefresh = reject; });
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: () => refreshResult,
    isAccessTokenFresh: () => true,
    isInvalidRefreshError: () => true,
  });
  await manager.acceptTokenSet(tokenSet());

  const pendingRefresh = manager.refreshSession();
  await manager.invalidate();
  await manager.acceptTokenSet(tokenSet({ accessToken: 'new-access', refreshToken: 'new-refresh' }));
  rejectRefresh(new Error('invalid_grant'));
  await pendingRefresh;

  assert.equal(manager.getSnapshot().status, 'authenticated');
  assert.equal(await manager.getValidAccessToken(), 'new-access');
  assert.equal(memory.current()?.refreshToken, 'new-refresh');
});
