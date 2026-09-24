import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import test from 'node:test';

const mobileRoot = new URL('../../', import.meta.url);

test('the unauthenticated screen is a root route beside the protected app group', () => {
  const rootLayout = readFileSync(new URL('app/_layout.tsx', mobileRoot), 'utf8');

  assert.equal(existsSync(new URL('app/sign-in.tsx', mobileRoot)), true);
  assert.match(rootLayout, /<Stack\.Screen name="sign-in"/);
  assert.doesNotMatch(rootLayout, /<Stack\.Screen name="\(public\)"/);
});

test('the authenticated app group remains the sole owner of the root path', () => {
  assert.equal(existsSync(new URL('app/(app)/index.tsx', mobileRoot)), true);
  assert.equal(existsSync(new URL('app/(public)/index.tsx', mobileRoot)), false);
});
