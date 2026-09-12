# FEAT-AUTH-ONBOARDING — Authentication and Onboarding

Requirements: [Identity/Learner](../requirements/identity-learner.md); stories:
[identity/learner](../requirements/stories/identity-learner.md).

## Authentication flow

Launch → check OIDC session → valid token enters app; otherwise Authorization Code + PKCE via
Keycloak. Core validates JWT issuer/JWK, maps `sub` through JIT provisioning, and enforces `ADMIN` or
`LEARNER` plus resource ownership. Passwords remain in Keycloak.

Mobile persists refresh/ID session material in SecureStore and keeps the access token in memory.
Startup refreshes persisted material before protected routes render. Access-token refresh is
single-flight; the shared API client performs at most one refresh and one retry after `401`, then
invalidates the local session. Remote end-session failure does not prevent local logout.

First login with incomplete learner profile enters onboarding; returning learner goes to Home.
Current frontend redirect integration must be verified from app code/runtime, not inferred from
backend endpoint presence.

## Onboarding and preferences

Input gồm current level, target, daily minutes, focus areas và optional preferred content. Initial
recommendation is rule-based. Persistent learning preferences live in learner-owned DB state;
session display/playback overrides remain local unless explicitly saved.

## Security/config/code

Realm/client/bootstrap details: `infra/keycloak/README.md`. Code:
`modules/identity`, `modules/learner`, `platform/security`, Admin/Mobile auth adapters. Dev seed and
bootstrap endpoints are not production user provisioning and must be disabled outside dev.
