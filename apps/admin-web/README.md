# Lyreo Admin Web

React/Vite client for content managers and operators. Product scope lives in the [PRD](../../docs/product/prd.md); this app owns browser UI and public OIDC/API configuration. Never put provider or server secrets in `VITE_*` values.

## Local development

From this directory, copy `.env.example` to `.env` and set the public endpoints. From the repository root, run `make admin` after `make dev-infra` and `make keycloak-seed`; the dev server normally listens on `http://localhost:5173`.

```bash
pnpm --filter @lyreo/admin-web typecheck
pnpm --filter @lyreo/admin-web test
pnpm --filter @lyreo/admin-web build
```

Tests use Vitest, React Testing Library, and jsdom. The initial smoke test checks only the testing rail.

## Local conventions

Authentication uses Authorization Code + PKCE with the public Keycloak client. Browser env values are baked into a production Vite build; the Dockerfile supplies relative API/auth defaults for the repository compose topology.

Admin owns its Web components and accessibility adapters. Use shared semantic light/dark tokens from [`design-system`](../../packages/design-system/README.md), shared translations from [`i18n`](../../packages/i18n/README.md), and local shadcn/Radix-style primitives. Keep feature pages organized by domain and avoid importing another page's private implementation. Use semantic classes rather than raw brand/theme color literals.
