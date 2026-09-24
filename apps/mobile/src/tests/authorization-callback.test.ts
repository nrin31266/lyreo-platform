import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveAuthorizationCallback } from '../auth/authorization-callback';

test('authorization callback accepts a code only for the matching PKCE request', () => {
  assert.deepEqual(resolveAuthorizationCallback({
    code: 'authorization-code',
    state: 'expected-state',
  }, {
    state: 'expected-state',
    codeVerifier: 'pkce-verifier',
  }), {
    type: 'exchange',
    code: 'authorization-code',
    codeVerifier: 'pkce-verifier',
    key: 'expected-state:authorization-code',
  });
});

test('authorization callback rejects missing or mismatched state and verifier', () => {
  assert.deepEqual(resolveAuthorizationCallback({
    code: 'authorization-code',
    state: 'unexpected-state',
  }, {
    state: 'expected-state',
    codeVerifier: 'pkce-verifier',
  }), { type: 'invalid' });

  assert.deepEqual(resolveAuthorizationCallback({
    code: 'authorization-code',
    state: 'expected-state',
  }, null), { type: 'invalid' });
});

test('authorization callback reports provider errors without exchanging a code', () => {
  assert.deepEqual(resolveAuthorizationCallback({
    error: 'access_denied',
    state: 'expected-state',
  }, {
    state: 'expected-state',
    codeVerifier: 'pkce-verifier',
  }), { type: 'provider-error' });
});
