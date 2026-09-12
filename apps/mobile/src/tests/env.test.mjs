import assert from 'node:assert/strict';
import test from 'node:test';
import { parseMobileEnv } from '../config/env.ts';

const validSource = {
  apiBaseUrl: 'http://10.0.2.2:8080/',
  keycloakUrl: 'http://10.0.2.2:8081/',
  keycloakRealm: 'lyreo',
  keycloakClientId: 'lyreo-mobile',
};

test('Mobile env is normalized and derives the OIDC issuer', () => {
  assert.deepEqual(parseMobileEnv(validSource), {
    coreApiUrl: 'http://10.0.2.2:8080',
    oidcIssuer: 'http://10.0.2.2:8081/realms/lyreo',
    oidcClientId: 'lyreo-mobile',
  });
});

test('Mobile env fails fast for missing or unsafe endpoint config', () => {
  assert.throws(
    () => parseMobileEnv({ ...validSource, apiBaseUrl: '' }),
    /EXPO_PUBLIC_API_BASE_URL/,
  );
  assert.throws(
    () => parseMobileEnv({ ...validSource, keycloakUrl: 'file:///tmp/keycloak' }),
    /must use http or https/,
  );
  assert.throws(
    () => parseMobileEnv({ ...validSource, keycloakRealm: 'realms/lyreo' }),
    /realm name/,
  );
});
