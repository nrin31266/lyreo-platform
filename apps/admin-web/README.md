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

The compact console uses the shared `adminThemes` slate/indigo maps and self-hosted IBM Plex Sans
Variable (Fontsource, OFL-1.1, Latin/Vietnamese subsets). The 49 px header and collapsible
216/56 px desktop navigation follow shadcn sidebar/dashboard composition. Mobile navigation remains
a labelled Radix dialog with focus restoration and Escape dismissal. Named tooltips make collapsed
links discoverable; the skip link remains first in keyboard order.

Use 16–20 px page gutters, 12–16 px panel insets and 32–36 px controls. Transcript editing and
semantic review occupy separate Radix tabs beside the player. State belongs to the parent so tab
changes do not discard unsaved edits. Content-sized textareas have a two-row fallback for older
browsers. Tables scroll within labelled regions rather than expanding the page. The overview links
to implemented operations and does not invent counts or analytics.

Global CSS owns the keyboard outline and reduced-motion behavior. Keep palette values in the
[shared theme package](../../packages/design-system/README.md), not feature pages. Mobile continues
to use learner `semanticThemes`; Admin palette/font changes do not change that runtime adapter.

Regression checks cover collapse persistence, mobile focus, tabs, checkpoint/polling conflicts
and theme contrast. Browser visual checks use fixture sessions/drafts and are not evidence for
live OIDC, YouTube playback or AI quality. Consult the [plan completion audit](../../docs/features/lesson-build.md#plan-completion-audit--2026-10-25)
for incomplete research, verified anchor recovery defect, replacement and retirement work.
