import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { getRoutes } from 'expo-router/build/getRoutes';
import requireContext from 'expo-router/build/testing-library/require-context-ponyfill';
import { resolveRootRoute } from '@/navigation/root-route';

test('Expo Router resolves the root path through the always-available index route', () => {
  const appDirectory = fileURLToPath(new URL('../../app', import.meta.url));
  const routeTree = getRoutes(requireContext(appDirectory), {
    ignoreRequireErrors: true,
    skipGenerated: true,
  });

  const rootIndex = routeTree?.children.find((route) => route.route === 'index');

  assert.equal(rootIndex?.contextKey, './index.tsx');
  assert.equal(
    routeTree?.children.some((route) => route.route === '(app)/index'),
    false,
  );
});

test('the root route handles every session state explicitly', () => {
  assert.deepEqual(resolveRootRoute('bootstrapping'), { screen: 'loading' });
  assert.deepEqual(resolveRootRoute('unauthenticated'), {
    screen: 'redirect',
    href: '/sign-in',
  });
  assert.deepEqual(resolveRootRoute('authenticated'), { screen: 'home' });
});
