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


## Workspace UI

The shared shell uses a persistent desktop sidebar and a Radix modal navigation drawer below
768 px, with focus restoration, Escape dismissal and a skip-to-content link. Navigation groups
content work separately from operations. The overview links to real actions rather than invented
metrics; the login screen uses the same identity and translations.

Global CSS owns one visible keyboard outline, compact typography, adaptive gutters and reduced
motion. House controls retain visible unfocused boundaries and invalid/disabled states. Keep colors
in the [shared theme package](../../packages/design-system/README.md); its neutral paper/cocoa colors
and 6/10/16 px radii also reach Mobile through the existing shared adapters.

Regression checks cover modal navigation, checkpoint/polling conflicts and shared theme contrast.
Browser visual checks use local fixture sessions; they do not prove live OIDC, YouTube playback or
AI teaching quality. Production acquisition and Lesson Prep retirement retain the gates in the
[authoring contract](../../docs/features/lesson-build.md).
