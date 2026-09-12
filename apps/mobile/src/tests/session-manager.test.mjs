import assert from 'node:assert/strict';
import test from 'node:test';
import { SessionManager } from '../auth/session-manager.ts';

function memoryStorage(initial = null) {
  let value = initial;
  let clearCount = 0;
  let legacyClearCount = 0;
  return {
    storage: {
      async read() { return value; },
      async write(next) { value = next; },
      async clear() { value = null; clearCount += 1; },
      async clearLegacyAccessToken() { legacyClearCount += 1; },
    },
    current: () => value,
    clearCount: () => clearCount,
    legacyClearCount: () => legacyClearCount,
  };
}

function tokenSet(overrides = {}) {
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
  let receivedRefreshToken;
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
  assert.equal(memory.current().refreshToken, 'rotated-refresh');
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
  const manager = new SessionManager({
    storage: memory.storage,
    refreshTokens: async () => { throw new TypeError('Network request failed'); },
    isAccessTokenFresh: () => false,
    isInvalidRefreshError: () => false,
  });

  assert.equal(await manager.bootstrap(), 'unavailable');
  assert.equal(manager.getSnapshot().status, 'unauthenticated');
  assert.deepEqual(memory.current(), persisted);
  assert.equal(manager.getLastRefreshFailure(), 'unavailable');
});

test('concurrent expired-token requests share one refresh and persist rotation', async () => {
  const memory = memoryStorage();
  let refreshCount = 0;
  let resolveRefresh;
  const refreshResult = new Promise(resolve => { resolveRefresh = resolve; });
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
  assert.equal(memory.current().refreshToken, 'refresh-2');
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
