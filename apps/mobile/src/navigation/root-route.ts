import type { SessionStatus } from '@/auth/session-manager';

export type RootRouteDecision =
  | { screen: 'loading' }
  | { screen: 'redirect'; href: '/sign-in' }
  | { screen: 'home' };

export function resolveRootRoute(status: SessionStatus): RootRouteDecision {
  switch (status) {
    case 'bootstrapping':
      return { screen: 'loading' };
    case 'unauthenticated':
      return { screen: 'redirect', href: '/sign-in' };
    case 'authenticated':
      return { screen: 'home' };
  }
}
