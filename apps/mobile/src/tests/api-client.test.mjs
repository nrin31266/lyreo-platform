import assert from 'node:assert/strict';
import test from 'node:test';
import { createApiClient } from '../api/client.ts';
import { ApiError, apiErrorFromResponse } from '../api/errors.ts';

test('Problem Details preserves safe fields, violations, and response correlation ID', async () => {
  const response = new Response(JSON.stringify({
    type: 'urn:lyreo:problem:request-validation-failed',
    title: 'Request validation failed',
    status: 400,
    detail: 'One or more request fields are invalid.',
    instance: '/api/v1/learner/preferences',
    code: 'REQUEST_VALIDATION_FAILED',
    correlationId: 'body-id',
    errors: [{ field: 'title', code: 'NotBlank', message: 'must not be blank' }],
  }), {
    status: 400,
    headers: {
      'Content-Type': 'application/problem+json',
      'X-Correlation-Id': 'header-id',
    },
  });

  const error = await apiErrorFromResponse(response);
  assert.equal(error.kind, 'validation');
  assert.equal(error.code, 'REQUEST_VALIDATION_FAILED');
  assert.equal(error.problemType, 'urn:lyreo:problem:request-validation-failed');
  assert.equal(error.instance, '/api/v1/learner/preferences');
  assert.equal(error.correlationId, 'header-id');
  assert.deepEqual(error.fieldErrors, [
    { field: 'title', code: 'NotBlank', message: 'must not be blank' },
  ]);
});

test('API client adds auth, refreshes once on 401, and retries once', async () => {
  const seenAuthorization = [];
  let fetchCount = 0;
  let refreshCount = 0;
  const client = createApiClient({
    baseUrl: 'http://core.test',
    getValidAccessToken: async () => 'access-1',
    refreshSession: async () => { refreshCount += 1; return 'access-2'; },
    invalidateSession: async () => {},
    fetchImplementation: async (_input, init) => {
      fetchCount += 1;
      seenAuthorization.push(new Headers(init.headers).get('Authorization'));
      if (fetchCount === 1) {
        return new Response(JSON.stringify({ code: 'AUTHENTICATION_REQUIRED' }), { status: 401 });
      }
      return new Response(JSON.stringify({ id: 'user-1' }), { status: 200 });
    },
  });

  assert.deepEqual(await client.request('/api/v1/me'), { id: 'user-1' });
  assert.equal(fetchCount, 2);
  assert.equal(refreshCount, 1);
  assert.deepEqual(seenAuthorization, ['Bearer access-1', 'Bearer access-2']);
});

test('a second 401 invalidates the session without an unbounded retry', async () => {
  let fetchCount = 0;
  let invalidateCount = 0;
  const client = createApiClient({
    baseUrl: 'http://core.test',
    getValidAccessToken: async () => 'access-1',
    refreshSession: async () => 'access-2',
    invalidateSession: async () => { invalidateCount += 1; },
    fetchImplementation: async () => {
      fetchCount += 1;
      return new Response(JSON.stringify({
        title: 'Authentication required',
        code: 'AUTHENTICATION_REQUIRED',
      }), { status: 401 });
    },
  });

  await assert.rejects(
    () => client.request('/api/v1/me'),
    error => error instanceof ApiError && error.kind === 'unauthorized',
  );
  assert.equal(fetchCount, 2);
  assert.equal(invalidateCount, 1);
});

test('fetch failures are normalized as network errors', async () => {
  const client = createApiClient({
    baseUrl: 'http://core.test',
    getValidAccessToken: async () => 'access-1',
    refreshSession: async () => null,
    invalidateSession: async () => {},
    fetchImplementation: async () => { throw new TypeError('Network request failed'); },
  });

  await assert.rejects(
    () => client.request('/api/v1/me'),
    error => error instanceof ApiError && error.kind === 'network',
  );
});
