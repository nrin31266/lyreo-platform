# FEAT-AUTH-ONBOARDING — Authentication and Onboarding

Requirements: [Identity/Learner](../requirements/identity-learner.md); stories:
[identity/learner](../requirements/stories/identity-learner.md).

## Authentication flow

Launch → check OIDC session → valid token enters app; otherwise Authorization Code + PKCE via
Keycloak. Core validates JWT issuer/JWK, maps `sub` through JIT provisioning, and enforces `ADMIN` or
`LEARNER` plus resource ownership. Passwords remain in Keycloak.

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
